package com.solari.similarity.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, zero-dependency RFC-8259 compliant JSON parser and pretty serializer.
 *
 * <p>Maps JSON objects to {@link LinkedHashMap}, arrays to {@link ArrayList}, strings to
 * {@link String}, numbers to {@link Double} or {@link Long}, booleans to {@link Boolean}, and
 * {@code null} to Java {@code null}.
 */
public final class JsonCodec {

  private JsonCodec() {}

  public static Object parse(String json) {
    if (json == null) {
      throw new IllegalArgumentException("JSON input cannot be null");
    }
    Parser parser = new Parser(json);
    Object value = parser.parseValue();
    parser.skipWhitespace();
    if (!parser.isAtEnd()) {
      throw new IllegalArgumentException(
          "Unexpected trailing content at position " + parser.pos);
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String json) {
    Object parsed = parse(json);
    if (!(parsed instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("Expected top-level JSON object");
    }
    return (Map<String, Object>) parsed;
  }

  public static String stringify(Object value) {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, value, 0, false);
    return sb.toString();
  }

  public static String prettyPrint(Object value) {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, value, 0, true);
    return sb.toString();
  }

  public static String getString(Map<String, Object> map, String key, String defaultValue) {
    if (map == null) {
      return defaultValue;
    }
    Object raw = map.get(key);
    if (raw == null) {
      return defaultValue;
    }
    return String.valueOf(raw);
  }

  public static int getInt(Map<String, Object> map, String key, int defaultValue) {
    if (map == null) {
      return defaultValue;
    }
    Object raw = map.get(key);
    if (raw instanceof Number num) {
      return num.intValue();
    }
    if (raw instanceof String s) {
      try {
        return Integer.parseInt(s.trim());
      } catch (NumberFormatException ignored) {
        return defaultValue;
      }
    }
    return defaultValue;
  }

