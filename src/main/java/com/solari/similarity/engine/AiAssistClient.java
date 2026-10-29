package com.solari.similarity.engine;

import com.solari.similarity.model.IncomingTicket;
import com.solari.similarity.model.SimilarTicketMatch;
import com.solari.similarity.util.JsonCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional AI Bonus Track adapter using standard JDK {@link HttpClient}.
 *
 * <p>When {@code GEMINI_API_KEY} or {@code OPENAI_API_KEY} is set in the environment and AI mode is
 * requested, this client provides:
 * <ol>
 *   <li>Cached dense vector embeddings for hybrid lexical + semantic re-ranking.
 *   <li>A concise 1-sentence LLM triage advisory note highlighting resolution applicability and any
 *       stage/root-cause differences.
 * </ol>
 *
 * <p>When no API key is configured or network calls fail, all methods degrade gracefully to {@link
 * Optional#empty()} so core triage never blocks or crashes.
 */
public final class AiAssistClient {

  private final String geminiKey;
  private final String openAiKey;
  private final HttpClient httpClient;
  private final Map<String, double[]> embeddingCache = new ConcurrentHashMap<>();

  public AiAssistClient() {
    this(System.getenv("GEMINI_API_KEY"), System.getenv("OPENAI_API_KEY"));
  }

  public AiAssistClient(String geminiKey, String openAiKey) {
    this.geminiKey = cleanKey(geminiKey);
    this.openAiKey = cleanKey(openAiKey);
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
  }

  public boolean isConfigured() {
    return !geminiKey.isEmpty() || !openAiKey.isEmpty();
  }

  public String providerName() {
    if (!geminiKey.isEmpty()) {
      return "Gemini";
    }
    if (!openAiKey.isEmpty()) {
      return "OpenAI";
    }
    return "Offline (Deterministic Hybrid)";
  }

  public Optional<double[]> embedText(String text) {
    if (!isConfigured() || text == null || text.isBlank()) {
      return Optional.empty();
    }
    String cacheKey = providerName() + ":" + text.trim();
    double[] cached = embeddingCache.get(cacheKey);
    if (cached != null) {
      return Optional.of(cached);
    }
    try {
      Optional<double[]> computed =
          !geminiKey.isEmpty() ? embedWithGemini(text) : embedWithOpenAi(text);
      computed.ifPresent(vec -> embeddingCache.put(cacheKey, vec));
      return computed;
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  public Optional<String> summarizeGuidance(
      IncomingTicket query, List<SimilarTicketMatch> topMatches) {
    if (!isConfigured()) {
      return Optional.empty();
    }
    String prompt = buildPrompt(query, topMatches);
    try {
      return !geminiKey.isEmpty() ? generateWithGemini(prompt) : generateWithOpenAi(prompt);
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  public static double cosineSimilarity(double[] a, double[] b) {
    if (a == null || b == null || a.length == 0 || a.length != b.length) {
      return 0.0;
    }
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    if (normA == 0.0 || normB == 0.0) {
      return 0.0;
    }
    return Math.max(0.0, dot / (Math.sqrt(normA) * Math.sqrt(normB)));
  }

  private static String buildPrompt(IncomingTicket query, List<SimilarTicketMatch> matches) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are an internal B2B SaaS support triage assistant for Solari Analytics.\n");
    sb.append("Give a concise 1-2 sentence guidance note for the support agent handling this new ticket.\n");
    sb.append("If the closest past ticket differs in failure stage or integration type, explicitly warn the agent.\n\n");
    sb.append("NEW TICKET:\nSubject: ").append(query.subject()).append("\n");
    sb.append("Description: ").append(query.description()).append("\n\n");
    if (matches.isEmpty()) {
      sb.append("MATCHED PAST TICKETS: None cleared relevance threshold.\n");
    } else {
      sb.append("CANDIDATE PAST TICKETS:\n");
      for (SimilarTicketMatch m : matches) {
        sb.append("- ").append(m.ticket().ticketId())
            .append(" [").append(m.tier().code()).append(", score=").append(m.score()).append("] ")
            .append(m.ticket().subject())
            .append(" | Resolution: ").append(m.ticket().resolutionNotes()).append("\n");
      }
    }
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private Optional<double[]> embedWithGemini(String text) throws Exception {
    String url =
        "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key="
            + geminiKey;
    Map<String, Object> body =
        Map.of("content", Map.of("parts", List.of(Map.of("text", text))));
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(6))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.stringify(body)))
            .build();
    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      return Optional.empty();
    }
    Map<String, Object> json = JsonCodec.parseObject(resp.body());
    Object embObj = json.get("embedding");
    if (embObj instanceof Map<?, ?> embMap) {
      Object valuesObj = ((Map<String, Object>) embMap).get("values");
      if (valuesObj instanceof List<?> list) {
        return Optional.of(toDoubleArray(list));
      }
    }
    return Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private Optional<double[]> embedWithOpenAi(String text) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", "text-embedding-3-small");
    body.put("input", text);
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/embeddings"))
            .timeout(Duration.ofSeconds(6))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + openAiKey)
            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.stringify(body)))
            .build();
    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      return Optional.empty();
    }
    Map<String, Object> json = JsonCodec.parseObject(resp.body());
    Object dataObj = json.get("data");
    if (dataObj instanceof List<?> dataList && !dataList.isEmpty()) {
      Object first = dataList.get(0);
      if (first instanceof Map<?, ?> firstMap) {
        Object emb = ((Map<String, Object>) firstMap).get("embedding");
        if (emb instanceof List<?> list) {
          return Optional.of(toDoubleArray(list));
        }
      }
    }
    return Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private Optional<String> generateWithGemini(String prompt) throws Exception {
    String url =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
            + geminiKey;
    Map<String, Object> body =
        Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.stringify(body)))
            .build();
    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      return Optional.empty();
    }
    Map<String, Object> json = JsonCodec.parseObject(resp.body());
    Object candidates = json.get("candidates");
    if (candidates instanceof List<?> candList && !candList.isEmpty()) {
      Map<String, Object> first = (Map<String, Object>) candList.get(0);
      Map<String, Object> content = (Map<String, Object>) first.get("content");
      if (content != null && content.get("parts") instanceof List<?> parts && !parts.isEmpty()) {
        Map<String, Object> part0 = (Map<String, Object>) parts.get(0);
        String text = JsonCodec.getString(part0, "text", "").trim();
        if (!text.isEmpty()) {
          return Optional.of(text);
        }
      }
    }
    return Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private Optional<String> generateWithOpenAi(String prompt) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", "gpt-4o-mini");
    body.put("temperature", 0.2);
    body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
    HttpRequest req =
        HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + openAiKey)
            .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.stringify(body)))
            .build();
    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      return Optional.empty();
    }
    Map<String, Object> json = JsonCodec.parseObject(resp.body());
    if (json.get("choices") instanceof List<?> choices && !choices.isEmpty()) {
      Map<String, Object> first = (Map<String, Object>) choices.get(0);
      Map<String, Object> message = (Map<String, Object>) first.get("message");
      String text = JsonCodec.getString(message, "content", "").trim();
      if (!text.isEmpty()) {
        return Optional.of(text);
      }
    }
    return Optional.empty();
  }

  private static double[] toDoubleArray(List<?> list) {
    double[] vec = new double[list.size()];
    for (int i = 0; i < list.size(); i++) {
      Object item = list.get(i);
      vec[i] = (item instanceof Number n) ? n.doubleValue() : 0.0;
    }
    return vec;
  }

  private static String cleanKey(String raw) {
    return raw == null ? "" : raw.trim();
  }
}
