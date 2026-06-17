package filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebFilter("/*")
public class AuthenticationFilter implements Filter {
  @Override public void init(FilterConfig filterConfig) { }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;
    String path = req.getRequestURI().substring(req.getContextPath().length());
    boolean publicPath = path.equals("/") || path.equals("/index.jsp") || path.equals("/login")
        || path.equals("/forgot") || path.equals("/reset") || path.equals("/invite") || path.startsWith("/assets/");
    if (publicPath || req.getSession(false) != null
        && req.getSession(false).getAttribute("loginUser") != null) {
      chain.doFilter(request, response);
      return;
    }
    res.sendRedirect(req.getContextPath() + "/index.jsp");
  }

  @Override public void destroy() { }
}
