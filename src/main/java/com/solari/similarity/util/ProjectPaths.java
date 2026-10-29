package com.solari.similarity.util;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves project data and static UI paths reliably regardless of whether the application is
 * launched from the repository root, a parent workspace folder in VS Code, Maven {@code target/},
 * or {@code out/main}.
 */
public final class ProjectPaths {

  private ProjectPaths() {}

  public static Path resolveProjectRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    if (hasTicketsJson(cwd)) {
      return cwd;
    }
    Path nested = cwd.resolve("support-ticket-similarity");
    if (hasTicketsJson(nested)) {
      return nested;
    }
    try {
      URI codeUri =
          ProjectPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
      Path cursor = Path.of(codeUri).toAbsolutePath().normalize();
      for (int i = 0; i < 5 && cursor != null; i++) {
        if (hasTicketsJson(cursor)) {
          return cursor;
        }
        cursor = cursor.getParent();
      }
    } catch (Exception ignored) {
      // Fall back to current working directory
    }
    return cwd;
  }

  public static Path resolveDefaultDataDir() {
    return resolveProjectRoot().resolve("data");
  }

  public static Path resolveStaticIndexHtml() {
    return resolveProjectRoot().resolve("src/main/resources/static/index.html");
  }

  private static boolean hasTicketsJson(Path dir) {
    return dir != null && Files.exists(dir.resolve("data/tickets.json"));
  }
}
