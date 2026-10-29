package com.solari.similarity.server;

import com.solari.similarity.engine.AiAssistClient;
import com.solari.similarity.engine.SimilarityEngine;
import com.solari.similarity.model.FeedbackEntry;
import com.solari.similarity.model.HistoricalTicket;
import com.solari.similarity.model.IncomingTicket;
import com.solari.similarity.model.TriageResponse;
import com.solari.similarity.store.TicketRepository;
import com.solari.similarity.util.JsonCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight zero-dependency HTTP server exposing REST APIs and the interactive Support Agent
 * Triage Console.
 */
public final class TriageHttpServer {

  private final HttpServer server;
  private final ExecutorService executor;
  private final TicketRepository repository;
  private final SimilarityEngine engine;
  private final AiAssistClient aiAssistClient;
  private final Path examplesPath;
  private final Path staticHtmlFallbackPath;

  public TriageHttpServer(
      int port,
      TicketRepository repository,
      SimilarityEngine engine,
      AiAssistClient aiAssistClient,
      Path examplesPath,
      Path staticHtmlFallbackPath)
      throws IOException {
    this.repository = repository;
    this.engine = engine;
    this.aiAssistClient = aiAssistClient;
    this.examplesPath = examplesPath;
    this.staticHtmlFallbackPath = staticHtmlFallbackPath;

    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    this.executor =
        Executors.newFixedThreadPool(
            8,
            runnable -> {
              Thread t = new Thread(runnable, "solari-http-worker");
              t.setDaemon(true);
              return t;
            });
    this.server.setExecutor(this.executor);
    registerRoutes();
  }

  public void start() {
    server.start();
  }

