package com.solari.similarity;

import com.solari.similarity.engine.AiAssistClient;
import com.solari.similarity.engine.SimilarityEngine;
import com.solari.similarity.model.IncomingTicket;
import com.solari.similarity.model.SimilarTicketMatch;
import com.solari.similarity.model.TriageResponse;
import com.solari.similarity.server.TriageHttpServer;
import com.solari.similarity.store.TicketRepository;
import com.solari.similarity.util.JsonCodec;
import com.solari.similarity.util.ProjectPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified CLI and HTTP server entrypoint for the Solari Support Ticket Similarity Assistant.
 *
 * <p>Examples:
 * <pre>
 *   # Evaluate all example incoming tickets (data/new_tickets_example.json)
 *   ./run.sh cli
 *
 *   # Triage an ad-hoc incoming ticket from the command line
 *   ./run.sh --subject "Getting billed twice" --description "Card shows duplicate monthly charge"
 *
 *   # Launch the interactive Agent Triage Web Console + REST API on http://localhost:8080
 *   ./run.sh server
 * </pre>
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) throws Exception {
    Path dataDir = ProjectPaths.resolveDefaultDataDir();
    Path inputPath = null;
    String subject = null;
    String description = "";
    int topN = 3;
    boolean jsonOutput = false;
    boolean useAi = false;
    boolean serve = false;
    int port = 8080;

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--serve", "server" -> serve = true;
        case "cli" -> serve = false;
        case "--port" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
        case "--data-dir" -> dataDir = Path.of(requireValue(args, ++i, "--data-dir"));
        case "--input" -> inputPath = Path.of(requireValue(args, ++i, "--input"));
        case "--subject" -> subject = requireValue(args, ++i, "--subject");
        case "--description" -> description = requireValue(args, ++i, "--description");
        case "--top" -> topN = Integer.parseInt(requireValue(args, ++i, "--top"));
        case "--json" -> jsonOutput = true;
        case "--ai" -> useAi = true;
        case "--help", "-h", "help" -> {
          printUsage();
          return;
        }
        default -> {
          System.err.println("Unknown option: " + arg);
          printUsage();
          System.exit(1);
        }
      }
    }

    TicketRepository repository = TicketRepository.fromDataDirectory(dataDir);
    AiAssistClient aiClient = new AiAssistClient();
    SimilarityEngine engine = new SimilarityEngine(repository.eligibleResolvedTickets(), aiClient);

    if (serve) {
      Path examplesPath = dataDir.resolve("new_tickets_example.json");
      Path staticFallback = ProjectPaths.resolveStaticIndexHtml();
      TriageHttpServer httpServer =
          new TriageHttpServer(port, repository, engine, aiClient, examplesPath, staticFallback);
      httpServer.start();
      System.out.println("====================================================================");
      System.out.println(" Solari Support Ticket Similarity Assistant — Live Server");
      System.out.println(" Web UI  : http://localhost:" + httpServer.port());
      System.out.println(" Health  : http://localhost:" + httpServer.port() + "/api/health");
      System.out.println(" Indexed : " + engine.indexedTicketCount() + " resolved tickets");
      System.out.println(" AI Mode : " + aiClient.providerName());
      System.out.println(" Press Ctrl+C to stop.");
      System.out.println("====================================================================");
      Thread.currentThread().join();
      return;
    }

    List<IncomingTicket> queries;
    if (subject != null) {
      queries = List.of(new IncomingTicket("CLI-1", subject, description));
    } else {
      Path resolvedInput =
          inputPath != null ? inputPath : dataDir.resolve("new_tickets_example.json");
      queries = TicketRepository.loadIncomingTickets(resolvedInput);
    }

    List<TriageResponse> responses = new ArrayList<>(queries.size());
    for (IncomingTicket q : queries) {
      responses.add(engine.triage(q, topN, useAi));
    }

    if (jsonOutput) {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("indexed_tickets", engine.indexedTicketCount());
      root.put("results", responses.stream().map(TriageResponse::toMap).toList());
      System.out.println(JsonCodec.prettyPrint(root));
    } else {
      printFormattedReport(responses, engine.indexedTicketCount(), aiClient);
    }
  }

  private static void printFormattedReport(
      List<TriageResponse> responses, int indexedCount, AiAssistClient aiClient) {
    System.out.println("================================================================================");
    System.out.printf(
        " SOLARI SUPPORT TRIAGE REPORT  |  Indexed Resolved Tickets: %d  |  AI: %s%n",
        indexedCount, aiClient.providerName());
    System.out.println("================================================================================");

    for (TriageResponse resp : responses) {
      System.out.println();
      System.out.printf(
          "[%s] %s%n", resp.query().ticketId(), resp.query().subject());
      if (!resp.query().description().isEmpty()) {
        System.out.println("  Description : " + resp.query().description());
      }
      System.out.printf(
          "  Status      : %s (%d ms)%n", resp.overallTier().displayLabel(), resp.latencyMs());
      System.out.println("  Summary     : " + resp.bannerMessage());
      if (!resp.aiAdvisoryNote().isEmpty()) {
        System.out.println("  AI Advisor  : " + resp.aiAdvisoryNote());
      }

      if (!resp.primaryMatches().isEmpty()) {
        System.out.println("  Recommended Past Resolutions:");
        int rank = 1;
        for (SimilarTicketMatch m : resp.primaryMatches()) {
          System.out.printf(
              "    %d. %s [%s | %s | %.1f%% | %s] — %s%n",
              rank++,
              m.ticket().ticketId(),
              m.ticket().category(),
              m.tier().code(),
              m.score() * 100.0,
              m.ticket().createdAt(),
              m.ticket().subject());
          System.out.println("       Matched Signals : " + String.join(", ", m.matchedTerms()));
          System.out.printf(
              "       Action (%s) : %s%n", m.resolutionType(), m.suggestedAction());
          System.out.println("       Root Cause      : " + m.rootCauseSummary());
          if (!m.symptomCaveat().isEmpty()) {
            System.out.println("       ! CAVEAT        : " + m.symptomCaveat());
          }
        }
      } else if (!resp.categoryNeighbors().isEmpty()) {
        System.out.println("  Low-Confidence Category Playbooks (General Reference Only):");
        for (SimilarTicketMatch m : resp.categoryNeighbors()) {
          System.out.printf(
              "    - %s [%s | %.1f%%] %s -> %s%n",
              m.ticket().ticketId(),
              m.ticket().category(),
              m.score() * 100.0,
              m.ticket().subject(),
              m.suggestedAction());
        }
      }
      System.out.println("--------------------------------------------------------------------------------");
    }
  }

  private static String requireValue(String[] args, int index, String flag) {
    if (index >= args.length) {
      throw new IllegalArgumentException("Missing value for " + flag);
    }
    return args[index];
  }

  private static void printUsage() {
    System.out.println(
        """
        Solari Support Ticket Similarity Assistant (Java 17+)

        Usage:
          ./run.sh cli                                   Run batch triage on data/new_tickets_example.json
          ./run.sh --subject "..." --description "..."   Triage a single custom ticket
          ./run.sh server [--port 8080]                  Start REST API + Agent Triage Web UI
          ./run.sh test                                  Run automated unit & integration test suite

        Flags:
          --input <path>        Path to incoming tickets JSON file
          --data-dir <path>     Directory containing tickets.json (default: data)
          --top <N>             Maximum primary matches to return (default: 3)
          --json                Emit machine-readable JSON output
          --ai                  Enable optional Gemini/OpenAI semantic reranking + LLM advisor
          --serve               Start HTTP server mode
          --port <port>         HTTP server port (default: 8080)
        """);
  }
}
