package com.solari.similarity.engine;

import com.solari.similarity.model.HistoricalTicket;
import com.solari.similarity.model.IncomingTicket;
import com.solari.similarity.model.MatchTier;
import com.solari.similarity.model.SimilarTicketMatch;
import com.solari.similarity.model.TriageResponse;
import com.solari.similarity.text.ResolutionExtractor;
import com.solari.similarity.text.ResolutionExtractor.ExtractedGuidance;
import com.solari.similarity.text.TextPreprocessor;
import com.solari.similarity.text.TextPreprocessor.ProcessedTokens;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Calibrated hybrid ticket retrieval and triage engine.
 *
 * <p>Combines:
 * <ol>
 *   <li><b>Field-Weighted Query-Normalized BM25F</b> ({@code subject} 3.0x, {@code tags} 2.5x,
 *       {@code category} 1.2x, {@code description} 1.0x, {@code resolution_notes} 0.35x) where
 *       unseen out-of-vocabulary subject entities carry full IDF weight in the query denominator,
 *       naturally suppressing false-positive scores on brand-new integrations (e.g., Snowflake).
 *   <li><b>Character 3-Gram TF-IDF Cosine Similarity</b> to capture morphological neighbors and
 *       minor spelling variations without external ML libraries.
 *   <li><b>Recency Tie-Breaker</b> gently favoring fresher resolutions when relevance is close.
 *   <li><b>Symptom-Stage &amp; Entity Guardrails</b> that distinguish exact matches ({@code STRONG})
 *       from stage-divergent workflow matches ({@code RELATED}, e.g., missing reset email vs.
 *       post-reset login rejection) and cold-start novel issues ({@code NONE}).
 *   <li><b>Optional Pluggable AI Reranker + Advisor</b> when {@code GEMINI_API_KEY} or
 *       {@code OPENAI_API_KEY} is enabled.
 * </ol>
 */
public final class SimilarityEngine {

  public static final double STRONG_THRESHOLD = 0.52;
  public static final double RELATED_THRESHOLD = 0.34;
  public static final double NEIGHBOR_FLOOR = 0.16;

  private static final double K1 = 1.4;
  private static final double B = 0.75;
  private static final double REFERENCE_STRONG_TF = 3.5;

  private static final double SUBJECT_DOC_WEIGHT = 3.0;
  private static final double TAGS_DOC_WEIGHT = 2.5;
  private static final double CATEGORY_DOC_WEIGHT = 1.2;
  private static final double DESCRIPTION_DOC_WEIGHT = 1.0;
  private static final double RESOLUTION_DOC_WEIGHT = 0.35;

  private static final double SUBJECT_QUERY_WEIGHT = 2.5;
  private static final double DESCRIPTION_QUERY_WEIGHT = 1.0;

  private final AiAssistClient aiAssistClient;
  private volatile IndexSnapshot snapshot;

  public SimilarityEngine(List<HistoricalTicket> tickets) {
    this(tickets, new AiAssistClient());
  }

  public SimilarityEngine(List<HistoricalTicket> tickets, AiAssistClient aiAssistClient) {
    this.aiAssistClient = aiAssistClient;
    rebuildIndex(tickets);
  }

  public void rebuildIndex(List<HistoricalTicket> allTickets) {
    List<HistoricalTicket> eligible =
        allTickets == null
            ? List.of()
            : allTickets.stream().filter(HistoricalTicket::isEligibleForIndexing).toList();
    this.snapshot = buildSnapshot(eligible);
  }

  public int indexedTicketCount() {
    return snapshot.docs.size();
  }

