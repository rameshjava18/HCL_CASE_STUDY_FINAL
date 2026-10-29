package com.solari.similarity.model;

import com.solari.similarity.text.PiiRedactor;
import com.solari.similarity.util.JsonCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable representation of a historical support ticket in Solari's knowledge base.
 */
public record HistoricalTicket(
    String ticketId,
    String category,
    String subject,
    String description,
    String resolutionNotes,
    List<String> tags,
    String createdAt,
    String status) {

  public HistoricalTicket {
    ticketId = safeTrim(ticketId);
    category = safeTrim(category).isEmpty() ? "general" : safeTrim(category).toLowerCase();
    subject = PiiRedactor.redact(safeTrim(subject));
    description = PiiRedactor.redact(safeTrim(description));
    resolutionNotes = PiiRedactor.redact(safeTrim(resolutionNotes));
    tags = tags == null ? List.of() : List.copyOf(tags);
    createdAt = safeTrim(createdAt);
    status = safeTrim(status).isEmpty() ? "resolved" : safeTrim(status).toLowerCase();
  }

  public boolean isEligibleForIndexing() {
    return "resolved".equalsIgnoreCase(status)
        && !ticketId.isEmpty()
        && (!subject.isEmpty() || !description.isEmpty())
        && !resolutionNotes.isEmpty();
  }

  public static HistoricalTicket fromMap(Map<String, Object> map) {
    return new HistoricalTicket(
        JsonCodec.getString(map, "ticket_id", ""),
        JsonCodec.getString(map, "category", "general"),
        JsonCodec.getString(map, "subject", ""),
        JsonCodec.getString(map, "description", ""),
        JsonCodec.getString(map, "resolution_notes", ""),
        JsonCodec.getStringList(map, "tags"),
        JsonCodec.getString(map, "created_at", ""),
        JsonCodec.getString(map, "status", "resolved"));
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("ticket_id", ticketId);
    map.put("category", category);
    map.put("subject", subject);
    map.put("description", description);
    map.put("resolution_notes", resolutionNotes);
    map.put("tags", tags);
    map.put("created_at", createdAt);
    map.put("status", status);
    return map;
  }

  private static String safeTrim(String s) {
    return s == null ? "" : s.trim();
  }
}
