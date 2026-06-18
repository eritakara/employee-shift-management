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

@WebFilter("/*")
public class SameSitePostFilter implements Filter {
  private final SameOriginPolicy policy = new SameOriginPolicy();
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    if (!"POST".equalsIgnoreCase(req.getMethod()) || sameOrigin(req)) {
      chain.doFilter(request, response);
      return;
    }
    ((HttpServletResponse) response).sendError(403, "Cross-site POST is not allowed");
  }

  private boolean sameOrigin(HttpServletRequest req) {
    return policy.allows(req.getScheme(), req.getServerName(), req.getServerPort(),
        req.getHeader("Sec-Fetch-Site"), req.getHeader("Origin"));
  }
}
