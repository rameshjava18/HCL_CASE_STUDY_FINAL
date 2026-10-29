package com.solari.similarity.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain-aware text normalizer, stemmer, synonym mapper, and character n-gram extractor for SaaS
 * support tickets.
 *
 * <p>Addresses specific tokenizing defects in the starter kit's {@code ticket_utils.py}:
 * <ul>
 *   <li>Expands contractions and strips possessives before punctuation removal (eliminating orphan
 *       {@code 's} and {@code 't} tokens).
 *   <li>Unifies multi-word and hyphenated SaaS terms ({@code "log in"} == {@code "login"},
 *       {@code "re-sync"} == {@code "resync"}, {@code "two-factor"} == {@code "2fa"}).
 *   <li>Filters English and support-boilerplate stopwords ({@code "customer"}, {@code "the"}, etc.).
 *   <li>Maps common support paraphrases ({@code "nothing"}/{@code "empty"} -> {@code "blank"},
 *       {@code "twice"}/{@code "double"} -> {@code "duplicate"}).
 * </ul>
 */
public final class TextPreprocessor {

  private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+");

  private static final List<Map.Entry<Pattern, String>> COMPOUND_NORMALIZATIONS =
      List.of(
          Map.entry(Pattern.compile("\\blog(?:ged|ging)?\\s+in\\b"), "login"),
          Map.entry(Pattern.compile("\\bsign(?:ed|ing)?\\s+in\\b"), "login"),
          Map.entry(Pattern.compile("\\blog(?:ged|ging)?\\s+out\\b"), "logout"),
          Map.entry(Pattern.compile("\\bre[-\\s]+sync(?:ed|ing)?\\b"), "resync"),
          Map.entry(Pattern.compile("\\btwo[-\\s]+factor\\b"), "2fa"),
          Map.entry(Pattern.compile("\\bread[-\\s]+only\\b"), "readonly"),
          Map.entry(Pattern.compile("\\brate[-\\s]+limits?\\b"), "ratelimit"),
          Map.entry(Pattern.compile("\\bpassword[-\\s]+reset\\b"), "password reset"),
          Map.entry(Pattern.compile("\\bduplicate[-\\s]+charges?\\b"), "duplicate charge"),
          Map.entry(Pattern.compile("\\bapi[-\\s]+keys?\\b"), "apikey"),
          Map.entry(Pattern.compile("\\b500[-\\s]+errors?\\b"), "500 error"));

  private static final Set<String> STOPWORDS =
      Set.of(
          "a", "about", "acros", "across", "after", "again", "ago", "all", "also", "an", "and",
          "any", "appear", "appears", "are", "as", "at", "back", "be", "been", "being", "both",
          "but", "by", "can", "cannot", "could", "customer", "customers", "did", "do", "does",
          "doing", "done", "each", "even", "every", "few", "fine", "for", "from", "get", "gets",
          "getting", "given", "got", "had", "has", "have", "having", "how", "if", "in", "into",
          "is", "issue", "issues", "it", "its", "just", "know", "last", "latest", "like", "made",
          "main", "make", "making", "many", "more", "most", "much", "need", "needs", "new",
          "newer", "next", "no", "not", "noticed", "notices", "now", "of", "on", "one", "only",
          "or", "other", "our", "out", "over", "own", "past", "per", "previous", "previously",
          "recent", "recently", "same", "say", "says", "see", "seen", "sees", "set", "several",
          "should", "show", "shown", "shows", "showing", "since", "so", "some", "started", "still",
          "such", "system", "than", "that", "the", "their", "them", "then", "there", "these",
          "they", "this", "those", "though", "through", "ticket", "tickets", "time", "times", "to",
          "too", "tried", "trying", "type", "unable", "under", "up", "used", "using", "very",
          "via", "want", "wants", "was", "way", "we", "well", "were", "what", "when", "where",
          "which", "while", "who", "will", "with", "without", "work", "working", "works", "would",
          "yet");

  private static final Map<String, String> SYNONYMS =
      Map.ofEntries(
          Map.entry("nothing", "blank"),
          Map.entry("empty", "blank"),
          Map.entry("whitespace", "blank"),
          Map.entry("twice", "duplicate"),
          Map.entry("double", "duplicate"),
          Map.entry("two", "duplicate"),
          Map.entry("sluggish", "slow"),
          Map.entry("unresponsive", "slow"),
          Map.entry("arrive", "deliver"),
          Map.entry("arrives", "deliver"),
          Map.entry("arriving", "deliver"),
          Map.entry("arrived", "deliver"),
          Map.entry("receive", "deliver"),
          Map.entry("received", "deliver"),
          Map.entry("receiving", "deliver"),
          Map.entry("disabled", "greyout"),
          Map.entry("greyed", "greyout"),
          Map.entry("grayed", "greyout"));

  private static final Map<String, List<String>> CATEGORY_EXPANSIONS =
      Map.of(
          "auth", List.of("auth", "login", "password", "authentication"),
          "sync", List.of("sync", "integration", "connector"),
          "render", List.of("render", "chart", "dashboard", "widget"),
          "billing", List.of("billing", "invoice", "charge", "payment"),
          "export", List.of("export", "csv", "pdf", "download"),
          "perm", List.of("permission", "role", "access"),
          "perf", List.of("performance", "slow", "timeout", "latency"),
          "api", List.of("api", "webhook", "endpoint"));

