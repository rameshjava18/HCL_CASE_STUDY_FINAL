package com.solari.similarity.store;

import com.solari.similarity.model.FeedbackEntry;
import com.solari.similarity.model.HistoricalTicket;
import com.solari.similarity.model.IncomingTicket;
import com.solari.similarity.util.JsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe repository managing historical tickets, example incoming tickets, live ticket
 * resolution persistence, and agent relevance feedback.
 *
 * <p>Seed data ({@code data/tickets.json}) is never mutated; newly resolved tickets added during
 * live sessions are persisted to {@code data/runtime_tickets.json} and feedback to
 * {@code data/feedback_log.json}.
 */
public final class TicketRepository {

  private final Path seedTicketsPath;
  private final Path runtimeTicketsPath;
  private final Path feedbackLogPath;
  private final List<HistoricalTicket> tickets = new ArrayList<>();
  private final List<FeedbackEntry> feedbackEntries = new ArrayList<>();
  private final AtomicInteger sequence = new AtomicInteger(2000);

  public TicketRepository(Path seedTicketsPath, Path runtimeTicketsPath, Path feedbackLogPath)
      throws IOException {
    this.seedTicketsPath = seedTicketsPath;
    this.runtimeTicketsPath = runtimeTicketsPath;
    this.feedbackLogPath = feedbackLogPath;
    reload();
  }

  public static TicketRepository fromDataDirectory(Path dataDir) throws IOException {
    return new TicketRepository(
        dataDir.resolve("tickets.json"),
        dataDir.resolve("runtime_tickets.json"),
        dataDir.resolve("feedback_log.json"));
  }

  public synchronized void reload() throws IOException {
    tickets.clear();
    feedbackEntries.clear();

    if (seedTicketsPath != null && Files.exists(seedTicketsPath)) {
      tickets.addAll(loadHistoricalTickets(seedTicketsPath));
    }
    if (runtimeTicketsPath != null && Files.exists(runtimeTicketsPath)) {
      Map<String, HistoricalTicket> merged = new LinkedHashMap<>();
      for (HistoricalTicket t : tickets) {
        merged.put(t.ticketId(), t);
      }
      for (HistoricalTicket rt : loadHistoricalTickets(runtimeTicketsPath)) {
        merged.put(rt.ticketId(), rt);
      }
      tickets.clear();
      tickets.addAll(merged.values());
    }
    if (feedbackLogPath != null && Files.exists(feedbackLogPath)) {
      feedbackEntries.addAll(loadFeedback(feedbackLogPath));
    }
  }

  public synchronized List<HistoricalTicket> allTickets() {
    return List.copyOf(tickets);
  }

  public synchronized List<HistoricalTicket> eligibleResolvedTickets() {
    return tickets.stream().filter(HistoricalTicket::isEligibleForIndexing).toList();
  }

  public synchronized HistoricalTicket addResolvedTicket(
      String ticketId,
      String category,
      String subject,
      String description,
      String resolutionNotes,
      List<String> tags)
      throws IOException {
    String assignedId =
        (ticketId == null || ticketId.isBlank())
            ? "T-" + sequence.incrementAndGet()
            : ticketId.trim();
    HistoricalTicket created =
        new HistoricalTicket(
            assignedId,
            category,
            subject,
            description,
            resolutionNotes,
            tags,
            LocalDate.now().toString(),
            "resolved");
    if (!created.isEligibleForIndexing()) {
      throw new IllegalArgumentException(
          "Resolved ticket requires non-empty subject/description and resolution_notes.");
    }
    tickets.removeIf(existing -> existing.ticketId().equals(created.ticketId()));
    tickets.add(created);
    persistRuntimeTickets();
    return created;
  }

  public synchronized FeedbackEntry recordFeedback(FeedbackEntry entry) throws IOException {
    if (entry.matchedTicketId().isEmpty()) {
      throw new IllegalArgumentException("matched_ticket_id is required");
    }
    feedbackEntries.add(entry);
    persistFeedback();
    return entry;
  }

  public synchronized List<FeedbackEntry> allFeedback() {
    return Collections.unmodifiableList(new ArrayList<>(feedbackEntries));
  }

  @SuppressWarnings("unchecked")
  public static List<HistoricalTicket> loadHistoricalTickets(Path path) throws IOException {
    String raw = Files.readString(path, StandardCharsets.UTF_8);
    Map<String, Object> root = JsonCodec.parseObject(raw);
    Object listObj = root.get("tickets");
    if (!(listObj instanceof List<?> list)) {
      return List.of();
    }
    List<HistoricalTicket> loaded = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item instanceof Map<?, ?> itemMap) {
        loaded.add(HistoricalTicket.fromMap((Map<String, Object>) itemMap));
      }
    }
    return loaded;
  }

  @SuppressWarnings("unchecked")
  public static List<IncomingTicket> loadIncomingTickets(Path path) throws IOException {
    String raw = Files.readString(path, StandardCharsets.UTF_8);
    Map<String, Object> root = JsonCodec.parseObject(raw);
    Object listObj = root.containsKey("new_tickets") ? root.get("new_tickets") : root.get("tickets");
    if (!(listObj instanceof List<?> list)) {
      return List.of();
    }
    List<IncomingTicket> loaded = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item instanceof Map<?, ?> itemMap) {
        loaded.add(IncomingTicket.fromMap((Map<String, Object>) itemMap));
      }
    }
    return loaded;
  }

  @SuppressWarnings("unchecked")
  private static List<FeedbackEntry> loadFeedback(Path path) throws IOException {
    String raw = Files.readString(path, StandardCharsets.UTF_8);
    Map<String, Object> root = JsonCodec.parseObject(raw);
    Object listObj = root.get("feedback");
    if (!(listObj instanceof List<?> list)) {
      return List.of();
    }
    List<FeedbackEntry> loaded = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item instanceof Map<?, ?> itemMap) {
        loaded.add(FeedbackEntry.fromMap((Map<String, Object>) itemMap));
      }
    }
    return loaded;
  }

  private void persistRuntimeTickets() throws IOException {
    if (runtimeTicketsPath == null) {
      return;
    }
    List<String> seedIds =
        (seedTicketsPath != null && Files.exists(seedTicketsPath))
            ? loadHistoricalTickets(seedTicketsPath).stream()
                .map(HistoricalTicket::ticketId)
                .toList()
            : List.of();

    List<Map<String, Object>> runtimeOnly =
        tickets.stream()
            .filter(t -> !seedIds.contains(t.ticketId()))
            .map(HistoricalTicket::toMap)
            .toList();

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("tickets", runtimeOnly);
    if (runtimeTicketsPath.getParent() != null) {
      Files.createDirectories(runtimeTicketsPath.getParent());
    }
    Files.writeString(runtimeTicketsPath, JsonCodec.prettyPrint(payload), StandardCharsets.UTF_8);
  }

  private void persistFeedback() throws IOException {
    if (feedbackLogPath == null) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("feedback", feedbackEntries.stream().map(FeedbackEntry::toMap).toList());
    if (feedbackLogPath.getParent() != null) {
      Files.createDirectories(feedbackLogPath.getParent());
    }
    Files.writeString(feedbackLogPath, JsonCodec.prettyPrint(payload), StandardCharsets.UTF_8);
  }
}
