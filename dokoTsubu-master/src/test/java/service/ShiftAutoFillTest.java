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

public class ShiftAutoFillTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-autofill-test-").toString());
    Database.initialize();

    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User coworker = users.authenticate("sato@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User otherManager = users.authenticate("hr@example.com", "Password1!"); // 他の権限

    PortalService portal = new PortalService();
    YearMonth targetMonth = YearMonth.now().plusMonths(1);

    // 1. テスト前データの準備
    long empId = employee.getId();
    long cowId = coworker.getId();

    // 既存の手動割り当て（DAY）を登録
    LocalDate manualDay = targetMonth.atDay(5);
    portal.saveShift(manager, empId, manualDay, "DAY", "DRAFT", "手動割り当て");

    // 承認済み有休を登録
    LocalDate leaveDay = targetMonth.atDay(10);
    Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,status,reason) VALUES(?,?,'FULL','APPROVED','有休')", empId, leaveDay);

    // 休み希望（OFF）を登録
    LocalDate preferredOffDay = targetMonth.atDay(15);
    long submissionId = Sql.insert("INSERT INTO shift_preference_submissions(user_id,target_month,status) VALUES(?,?,?)", empId, targetMonth.atDay(1), "SUBMITTED");
    Sql.insert("INSERT INTO shift_preferences(submission_id,preference_date,request_type) VALUES(?,?,?)", submissionId, preferredOffDay, "OFF");

    // 2. 自動補完実行 (正常系)
    System.out.println("Running autoFillShifts for manager...");
    int count = portal.autoFillShifts(manager, targetMonth);
    System.out.println("Auto fill count: " + count);
    check(count > 0, "filled shifts count is positive");

    // 3. 補完ルールの検証
    // Rule A: 既存シフトが上書きされていないこと
    Map<String, Object> manualShift = Sql.one("SELECT work_type_code, note FROM shifts WHERE user_id=? AND work_date=?", empId, manualDay);
    check("DAY".equals(manualShift.get("work_type_code")), "existing manual shift remains DAY");
    check("手動割り当て".equals(manualShift.get("note")), "existing manual shift note remains unchanged");

    // Rule B: 承認済み有休が上書きされていないこと
    // 有休の日には shifts テーブルに DAY/NIGHT は割り当てられていないはず（または LEAVE が優先設定）
    Map<String, Object> leaveShift = Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", empId, leaveDay);
    check("LEAVE".equals(leaveShift.get("work_type_code")), "approved leave is explicitly assigned");

    // Rule C: 休み希望の日には勤務が割り当てられていないこと
    Map<String, Object> prefOffShift = Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", empId, preferredOffDay);
    check("OFF".equals(prefOffShift.get("work_type_code")), "preferred off day is explicitly assigned");

    int scopedPeople = ((Number) Sql.one("SELECT COUNT(*) metric_value FROM users WHERE active=TRUE AND role<>'HR' AND branch_id=? AND department_id=?",
        manager.getBranchId(), manager.getDepartmentId()).get("metric_value")).intValue();
    int rosterCells = ((Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE s.work_date BETWEEN ? AND ? AND u.active=TRUE AND u.role<>'HR' AND u.branch_id=? AND u.department_id=?",
        targetMonth.atDay(1), targetMonth.atEndOfMonth(), manager.getBranchId(), manager.getDepartmentId()).get("metric_value")).intValue();
    check(rosterCells == scopedPeople * targetMonth.lengthOfMonth(),
        "all assignable roster cells are filled in a small branch");

    List<Map<String, Object>> workloads = Sql.query("SELECT u.id,COUNT(*) metric_value FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE s.work_date BETWEEN ? AND ? AND s.work_type_code IN('DAY','NIGHT','NIGHT_OFF') "
        + "AND u.role='EMPLOYEE' AND u.branch_id=? AND u.department_id=? GROUP BY u.id",
        targetMonth.atDay(1), targetMonth.atEndOfMonth(), manager.getBranchId(), manager.getDepartmentId());
    int minimumWorkload = workloads.stream().mapToInt(row -> ((Number) row.get("metric_value")).intValue()).min().orElse(0);
    int maximumWorkload = workloads.stream().mapToInt(row -> ((Number) row.get("metric_value")).intValue()).max().orElse(0);
    check(workloads.size() == 2 && maximumWorkload - minimumWorkload <= 2,
        "employee workloads are balanced: min=" + minimumWorkload + ", max=" + maximumWorkload);
    check(Sql.query("SELECT s.id FROM shifts s JOIN users u ON u.id=s.user_id WHERE s.work_date BETWEEN ? AND ? "
        + "AND u.role='MANAGER' AND s.work_type_code='NIGHT'", targetMonth.atDay(1), targetMonth.atEndOfMonth()).isEmpty(),
        "manager is not assigned night shifts");

    // Rule D: 夜勤を補完した場合、翌日は夜勤明け（NIGHT_OFF）が同時に設定されていること
    List<Map<String, Object>> NIGHTShifts = Sql.query("SELECT work_date FROM shifts WHERE user_id=? AND work_type_code='NIGHT'", empId);
    for (Map<String, Object> night : NIGHTShifts) {
      LocalDate nightDate = util.DateUtil.toDate(night.get("work_date"));
      LocalDate nextDate = nightDate.plusDays(1);
      if (nextDate.getMonthValue() == targetMonth.getMonthValue()) {
        Map<String, Object> nextShift = Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", empId, nextDate);
        check("NIGHT_OFF".equals(nextShift.get("work_type_code")), "day after NIGHT must be NIGHT_OFF");
      }
    }

    // 4. 重複実行の検証 (再実行しても既存シフトを上書きしない)
    int secondCount = portal.autoFillShifts(manager, targetMonth);
    System.out.println("Second auto fill count: " + secondCount);
    check(secondCount == 0, "second run fills nothing because staffing is satisfied and existing assignments are kept");

    Sql.update("UPDATE work_types SET required_staff=? WHERE code='NIGHT'", scopedPeople + 1);
    check(portal.shiftWarnings(manager, targetMonth).stream().anyMatch(row -> "STAFF_SHORTAGE".equals(row.get("warning"))),
        "an impossible staffing requirement produces a visible warning");

    // 5. 確定済みロックの検証
    portal.confirmMonth(manager, targetMonth, "確定テスト");
    expectFailure(() -> portal.autoFillShifts(manager, targetMonth), "confirmed month auto fill restriction");

    // 6. 権限保護の検証 (無権限ユーザーによるアクセス)
    YearMonth nextMonth = targetMonth.plusMonths(1);
    expectSecurityFailure(() -> portal.autoFillShifts(employee, nextMonth), "employee cannot auto fill");

    System.out.println("ShiftAutoFillTest: all checks passed");
  }

  private static void expectFailure(Runnable action, String label) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      System.out.println("PASS: Expected failure -> " + label + " (" + expected.getMessage() + ")");
      return;
    }
    throw new AssertionError("Failed: " + label);
  }

  private static void expectSecurityFailure(Runnable action, String label) {
    try {
      action.run();
    } catch (SecurityException expected) {
      System.out.println("PASS: Expected security failure -> " + label + " (" + expected.getMessage() + ")");
      return;
    }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