  private static final Pattern NON_DELIVERY_CUE =
      Pattern.compile(
          "\\b(?:never\\s+(?:arrives?|arrived|received|got|sent|shows?\\s+up)"
              + "|not\\s+(?:being\\s+)?(?:received|arriving|delivered|sent)"
              + "|hasn'?t\\s+(?:shown\\s+up|arrived|been\\s+received)"
              + "|stopped\\s+arriving|missing\\s+email|email\\s+never)\\b",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern POST_RECEIPT_CUE =
      Pattern.compile(
          "\\b(?:via\\s+the\\s+email\\s+link|reset\\s+confirmation\\s+email\\s+displayed"
              + "|clicked\\s+(?:the\\s+)?link|after\\s+password\\s+reset)\\b",
          Pattern.CASE_INSENSITIVE);

  private TextPreprocessor() {}

  public record ProcessedTokens(List<String> stems, Map<String, String> stemToSurface) {}

  public static ProcessedTokens process(String rawText) {
    if (rawText == null || rawText.isBlank()) {
      return new ProcessedTokens(List.of(), Map.of());
    }
    String cleaned = preClean(rawText);
    Matcher matcher = TOKEN_PATTERN.matcher(cleaned);
    List<String> stems = new ArrayList<>();
    Map<String, String> stemToSurface = new LinkedHashMap<>();

    while (matcher.find()) {
      String surface = matcher.group();
      if (surface.length() <= 1 || STOPWORDS.contains(surface)) {
        continue;
      }
      String mapped = SYNONYMS.getOrDefault(surface, surface);
      String stem = stemWord(mapped);
      if (stem.length() <= 1 || STOPWORDS.contains(stem)) {
        continue;
      }
      stems.add(stem);
      stemToSurface.putIfAbsent(stem, surface);
    }
    return new ProcessedTokens(stems, stemToSurface);
  }

  public static List<String> tokenize(String rawText) {
    return process(rawText).stems();
  }

  public static List<String> expandCategory(String category) {
    if (category == null || category.isBlank()) {
      return List.of();
    }
    String key = category.trim().toLowerCase(Locale.ROOT);
    List<String> expanded = CATEGORY_EXPANSIONS.getOrDefault(key, List.of(key));
    List<String> stems = new ArrayList<>(expanded.size());
    for (String word : expanded) {
      stems.add(stemWord(SYNONYMS.getOrDefault(word, word)));
    }
    return stems;
  }

  public static Set<String> charTrigrams(List<String> tokens) {
    Set<String> trigrams = new LinkedHashSet<>();
    for (String token : tokens) {
      String padded = "^" + token + "$";
      for (int i = 0; i <= padded.length() - 3; i++) {
        trigrams.add(padded.substring(i, i + 3));
      }
    }
    return trigrams;
  }

  public static boolean hasNonDeliverySymptom(String text) {
    return text != null && NON_DELIVERY_CUE.matcher(text).find();
  }

  public static boolean assumesArtifactReceived(String text) {
    return text != null && POST_RECEIPT_CUE.matcher(text).find();
  }

  private static String preClean(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    // Normalize curly apostrophes to ASCII apostrophe
    lower = lower.replace('\u2019', '\'').replace('\u2018', '\'');
    // Expand contractions and strip possessives BEFORE punctuation removal
    lower = lower.replaceAll("\\bcan't\\b", "cannot");
    lower = lower.replaceAll("\\bwon't\\b", "will not");
    lower = lower.replaceAll("n't\\b", " not");
    lower = lower.replaceAll("'s\\b", "");
    lower = lower.replaceAll("'(?:re|ve|ll|d|m)\\b", "");

    for (Map.Entry<Pattern, String> entry : COMPOUND_NORMALIZATIONS) {
      lower = entry.getKey().matcher(lower).replaceAll(entry.getValue());
    }
    return lower;
  }

  static String stemWord(String word) {
    if (word.length() <= 3 || Character.isDigit(word.charAt(0))) {
      return word;
    }
    if (word.endsWith("ies") && word.length() > 4) {
      return word.substring(0, word.length() - 3) + "y";
    }
    if (word.endsWith("ing") && word.length() > 5) {
      String base = word.substring(0, word.length() - 3);
      if (base.endsWith("at") || base.endsWith("iz")) {
        return base + "e";
      }
      if (base.length() >= 3 && base.charAt(base.length() - 1) == base.charAt(base.length() - 2)) {
        return base.substring(0, base.length() - 1);
      }
      return base;
    }
    if (word.endsWith("ed") && word.length() > 4) {
      String base = word.substring(0, word.length() - 2);
      if (base.endsWith("charg") || base.endsWith("updat") || base.endsWith("disabl")
          || base.endsWith("enabl") || base.endsWith("expir") || base.endsWith("cach")
          || base.endsWith("schedul") || base.endsWith("invit") || base.endsWith("revok")) {
        return base + "e";
      }
      if (base.endsWith("i")) {
        return base.substring(0, base.length() - 1) + "y";
      }
      return base;
    }
    if (word.endsWith("es") && word.length() > 4) {
      if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes")
          || word.endsWith("zes") || word.endsWith("ses")) {
        return word.substring(0, word.length() - 2);
      }
      return word.substring(0, word.length() - 1);
    }
    if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && word.length() > 3) {
      return word.substring(0, word.length() - 1);
    }
    return word;
  }
}
