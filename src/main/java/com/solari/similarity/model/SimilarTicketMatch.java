package com.solari.similarity.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranked similarity result pairing a historical ticket with explainability signals and extracted
 * resolution guidance.
 */
public record SimilarTicketMatch(
    HistoricalTicket ticket,
    double score,
    MatchTier tier,
    List<String> matchedTerms,
    String resolutionType,
    String suggestedAction,
    String rootCauseSummary,
    String symptomCaveat) {

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("ticket_id", ticket.ticketId());
    map.put("category", ticket.category());
    map.put("subject", ticket.subject());
    map.put("description", ticket.description());
    map.put("resolution_notes", ticket.resolutionNotes());
    map.put("tags", ticket.tags());
    map.put("created_at", ticket.createdAt());
    map.put("score", Math.round(score * 1000.0) / 1000.0);
    map.put("tier", tier.code());
    map.put("tier_label", tier.displayLabel());
    map.put("matched_terms", matchedTerms);
    map.put("resolution_type", resolutionType);
    map.put("suggested_action", suggestedAction);
    map.put("root_cause_summary", rootCauseSummary);
    map.put("symptom_caveat", symptomCaveat == null ? "" : symptomCaveat);
    return map;
  }
}
