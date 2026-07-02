package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import model.User;

public class AttendanceAdjustmentApprovalRulesTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-approval-rules-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();

    // 既存のデモユーザーを取得 (branch_id=1, department_id=1)
    User employee = users.authenticate("employee@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");

    // テスト用の別店舗/別部署の店長を作成
    long branch2Id = Sql.insert("INSERT INTO branches(name) VALUES('Branch 2')");
    long dept2Id = Sql.insert("INSERT INTO departments(name) VALUES('Dept 2')");
    long manager2Id = Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,role,branch_id,department_id,employment_type_id,hire_date) VALUES('M999','Manager 2','manager2@example.com','hash','MANAGER',?,?,1,CURRENT_DATE)", branch2Id, dept2Id);
    User manager2 = new User(manager2Id, "M999", "Manager 2", "manager2@example.com", "MANAGER", branch2Id, dept2Id, "Branch 2", "Dept 2", "ja");

    // テスト用の別の人事担当者 (HR)
    long hr2Id = Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,role,branch_id,department_id,employment_type_id,hire_date) VALUES('H999','HR 2','hr2@example.com','hash','HR',1,1,1,CURRENT_DATE)");
    User hr2 = new User(hr2Id, "H999", "HR 2", "hr2@example.com", "HR", 1L, 1L, "Branch", "Dept", "ja");

    AttendanceService service = new AttendanceService();
    LocalDate day = LocalDate.now();

    // ==========================================
    // 1. 一般従業員 (EMPLOYEE) の申請検証
    // ==========================================
    long att1 = createAttendance(employee.getId(), day);
    long req1 = createAdjustment(att1, employee.getId());

    // - 同一店舗・部署の店長 (MANAGER) が承認できること
    service.decideAttendanceAdjustment(manager, req1, true);
    checkStatus(req1, "APPROVED");

    // - 承認時の監査ログ actor_id の検証
    long auditActorId = ((Number) Sql.one("SELECT actor_id FROM audit_logs WHERE action='APPROVE_ATTENDANCE_ADJUSTMENT' AND target_id=?", String.valueOf(req1)).get("actor_id")).longValue();
    check(auditActorId == manager.getId(), "Audit actor_id matches manager");

    long req1_2 = createAdjustment(att1, employee.getId());
    // - 人事担当者 (HR) も全社権限で承認できること
    service.decideAttendanceAdjustment(hr, req1_2, true);
    checkStatus(req1_2, "APPROVED");

    // - 申請者本人は承認できないこと
    expectFailure(() -> service.decideAttendanceAdjustment(employee, req1_2, true), "Employee cannot approve own request");

    // - 管理対象外（別店舗・別部署）の店長 (MANAGER2) は承認できないこと
    expectFailure(() -> service.decideAttendanceAdjustment(manager2, req1_2, true), "Manager of different scope cannot approve employee request");


    // ==========================================
    // 2. 店長 (MANAGER) の申請検証
    // ==========================================
    long att2 = createAttendance(manager.getId(), day);
    long req2 = createAdjustment(att2, manager.getId());

    // - 人事担当者 (HR) が承認できること
    service.decideAttendanceAdjustment(hr, req2, true);
    checkStatus(req2, "APPROVED");

    // - 承認時の監査ログ actor_id の検証
    long auditActorId2 = ((Number) Sql.one("SELECT actor_id FROM audit_logs WHERE action='APPROVE_ATTENDANCE_ADJUSTMENT' AND target_id=?", String.valueOf(req2)).get("actor_id")).longValue();
    check(auditActorId2 == hr.getId(), "Audit actor_id matches HR");

    long req2_2 = createAdjustment(att2, manager.getId());
    // - 他の店長 (MANAGER2) は承認できないこと
    expectFailure(() -> service.decideAttendanceAdjustment(manager2, req2_2, true), "Manager cannot approve other manager request");

    // - 申請した店長本人は承認できないこと
    expectFailure(() -> service.decideAttendanceAdjustment(manager, req2_2, true), "Manager cannot approve own request");


    // ==========================================
    // 3. 人事担当者 (HR) の申請検証
    // ==========================================
    long att3 = createAttendance(hr.getId(), day);
    long req3 = createAdjustment(att3, hr.getId());

    // - 申請したHR本人以外の人事担当者 (HR2) が承認できること
    service.decideAttendanceAdjustment(hr2, req3, true);
    checkStatus(req3, "APPROVED");

    // - 承認時の監査ログ actor_id の検証
    long auditActorId3 = ((Number) Sql.one("SELECT actor_id FROM audit_logs WHERE action='APPROVE_ATTENDANCE_ADJUSTMENT' AND target_id=?", String.valueOf(req3)).get("actor_id")).longValue();
    check(auditActorId3 == hr2.getId(), "Audit actor_id matches HR 2");

    long req3_2 = createAdjustment(att3, hr.getId());
    // - 申請したHR本人は承認できないこと
    expectFailure(() -> service.decideAttendanceAdjustment(hr, req3_2, true), "HR cannot approve own request");

    // - 一般従業員 (EMPLOYEE) は承認できないこと
    expectFailure(() -> service.decideAttendanceAdjustment(employee, req3_2, true), "Employee cannot approve HR request");

    // - 店長 (MANAGER2) は承認できないこと
    expectFailure(() -> service.decideAttendanceAdjustment(manager2, req3_2, true), "Manager cannot approve HR request");

    // ==========================================
    // 4. 新規要件: 打刻修正申請の対象勤怠取得・申請制限の検証
    // ==========================================

    // - 店長アカウントで myAttendance を実行したとき、店長本人の勤怠のみ取得され、同一店舗の一般従業員の勤怠が含まれないこと。
    java.util.List<java.util.Map<String, Object>> managerMine = service.myAttendance(manager, java.time.YearMonth.from(day));
    boolean hasManager = false;
    boolean hasEmployee = false;
    for (java.util.Map<String, Object> r : managerMine) {
      long uid = ((Number) r.get("user_id")).longValue();
      if (uid == manager.getId()) hasManager = true;
      if (uid == employee.getId()) hasEmployee = true;
    }
    check(hasManager, "myAttendance for manager includes manager's own attendance");
    check(!hasEmployee, "myAttendance for manager does not include employee's attendance");

    // - 店長アカウントが他従業員の attendanceId を指定して打刻修正申請を実行したとき、SecurityException が発生して申請が作成されないこと。
    long employeeAtt = createAttendance(employee.getId(), day.minusDays(1));
    expectFailure(() -> service.requestAttendanceAdjustment(manager, employeeAtt, day.minusDays(1).atTime(9, 0), day.minusDays(1).atTime(18, 0), "manager trying to request for employee"),
        "Manager cannot request adjustment for employee");

    // - 一般従業員が本人の勤怠に対して申請を実行したときは成功し、他人の attendanceId を指定したときは SecurityException が発生して申請が作成されないこと。
    long employeeAttSelf = createAttendance(employee.getId(), day.minusDays(2));
    long managerAtt = createAttendance(manager.getId(), day.minusDays(2));
    LocalDate employeeWorkDay = day.minusDays(2);
    expectFailure(() -> service.requestAttendanceAdjustment(employee, employeeAttSelf, null, employeeWorkDay.atTime(18, 0), "missing clock in"),
        "Adjustment requires requested clock-in");
    expectFailure(() -> service.requestAttendanceAdjustment(employee, employeeAttSelf, employeeWorkDay.atTime(18, 0), employeeWorkDay.atTime(9, 0), "reversed times"),
        "Adjustment rejects reversed times");
    expectFailure(() -> service.requestAttendanceAdjustment(employee, employeeAttSelf, employeeWorkDay.minusDays(1).atTime(9, 0), employeeWorkDay.atTime(18, 0), "wrong work date"),
        "Adjustment clock-in must match work date");
    expectFailure(() -> service.requestAttendanceAdjustment(employee, employeeAttSelf, employeeWorkDay.atTime(1, 0), employeeWorkDay.plusDays(1).atTime(2, 0), "too long"),
        "Adjustment duration cannot exceed 24 hours");
    expectFailure(() -> service.requestAttendanceAdjustment(employee, employeeAttSelf, employeeWorkDay.atTime(9, 0), employeeWorkDay.atTime(18, 0), "x".repeat(1001)),
        "Adjustment reason length is limited");
    // 本人の申請は成功すること
    try {
      service.requestAttendanceAdjustment(employee, employeeAttSelf, day.minusDays(2).atTime(9, 0), day.minusDays(2).atTime(18, 0), "employee requesting for self");
    } catch (Exception e) {
      throw new AssertionError("Employee should be able to request adjustment for self, but failed: " + e.getMessage());
    }
    expectFailure(() -> service.requestAttendanceAdjustment(employee, employeeAttSelf, employeeWorkDay.atTime(9, 30), employeeWorkDay.atTime(18, 30), "duplicate pending request"),
        "Adjustment rejects a second pending request for the same attendance");
    // 他人の申請は失敗すること
    expectFailure(() -> service.requestAttendanceAdjustment(employee, managerAtt, day.minusDays(2).atTime(9, 0), day.minusDays(2).atTime(18, 0), "employee trying to request for manager"),
        "Employee cannot request adjustment for manager");

    System.out.println("AttendanceAdjustmentApprovalRulesTest: all checks passed");
  }

  private static long createAttendance(long userId, LocalDate day) {
    return Sql.insert("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?,'COMPLETE')",
        userId, day, day.atTime(9, 0), day.atTime(17, 0));
  }

  private static long createAdjustment(long attendanceId, long userId) {
    LocalDate day = LocalDate.now();
    return Sql.insert("INSERT INTO attendance_adjustments(attendance_id,requested_by,requested_in,requested_out,reason) VALUES(?,?,?,?,?)",
        attendanceId, userId, day.atTime(9, 0), day.atTime(18, 0), "correction");
  }

  private static void checkStatus(long requestId, String expectedStatus) {
    String status = String.valueOf(Sql.one("SELECT status FROM attendance_adjustments WHERE id=?", requestId).get("status"));
    if (!expectedStatus.equals(status)) {
      throw new AssertionError("Expected status: " + expectedStatus + ", but got: " + status);
    }
  }

  private static void expectFailure(Runnable action, String label) {
    try {
      action.run();
    } catch (SecurityException | IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("Expected failure not met: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Assertion failed: " + label);
  }
}
