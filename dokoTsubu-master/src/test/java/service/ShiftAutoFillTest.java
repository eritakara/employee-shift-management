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
    for (int index = 3; index <= 4; index++) {
      Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role,locale,active,weekly_work_days) "
          + "SELECT ?,?,?,password_hash,hire_date,branch_id,department_id,employment_type_id,'EMPLOYEE',locale,TRUE,weekly_work_days FROM users WHERE id=?",
          "AUTO_BAL_" + index, "自動補完 従業員" + index, "auto-balance-" + index + "@example.com", empId);
    }

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

    // Existing assignments must also be normalized: OFF/DAY/NIGHT after NIGHT become NIGHT_OFF.
    LocalDate existingNightBeforeOff = targetMonth.atDay(19);
    portal.saveShift(manager, empId, existingNightBeforeOff, "NIGHT", "DRAFT", "existing night before off");
    portal.saveShift(manager, empId, existingNightBeforeOff.plusDays(1), "OFF", "DRAFT", "existing off");
    LocalDate existingNightBeforeDay = targetMonth.atDay(21);
    portal.saveShift(manager, cowId, existingNightBeforeDay, "NIGHT", "DRAFT", "existing night before day");
    portal.saveShift(manager, cowId, existingNightBeforeDay.plusDays(1), "DAY", "DRAFT", "existing day");
    LocalDate existingNightBeforeNight = targetMonth.atDay(23);
    portal.saveShift(manager, cowId, existingNightBeforeNight, "NIGHT", "DRAFT", "existing night before night");
    portal.saveShift(manager, cowId, existingNightBeforeNight.plusDays(1), "NIGHT", "DRAFT", "existing second night");
    LocalDate nightBeforeApprovedLeave = leaveDay.minusDays(1);
    portal.saveShift(manager, empId, nightBeforeApprovedLeave, "NIGHT", "DRAFT", "night before approved leave");
    LocalDate monthEndNight = targetMonth.atEndOfMonth();
    portal.saveShift(manager, cowId, monthEndNight, "NIGHT", "DRAFT", "month-end night");

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
    check("NIGHT_OFF".equals(type(empId, existingNightBeforeOff.plusDays(1))),
        "existing OFF after NIGHT is normalized to NIGHT_OFF");
    check("NIGHT_OFF".equals(type(cowId, existingNightBeforeDay.plusDays(1))),
        "existing DAY after NIGHT is normalized to NIGHT_OFF");
    check("NIGHT_OFF".equals(type(cowId, existingNightBeforeNight.plusDays(1))),
        "existing NIGHT after NIGHT is normalized to NIGHT_OFF");
    check("LEAVE".equals(type(empId, leaveDay)),
        "approved leave takes priority over NIGHT_OFF normalization");
    check("NIGHT_OFF".equals(type(cowId, monthEndNight.plusDays(1))),
        "month-end NIGHT creates NIGHT_OFF on the first day of the next month");

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
    check(workloads.size() == scopedPeople - 1 && maximumWorkload - minimumWorkload <= 2,
        "employee workloads are balanced: min=" + minimumWorkload + ", max=" + maximumWorkload);

    List<Map<String, Object>> nightCounts = typeCounts(targetMonth, manager, "NIGHT");
    int minimumNights = nightCounts.stream().mapToInt(row -> ((Number) row.get("metric_value")).intValue()).min().orElse(0);
    int maximumNights = nightCounts.stream().mapToInt(row -> ((Number) row.get("metric_value")).intValue()).max().orElse(0);
    check(nightCounts.size() == scopedPeople - 1 && minimumNights > 0 && maximumNights - minimumNights <= 1,
        "night assignments are balanced and no eligible employee remains at zero: min=" + minimumNights + ", max=" + maximumNights);

    List<Map<String, Object>> dayCounts = typeCounts(targetMonth, manager, "DAY");
    int minimumDays = dayCounts.stream().mapToInt(row -> ((Number) row.get("metric_value")).intValue()).min().orElse(0);
    int maximumDays = dayCounts.stream().mapToInt(row -> ((Number) row.get("metric_value")).intValue()).max().orElse(0);
    check(dayCounts.size() == scopedPeople - 1 && maximumDays - minimumDays <= 2,
        "day assignments are balanced: min=" + minimumDays + ", max=" + maximumDays);
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
        boolean approvedLeave = !Sql.one("SELECT id FROM leave_requests WHERE user_id=? AND leave_date=? AND status='APPROVED'", empId, nextDate).isEmpty();
        check("NIGHT_OFF".equals(nextShift.get("work_type_code"))
                || (approvedLeave && "LEAVE".equals(nextShift.get("work_type_code"))),
            "day after NIGHT must be NIGHT_OFF unless approved leave takes priority");
      }
    }

    // 4. 重複実行の検証 (再実行しても既存シフトを上書きしない)
    int secondCount = portal.autoFillShifts(manager, targetMonth);
    System.out.println("Second auto fill count: " + secondCount);
    check(secondCount == 0, "second run fills nothing because staffing is satisfied and existing assignments are kept");

    long imbalanceUserId = ((Number) Sql.one("SELECT id FROM users WHERE employee_number='AUTO_BAL_4'").get("id")).longValue();
    Sql.update("UPDATE shifts SET work_type_code='DAY' WHERE user_id=? AND work_date BETWEEN ? AND ? AND work_type_code='NIGHT'",
        imbalanceUserId, targetMonth.atDay(1), targetMonth.atEndOfMonth());
    check(portal.shiftWarnings(manager, targetMonth).stream().anyMatch(row -> "SHIFT_TYPE_IMBALANCE".equals(row.get("warning"))),
        "an unresolvable shift type imbalance produces a visible warning");

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

  private static String type(long userId, LocalDate date) {
    return String.valueOf(Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", userId, date)
        .get("work_type_code"));
  }

  private static List<Map<String, Object>> typeCounts(YearMonth month, User manager, String type) {
    return Sql.query("SELECT u.id,COUNT(s.id) metric_value FROM users u LEFT JOIN shifts s ON s.user_id=u.id "
        + "AND s.work_date BETWEEN ? AND ? AND s.work_type_code=? WHERE u.active=TRUE AND u.role='EMPLOYEE' "
        + "AND u.branch_id=? AND u.department_id=? GROUP BY u.id ORDER BY u.id",
        month.atDay(1), month.atEndOfMonth(), type, manager.getBranchId(), manager.getDepartmentId());
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
