package util;

import java.io.PrintStream;

public final class SecurityLog {
  private SecurityLog() { }

  public static void error(String operation, Throwable error) {
    write("ERROR", operation, error, isProduction(), System.err);
  }

  public static void warn(String operation, Throwable error) {
    write("WARN", operation, error, isProduction(), System.err);
  }

  public static void write(String level, String operation, Throwable error,
      boolean production, PrintStream output) {
    String safeLevel = "WARN".equals(level) ? "WARN" : "ERROR";
    String safeOperation = safeOperation(operation);
    String errorType = error == null ? "UnknownError" : error.getClass().getSimpleName();
    output.println("[" + safeLevel + "] " + safeOperation + " (type=" + errorType + ")");
    if (!production && error != null) error.printStackTrace(output);
  }

  private static boolean isProduction() {
    return "production".equalsIgnoreCase(System.getenv("APP_ENV"));
  }

  private static String safeOperation(String operation) {
    if (operation == null || operation.isBlank()) return "Application operation failed";
    return operation.replaceAll("[^A-Za-z0-9 _./:-]", "_");
  }
}
