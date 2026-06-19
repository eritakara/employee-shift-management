package service;

import java.nio.file.Files;
import java.nio.file.Path;

public class UiStateCoverageTest {
  public static void main(String[] args) throws Exception {
    Path web = Path.of("src/main/webapp");
    String script = Files.readString(web.resolve("assets/app.js"));
    String css = Files.readString(web.resolve("assets/app.css"));
    String application = Files.readString(web.resolve("WEB-INF/jsp/app.jsp"));
    String error = Files.readString(web.resolve("WEB-INF/jsp/error.jsp"));
    String deployment = Files.readString(web.resolve("WEB-INF/web.xml"));

    check(script.contains("form.dataset.submitting === 'true'"), "forms prevent duplicate submission");
    check(script.contains("aria-busy"), "forms expose loading state");
    check(script.contains("role', 'status'"), "loading message is announced");
    check(css.contains(".loading-indicator"), "loading state has a common visual style");
    check(application.contains("class=\"empty\""), "application has a common empty state");
    check(application.contains("class=\"alert danger\""), "application has a common input error state");
    check(application.contains("href=\"<%=ctx%>/app/leave\">"), "leave navigation is consolidated into one menu item");
    check(application.contains("class=\"page-tabs leave-tabs\""), "leave page exposes in-page tabs");
    check(application.contains("leave?tab=balance") && application.contains("leave?tab=request")
        && application.contains("leave?tab=history"), "leave tabs link to balance, request, and history views");
    check(application.indexOf("leave?tab=request") < application.indexOf("leave?tab=history")
        && application.indexOf("leave?tab=history") < application.indexOf("leave?tab=balance"),
        "leave tabs are ordered request, history, balance");
    check(application.contains("leave-tab-request") && application.contains("leave-tab-history")
        && application.contains("leave-tab-balance"), "leave tabs have semantic color classes");
    check(css.contains(".leave-tabs .leave-tab-request") && css.contains(".leave-tabs .leave-tab-history")
        && css.contains(".leave-tabs .leave-tab-balance"), "leave tabs have distinct color styles");
    check(application.contains("pageKey.equals(\"leave/request\")") && application.contains("pageKey.equals(\"leave/history\")")
        && application.contains("pageKey.equals(\"leave/balance\")"), "legacy leave URLs map to the matching tab");
    check(deployment.contains("<error-code>404</error-code>"), "404 uses the common error page");
    check(deployment.contains("<error-code>500</error-code>"), "500 uses the common error page");
    check(deployment.contains("<exception-type>java.lang.Throwable</exception-type>"), "unexpected errors use the common error page");
    check(error.contains("role=\"alert\""), "server errors are announced accessibly");
    check(!error.contains("exception") && !error.contains("stackTrace") && !error.contains("sql"),
        "server error page does not expose internal details");
    check(script.contains("Work, overtime, and leave trend"), "updated dashboard heading has an English translation");
    check(script.contains("Reissue invitation"), "invitation action has an English translation");
    System.out.println("UiStateCoverageTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}