  public TriageResponse triage(IncomingTicket query, int topN, boolean useAi) {
    long startNanos = System.nanoTime();
    int limit = Math.max(1, Math.min(topN, 10));
    IndexSnapshot idx = this.snapshot;

    if (query == null || query.isBlank() || idx.docs.isEmpty()) {
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
      return new TriageResponse(
          query == null ? new IncomingTicket("NEW", "", "") : query,
          MatchTier.NONE,
          true,
          "Enter a ticket subject or description to search historical resolutions.",
          List.of(),
          List.of(),
          List.of(),
          "",
          "Hybrid BM25F + Char-3Gram",
          elapsedMs);
    }

    ProcessedTokens subjProc = TextPreprocessor.process(query.subject());
    ProcessedTokens descProc = TextPreprocessor.process(query.description());

    Map<String, Double> queryWeights = new LinkedHashMap<>();
    Map<String, String> querySurface = new LinkedHashMap<>();
    for (String stem : subjProc.stems()) {
      queryWeights.merge(stem, SUBJECT_QUERY_WEIGHT, Double::sum);
      querySurface.putIfAbsent(stem, subjProc.stemToSurface().getOrDefault(stem, stem));
    }
    for (String stem : descProc.stems()) {
      queryWeights.merge(stem, DESCRIPTION_QUERY_WEIGHT, Double::sum);
      querySurface.putIfAbsent(stem, descProc.stemToSurface().getOrDefault(stem, stem));
    }

    if (queryWeights.isEmpty()) {
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
      return new TriageResponse(
          query,
          MatchTier.NONE,
          true,
          "Ticket contains no distinctive diagnostic terms to match against past cases.",
          List.of(),
          List.of(),
          List.of(),
          "",
          "Hybrid BM25F + Char-3Gram",
          elapsedMs);
    }

    // Detect novel/unindexed entities in the incoming ticket subject (e.g., "Snowflake", "warehouse")
    List<String> unindexedSubjectEntities = new ArrayList<>();
    List<String> uniqueSubjStems = subjProc.stems().stream().distinct().toList();
    for (String stem : uniqueSubjStems) {
      if (!idx.idf.containsKey(stem)) {
        String surface = subjProc.stemToSurface().getOrDefault(stem, stem);
        if (!unindexedSubjectEntities.contains(surface)) {
          unindexedSubjectEntities.add(surface);
        }
      }
    }

    double refSaturation = (REFERENCE_STRONG_TF * (K1 + 1.0)) / (REFERENCE_STRONG_TF + K1);
    double maxPossibleBm25 = 0.0;
    for (Map.Entry<String, Double> qEntry : queryWeights.entrySet()) {
      double termIdf = idx.idf.getOrDefault(qEntry.getKey(), idx.oovIdf);
      maxPossibleBm25 += qEntry.getValue() * termIdf * refSaturation;
    }

    Set<String> queryTrigrams = TextPreprocessor.charTrigrams(new ArrayList<>(queryWeights.keySet()));
    Map<String, Double> queryTrigramVec = buildTrigramVector(queryTrigrams, idx.trigramIdf, idx.oovIdf);

    boolean usedEmbeddings = false;
    Optional<double[]> queryEmbedding =
        (useAi && aiAssistClient.isConfigured())
            ? aiAssistClient.embedText(query.subject() + "\n" + query.description())
            : Optional.empty();

    String rawQueryText = query.subject() + " " + query.description();
    boolean queryReportsNonDelivery = TextPreprocessor.hasNonDeliverySymptom(rawQueryText);

    List<ScoredCandidate> scored = new ArrayList<>(idx.docs.size());
    for (IndexedDoc doc : idx.docs) {
      double rawBm25 = 0.0;
      List<Map.Entry<String, Double>> termContributions = new ArrayList<>();

      for (Map.Entry<String, Double> qEntry : queryWeights.entrySet()) {
        String term = qEntry.getKey();
        Double docFreq = doc.weightedTf.get(term);
        if (docFreq == null || docFreq <= 0.0) {
          continue;
        }
        double idfVal = idx.idf.getOrDefault(term, 0.0);
        double denom = docFreq + K1 * (1.0 - B + B * (doc.docLength / idx.avgDocLength));
        double contrib = qEntry.getValue() * idfVal * ((docFreq * (K1 + 1.0)) / denom);
        rawBm25 += contrib;
        termContributions.add(Map.entry(term, contrib));
      }

      double normBm25 = maxPossibleBm25 > 0.0 ? Math.min(1.0, rawBm25 / maxPossibleBm25) : 0.0;
      double trigramSim = cosineMap(queryTrigramVec, doc.trigramVec);
      double recencyBonus = rawBm25 > 0.0 ? doc.recencyScore * 0.025 : 0.0;

      double combined = 0.84 * normBm25 + 0.14 * trigramSim + recencyBonus;

      if (queryEmbedding.isPresent()) {
        Optional<double[]> docEmb =
            aiAssistClient.embedText(
                doc.ticket.subject() + "\n" + doc.ticket.description() + "\n"
                    + String.join(" ", doc.ticket.tags()));
        if (docEmb.isPresent()) {
          usedEmbeddings = true;
          double denseSim = AiAssistClient.cosineSimilarity(queryEmbedding.get(), docEmb.get());
          // Keep lexical entity specificity primary (0.65) so novel integrations stay calibrated
          combined = 0.65 * combined + 0.35 * denseSim;
        }
      }

      combined = Math.min(0.99, Math.max(0.0, combined));

      termContributions.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
      List<String> matchedDisplayTerms = new ArrayList<>();
      for (Map.Entry<String, Double> entry : termContributions) {
        String surface = querySurface.getOrDefault(entry.getKey(), entry.getKey());
        if (!matchedDisplayTerms.contains(surface)) {
          matchedDisplayTerms.add(surface);
        }
        if (matchedDisplayTerms.size() >= 6) {
          break;
        }
      }

      String caveat = detectCaveat(queryReportsNonDelivery, doc.ticket);
      scored.add(new ScoredCandidate(doc.ticket, combined, matchedDisplayTerms, caveat));
    }

    scored.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());

