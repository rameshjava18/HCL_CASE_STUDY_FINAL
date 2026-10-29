package com.solari.similarity.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete triage response returned to the CLI, REST API, or Agent Triage Web UI.
 */
public record TriageResponse(
    IncomingTicket query,
    MatchTier overallTier,
    boolean noStrongMatch,
    String bannerMessage,
    List<SimilarTicketMatch> primaryMatches,
    List<SimilarTicketMatch> categoryNeighbors,
    List<String> unindexedEntities,
    String aiAdvisoryNote,
    String retrievalMode,
    long latencyMs) {

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("query", query.toMap());
    map.put("overall_tier", overallTier.code());
    map.put("overall_tier_label", overallTier.displayLabel());
    map.put("no_strong_match", noStrongMatch);
    map.put("banner_message", bannerMessage);
    map.put("unindexed_entities", unindexedEntities);
    map.put(
        "primary_matches",
        primaryMatches.stream().map(SimilarTicketMatch::toMap).toList());
    map.put(
        "category_neighbors",
        categoryNeighbors.stream().map(SimilarTicketMatch::toMap).toList());
    map.put("ai_advisory_note", aiAdvisoryNote == null ? "" : aiAdvisoryNote);
    map.put("retrieval_mode", retrievalMode);
    map.put("latency_ms", latencyMs);
    return map;
  }
}
