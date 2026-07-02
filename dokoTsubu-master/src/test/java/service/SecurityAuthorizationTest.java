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
import util.PasswordUtil;

public class SecurityAuthorizationTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-security-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User coworker = users.authenticate("sato@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    check(employee != null && coworker != null && manager != null && hr != null, "all roles authenticate");
    check(users.authenticate("employee@example.com", "wrong-password") == null, "wrong password rejected");
    Sql.update("UPDATE users SET active=FALSE WHERE id=?", coworker.getId());
    check(users.authenticate("sato@example.com", "Password1!") == null, "inactive account rejected");
    check(new SessionUserService().refresh(coworker) == null, "existing inactive session rejected");
    Sql.update("UPDATE users SET active=TRUE WHERE id=?", coworker.getId());
    Sql.update("UPDATE users SET role='MANAGER' WHERE id=?", employee.getId());
    check(new SessionUserService().refresh(employee).isManager(), "session role refreshed");
    Sql.update("UPDATE users SET role='EMPLOYEE' WHERE id=?", employee.getId());

    PortalService portal = new PortalService();
    LocalDate targetDate = LocalDate.now().plusDays(10);
    expectDenied(() -> portal.saveShift(employee, coworker.getId(), targetDate, "DAY", "DRAFT", "unauthorized"),
        "employee cannot edit coworker shift");
    expectDenied(() -> portal.saveShift(manager, hr.getId(), targetDate, "DAY", "DRAFT", "outside scope"),
        "manager cannot edit outside scope");
    portal.saveShift(hr, coworker.getId(), targetDate, "DAY", "DRAFT", "HR scope");
    check(!Sql.query("SELECT id FROM shifts WHERE user_id=? AND work_date=?", coworker.getId(), targetDate).isEmpty(), "HR company scope");
    expectDenied(() -> new SettingsService().all(employee), "HR settings protected");
    expectDenied(() -> portal.audit(employee), "audit protected");
    long coworkerAttendance = Sql.insert("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
        coworker.getId(), LocalDate.now(), LocalDate.now().atTime(8, 0), LocalDate.now().atTime(17, 0));
    expectDenied(() -> portal.requestAttendanceAdjustment(employee, coworkerAttendance, LocalDate.now().atTime(8, 30),
        LocalDate.now().atTime(17, 30), "IDOR"), "other employee attendance ID rejected");
    long hrAttendance = Sql.insert("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
        hr.getId(), LocalDate.now(), LocalDate.now().atTime(8, 0), LocalDate.now().atTime(17, 0));
    expectDenied(() -> portal.finalizeAttendance(manager, hrAttendance, true), "outside-scope attendance ID rejected");
    long coworkerLeave = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason) VALUES(?,?,'FULL','IDOR')",
        coworker.getId(), LocalDate.now().plusDays(5));
    expectDenied(() -> portal.cancelLeave(employee, coworkerLeave), "other employee leave ID rejected");

    long otherBranchId = ((Number) Sql.one("SELECT id FROM branches WHERE id<>? ORDER BY id LIMIT 1", employee.getBranchId()).get("id")).longValue();
    Sql.update("UPDATE users SET branch_id=? WHERE id=?", otherBranchId, hr.getId());
    LocalDate publicShiftDate = YearMonth.now().atDay(1);
    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,note,updated_by) VALUES(?,?,'DAY','CONFIRMED','private detail',?)",
        hr.getId(), publicShiftDate, hr.getId());
    List<Map<String, Object>> publicRoster = new ShiftService().branchShifts(YearMonth.now(), otherBranchId);
    check(publicRoster.stream().anyMatch(row -> ((Number) row.get("user_id")).longValue() == hr.getId()),
        "employee-facing roster can include another branch for help coordination");
    check(publicRoster.stream().allMatch(row -> !row.containsKey("employee_number") && !row.containsKey("note")),
        "public cross-branch roster excludes employee number and detail note");
    check(publicRoster.stream().allMatch(row -> "CONFIRMED".equals(row.get("status"))),
        "public cross-branch roster contains confirmed shifts only");
    expectDenied(() -> portal.saveShift(employee, hr.getId(), targetDate.plusDays(5), "DAY", "DRAFT", "cross-branch edit"),
        "employee cannot edit another branch shift");

    long otherDepartmentId = ((Number) Sql.one("SELECT id FROM departments WHERE id<>? ORDER BY id LIMIT 1", manager.getDepartmentId()).get("id")).longValue();
    long employmentId = ((Number) Sql.one("SELECT id FROM employment_types ORDER BY id LIMIT 1").get("id")).longValue();
    long otherDepartmentUserId = Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role) VALUES(?,?,?,?,?,?,?,?,'EMPLOYEE')",
        "OTHER-DEPT", "別部署 従業員", "other-dept@example.com", PasswordUtil.hash("Password1!"), LocalDate.now().minusYears(1),
        manager.getBranchId(), otherDepartmentId, employmentId);
    long otherDepartmentLeaveId = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason) VALUES(?,?,'FULL','other department private reason')",
        otherDepartmentUserId, LocalDate.now().plusDays(20));
    Sql.insert("INSERT INTO leave_history(user_id,event_type,event_date,days,note) VALUES(?,'GRANT',?,1,'other department private history')",
        otherDepartmentUserId, LocalDate.now());
    LeaveService leaves = new LeaveService();
    check(leaves.leaveRequests(manager).stream().noneMatch(row -> ((Number) row.get("id")).longValue() == otherDepartmentLeaveId),
        "manager cannot view another department leave request in same branch");
    check(leaves.leaveHistory(manager).stream().noneMatch(row -> ((Number) row.get("user_id")).longValue() == otherDepartmentUserId),
        "manager cannot view another department leave history in same branch");
    check(leaves.leaveRequests(hr).stream().anyMatch(row -> ((Number) row.get("id")).longValue() == otherDepartmentLeaveId),
        "HR can view company-wide leave requests");
    check(leaves.leaveHistory(hr).stream().anyMatch(row -> ((Number) row.get("user_id")).longValue() == otherDepartmentUserId),
        "HR can view company-wide leave history");

    portal.addDelegation(manager, manager.getId(), employee.getId(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
    long delegationId = ((Number) Sql.one("SELECT MAX(id) id FROM delegations WHERE delegate_id=?", employee.getId()).get("id")).longValue();
    LocalDate delegatedDate = targetDate.plusDays(1);
    portal.saveShift(employee, coworker.getId(), delegatedDate, "DAY", "DRAFT", "active delegate");
    check(!Sql.query("SELECT id FROM shifts WHERE user_id=? AND work_date=?", coworker.getId(), delegatedDate).isEmpty(), "active delegate allowed");

    portal.updateDelegation(manager, delegationId, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), false);
    expectDenied(() -> portal.saveShift(employee, coworker.getId(), targetDate.plusDays(2), "DAY", "DRAFT", "inactive delegate"),
        "inactive delegate rejected");
    portal.updateDelegation(manager, delegationId, LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), true);
    expectDenied(() -> portal.saveShift(employee, coworker.getId(), targetDate.plusDays(3), "DAY", "DRAFT", "future delegate"),
        "future delegate rejected");
    portal.updateDelegation(manager, delegationId, LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), true);
    expectDenied(() -> portal.saveShift(employee, coworker.getId(), targetDate.plusDays(4), "DAY", "DRAFT", "expired delegate"),
        "expired delegate rejected");
    System.out.println("SecurityAuthorizationTest: all checks passed");
  }

  private static void expectDenied(Runnable action, String label) {
    try { action.run(); } catch (SecurityException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
