package com.solari.similarity;

import com.solari.similarity.engine.AiAssistClient;
import com.solari.similarity.engine.SimilarityEngine;
import com.solari.similarity.model.FeedbackEntry;
import com.solari.similarity.model.HistoricalTicket;
import com.solari.similarity.model.IncomingTicket;
import com.solari.similarity.model.MatchTier;
import com.solari.similarity.model.TriageResponse;
import com.solari.similarity.server.TriageHttpServer;
import com.solari.similarity.store.TicketRepository;
import com.solari.similarity.text.PiiRedactor;
import com.solari.similarity.text.TextPreprocessor;
import com.solari.similarity.util.JsonCodec;
import com.solari.similarity.util.ProjectPaths;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Self-contained unit and end-to-end integration test suite.
 *
 * <p>Compatible both with {@code mvn test} (Maven Surefire POJO convention: public {@code test*}
 * methods) and zero-dependency execution via {@code ./run.sh test}.
 */
public class SimilarityAssistantTest {

  private static final Path SEED_DATA_DIR = ProjectPaths.resolveDefaultDataDir();

  public static void main(String[] args) throws Exception {
    SimilarityAssistantTest suite = new SimilarityAssistantTest();
    List<Method> testMethods =
        Arrays.stream(SimilarityAssistantTest.class.getDeclaredMethods())
            .filter(m -> m.getName().startsWith("test") && m.getParameterCount() == 0)
            .sorted(Comparator.comparing(Method::getName))
            .toList();

    int passed = 0;
    int failed = 0;
    System.out.println("Running " + testMethods.size() + " tests in SimilarityAssistantTest...");
    for (Method m : testMethods) {
      try {
        m.invoke(suite);
        passed++;
        System.out.println("  [PASS] " + m.getName());
      } catch (InvocationTargetException e) {
        failed++;
        Throwable cause = e.getTargetException();
        System.err.println("  [FAIL] " + m.getName() + " -> " + cause.getMessage());
        cause.printStackTrace(System.err);
      }
    }
    System.out.printf("Completed: %d passed, %d failed.%n", passed, failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  public void test01JsonCodecRoundTripAndEscapes() {
    String json =
        """
        {
          "title": "Line 1\\nLine 2 \\"quoted\\" \\u2713",
          "count": 42,
          "ratio": 0.875,
          "active": true,
          "empty": null,
          "tags": ["auth", "sso"]
        }
        """;
    Map<String, Object> parsed = JsonCodec.parseObject(json);
    assertEquals("Line 1\nLine 2 \"quoted\" \u2713", parsed.get("title"), "Unicode + escape decode");
    assertEquals(42L, parsed.get("count"), "Integer decode");
    assertEquals(Boolean.TRUE, parsed.get("active"), "Boolean decode");
    assertTrue(parsed.containsKey("empty") && parsed.get("empty") == null, "Null decode");

    String reserialized = JsonCodec.prettyPrint(parsed);
    Map<String, Object> reparsed = JsonCodec.parseObject(reserialized);
    assertEquals(parsed.get("title"), reparsed.get("title"), "Round-trip string equality");
    assertEquals(List.of("auth", "sso"), JsonCodec.getStringList(reparsed, "tags"), "String list");
  }

  public void test02PreprocessorEliminatesStarterKitContractionAndCompoundBugs() {
    List<String> tokens =
        TextPreprocessor.tokenize("Customer's account can't log in after re-sync; hasn't arrived");
    assertTrue(!tokens.contains("s"), "Must not emit orphan 's token from possessive");
    assertTrue(!tokens.contains("t"), "Must not emit orphan 't token from contraction");
    assertTrue(!tokens.contains("customer"), "Must filter ubiquitous 'customer' boilerplate");
    assertTrue(tokens.contains("login"), "Must unify 'log in' into 'login'");
    assertTrue(tokens.contains("resync"), "Must unify 're-sync' into 'resync'");
    assertTrue(tokens.contains("deliver"), "Must canonicalize 'arrived' -> 'deliver'");
  }

  public void test03PiiRedactionScrubsEmailsTokensAndCards() {
    String dirty =
        "Contact jane.doe@retailcorp.io with token Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 "
            + "or card 4111-2222-3333-4444 regarding T-1303.";
    String clean = PiiRedactor.redact(dirty);
    assertTrue(!clean.contains("jane.doe@retailcorp.io"), "Email must be scrubbed");
    assertTrue(clean.contains("[EMAIL_REDACTED]"), "Email placeholder expected");
    assertTrue(clean.contains("[TOKEN_REDACTED]"), "Bearer token placeholder expected");
    assertTrue(clean.contains("[CARD_REDACTED]"), "Card placeholder expected");
    assertTrue(clean.contains("T-1303"), "Ticket ID must remain intact");
  }

  public void test04UnresolvedOrEmptyTicketsExcludedFromIndex() {
    HistoricalTicket resolved =
        new HistoricalTicket(
            "T-1", "auth", "SSO login error", "Blank screen", "Remapped email attribute.",
            List.of("sso"), "2025-11-01", "resolved");
    HistoricalTicket openTicket =
        new HistoricalTicket(
            "T-2", "auth", "SSO login error", "Blank screen", "",
            List.of("sso"), "2025-11-02", "open");
    HistoricalTicket missingNotes =
        new HistoricalTicket(
            "T-3", "auth", "SSO login error", "Blank screen", "   ",
            List.of("sso"), "2025-11-03", "resolved");

    SimilarityEngine engine =
        new SimilarityEngine(
            List.of(resolved, openTicket, missingNotes), new AiAssistClient("", ""));
    assertEquals(1, engine.indexedTicketCount(), "Only resolved ticket with notes may be indexed");
  }

  public void test05Nt1FlagsRelatedPasswordResetWithStageMismatchCaveat() throws Exception {
    SimilarityEngine engine = loadSeedEngine();
    List<IncomingTicket> examples =
        TicketRepository.loadIncomingTickets(SEED_DATA_DIR.resolve("new_tickets_example.json"));
    IncomingTicket nt1 = examples.get(0);

    TriageResponse resp = engine.triage(nt1, 3, false);
    assertTrue(!resp.noStrongMatch(), "NT-1 should surface related password-reset ticket");
    assertEquals("T-1001", resp.primaryMatches().get(0).ticket().ticketId(), "Top match T-1001");
    assertEquals(
        MatchTier.RELATED,
        resp.primaryMatches().get(0).tier(),
        "NT-1 vs T-1001 should downgrade to RELATED due to missing-email vs post-receipt stage");
    assertTrue(
        !resp.primaryMatches().get(0).symptomCaveat().isEmpty(),
        "NT-1 top match must warn agent about missing-email stage difference");
  }

  public void test06Nt2FindsBlankRevenueChartAsStrongMatch() throws Exception {
    SimilarityEngine engine = loadSeedEngine();
    List<IncomingTicket> examples =
        TicketRepository.loadIncomingTickets(SEED_DATA_DIR.resolve("new_tickets_example.json"));
    IncomingTicket nt2 = examples.get(1);

    TriageResponse resp = engine.triage(nt2, 3, false);
    assertTrue(!resp.noStrongMatch(), "NT-2 should find strong match");
    assertEquals("T-1201", resp.primaryMatches().get(0).ticket().ticketId(), "NT-2 -> T-1201");
    assertEquals(MatchTier.STRONG, resp.overallTier(), "NT-2 should be STRONG tier");
  }

  public void test07Nt3FindsDuplicateBillingChargeAsStrongMatch() throws Exception {
    SimilarityEngine engine = loadSeedEngine();
    List<IncomingTicket> examples =
        TicketRepository.loadIncomingTickets(SEED_DATA_DIR.resolve("new_tickets_example.json"));
    IncomingTicket nt3 = examples.get(2);

    TriageResponse resp = engine.triage(nt3, 3, false);
    assertTrue(!resp.noStrongMatch(), "NT-3 should find strong match");
    assertEquals("T-1303", resp.primaryMatches().get(0).ticket().ticketId(), "NT-3 -> T-1303");
    assertEquals(MatchTier.STRONG, resp.overallTier(), "NT-3 should be STRONG tier");
  }

  public void test08Nt4SnowflakeColdStartTriggersNoStrongMatchGuardrail() throws Exception {
    SimilarityEngine engine = loadSeedEngine();
    List<IncomingTicket> examples =
        TicketRepository.loadIncomingTickets(SEED_DATA_DIR.resolve("new_tickets_example.json"));
    IncomingTicket nt4 = examples.get(3);

    TriageResponse resp = engine.triage(nt4, 3, false);
    assertTrue(resp.noStrongMatch(), "NT-4 Snowflake ticket must trigger noStrongMatch");
    assertEquals(MatchTier.NONE, resp.overallTier(), "Overall tier must be NONE for NT-4");
    assertTrue(
        resp.unindexedEntities().contains("snowflake"),
        "Unindexed subject entities should flag 'snowflake'");
    for (var neighbor : resp.categoryNeighbors()) {
      assertEquals(
          "sync",
          neighbor.ticket().category(),
          "Optional low-confidence neighbors for NT-4 should stay in 'sync', not random categories");
    }
  }

  public void test09LiveColdStartResolutionLoopAndHttpApiEndToEnd() throws Exception {
    Path tempDir = Files.createTempDirectory("solari-test-");
    Path seedCopy = tempDir.resolve("tickets.json");
    Files.copy(SEED_DATA_DIR.resolve("tickets.json"), seedCopy);
    Path examplesCopy = tempDir.resolve("new_tickets_example.json");
    Files.copy(SEED_DATA_DIR.resolve("new_tickets_example.json"), examplesCopy);

    TicketRepository repo = TicketRepository.fromDataDirectory(tempDir);
    AiAssistClient offlineAi = new AiAssistClient("", "");
    SimilarityEngine engine = new SimilarityEngine(repo.eligibleResolvedTickets(), offlineAi);

    TriageHttpServer server =
        new TriageHttpServer(
            0,
            repo,
            engine,
            offlineAi,
            examplesCopy,
            ProjectPaths.resolveStaticIndexHtml());
    server.start();
    try {
      int port = server.port();
      HttpClient client = HttpClient.newHttpClient();

      // 1. Health endpoint
      HttpResponse<String> healthResp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, healthResp.statusCode(), "GET /api/health status");
      Map<String, Object> healthJson = JsonCodec.parseObject(healthResp.body());
      assertEquals(40, JsonCodec.getInt(healthJson, "indexed_resolved_tickets", 0), "40 indexed");

      // 2. Resolve Snowflake ticket live via POST /api/tickets
      String createPayload =
          JsonCodec.stringify(
              Map.of(
                  "category", "sync",
                  "subject", "Snowflake data warehouse integration connection failing",
                  "description", "Customer Snowflake warehouse sync fails with connection timeout.",
                  "resolution_notes",
                  "Walked customer through allowlisting Solari NAT egress IPs in their Snowflake network policy.",
                  "tags", List.of("snowflake", "warehouse", "sync")));
      HttpResponse<String> createResp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tickets"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(createPayload))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, createResp.statusCode(), "POST /api/tickets status");

      // 3. Re-query NT-4 via POST /api/match -> now finds a STRONG match!
      String matchPayload =
          JsonCodec.stringify(
              Map.of(
                  "ticket_id", "NT-4B",
                  "subject", "Our data warehouse integration with Snowflake is failing",
                  "description", "Snowflake warehouse sync failing with connection error."));
      HttpResponse<String> matchResp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/match"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(matchPayload))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, matchResp.statusCode(), "POST /api/match status");
      Map<String, Object> matchJson = JsonCodec.parseObject(matchResp.body());
      assertEquals(
          Boolean.FALSE,
          matchJson.get("no_strong_match"),
          "After indexing Snowflake resolution, new Snowflake ticket should match");
      assertEquals("STRONG", matchJson.get("overall_tier"), "Should upgrade to STRONG match");

      // 4. Record agent feedback via POST /api/feedback
      String feedbackPayload =
          JsonCodec.stringify(
              Map.of(
                  "incoming_ticket_id", "NT-4B",
                  "matched_ticket_id", "T-2001",
                  "helpful", true,
                  "note", "Exact Snowflake IP allowlist fix worked"));
      HttpResponse<String> fbResp =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/feedback"))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(feedbackPayload))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(201, fbResp.statusCode(), "POST /api/feedback status");
      assertEquals(1, repo.allFeedback().size(), "Feedback persisted in repository");
    } finally {
      server.stop(0);
    }
  }

  public void test10BlankAndGibberishQueriesHandledGracefully() throws Exception {
    SimilarityEngine engine = loadSeedEngine();
    TriageResponse blankResp = engine.triage(new IncomingTicket("E-1", "   ", ""), 3, false);
    assertTrue(blankResp.noStrongMatch(), "Blank ticket must return noStrongMatch");

    TriageResponse gibberish =
        engine.triage(
            new IncomingTicket("E-2", "zxqv plmk qwerty99", "completely unrelated xylophone"),
            3,
            false);
    assertTrue(gibberish.noStrongMatch(), "Unrelated ticket must return noStrongMatch");
    assertTrue(gibberish.primaryMatches().isEmpty(), "Primary matches must be empty");
  }

  public void test11SingleTokenOverlapCappedBelowStrongOnMultiTermQuery() throws Exception {
    SimilarityEngine engine = loadSeedEngine();
    TriageResponse resp =
        engine.triage(
            new IncomingTicket("S-1", "Getting charged twice", "Duplicate monthly charge"),
            3,
            false);
    assertEquals("T-1303", resp.primaryMatches().get(0).ticket().ticketId(), "Top match T-1303");
    assertEquals(MatchTier.STRONG, resp.primaryMatches().get(0).tier(), "T-1303 is STRONG");
    if (resp.primaryMatches().size() > 1) {
      assertTrue(
          resp.primaryMatches().get(1).tier() != MatchTier.STRONG,
          "Single-term sync duplicate match must not be marked STRONG");
    }
  }

  private static SimilarityEngine loadSeedEngine() throws Exception {
    TicketRepository repo =
        new TicketRepository(SEED_DATA_DIR.resolve("tickets.json"), null, null);
    return new SimilarityEngine(repo.eligibleResolvedTickets(), new AiAssistClient("", ""));
  }

  private static void assertTrue(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError("Assertion failed: " + message);
    }
  }

  private static void assertEquals(Object expected, Object actual, String message) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(
          message + " | expected=<" + expected + "> but actual=<" + actual + ">");
    }
  }
}
