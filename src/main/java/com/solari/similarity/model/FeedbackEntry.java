package com.solari.similarity.model;

import com.solari.similarity.util.JsonCodec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent feedback event recording whether a surfaced historical ticket helped resolve the case.
 */
public record FeedbackEntry(
    String incomingTicketId,
    String matchedTicketId,
    boolean helpful,
    String note,
    String createdAt) {

  public FeedbackEntry {
    incomingTicketId = incomingTicketId == null ? "" : incomingTicketId.trim();
    matchedTicketId = matchedTicketId == null ? "" : matchedTicketId.trim();
    note = note == null ? "" : note.trim();
    createdAt = createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt;
  }

  public static FeedbackEntry fromMap(Map<String, Object> map) {
    return new FeedbackEntry(
        JsonCodec.getString(map, "incoming_ticket_id", ""),
        JsonCodec.getString(map, "matched_ticket_id", ""),
        JsonCodec.getBoolean(map, "helpful", true),
        JsonCodec.getString(map, "note", ""),
        JsonCodec.getString(map, "created_at", Instant.now().toString()));
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("incoming_ticket_id", incomingTicketId);
    map.put("matched_ticket_id", matchedTicketId);
    map.put("helpful", helpful);
    map.put("note", note);
    map.put("created_at", createdAt);
    return map;
  }
}
