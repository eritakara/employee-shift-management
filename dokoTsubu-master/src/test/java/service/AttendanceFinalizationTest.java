package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import model.User;

public class AttendanceFinalizationTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-attendance-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    long otherId = ((Number) Sql.one("SELECT id FROM users WHERE email='sato@example.com'").get("id")).longValue();
    LocalDate today = LocalDate.now();
    YearMonth month = YearMonth.from(today);
    long attendanceId = Sql.insert("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
        employee.getId(), today, today.atTime(8, 0), today.atTime(17, 0));
    long otherAttendanceId = Sql.insert("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
        otherId, today, today.atTime(8, 0), today.atTime(17, 0));
    PortalService portal = new PortalService();

    portal.finalizeAttendanceEmployeeMonth(manager, employee.getId(), month, true);
    check(finalized(attendanceId), "employee month finalized");
    check(!finalized(otherAttendanceId), "other employee remains open");
    expectFailure(() -> portal.clock(employee, true, null, null, "UNKNOWN"), "clock blocked after finalization");
    expectFailure(() -> portal.requestAttendanceAdjustment(employee, attendanceId, today.atTime(8, 30), today.atTime(17, 30), "fix"),
        "adjustment request blocked after finalization");

    portal.finalizeAttendanceEmployeeMonth(manager, employee.getId(), month, false);
    portal.requestAttendanceAdjustment(employee, attendanceId, today.atTime(8, 30), today.atTime(17, 30), "fix");
    long adjustmentId = ((Number) Sql.one("SELECT MAX(id) id FROM attendance_adjustments WHERE attendance_id=?", attendanceId).get("id")).longValue();
    portal.finalizeAttendanceEmployeeMonth(manager, employee.getId(), month, true);
    expectFailure(() -> portal.decideAttendanceAdjustment(manager, adjustmentId, true), "approval blocked after finalization");
    portal.finalizeAttendanceEmployeeMonth(manager, employee.getId(), month, false);
    portal.decideAttendanceAdjustment(manager, adjustmentId, true);
    check("APPROVED".equals(Sql.one("SELECT status FROM attendance_adjustments WHERE id=?", adjustmentId).get("status")), "approval after reopen");

    portal.finalizeAttendanceMonth(manager, month, true);
    check(finalized(attendanceId) && finalized(otherAttendanceId), "scoped month finalized");
    portal.finalizeAttendanceMonth(manager, month, false);
    check(!finalized(attendanceId) && !finalized(otherAttendanceId), "scoped month reopened");
    System.out.println("AttendanceFinalizationTest: all checks passed");
  }

  private static boolean finalized(long id) {
    return Boolean.TRUE.equals(Sql.one("SELECT finalized FROM attendance WHERE id=?", id).get("finalized"));
  }

  private static void expectFailure(Runnable action, String label) {
    try { action.run(); }
    catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