  public static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
    if (map == null) {
      return defaultValue;
    }
    Object raw = map.get(key);
    if (raw instanceof Boolean b) {
      return b;
    }
    if (raw instanceof String s) {
      return Boolean.parseBoolean(s.trim());
    }
    return defaultValue;
  }

  public static List<String> getStringList(Map<String, Object> map, String key) {
    if (map == null) {
      return List.of();
    }
    Object raw = map.get(key);
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item != null) {
        String s = String.valueOf(item).trim();
        if (!s.isEmpty()) {
          result.add(s);
        }
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static void writeValue(StringBuilder sb, Object value, int indent, boolean pretty) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String s) {
      writeString(sb, s);
    } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
      sb.append(value);
    } else if (value instanceof Number num) {
      double d = num.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        sb.append("0.0");
      } else {
        sb.append(num);
      }
    } else if (value instanceof Map<?, ?> rawMap) {
      Map<Object, Object> map = (Map<Object, Object>) rawMap;
      if (map.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append('{');
      int count = 0;
      for (Map.Entry<Object, Object> entry : map.entrySet()) {
        if (count++ > 0) {
          sb.append(',');
        }
        if (pretty) {
          sb.append('\n');
          appendIndent(sb, indent + 2);
        }
        writeString(sb, String.valueOf(entry.getKey()));
        sb.append(pretty ? ": " : ":");
        writeValue(sb, entry.getValue(), indent + 2, pretty);
      }
      if (pretty) {
        sb.append('\n');
        appendIndent(sb, indent);
      }
      sb.append('}');
    } else if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        sb.append("[]");
        return;
      }
      sb.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        if (pretty) {
          sb.append('\n');
          appendIndent(sb, indent + 2);
        }
        writeValue(sb, list.get(i), indent + 2, pretty);
      }
      if (pretty) {
        sb.append('\n');
        appendIndent(sb, indent);
      }
      sb.append(']');
    } else {
      writeString(sb, String.valueOf(value));
    }
  }

  private static void appendIndent(StringBuilder sb, int spaces) {
    for (int i = 0; i < spaces; i++) {
      sb.append(' ');
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  private static final class Parser {
    private final String src;
    private int pos;

    Parser(String src) {
      this.src = src;
      this.pos = 0;
    }

    boolean isAtEnd() {
      return pos >= src.length();
    }

    void skipWhitespace() {
      while (pos < src.length()) {
        char c = src.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          pos++;
        } else {
          break;
        }
      }
    }

    Object parseValue() {
      skipWhitespace();
      if (isAtEnd()) {
        throw error("Unexpected end of JSON input");
      }
      char c = src.charAt(pos);
      return switch (c) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> parseString();
        case 't' -> parseLiteral("true", Boolean.TRUE);
        case 'f' -> parseLiteral("false", Boolean.FALSE);
        case 'n' -> parseLiteral("null", null);
        default -> {
          if (c == '-' || (c >= '0' && c <= '9')) {
            yield parseNumber();
          }
          throw error("Unexpected character '" + c + "'");
        }
      };
    }

    private Map<String, Object> parseObject() {
      expect('{');
      Map<String, Object> map = new LinkedHashMap<>();
      skipWhitespace();
      if (peek('}')) {
        pos++;
        return map;
      }
      while (true) {
        skipWhitespace();
        if (isAtEnd() || src.charAt(pos) != '"') {
          throw error("Expected quoted object key");
        }
        String key = parseString();
        skipWhitespace();
        expect(':');
        Object val = parseValue();
        map.put(key, val);
        skipWhitespace();
        if (peek(',')) {
          pos++;
        } else if (peek('}')) {
          pos++;
          break;
        } else {
          throw error("Expected ',' or '}' in object");
        }
      }
      return map;
    }

    private List<Object> parseArray() {
      expect('[');
      List<Object> list = new ArrayList<>();
      skipWhitespace();
      if (peek(']')) {
        pos++;
        return list;
      }
      while (true) {
        list.add(parseValue());
        skipWhitespace();
        if (peek(',')) {
          pos++;
        } else if (peek(']')) {
          pos++;
          break;
        } else {
          throw error("Expected ',' or ']' in array");
        }
      }
      return list;
    }

    private String parseString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (pos < src.length()) {
        char c = src.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          if (pos >= src.length()) {
            throw error("Unterminated escape sequence");
          }
          char esc = src.charAt(pos++);
          switch (esc) {
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case '/' -> sb.append('/');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' -> {
              if (pos + 4 > src.length()) {
                throw error("Incomplete unicode escape");
              }
              String hex = src.substring(pos, pos + 4);
              pos += 4;
              try {
                sb.append((char) Integer.parseInt(hex, 16));
              } catch (NumberFormatException e) {
                throw error("Invalid unicode escape \\u" + hex);
              }
            }
            default -> throw error("Invalid escape character '\\" + esc + "'");
          }
        } else {
          sb.append(c);
        }
      }
      throw error("Unterminated string literal");
    }

    private Object parseLiteral(String literal, Object result) {
      if (src.startsWith(literal, pos)) {
        pos += literal.length();
        return result;
      }
      throw error("Expected '" + literal + "'");
    }

    private Number parseNumber() {
      int start = pos;
      if (peek('-')) {
        pos++;
      }
      if (isAtEnd() || !Character.isDigit(src.charAt(pos))) {
        throw error("Invalid number");
      }
      while (!isAtEnd() && Character.isDigit(src.charAt(pos))) {
        pos++;
      }
      boolean isFloating = false;
      if (!isAtEnd() && src.charAt(pos) == '.') {
        isFloating = true;
        pos++;
        if (isAtEnd() || !Character.isDigit(src.charAt(pos))) {
          throw error("Invalid fractional number");
        }
        while (!isAtEnd() && Character.isDigit(src.charAt(pos))) {
          pos++;
        }
      }
      if (!isAtEnd() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
        isFloating = true;
        pos++;
        if (!isAtEnd() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
          pos++;
        }
        if (isAtEnd() || !Character.isDigit(src.charAt(pos))) {
          throw error("Invalid exponent in number");
        }
        while (!isAtEnd() && Character.isDigit(src.charAt(pos))) {
          pos++;
        }
      }
      String token = src.substring(start, pos);
      try {
        if (isFloating) {
          return Double.parseDouble(token);
        }
        return Long.parseLong(token);
      } catch (NumberFormatException e) {
        return Double.parseDouble(token);
      }
    }

    private boolean peek(char expected) {
      return !isAtEnd() && src.charAt(pos) == expected;
    }

    private void expect(char expected) {
      if (isAtEnd() || src.charAt(pos) != expected) {
        throw error("Expected '" + expected + "'");
      }
      pos++;
    }

    private IllegalArgumentException error(String msg) {
      return new IllegalArgumentException(msg + " at position " + pos);
    }
  }
}
