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
    String shiftRoster = Files.readString(web.resolve("WEB-INF/jsp/_shiftRoster.jspf"));
    String error = Files.readString(web.resolve("WEB-INF/jsp/error.jsp"));
    String deployment = Files.readString(web.resolve("WEB-INF/web.xml"));
    String leaveService = Files.readString(main.resolve("service/LeaveService.java"));
    String notificationService = Files.readString(main.resolve("service/NotificationService.java"));
    String servlet = Files.readString(main.resolve("servlet/PortalServlet.java"));

    check(script.contains("form.dataset.submitting === 'true'"), "forms prevent duplicate submission");
    check(script.contains("aria-busy"), "forms expose loading state");
    check(script.contains("role', 'status'"), "loading message is announced");
    check(application.contains("assets/app.css?v=20260629-1")
        && application.contains("assets/app.js?v=20260628-1"), "updated app assets use the latest cache buster");
    check(css.contains(".loading-indicator"), "loading state has a common visual style");
    check(application.contains("class=\"empty\""), "application has a common empty state");
    check(application.contains("class=\"alert danger\""), "application has a common input error state");
    check(application.contains("今日の要対応")
        && application.contains("今日のシフト不足")
        && application.contains("月間シフト未確定")
        && application.contains("残業アラート"),
        "dashboard presents actionable items first");
    check(application.indexOf("今日の要対応") < application.indexOf("今月の状況")
        && application.indexOf("今月の状況") < application.indexOf("推移グラフ")
        && application.indexOf("推移グラフ") < application.indexOf("dashboard-roster-heading"),
        "dashboard sections follow the operational reading order");
    check(application.contains("直近6か月の実勤務時間を月別に確認できます。")
        && application.contains("所定時間を超えた残業時間の推移です。")
        && application.contains("承認済みの有休取得日数を月別に表示します。"),
        "dashboard charts explain their data");
    check(application.contains("シフト調整へ") && css.contains(".dashboard-action-grid"),
        "dashboard offers responsive action links");
    check(application.contains("data-attendance-employee-select")
        && application.contains("data-attendance-employee-confirm")
        && application.contains("data-attendance-employee-release"),
        "attendance employee close actions expose state-aware controls");
    check(application.contains("この従業員を確定する")
        && application.contains("この従業員の確定を解除する")
        && application.contains("この月を一括確定する")
        && application.contains("この月の確定を解除する"),
        "attendance close actions use clear labels");
    check(application.contains("attendance-step-flow")
        && application.contains("<h2>対象月を選ぶ</h2>")
        && application.contains("<h2>確定前チェックを確認する</h2>")
        && application.contains("<h2>従業員別に確定する</h2>")
        && application.contains("<h2>全員確認後、月次一括確定する</h2>")
        && application.contains("<h2>詳細確認</h2>"),
        "attendance finalization is presented as a five-step flow");
    check(application.indexOf("<h2>従業員別に確定する</h2>")
        < application.indexOf("<h2>全員確認後、月次一括確定する</h2>"),
        "employee finalization precedes monthly finalization");
    check(application.contains("name=\"month\" value=\"<%=month%>\" data-auto-submit")
        && !application.contains("<button>対象月を表示</button>"),
        "attendance month selection refreshes automatically without a display button");
    check(application.contains("対象件数が0件のため、月次確定できません。")
        && application.contains("rows.isEmpty()?\"disabled\""),
        "empty attendance months cannot be finalized");
    check(script.contains("dataset.confirmMessage") && script.contains("window.confirm(confirmMessage)"),
        "sensitive attendance close actions require confirmation");
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
    check(application.contains("statusLabel(row.get(\"status\"), en)"), "leave request statuses use display labels");
    check(application.contains("leaveUnitLabel(row.get(\"leave_unit\"), en)"), "leave request units use display labels");
    check(application.contains("if (\"APPROVED\".equals(status)) return en ? \"Approved\" : \"承認済み\"")
        && application.contains("if (\"FULL\".equals(unit)) return en ? \"Full day\" : \"1日\""), "leave status and unit labels are localized");
    check(application.contains("warningLabel(w.get(\"warning\"), en)")
        && application.contains("if (\"STAFF_SHORTAGE\".equals(warning)) return en ? \"Staff shortage\" : \"人員不足\""),
        "shift warning types are localized");
    check(application.contains("pageKey.equals(\"shifts/history\") || pageKey.equals(\"shifts/change\")")
        && application.contains("String rosterTitle=\"月間シフト\"")
        && application.contains("pageKey.equals(\"shifts/confirm\")"),
        "schedule editor and confirmation use the monthly roster");
    check(application.contains("!pageKey.equals(\"shifts/change\")"),
        "shift change request view hides the all-employee monthly schedule");
    check(application.contains("manager && !pageKey.equals(\"shifts/change\")"),
        "shift change request form hides the editable shift status");
    check(application.contains("<%if(!pageKey.equals(\"shifts/change\")){%><div class=\"toolbar no-print\">"),
        "shift change request view hides the month toolbar");
    check(application.contains("\"勤務区分を変更\"") && application.contains("\"変更を保存\""),
        "shift adjustment form uses change-oriented wording");
    check(script.contains("'勤務区分を変更':'Change work type'")
        && script.contains("'変更を保存':'Save change'"), "shift adjustment wording supports English display");
    check(application.contains("shift-workflow-metrics") && application.contains("希望一覧を開く")
        && application.contains("提出希望日を確認"), "shift adjustment summarizes and collapses preferences");
    check(application.contains("shift-coverage-summary") && application.contains("選択中のシフトを変更")
        && application.contains("data-shift-editor hidden"), "shift adjustment prioritizes coverage and reveals editing on selection");
    check(shiftRoster.contains("rosterEditable=pageKey.equals(\"shifts/manage\")")
        && shiftRoster.contains("data-shift-edit-cell"), "only shift adjustment roster exposes editable cells");
    check(script.contains("const shiftEditor = document.querySelector('[data-shift-editor]')")
        && script.contains("cell.dataset.workTypeLabel"), "shift cell selection populates the editor");
    check(application.contains("<input type=\"hidden\" name=\"status\" data-shift-editor-status>")
        && !application.contains("<label>状態<select name=\"status\" data-shift-editor-status>"),
        "shift cell editor preserves status without exposing an editable field");
    check(shiftRoster.contains("rosterAllConfirmed") && shiftRoster.contains("rosterMixedStatus")
        && shiftRoster.contains("rosterCellUnconfirmed"), "monthly roster summarizes confirmation status");
    check(application.contains("\"shifts/manage\",\"shifts/confirm\"")
        && application.contains("<%=shiftMonthAutoSubmit?\"data-auto-submit\":\"\"%>")
        && application.contains("<%if(!shiftMonthAutoSubmit){%><button type=\"submit\">表示</button><%}%>"),
        "schedule editor and confirmation auto-submit month changes");
    check(application.contains("leaveEventTypeLabel(event.get(\"event_type\"), en)")
        && application.contains("leaveNoteLabel(event.get(\"note\"), en)"), "leave ledger type and note use display labels");
    check(application.contains("days(balance.get(\"days_remaining\"))"), "leave balance card uses compact day formatting");
    check(application.contains("days(event.get(\"days\"))"), "leave ledger days use compact number formatting");
    check(application.contains("class=\"approver-panel\"") && application.contains("leaveApprovers"),
        "leave request form shows approver information");
    check(application.contains("textarea name=\"reason\" maxlength=\"1000\"></textarea>"),
        "leave request reason is optional in the form");
    check(application.contains("data-leave-request-form") && application.contains("name=\"dates\" data-leave-dates-input")
        && script.contains("data-leave-date-add") && script.contains("selectedDates"),
        "leave request form supports selecting multiple dates");
    check(application.contains("data-leave-reject-open") && application.contains("data-leave-reject-dialog")
        && application.contains("name=\"rejectionReason\" required maxlength=\"500\" rows=\"4\" placeholder=\"却下理由を入力してください\""),
        "leave rejection uses a reason dialog");
    check(application.contains("data-leave-reject-requester") && application.contains("data-leave-reject-date")
        && application.contains("data-leave-reject-reason") && application.contains("data-leave-reject-status"),
        "leave rejection dialog shows request details");
    check(!application.contains("class=\"leave-reject-form\""), "leave rejection reason is not shown inline");
    check(css.contains(".approver-panel") && css.contains(".approver-list"),
        "leave approver information has dedicated styles");
    check(css.contains(".leave-reject-dialog") && css.contains(".decision-summary"),
        "leave rejection dialog has dedicated layout");
    check(script.contains("data-leave-reject-open") && script.contains("却下理由を入力してください"),
        "leave rejection dialog is controlled by JavaScript");
    check(servlet.contains("req.setAttribute(\"leaveApprovers\", leaveService.leaveApprovers(user))"),
        "leave approver information is passed to the view");
    check(application.contains("data-leave-reject-open") && leaveService.contains("approver_type"),
        "leave request approver display is manager oriented");
    check(leaveService.contains("public List<Map<String, Object>> leaveApprovers(User user)")
        && notificationService.contains("role='MANAGER' AND branch_id=?")
        && leaveService.contains("\"MANAGER\".equals(applicantRole)")
        && leaveService.contains("\"HR\".equals(applicantRole)")
        && leaveService.contains("COUNT(l.id) approval_count")
        && notificationService.contains("notifyLeaveApprovers")
        && leaveService.contains("assertLeaveApprovalScope"), "leave approvers are role-aware and workload ordered");
    check(leaveService.contains("reason == null ? \"\" : reason.trim()")
        && !leaveService.contains("throw new IllegalArgumentException(\"理由を入力してください。\")"),
        "leave request reason is optional on the server");
    check(leaveService.contains("throw new IllegalArgumentException(\"却下理由を入力してください。\")")
        && leaveService.contains("却下理由: "), "leave rejection reason is required and included in notification");
    check(application.contains("if (\"USE\".equals(type)) return en ? \"Use\" : \"取得\"")
        && application.contains("if (\"statutory expiry\".equals(note)) return en ? \"Statutory expiry\" : \"法定失効\""),
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
