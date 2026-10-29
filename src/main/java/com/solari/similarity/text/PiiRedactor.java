package com.solari.similarity.text;

import java.util.regex.Pattern;

/**
 * Scrubs common customer PII patterns (emails, bearer/API secrets, payment card numbers) before
 * tickets are indexed or displayed to other support agents.
 */
public final class PiiRedactor {

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");

  private static final Pattern SECRET_TOKEN_PATTERN =
      Pattern.compile("\\b(?:sk-[A-Za-z0-9]{16,}|Bearer\\s+[A-Za-z0-9._\\-]{16,})\\b");

  private static final Pattern CARD_NUMBER_PATTERN =
      Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");

  private PiiRedactor() {}

  public static String redact(String input) {
    if (input == null || input.isEmpty()) {
      return "";
    }
    String scrubbed = EMAIL_PATTERN.matcher(input).replaceAll("[EMAIL_REDACTED]");
    scrubbed = SECRET_TOKEN_PATTERN.matcher(scrubbed).replaceAll("[TOKEN_REDACTED]");
    scrubbed = CARD_NUMBER_PATTERN.matcher(scrubbed).replaceAll("[CARD_REDACTED]");
    return scrubbed;
  }
}