  public void stop(int delaySeconds) {
    server.stop(delaySeconds);
    executor.shutdownNow();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void registerRoutes() {
    server.createContext("/api/health", this::handleHealth);
    server.createContext("/api/examples", this::handleExamples);
    server.createContext("/api/match", this::handleMatch);
    server.createContext("/api/tickets", this::handleTickets);
    server.createContext("/api/feedback", this::handleFeedback);
    server.createContext("/", this::handleStaticUi);
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, 405, Map.of("error", "Method not allowed"));
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "ok");
    body.put("indexed_resolved_tickets", engine.indexedTicketCount());
    body.put("total_tickets", repository.allTickets().size());
    body.put("feedback_count", repository.allFeedback().size());
    body.put("ai_configured", aiAssistClient.isConfigured());
    body.put("ai_provider", aiAssistClient.providerName());
    sendJson(exchange, 200, body);
  }

  private void handleExamples(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, 405, Map.of("error", "Method not allowed"));
      return;
    }
    List<IncomingTicket> examples =
        (examplesPath != null && Files.exists(examplesPath))
            ? TicketRepository.loadIncomingTickets(examplesPath)
            : List.of();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("examples", examples.stream().map(IncomingTicket::toMap).toList());
    sendJson(exchange, 200, body);
  }

  private void handleMatch(HttpExchange exchange) throws IOException {
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
      handleCorsPreflight(exchange);
      return;
    }
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, 405, Map.of("error", "Use POST /api/match"));
      return;
    }
    try {
      Map<String, Object> req = parseRequestBody(exchange);
      IncomingTicket incoming = IncomingTicket.fromMap(req);
      if (incoming.isBlank()) {
        sendJson(exchange, 400, Map.of("error", "Provide non-empty 'subject' or 'description'."));
        return;
      }
      int topN = JsonCodec.getInt(req, "top_n", 3);
      boolean useAi = JsonCodec.getBoolean(req, "use_ai", false);
      TriageResponse response = engine.triage(incoming, topN, useAi);
      sendJson(exchange, 200, response.toMap());
    } catch (IllegalArgumentException e) {
      sendJson(exchange, 400, Map.of("error", e.getMessage()));
    } catch (Exception e) {
      sendJson(exchange, 500, Map.of("error", "Internal server error: " + e.getMessage()));
    }
  }

  private void handleTickets(HttpExchange exchange) throws IOException {
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
      handleCorsPreflight(exchange);
      return;
    }
    String method = exchange.getRequestMethod().toUpperCase();
    if ("GET".equals(method)) {
      String categoryFilter = queryParam(exchange.getRequestURI(), "category");
      List<Map<String, Object>> list =
          repository.eligibleResolvedTickets().stream()
              .filter(
                  t ->
                      categoryFilter.isEmpty()
                          || t.category().equalsIgnoreCase(categoryFilter))
              .map(HistoricalTicket::toMap)
              .toList();
      Map<String, Object> resp = new LinkedHashMap<>();
      resp.put("count", list.size());
      resp.put("tickets", list);
      sendJson(exchange, 200, resp);
      return;
    }
    if ("POST".equals(method)) {
      try {
        Map<String, Object> req = parseRequestBody(exchange);
        HistoricalTicket created =
            repository.addResolvedTicket(
                JsonCodec.getString(req, "ticket_id", ""),
                JsonCodec.getString(req, "category", "general"),
                JsonCodec.getString(req, "subject", ""),
                JsonCodec.getString(req, "description", ""),
                JsonCodec.getString(req, "resolution_notes", ""),
                JsonCodec.getStringList(req, "tags"));
        engine.rebuildIndex(repository.eligibleResolvedTickets());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "indexed");
        resp.put("indexed_resolved_tickets", engine.indexedTicketCount());
        resp.put("ticket", created.toMap());
        sendJson(exchange, 201, resp);
      } catch (IllegalArgumentException e) {
        sendJson(exchange, 400, Map.of("error", e.getMessage()));
      }
      return;
    }
    sendJson(exchange, 405, Map.of("error", "Method not allowed"));
  }

  private void handleFeedback(HttpExchange exchange) throws IOException {
    if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
      handleCorsPreflight(exchange);
      return;
    }
    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      List<Map<String, Object>> all =
          repository.allFeedback().stream().map(FeedbackEntry::toMap).toList();
      sendJson(exchange, 200, Map.of("count", all.size(), "feedback", all));
      return;
    }
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, 405, Map.of("error", "Method not allowed"));
      return;
    }
    try {
      Map<String, Object> req = parseRequestBody(exchange);
      FeedbackEntry saved = repository.recordFeedback(FeedbackEntry.fromMap(req));
      sendJson(
          exchange,
          201,
          Map.of(
              "status", "recorded",
              "total_feedback", repository.allFeedback().size(),
              "entry", saved.toMap()));
    } catch (IllegalArgumentException e) {
      sendJson(exchange, 400, Map.of("error", e.getMessage()));
    }
  }

  private void handleStaticUi(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (!"/".equals(path) && !"/index.html".equals(path)) {
      sendJson(exchange, 404, Map.of("error", "Not found: " + path));
      return;
    }
    byte[] htmlBytes = loadIndexHtml();
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, htmlBytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(htmlBytes);
    }
  }

  private byte[] loadIndexHtml() throws IOException {
    try (InputStream is = TriageHttpServer.class.getResourceAsStream("/static/index.html")) {
      if (is != null) {
        return is.readAllBytes();
      }
    }
    if (staticHtmlFallbackPath != null && Files.exists(staticHtmlFallbackPath)) {
      return Files.readAllBytes(staticHtmlFallbackPath);
    }
    return "<html><body><h1>Solari Triage API Running</h1></body></html>"
        .getBytes(StandardCharsets.UTF_8);
  }

  private static Map<String, Object> parseRequestBody(HttpExchange exchange) throws IOException {
    String raw;
    try (InputStream is = exchange.getRequestBody()) {
      raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (raw.isBlank()) {
      throw new IllegalArgumentException("Request body cannot be empty.");
    }
    return JsonCodec.parseObject(raw);
  }

  private static String queryParam(URI uri, String key) {
    String rawQuery = uri.getRawQuery();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return "";
    }
    for (String pair : rawQuery.split("&")) {
      int idx = pair.indexOf('=');
      if (idx > 0 && pair.substring(0, idx).equals(key)) {
        return pair.substring(idx + 1);
      }
    }
    return "";
  }

  private static void handleCorsPreflight(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    exchange.sendResponseHeaders(204, -1);
  }

  private static void sendJson(HttpExchange exchange, int status, Map<String, Object> payload)
      throws IOException {
    byte[] bytes = JsonCodec.prettyPrint(payload).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
