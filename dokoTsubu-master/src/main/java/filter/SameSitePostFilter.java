package filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;

@WebFilter("/*")
public class SameSitePostFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    if (!"POST".equalsIgnoreCase(req.getMethod()) || isPublic(req.getServletPath()) || sameOrigin(req)) {
      chain.doFilter(request, response);
      return;
    }
    ((HttpServletResponse) response).sendError(403, "Cross-site POST is not allowed");
  }

  private boolean isPublic(String path) {
    return "/login".equals(path) || "/forgot".equals(path) || "/reset".equals(path) || "/invite".equals(path);
  }

  private boolean sameOrigin(HttpServletRequest req) {
    String fetchSite = req.getHeader("Sec-Fetch-Site");
    if ("same-origin".equals(fetchSite)) return true;
    String origin = req.getHeader("Origin");
    if (origin == null) return false;
    try {
      URI uri = URI.create(origin);
      int expectedPort = req.getServerPort();
      int actualPort = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
      return req.getScheme().equals(uri.getScheme()) && req.getServerName().equalsIgnoreCase(uri.getHost()) && expectedPort == actualPort;
    } catch (IllegalArgumentException e) { return false; }
  }
}
