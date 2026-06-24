package servlet;

import dao.UserDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import model.User;
import service.AuditService;
import service.AccountTokenService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@WebServlet(urlPatterns = {"/login", "/logout", "/account", "/forgot", "/reset", "/invite", "/privacy"})
public class AuthServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private final UserDAO users = new UserDAO();
  private final AccountTokenService tokens = new AccountTokenService();
  private static final ConcurrentMap<String, Attempt> ATTEMPTS = new ConcurrentHashMap<>();
  private record Attempt(int count, long firstAt) { }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {
    req.setCharacterEncoding("UTF-8");
    String path = req.getServletPath();
    if ("/login".equals(path)) {
      System.err.println("LOGIN POST RECEIVED: email=" + req.getParameter("email"));
      try {
        String attemptKey = req.getRemoteAddr() + ":" + String.valueOf(req.getParameter("email")).toLowerCase();
        Attempt attempt = ATTEMPTS.get(attemptKey);
        long now = System.currentTimeMillis();
        if (attempt != null && now - attempt.firstAt() < 300_000 && attempt.count() >= 5) {
          req.setAttribute("error", "ログイン試行回数が上限に達しました。5分後に再試行してください。");
          req.getRequestDispatcher("/index.jsp").forward(req, res);
          return;
        }
        User user = null;
        try {
          user = users.authenticate(req.getParameter("email"), req.getParameter("password"));
        } catch (Throwable e) {
          System.err.println("CRITICAL: Authentication failed unexpectedly!");
          e.printStackTrace(System.err);
          req.setAttribute("error", "ログイン処理中にサーバーエラーが発生しました。");
          req.getRequestDispatcher("/index.jsp").forward(req, res);
          return;
        }
        if (user == null) {
          ATTEMPTS.compute(attemptKey, (key, old) -> old == null || now - old.firstAt() >= 300_000 ? new Attempt(1, now) : new Attempt(old.count() + 1, old.firstAt()));
          req.setAttribute("error", "メールアドレスまたはパスワードが正しくありません。");
          req.getRequestDispatcher("/index.jsp").forward(req, res);
          return;
        }
        ATTEMPTS.remove(attemptKey);
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate();
        req.getSession(true).setAttribute("loginUser", user);
        req.getSession().setMaxInactiveInterval(30 * 60);
        try {
          AuditService.record(user.getId(), "LOGIN", "USER", String.valueOf(user.getId()), null, null);
        } catch (Throwable e) {
          System.err.println("WARNING: Audit log creation failed during login. Continuing login process.");
          e.printStackTrace(System.err);
        }
        res.sendRedirect(req.getContextPath() + "/app/dashboard");
        return;
      } catch (Throwable t) {
        System.err.println("CRITICAL: Unhandled exception during login request processing.");
        t.printStackTrace(System.err);
        req.setAttribute("error", "システムエラーが発生しました。");
        req.getRequestDispatcher("/index.jsp").forward(req, res);
        return;
      }
    }
    if ("/forgot".equals(path)) {
      String token = tokens.issue(req.getParameter("email"), "RESET", util.ServletUtil.baseUrl(req));
      req.setAttribute("sent", true);
      if (util.ServletUtil.isLocal(req) && token != null) req.setAttribute("devLink", req.getContextPath() + "/reset?token=" + token);
      try { req.getRequestDispatcher("/WEB-INF/jsp/forgot.jsp").forward(req, res); } catch (ServletException e) { throw new IOException(e); }
      return;
    }
    if ("/reset".equals(path) || "/invite".equals(path)) {
      String type = "/invite".equals(path) ? "INVITE" : "RESET";
      try {
        if (tokens.consume(req.getParameter("token"), type, req.getParameter("password"))) {
          req.setAttribute("complete", true);
        } else {
          req.setAttribute("error", "リンクが無効または期限切れです。");
        }
      } catch (IllegalArgumentException e) { req.setAttribute("error", e.getMessage()); }
      try { req.getRequestDispatcher("/WEB-INF/jsp/reset.jsp").forward(req, res); } catch (ServletException e) { throw new IOException(e); }
      return;
    }
    if ("/logout".equals(path)) {
      HttpSession session = req.getSession(false);
      if (session != null) {
        User user = (User) session.getAttribute("loginUser");
        if (user != null) AuditService.record(user.getId(), "LOGOUT", "USER", String.valueOf(user.getId()), null, null);
        session.invalidate();
      }
      res.sendRedirect(req.getContextPath() + "/index.jsp");
      return;
    }
    User user = (User) req.getSession().getAttribute("loginUser");
    if ("/account".equals(path)) {
      String locale = req.getParameter("locale");
      String password = req.getParameter("password");
      if (locale != null) users.updateLocale(user.getId(), locale);
      if (password != null && password.length() >= 10) users.updatePassword(user.getId(), password);
      req.getSession().setAttribute("loginUser", users.findById(user.getId()));
      AuditService.record(user.getId(), "UPDATE_ACCOUNT", "USER", String.valueOf(user.getId()), null, null);
      res.sendRedirect(req.getContextPath() + "/app/account?success=1");
    }
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    if ("/logout".equals(req.getServletPath())) {
      res.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    } else if ("/privacy".equals(req.getServletPath())) {
      try { req.getRequestDispatcher("/WEB-INF/jsp/privacy.jsp").forward(req, res); } catch (ServletException e) { throw new IOException(e); }
    } else if ("/forgot".equals(req.getServletPath())) {
      try { req.getRequestDispatcher("/WEB-INF/jsp/forgot.jsp").forward(req, res); } catch (ServletException e) { throw new IOException(e); }
    } else if ("/reset".equals(req.getServletPath()) || "/invite".equals(req.getServletPath())) {
      String type = "/invite".equals(req.getServletPath()) ? "INVITE" : "RESET";
      req.setAttribute("tokenValid", tokens.isValid(req.getParameter("token"), type));
      try { req.getRequestDispatcher("/WEB-INF/jsp/reset.jsp").forward(req, res); } catch (ServletException e) { throw new IOException(e); }
    } else {
      res.sendRedirect(req.getContextPath() + "/app/account");
    }
  }


}
