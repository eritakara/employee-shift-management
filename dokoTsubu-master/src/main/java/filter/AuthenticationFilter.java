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
import model.User;
import service.SessionUserService;

@WebFilter("/*")
public class AuthenticationFilter implements Filter {
  private final SessionUserService sessionUsers = new SessionUserService();
  @Override public void init(FilterConfig filterConfig) { }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;
    String path = req.getRequestURI().substring(req.getContextPath().length());
    boolean publicPath = path.equals("/") || path.equals("/index.jsp") || path.equals("/login")
        || path.equals("/forgot") || path.equals("/reset") || path.equals("/invite") || path.startsWith("/assets/");
    if (publicPath) {
      chain.doFilter(request, response);
      return;
    }
    if (req.getSession(false) != null) {
      User refreshed = sessionUsers.refresh((User) req.getSession(false).getAttribute("loginUser"));
      if (refreshed != null) {
        req.getSession(false).setAttribute("loginUser", refreshed);
        chain.doFilter(request, response);
        return;
      }
      req.getSession(false).invalidate();
    }
    res.sendRedirect(req.getContextPath() + "/index.jsp");
  }

  @Override public void destroy() { }
}
