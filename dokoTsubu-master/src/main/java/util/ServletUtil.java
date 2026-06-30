package util;

import jakarta.servlet.http.HttpServletRequest;

public final class ServletUtil {
  private ServletUtil() { }

  public static String baseUrl(HttpServletRequest req) {
    String configured = System.getenv("APP_BASE_URL");
    if (configured == null || configured.isBlank()) configured = System.getProperty("APP_BASE_URL");
    if (configured != null && !configured.isBlank()) return configured.replaceAll("/+$", "");
    String appEnv = System.getenv("APP_ENV");
    if (appEnv == null || appEnv.isBlank()) appEnv = System.getProperty("APP_ENV", "");
    if ("production".equalsIgnoreCase(appEnv)) {
      throw new IllegalStateException("APP_BASE_URL is not configured");
    }
    int port = req.getServerPort();
    String portPart = ("http".equals(req.getScheme()) && port == 80) || ("https".equals(req.getScheme()) && port == 443) ? "" : ":" + port;
    return req.getScheme() + "://" + req.getServerName() + portPart + req.getContextPath();
  }

  public static boolean isLocal(HttpServletRequest req) {
    String host = req.getServerName();
    return "localhost".equals(host) || "127.0.0.1".equals(host);
  }
}
