package com.solari.similarity.model;

/**
 * Calibrated confidence tier for historical ticket matches.
 */
public enum MatchTier {
  /** High-confidence match: aligned product area, symptom, and likely reusable resolution. */
  STRONG("STRONG", "Strong Match"),
  /** Medium-confidence or stage-divergent match: useful context, verify root cause before applying. */
  RELATED("RELATED", "Related Match — Verify Nuance"),
  /** Low-confidence category neighbor shown only as optional background context. */
  WEAK("WEAK", "Category Neighbor (Low Confidence)"),
  /** Returned at the response level when no candidate clears the relevance floor. */
  NONE("NONE", "No Strong Historical Match");

  private final String code;
  private final String displayLabel;

  MatchTier(String code, String displayLabel) {
    this.code = code;
    this.displayLabel = displayLabel;
  }

  public String code() {
    return code;
  }

  public String displayLabel() {
    return displayLabel;
  }
}