    boolean highUnseenSubjectRatio =
        !uniqueSubjStems.isEmpty()
            && ((double) unindexedSubjectEntities.size() / uniqueSubjStems.size()) >= 0.40;

    List<SimilarTicketMatch> primaryMatches = new ArrayList<>();
    List<SimilarTicketMatch> categoryNeighbors = new ArrayList<>();

    for (ScoredCandidate cand : scored) {
      if (cand.score < NEIGHBOR_FLOOR || cand.matchedTerms.isEmpty()) {
        continue;
      }
      ExtractedGuidance guidance =
          ResolutionExtractor.extract(cand.ticket.resolutionNotes(), cand.ticket.createdAt());

      boolean enoughMatchedSignals =
          queryWeights.size() <= 1 || cand.matchedTerms.size() >= 2;

      MatchTier tier;
      if (cand.score >= STRONG_THRESHOLD
          && enoughMatchedSignals
          && cand.caveat.isEmpty()
          && !highUnseenSubjectRatio) {
        tier = MatchTier.STRONG;
      } else if (cand.score >= RELATED_THRESHOLD) {
        tier = MatchTier.RELATED;
      } else {
        tier = MatchTier.WEAK;
      }

      SimilarTicketMatch match =
          new SimilarTicketMatch(
              cand.ticket,
              cand.score,
              tier,
              cand.matchedTerms,
              guidance.resolutionType(),
              guidance.suggestedAction(),
              guidance.rootCauseSummary(),
              cand.caveat);

      if (tier == MatchTier.STRONG || tier == MatchTier.RELATED) {
        if (primaryMatches.size() < limit) {
          primaryMatches.add(match);
        }
      } else if (categoryNeighbors.size() < 2) {
        categoryNeighbors.add(match);
      }
    }

    boolean noStrongMatch = primaryMatches.isEmpty();
    MatchTier overallTier =
        noStrongMatch ? MatchTier.NONE : primaryMatches.get(0).tier();

    String banner = buildBanner(overallTier, primaryMatches, categoryNeighbors, unindexedSubjectEntities);
    String modeLabel =
        usedEmbeddings
            ? "Hybrid BM25F + Char-3Gram + " + aiAssistClient.providerName() + " Embeddings"
            : "Hybrid BM25F + Char-3Gram (Deterministic)";

