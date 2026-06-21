package util;

import jakarta.servlet.http.HttpServletRequest;

public final class ServletUtil {
  private ServletUtil() { }

  public static String baseUrl(HttpServletRequest req) {
    int port = req.getServerPort();
    String portPart = ("http".equals(req.getScheme()) && port == 80) || ("https".equals(req.getScheme()) && port == 443) ? "" : ":" + port;
    return req.getScheme() + "://" + req.getServerName() + portPart + req.getContextPath();
  }

  public static boolean isLocal(HttpServletRequest req) {
    String host = req.getServerName();
    return "localhost".equals(host) || "127.0.0.1".equals(host);
  }
}
