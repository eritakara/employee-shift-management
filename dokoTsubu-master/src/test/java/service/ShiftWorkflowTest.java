package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import model.User;

public class ShiftWorkflowTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-shift-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User coworker = users.authenticate("sato@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    PortalService portal = new PortalService();
    SettingsService settings = new SettingsService();
    LocalDate actualToday = LocalDate.now();
    LocalDate beforeDeadline = actualToday.withDayOfMonth(Math.min(14, actualToday.lengthOfMonth()));
    LocalDate afterDeadline = actualToday.withDayOfMonth(Math.min(16, actualToday.lengthOfMonth()));
    YearMonth targetMonth = YearMonth.from(beforeDeadline.plusMonths(1));
    LocalDate targetDate = targetMonth.atDay(1);

    portal.submitPreferredShift(employee, targetDate, "DAY", "preferred", beforeDeadline);
    Map<String, Object> submitted = Sql.one("SELECT status,work_type_code FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), targetDate);
    check("SUBMITTED".equals(submitted.get("status")), "preferred shift submitted");
    expectFailure(() -> portal.submitPreferredShift(employee, targetMonth.atDay(2), "DAY", "late", afterDeadline), "submission deadline");
    expectFailure(() -> portal.submitPreferredShift(employee, targetMonth.minusMonths(2).atDay(1), "DAY", "past month", beforeDeadline), "past month block");

    settings.update(hr, "ALLOW_CONFIRM_WITH_WARNINGS", "false");
    expectFailure(() -> portal.confirmMonth(manager, targetMonth, "blocked"), "warnings block confirmation");
    settings.update(hr, "ALLOW_CONFIRM_WITH_WARNINGS", "true");
    expectFailure(() -> portal.confirmMonth(manager, targetMonth, ""), "warning reason required");
    portal.confirmMonth(manager, targetMonth, "人員不足を確認済み");
    check("CONFIRMED".equals(Sql.one("SELECT status FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), targetDate).get("status")), "month confirmed");

    portal.requestShiftChange(employee, targetDate, "OFF", "予定変更");
    long requestId = ((Number) Sql.one("SELECT MAX(id) id FROM shift_change_requests WHERE user_id=?", employee.getId()).get("id")).longValue();
    check("日勤".equals(portal.shiftChangeRequests(employee).get(0).get("current_type")),
        "shift change current work type is localized");
    portal.decideShiftChange(manager, requestId, true);
    check("OFF".equals(Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), targetDate).get("work_type_code")), "approved change applied");
    expectFailure(() -> portal.requestShiftChange(employee, targetMonth.atDay(2), "LEAVE", "paid leave through shift change"), "paid leave hidden from shift change");
    LocalDate rejectDate = targetMonth.atDay(Math.min(3, targetMonth.lengthOfMonth()));
    portal.requestShiftChange(employee, rejectDate, "OFF", "reject test");
    long rejectRequestId = ((Number) Sql.one("SELECT MAX(id) id FROM shift_change_requests WHERE user_id=?", employee.getId()).get("id")).longValue();
    expectFailure(() -> portal.decideShiftChange(manager, rejectRequestId, false, ""), "shift rejection reason required");
    check("PENDING".equals(Sql.one("SELECT status FROM shift_change_requests WHERE id=?", rejectRequestId).get("status")), "blank shift rejection stays pending");
    portal.decideShiftChange(manager, rejectRequestId, false, "人員不足のため");
    check("REJECTED".equals(Sql.one("SELECT status FROM shift_change_requests WHERE id=?", rejectRequestId).get("status")), "shift rejection with reason applied");

    check(((Number) Sql.one("SELECT required_staff metric_value FROM work_types WHERE code='DAY'").get("metric_value")).intValue() == 1,
        "day shift requires one worker");
    check(((Number) Sql.one("SELECT required_staff metric_value FROM work_types WHERE code='NIGHT'").get("metric_value")).intValue() == 1,
        "night shift requires one worker");
    LocalDate boundaryDate = targetMonth.atDay(Math.min(10, targetMonth.lengthOfMonth() - 1));
    List<Map<String, Object>> emptyWarnings = portal.shiftWarningsForDate(manager, boundaryDate);
    check(count(emptyWarnings, "STAFF_SHORTAGE") == 2, "zero-staff shortage by work type");
    portal.saveShift(manager, employee.getId(), boundaryDate, "NIGHT", "DRAFT", "night");
    portal.saveShift(manager, coworker.getId(), boundaryDate, "DAY", "DRAFT", "day");
    check(count(portal.shiftWarningsForDate(manager, boundaryDate), "STAFF_SHORTAGE") == 0,
        "one worker per day and night shift satisfies staffing");
    portal.saveShift(manager, employee.getId(), boundaryDate.plusDays(1), "DAY", "DRAFT", "overlap");
    check(count(portal.shiftWarningsForDate(manager, boundaryDate.plusDays(1)), "NIGHT_REST") == 1, "night-rest overlap detected");
    portal.saveShift(manager, employee.getId(), boundaryDate.plusDays(1), "OFF", "DRAFT", "rest");
    check(count(portal.shiftWarningsForDate(manager, boundaryDate.plusDays(1)), "NIGHT_REST") == 0, "off day clears night-rest warning");
    check(portal.shiftWarnings(manager, targetMonth).stream().noneMatch(row -> row.get("work_date") == null), "monthly warnings have dates");

    // ==========================================
    // 勤務区分「早番」「遅番」のクリーンアップ＆移行テスト
    // ==========================================
    // 1. テスト用の「早番（EARLY）」「遅番（LATE）」を work_types にインサート
    Sql.update("INSERT INTO work_types(code,name_ja,name_en,crosses_midnight,break_minutes,required_staff) VALUES('EARLY','早番','Early',FALSE,60,0)");
    Sql.update("INSERT INTO work_types(code,name_ja,name_en,crosses_midnight,break_minutes,required_staff) VALUES('LATE','遅番','Late',FALSE,60,0)");

    LocalDate migrationTestDate1 = targetMonth.atDay(20);
    LocalDate migrationTestDate2 = targetMonth.atDay(21);
    LocalDate migrationTestDate3 = targetMonth.atDay(22);

    // 2. shifts に早期・遅番シフトをインサート
    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,note,updated_by) VALUES(?,?,'EARLY','DRAFT','early shift test',?)",
        employee.getId(), migrationTestDate1, manager.getId());
    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,note,updated_by) VALUES(?,?,'LATE','DRAFT','late shift test',?)",
        employee.getId(), migrationTestDate2, manager.getId());

    // 3. shift_change_requests に早期申請をインサート
    Sql.update("INSERT INTO shift_change_requests(user_id,work_date,requested_work_type,reason) VALUES(?,?,'EARLY','change request test')",
        employee.getId(), migrationTestDate1);

    // 4. shift_preference_submissions & shift_preferences に希望シフトを登録
    long submissionId = ((Number) Sql.one("SELECT id FROM shift_preference_submissions WHERE user_id=? AND target_month=?",
        employee.getId(), targetMonth.atDay(1)).getOrDefault("id", 0L)).longValue();
    if (submissionId == 0L) {
      Sql.update("INSERT INTO shift_preference_submissions(user_id,target_month,status) VALUES(?,?,'DRAFT')",
          employee.getId(), targetMonth.atDay(1));
      submissionId = ((Number) Sql.one("SELECT id FROM shift_preference_submissions WHERE user_id=? AND target_month=?",
          employee.getId(), targetMonth.atDay(1)).get("id")).longValue();
    }
    Sql.update("INSERT INTO shift_preferences(submission_id,preference_date,request_type,note) VALUES(?,?,'LATE','preference test')",
        submissionId, migrationTestDate3);

    // 5. 移行処理を実行
    try (java.sql.Connection conn = Database.getConnection()) {
      Database.cleanupEarlyLateWorkTypes(conn);
    }

    // 6. アサーション検証
    // - work_types から EARLY / LATE が削除されていること
    check(Sql.query("SELECT * FROM work_types WHERE code IN ('EARLY', 'LATE')").isEmpty(), "work_types EARLY/LATE deleted");
    // - shifts の work_type_code が DAY に移行されていること
    check("DAY".equals(Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), migrationTestDate1).get("work_type_code")), "shifts EARLY migrated to DAY");
    check("DAY".equals(Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), migrationTestDate2).get("work_type_code")), "shifts LATE migrated to DAY");
    // - shift_change_requests.requested_work_type が DAY へ移行されていること
    check("DAY".equals(Sql.one("SELECT requested_work_type FROM shift_change_requests WHERE user_id=? AND work_date=?", employee.getId(), migrationTestDate1).get("requested_work_type")), "change request EARLY migrated to DAY");
    // - shift_preferences.request_type が DAY へ移行されていること
    check("DAY".equals(Sql.one("SELECT request_type FROM shift_preferences WHERE submission_id=? AND preference_date=?", submissionId, migrationTestDate3).get("request_type")), "preference LATE migrated to DAY");

    // 7. 冪等性の検証（EARLY / LATE が存在しない状態で再実行しても例外が起きないこと）
    try (java.sql.Connection conn = Database.getConnection()) {
      Database.cleanupEarlyLateWorkTypes(conn);
    } catch (Exception e) {
      throw new AssertionError("cleanupEarlyLateWorkTypes failed on second run: " + e.getMessage());
    }

    System.out.println("ShiftWorkflowTest: all checks passed");
  }

  private static long count(List<Map<String, Object>> rows, String warning) {
    return rows.stream().filter(row -> warning.equals(row.get("warning"))).count();
  }

  private static void expectFailure(Runnable action, String label) {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
