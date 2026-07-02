package service;

import config.Database;
import dao.UserDAO;
import filter.SameOriginPolicy;
import filter.TransportSecurityPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.User;
import util.HtmlEscaper;
import util.SecurityLog;

public class SecurityHardeningTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-hardening-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User hr = users.authenticate("hr@example.com", "Password1!");

    String escaped = HtmlEscaper.escape("<script>alert('xss')</script>\"");
    check(!escaped.contains("<script>") && escaped.contains("&lt;script&gt;") && escaped.contains("&#39;") && escaped.endsWith("&quot;"), "HTML escaped");

    SameOriginPolicy origin = new SameOriginPolicy();
    check(origin.allows("https", "shift.example.jp", 443, "same-origin", null), "fetch metadata same origin");
    check(origin.allows("https", "shift.example.jp", 443, null, "https://shift.example.jp"), "matching origin");
    check(!origin.allows("https", "shift.example.jp", 443, "cross-site", "https://evil.example"), "cross-site rejected");
    check(!origin.allows("https", "shift.example.jp", 443, null, null), "missing origin rejected");
    check(!origin.allows("https", "shift.example.jp", 443, null, "https://shift.example.jp:444"), "wrong port rejected");
    check(TransportSecurityPolicy.shouldUseHsts("production", true, null), "HSTS enabled for direct production HTTPS");
    check(TransportSecurityPolicy.shouldUseHsts("production", false, "https"), "HSTS enabled behind production HTTPS proxy");
    check(!TransportSecurityPolicy.shouldUseHsts("production", false, "http"), "HSTS disabled for production HTTP request");
    check(!TransportSecurityPolicy.shouldUseHsts("development", true, "https"), "HSTS disabled outside production");

    RuntimeException diagnosticError = new RuntimeException("sensitive-value-marker");
    ByteArrayOutputStream productionLogBytes = new ByteArrayOutputStream();
    SecurityLog.write("ERROR", "Authentication failed", diagnosticError, true,
        new PrintStream(productionLogBytes, true, java.nio.charset.StandardCharsets.UTF_8));
    String productionLog = productionLogBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    check(productionLog.contains("Authentication failed") && productionLog.contains("RuntimeException"),
        "production log keeps safe operation and error type");
    check(!productionLog.contains("sensitive-value-marker") && !productionLog.contains("SecurityHardeningTest.java"),
        "production log excludes exception message and stack trace");
    ByteArrayOutputStream developmentLogBytes = new ByteArrayOutputStream();
    SecurityLog.write("ERROR", "Authentication failed", diagnosticError, false,
        new PrintStream(developmentLogBytes, true, java.nio.charset.StandardCharsets.UTF_8));
    String developmentLog = developmentLogBytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    check(developmentLog.contains("sensitive-value-marker") && developmentLog.contains("SecurityHardeningTest.java"),
        "development log keeps diagnostic exception details");

    RequestRateLimiter limiter = new RequestRateLimiter(2, 1_000, 2);
    limiter.record("first", 1_000);
    check(!limiter.isBlocked("first", 1_000), "rate limiter allows attempts below limit");
    limiter.record("first", 1_100);
    check(limiter.isBlocked("first", 1_100), "rate limiter blocks at limit");
    check(!limiter.isBlocked("first", 2_001), "rate limiter expires old window");
    limiter.record("second", 2_100);
    limiter.record("third", 2_200);
    limiter.record("fourth", 2_300);
    check(limiter.size() <= 2, "rate limiter bounds stored keys");
    limiter.clear("fourth");
    check(!limiter.isBlocked("fourth", 2_300), "rate limiter clears successful key");

    check(users.authenticate("' OR 1=1 --", "anything") == null, "login SQL injection rejected");
    ExportService exports = new ExportService();
    expectInvalid(() -> exports.rows(hr, "shifts' UNION SELECT * FROM users --", LocalDate.now(), LocalDate.now(), null, null, null),
        "export type SQL injection rejected");
    Map<String, Object> formula = new LinkedHashMap<>();
    formula.put("value", "@SUM(1,1)");
    check(exports.csv(List.of(formula)).contains("'@SUM(1,1)"), "CSV formula neutralized");

    String appJsp = Files.readString(Path.of("src/main/webapp/WEB-INF/jsp/app.jsp"));
    check(appJsp.contains("action=\"<%=ctx%>/logout\" method=\"post\"") && !appJsp.contains("href=\"<%=ctx%>/logout\""), "logout uses POST");
    String resetJsp = Files.readString(Path.of("src/main/webapp/WEB-INF/jsp/reset.jsp"));
    check(resetJsp.contains("HtmlEscaper.escape(request.getParameter(\"token\"))"), "reset token escaped");
    String webXml = Files.readString(Path.of("src/main/webapp/WEB-INF/web.xml"));
    check(webXml.contains("<http-only>true</http-only>"), "session cookie HttpOnly configured");
    String contextXml = Files.readString(Path.of("src/main/webapp/META-INF/context.xml"));
    check(contextXml.contains("sameSiteCookies=\"lax\""), "session cookie SameSite Lax configured");
    System.out.println("SecurityHardeningTest: all checks passed");
  }

  private static void expectInvalid(Runnable action, String label) {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
