package com.solari.similarity.model;

import com.solari.similarity.util.JsonCodec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable representation of a newly arrived incoming support ticket to triage.
 */
public record IncomingTicket(String ticketId, String subject, String description) {

  public IncomingTicket {
    ticketId = ticketId == null || ticketId.isBlank() ? "NEW" : ticketId.trim();
    subject = subject == null ? "" : subject.trim();
    description = description == null ? "" : description.trim();
  }

  public boolean isBlank() {
    return subject.isEmpty() && description.isEmpty();
  }

  public static IncomingTicket fromMap(Map<String, Object> map) {
    return new IncomingTicket(
        JsonCodec.getString(map, "ticket_id", "NEW"),
        JsonCodec.getString(map, "subject", ""),
        JsonCodec.getString(map, "description", ""));
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("ticket_id", ticketId);
    map.put("subject", subject);
    map.put("description", description);
    return map;
  }
}