    String aiNote = "";
    if (useAi && aiAssistClient.isConfigured()) {
      List<SimilarTicketMatch> context =
          !primaryMatches.isEmpty() ? primaryMatches : categoryNeighbors;
      aiNote = aiAssistClient.summarizeGuidance(query, context).orElse("");
    }

    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    return new TriageResponse(
        query,
        overallTier,
        noStrongMatch,
        banner,
        primaryMatches,
        noStrongMatch ? categoryNeighbors : List.of(),
        unindexedSubjectEntities,
        aiNote,
        modeLabel,
        elapsedMs);
  }

  private static String detectCaveat(boolean queryReportsNonDelivery, HistoricalTicket doc) {
    String docText = doc.subject() + " " + doc.description() + " " + doc.resolutionNotes();
    if (queryReportsNonDelivery && TextPreprocessor.assumesArtifactReceived(docText)) {
      return "Stage mismatch check: Incoming ticket reports the email/message never arrived, "
          + "whereas " + doc.ticketId() + " occurred after the customer received the email. "
          + "Check outbound email delivery/suppression logs before applying "
          + doc.ticketId() + "'s post-receipt steps.";
    }
    return "";
  }

  private static String buildBanner(
      MatchTier overallTier,
      List<SimilarTicketMatch> primary,
      List<SimilarTicketMatch> neighbors,
      List<String> unindexedEntities) {
    if (overallTier == MatchTier.STRONG) {
      SimilarTicketMatch top = primary.get(0);
      return "Strong historical match found (" + top.ticket().ticketId()
          + " in '" + top.ticket().category() + "'). Review suggested action for consistent reply.";
    }
    if (overallTier == MatchTier.RELATED) {
      SimilarTicketMatch top = primary.get(0);
      if (!top.symptomCaveat().isEmpty()) {
        return "Related workflow match (" + top.ticket().ticketId()
            + "), but failure stage differs — read the symptom caveat carefully before replying.";
      }
      return "Related historical ticket(s) found. Verify root-cause alignment before applying.";
    }
    StringBuilder msg =
        new StringBuilder("No strong historical match cleared confidence threshold.");
    if (!unindexedEntities.isEmpty()) {
      msg.append(" Unseen product/integration term(s) in knowledge base: ")
          .append(String.join(", ", unindexedEntities))
          .append(".");
    }
    if (!neighbors.isEmpty()) {
      msg.append(" Showing low-confidence '")
          .append(neighbors.get(0).ticket().category())
          .append("' category playbooks for general troubleshooting only.");
    }
    return msg.toString();
  }

  private static IndexSnapshot buildSnapshot(List<HistoricalTicket> tickets) {
    int n = tickets.size();
    if (n == 0) {
      return new IndexSnapshot(List.of(), Map.of(), Map.of(), 1.0, 1.0);
    }

    Map<String, Integer> docFreq = new HashMap<>();
    Map<String, Integer> trigramDocFreq = new HashMap<>();
    LocalDate latestDate = findLatestDate(tickets);

    record RawDoc(
        HistoricalTicket ticket,
        Map<String, Double> weightedTf,
        double docLength,
        Set<String> trigrams,
        double recencyScore) {}

    List<RawDoc> rawDocs = new ArrayList<>(n);
    double totalLength = 0.0;

    for (HistoricalTicket t : tickets) {
      Map<String, Double> tf = new LinkedHashMap<>();
      addWeightedTokens(tf, TextPreprocessor.tokenize(t.subject()), SUBJECT_DOC_WEIGHT);
      addWeightedTokens(
          tf, TextPreprocessor.tokenize(String.join(" ", t.tags())), TAGS_DOC_WEIGHT);
      addWeightedTokens(tf, TextPreprocessor.expandCategory(t.category()), CATEGORY_DOC_WEIGHT);
      addWeightedTokens(tf, TextPreprocessor.tokenize(t.description()), DESCRIPTION_DOC_WEIGHT);
      addWeightedTokens(
          tf, TextPreprocessor.tokenize(t.resolutionNotes()), RESOLUTION_DOC_WEIGHT);

      double dl = tf.values().stream().mapToDouble(Double::doubleValue).sum();
      totalLength += dl;

      for (String term : tf.keySet()) {
        docFreq.merge(term, 1, Integer::sum);
      }

      List<String> coreTokens = new ArrayList<>();
      coreTokens.addAll(TextPreprocessor.tokenize(t.subject()));
      coreTokens.addAll(TextPreprocessor.tokenize(String.join(" ", t.tags())));
      coreTokens.addAll(TextPreprocessor.tokenize(t.description()));
      Set<String> trigrams = TextPreprocessor.charTrigrams(coreTokens);
      for (String tri : trigrams) {
        trigramDocFreq.merge(tri, 1, Integer::sum);
      }

      double recency = computeRecencyScore(t.createdAt(), latestDate);
      rawDocs.add(new RawDoc(t, tf, Math.max(1.0, dl), trigrams, recency));
    }

    Map<String, Double> idf = new HashMap<>(docFreq.size());
    for (Map.Entry<String, Integer> e : docFreq.entrySet()) {
      double df = e.getValue();
      idf.put(e.getKey(), Math.log(1.0 + (n - df + 0.5) / (df + 0.5)));
    }

    Map<String, Double> trigramIdf = new HashMap<>(trigramDocFreq.size());
    for (Map.Entry<String, Integer> e : trigramDocFreq.entrySet()) {
      double df = e.getValue();
      trigramIdf.put(e.getKey(), Math.log(1.0 + (n - df + 0.5) / (df + 0.5)));
    }

    double oovIdf = Math.log(1.0 + (n + 0.5) / 0.5);
    double avgDl = Math.max(1.0, totalLength / n);

    List<IndexedDoc> indexedDocs = new ArrayList<>(n);
    for (RawDoc rd : rawDocs) {
      Map<String, Double> triVec = buildTrigramVector(rd.trigrams, trigramIdf, oovIdf);
      indexedDocs.add(
          new IndexedDoc(rd.ticket, rd.weightedTf, rd.docLength, triVec, rd.recencyScore));
    }

    return new IndexSnapshot(indexedDocs, idf, trigramIdf, avgDl, oovIdf);
  }

  private static void addWeightedTokens(
      Map<String, Double> target, List<String> tokens, double weight) {
    for (String tok : tokens) {
      target.merge(tok, weight, Double::sum);
    }
  }

  private static Map<String, Double> buildTrigramVector(
      Set<String> trigrams, Map<String, Double> trigramIdf, double fallbackIdf) {
    if (trigrams.isEmpty()) {
      return Map.of();
    }
    Map<String, Double> raw = new HashMap<>(trigrams.size());
    double normSq = 0.0;
    for (String tri : trigrams) {
      double w = trigramIdf.getOrDefault(tri, fallbackIdf);
      raw.put(tri, w);
      normSq += w * w;
    }
    if (normSq == 0.0) {
      return Map.of();
    }
    double norm = Math.sqrt(normSq);
    Map<String, Double> normalized = new HashMap<>(raw.size());
    for (Map.Entry<String, Double> e : raw.entrySet()) {
      normalized.put(e.getKey(), e.getValue() / norm);
    }
    return normalized;
  }

  private static double cosineMap(Map<String, Double> unitA, Map<String, Double> unitB) {
    if (unitA.isEmpty() || unitB.isEmpty()) {
      return 0.0;
    }
    Map<String, Double> smaller = unitA.size() <= unitB.size() ? unitA : unitB;
    Map<String, Double> larger = smaller == unitA ? unitB : unitA;
    double dot = 0.0;
    for (Map.Entry<String, Double> e : smaller.entrySet()) {
      Double other = larger.get(e.getKey());
      if (other != null) {
        dot += e.getValue() * other;
      }
    }
    return dot;
  }

  private static LocalDate findLatestDate(List<HistoricalTicket> tickets) {
    LocalDate max = LocalDate.of(2025, 1, 1);
    for (HistoricalTicket t : tickets) {
      try {
        LocalDate d = LocalDate.parse(t.createdAt());
        if (d.isAfter(max)) {
          max = d;
        }
      } catch (Exception ignored) {
        // Ignore unparseable dates
      }
    }
    return max;
  }

  private static double computeRecencyScore(String createdAt, LocalDate referenceMax) {
    if (createdAt == null || createdAt.isBlank()) {
      return 0.5;
    }
    try {
      LocalDate d = LocalDate.parse(createdAt);
      long daysOld = Math.max(0L, ChronoUnit.DAYS.between(d, referenceMax));
      // Half-life of 90 days
      return Math.exp(-daysOld / 130.0);
    } catch (Exception ignored) {
      return 0.5;
    }
  }

  private record ScoredCandidate(
      HistoricalTicket ticket, double score, List<String> matchedTerms, String caveat) {}

  private record IndexedDoc(
      HistoricalTicket ticket,
      Map<String, Double> weightedTf,
      double docLength,
      Map<String, Double> trigramVec,
      double recencyScore) {}

  private record IndexSnapshot(
      List<IndexedDoc> docs,
      Map<String, Double> idf,
      Map<String, Double> trigramIdf,
      double avgDocLength,
      double oovIdf) {}
}
