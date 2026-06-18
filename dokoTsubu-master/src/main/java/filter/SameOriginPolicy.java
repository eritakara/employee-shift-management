package filter;

import java.net.URI;

public class SameOriginPolicy {
  public boolean allows(String scheme, String host, int port, String fetchSite, String origin) {
    if ("same-origin".equals(fetchSite)) return true;
    if (origin == null || origin.isBlank()) return false;
    try {
      URI uri = URI.create(origin);
      int actualPort = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
      return scheme.equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost()) && port == actualPort;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
