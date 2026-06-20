package service;

import java.nio.file.Files;
import java.nio.file.Path;

public class UiStateCoverageTest {
  public static void main(String[] args) throws Exception {
    Path web = Path.of("src/main/webapp");
    Path main = Path.of("src/main/java");
    String script = Files.readString(web.resolve("assets/app.js"));
    String css = Files.readString(web.resolve("assets/app.css"));
    String application = Files.readString(web.resolve("WEB-INF/jsp/app.jsp"));
    String error = Files.readString(web.resolve("WEB-INF/jsp/error.jsp"));
    String deployment = Files.readString(web.resolve("WEB-INF/web.xml"));
    String portal = Files.readString(main.resolve("service/PortalService.java"));
    String servlet = Files.readString(main.resolve("servlet/PortalServlet.java"));

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
    check(application.contains("statusLabel(row.get(\"status\"))"), "leave request statuses use display labels");
    check(application.contains("leaveUnitLabel(row.get(\"leave_unit\"))"), "leave request units use display labels");
    check(application.contains("if (\"APPROVED\".equals(status)) return \"承認済み\"")
        && application.contains("if (\"FULL\".equals(unit)) return \"1日\""), "leave status and unit labels are localized");
    check(application.contains("leaveEventTypeLabel(event.get(\"event_type\"))")
        && application.contains("leaveNoteLabel(event.get(\"note\"))"), "leave ledger type and note use display labels");
    check(application.contains("days(balance.get(\"days_remaining\"))"), "leave balance card uses compact day formatting");
    check(application.contains("days(event.get(\"days\"))"), "leave ledger days use compact number formatting");
    check(application.contains("class=\"approver-panel\"") && application.contains("leaveApprovers"),
        "leave request form shows approver information");
    check(application.contains("理由（任意）<textarea name=\"reason\" maxlength=\"1000\"></textarea>"),
        "leave request reason is optional in the form");
    check(application.contains("name=\"rejectionReason\" required maxlength=\"500\" placeholder=\"却下理由\""),
        "leave rejection requires a reason in the approval form");
    check(css.contains(".approver-panel") && css.contains(".approver-list"),
        "leave approver information has dedicated styles");
    check(css.contains(".leave-reject-form"), "leave rejection reason has dedicated layout");
    check(servlet.contains("req.setAttribute(\"leaveApprovers\", portal.leaveApprovers(user))"),
        "leave approver information is passed to the view");
    check(application.contains("店長が設定されていません") && portal.contains("'店長' approver_type"),
        "leave request approver display is manager oriented");
    check(portal.contains("public List<Map<String, Object>> leaveApprovers(User user)")
        && portal.contains("role='MANAGER' AND branch_id=?")
        && portal.contains("notifyLeaveApprovers")
        && portal.contains("assertLeaveApprovalScope"), "employee leave approvers are branch managers");
    check(portal.contains("reason == null ? \"\" : reason.trim()")
        && !portal.contains("throw new IllegalArgumentException(\"理由を入力してください。\")"),
        "leave request reason is optional on the server");
    check(portal.contains("throw new IllegalArgumentException(\"却下理由を入力してください。\")")
        && portal.contains("却下理由: "), "leave rejection reason is required and included in notification");
    check(application.contains("if (\"USE\".equals(type)) return \"取得\"")
        && application.contains("if (\"statutory expiry\".equals(note)) return \"法定失効\""),
        "leave ledger labels are localized");
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

