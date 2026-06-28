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
    check(leaveShift.isEmpty() || "LEAVE".equals(leaveShift.get("work_type_code")), "approved leave not overwritten by DAY/NIGHT");

    // Rule C: 休み希望の日には勤務が割り当てられていないこと
    Map<String, Object> prefOffShift = Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", empId, preferredOffDay);
    check(prefOffShift.isEmpty() || "OFF".equals(prefOffShift.get("work_type_code")), "preferred off day not overwritten by DAY/NIGHT");

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
