package com.solari.similarity.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits historical {@code resolution_notes} into an actionable step for the triaging support
 * agent and a concise historical root-cause summary, distinguishing repeatable customer playbooks
 * from previously shipped engineering bug fixes.
 */
public final class ResolutionExtractor {

  private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");

  private static final Pattern PLAYBOOK_ACTION_PREFIX =
      Pattern.compile(
          "^(?:advised|walked|switched|explained|recommended|updated\\s+the\\s+setting"
              + "|had\\s+them|assigned|clarified|manually|refunded|issued)",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern CODE_FIX_CUE =
      Pattern.compile(
          "\\b(?:added\\s+(?:an?|the|missing|row-level|idempotency|exponential|full-text|session)"
              + "|fixed\\s+the|restored\\s+an|moved\\s+large|adjusted\\s+the|scaled\\s+out"
              + "|removed\\s+the\\s+limit|cached\\s+the|made\\s+widget\\s+loads)\\b",
          Pattern.CASE_INSENSITIVE);

  private ResolutionExtractor() {}

  public record ExtractedGuidance(
      String resolutionType, String suggestedAction, String rootCauseSummary) {}

  public static ExtractedGuidance extract(String resolutionNotes, String createdAt) {
    if (resolutionNotes == null || resolutionNotes.isBlank()) {
      return new ExtractedGuidance(
          "Unresolved",
          "No resolution notes recorded on this ticket.",
          "Unknown root cause.");
    }

    String trimmed = resolutionNotes.trim();
    String[] rawSentences = SENTENCE_SPLIT.split(trimmed);
    List<String> sentences = new ArrayList<>();
    for (String s : rawSentences) {
      if (!s.isBlank()) {
        sentences.add(s.trim());
      }
    }

    boolean hasCodeFix = CODE_FIX_CUE.matcher(trimmed).find();
    String firstSentence = sentences.get(0);
    String actionSentence = null;
    for (String s : sentences) {
      if (PLAYBOOK_ACTION_PREFIX.matcher(s).find()) {
        actionSentence = s;
        break;
      }
    }

    if (actionSentence != null && !hasCodeFix) {
      String rootCause = sentences.size() > 1 && !sentences.get(0).equals(actionSentence)
          ? sentences.get(0)
          : "Customer configuration or workflow issue.";
      return new ExtractedGuidance("Support Playbook", actionSentence, rootCause);
    }

    if (actionSentence != null) {
      String dateNote = (createdAt != null && !createdAt.isBlank()) ? " (" + createdAt + ")" : "";
      return new ExtractedGuidance(
          "Past Fix + Customer Action",
          actionSentence + " Verify whether the prior fix" + dateNote + " regressed.",
          firstSentence);
    }

    if (hasCodeFix) {
      String secondPart = sentences.size() > 1 ? sentences.get(1) : trimmed;
      String dateNote = (createdAt != null && !createdAt.isBlank()) ? " on " + createdAt : "";
      String lower = secondPart.substring(0, 1).toLowerCase(Locale.ROOT) + secondPart.substring(1);
      return new ExtractedGuidance(
          "Prior Engineering Fix",
          "Confirm if customer hit an unpatched edge case or regression (previously fixed"
              + dateNote + ": " + lower + ")",
          firstSentence);
    }

    String action = sentences.size() > 1 ? sentences.get(1) : firstSentence;
    return new ExtractedGuidance("Support Playbook", action, firstSentence);
  }
}
