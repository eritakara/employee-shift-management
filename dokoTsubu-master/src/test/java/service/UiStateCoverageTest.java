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
    String servletUtil = Files.readString(main.resolve("util/ServletUtil.java"));

    check(script.contains("form.dataset.submitting === 'true'"), "forms prevent duplicate submission");
    check(script.contains("aria-busy"), "forms expose loading state");
    check(script.contains("role', 'status'"), "loading message is announced");
    check(application.contains("assets/app.css?v=20260629-1")
        && application.contains("assets/app.js?v=20260703-2"), "updated app assets use the latest cache buster");
    check(css.contains(".loading-indicator"), "loading state has a common visual style");
    check(application.contains("class=\"empty\""), "application has a common empty state");
    check(application.contains("if (\"DEMO_ATTENDANCE_RESET_TOKEN\".equals(value)) return \"デモ勤怠リセット確認キー\"")
        && application.contains("return \"最後に実行されたデモ勤怠リセットの確認キー\"")
        && application.contains("settingDescription(key,setting.get(\"description\"))"),
        "demo attendance reset setting keeps its internal key while showing Japanese labels");
    check(application.contains("href=\"<%=ctx%>/app/masters/work-types\">勤務区分・休憩・必要人数</a>")
        && !application.contains("href=\"<%=ctx%>/app/masters/staffing\">必要人数</a>"),
        "work type, break, and staffing settings use one consolidated master-data tab");
    check(servlet.contains("TITLES.put(\"masters/work-types\", \"勤務区分・休憩・必要人数管理\")")
        && servlet.contains("TITLES.put(\"masters/staffing\", \"勤務区分・休憩・必要人数管理\")")
        && servlet.contains("page.endsWith(\"catalogs\") ? \"employment\" : \"work_types\""),
        "legacy staffing URL remains an alias of the consolidated work-types screen");
    check(application.contains("class=\"alert danger\""), "application has a common input error state");
    check(application.contains("確認事項")
        && application.contains("本日のシフト不足")
        && application.contains("月間シフト未確定")
        && application.contains("残業アラート"),
        "dashboard presents actionable items first");
    check(application.indexOf("確認事項") < application.indexOf("今月の勤務サマリー")
        && application.indexOf("今月の勤務サマリー") < application.indexOf("勤務状況の推移")
        && application.indexOf("勤務状況の推移") < application.indexOf("dashboard-roster-heading"),
        "dashboard sections follow the operational reading order");
    check(application.contains("直近6か月の1人あたり平均勤務時間を月別に確認できます。")
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
    int attendancePageStart = application.indexOf("else if(pageKey.startsWith(\"attendance/\"))");
    int attendanceRejectDialog = application.indexOf("data-attendance-reject-dialog");
    int notificationsPageStart = application.indexOf("else if(pageKey.equals(\"notifications\"))");
    check(attendancePageStart >= 0 && attendanceRejectDialog > attendancePageStart
        && notificationsPageStart > attendanceRejectDialog,
        "attendance rejection dialog is rendered inside the attendance page branch");
    check(script.contains("document.querySelector('[data-attendance-reject-dialog]')")
        && script.contains("const setAttendanceText = (selector, value) =>")
        && script.contains("setAttendanceText('[data-attendance-reject-requester]', button.dataset.requester)")
        && script.contains("attendanceRejectDialog.showModal()")
        && script.contains("reasonInput.focus()"),
        "attendance rejection button populates and opens the reason dialog without relying on another dialog scope");
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
    check(application.contains("提出期限を過ぎているため、希望シフトは提出できません。")
        && application.contains("data-submission-open=\"<%=submissionOpen%>\"")
        && application.contains("<%=submissionOpen?\"\":\"disabled\"%>"),
        "closed preference window disables inputs and submit button with guidance");
    check(script.contains("form.dataset.submissionOpen !== 'false'")
        && script.contains("form.addEventListener('submit', event => event.preventDefault())")
        && script.contains("if (!submissionOpen) return;"),
        "closed preference window blocks client-side selection and submission");
    check(application.contains("shift-coverage-summary") && application.contains("選択中のシフトを変更")
        && application.contains("data-shift-editor hidden"), "shift adjustment prioritizes coverage and reveals editing on selection");
    check(shiftRoster.contains("pageKey.equals(\"shifts/manage\")||pageKey.equals(\"shifts/confirm\")")
        && shiftRoster.contains("rosterShift!=null&&\"CONFIRMED\".equals(rosterShiftStatus)")
        && shiftRoster.contains("data-shift-edit-cell"),
        "shift adjustment and confirmed-roster screens expose only their permitted editable cells");
    check(shiftRoster.contains("rosterShowEmployeeNumber=!pageKey.equals(\"shifts/manage\")")
        && shiftRoster.contains("data-employee=\"<%=e(rosterEmployeeLabel)%>\""),
        "shift adjustment roster hides employee numbers from both the table and selected-cell editor");
    check(script.contains("const shiftEditor = document.querySelector('[data-shift-editor]')")
        && script.contains("cell.dataset.workTypeLabel"), "shift cell selection populates the editor");
    check(application.contains("<input type=\"hidden\" name=\"status\" data-shift-editor-status>")
        && !application.contains("<label>状態<select name=\"status\" data-shift-editor-status>"),
        "shift cell editor preserves status without exposing an editable field");
    check(application.contains("name=\"action\" value=\"adjustConfirmedShift\"")
        && application.contains("このシフトだけ変更する")
        && application.contains("月全体の確定状態は維持されます"),
        "confirmed roster offers a clearly scoped single-shift edit action");
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
    check(script.contains("Resend invitation email"), "invitation email action has an English translation");
    check(servlet.contains("招待メールを送信しました")
        && servlet.contains("招待メールの送信に失敗しました。管理者に確認してください"),
        "invitation email result messages are user friendly");
    check(servlet.contains("getAttribute(\"flash\") == null && req.getSession().getAttribute(\"error\") == null"),
        "default save message is not added when an action reports an error");
    check(servlet.contains("returnMonth = attendanceMonth.toString()")
        && servlet.contains("finalized ? \"一括確定\" : \"確定解除\"")
        && servlet.contains("\"?month=\" + returnMonth"),
        "attendance month finalization redirects back to the processed month with a specific result message");
    check(servletUtil.contains("APP_BASE_URL is not configured")
        && servlet.contains("Invitation email configuration or delivery failed"),
        "missing production base URL fails with a safe diagnostic");
    System.out.println("UiStateCoverageTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
