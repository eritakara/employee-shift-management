package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Map;
import model.User;

public class CoreWorkflowE2ETest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-core-e2e-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User delegate = users.authenticate("sato@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    PortalService portal = new PortalService();

    shiftSubmissionToConfirmation(portal, employee, manager);
    leaveRequestToBalanceUpdate(portal, employee, manager);
    clockToAdjustmentAndFinalization(portal, employee, manager);
    delegatedApprovalDuringManagerAbsence(portal, employee, delegate, manager, hr);
    System.out.println("CoreWorkflowE2ETest: all checks passed");
  }

  private static void shiftSubmissionToConfirmation(PortalService portal, User employee, User manager) {
    LocalDate policyToday = LocalDate.of(2026, 6, 10);
    YearMonth targetMonth = YearMonth.of(2026, 7);
    LocalDate workDate = targetMonth.atDay(8);
    portal.submitPreferredShift(employee, workDate, "DAY", "E2E preferred shift", policyToday);
    check("SUBMITTED".equals(shift(employee, workDate).get("status")), "E2E-001 shift submitted");
    portal.confirmMonth(manager, targetMonth, "E2E staffing warning acknowledged");
    check("CONFIRMED".equals(shift(employee, workDate).get("status")), "E2E-001 employee sees confirmed shift");
  }

  private static void leaveRequestToBalanceUpdate(PortalService portal, User employee, User manager) {
    LocalDate leaveDate = LocalDate.now().plusMonths(2).withDayOfMonth(12);
    BigDecimal before = remaining(employee.getId());
    portal.requestLeave(employee, leaveDate, "FULL", null, "E2E leave request");
    long requestId = id("SELECT MAX(id) id FROM leave_requests WHERE user_id=?", employee.getId());
    portal.decideLeave(manager, requestId, true);
    check("APPROVED".equals(Sql.one("SELECT status FROM leave_requests WHERE id=?", requestId).get("status")),
        "E2E-002 leave approved");
    check(remaining(employee.getId()).compareTo(before.subtract(BigDecimal.ONE)) == 0,
        "E2E-002 leave balance updated");
  }

  private static void clockToAdjustmentAndFinalization(PortalService portal, User employee, User manager) {
    LocalDate today = LocalDate.now();
    portal.clock(employee, true, "35.681236", "139.767125", "ACQUIRED");
    portal.clock(employee, false, "35.681236", "139.767125", "ACQUIRED");
    long attendanceId = id("SELECT id FROM attendance WHERE user_id=? AND work_date=?", employee.getId(), today);
    LocalDateTime correctedIn = today.atTime(8, 45);
    LocalDateTime correctedOut = today.atTime(17, 45);
    portal.requestAttendanceAdjustment(employee, attendanceId, correctedIn, correctedOut, "E2E correction");
    long adjustmentId = id("SELECT MAX(id) id FROM attendance_adjustments WHERE attendance_id=?", attendanceId);
    portal.decideAttendanceAdjustment(manager, adjustmentId, true);
    portal.finalizeAttendanceEmployeeMonth(manager, employee.getId(), YearMonth.from(today), true);
    Map<String, Object> attendance = Sql.one("SELECT clock_in,clock_out,finalized FROM attendance WHERE id=?", attendanceId);
    check(Boolean.TRUE.equals(attendance.get("finalized")), "E2E-003 attendance finalized");
    check(correctedIn.equals(((Timestamp) attendance.get("clock_in")).toLocalDateTime())
            && correctedOut.equals(((Timestamp) attendance.get("clock_out")).toLocalDateTime()),
        "E2E-003 approved correction applied");
  }

  private static void delegatedApprovalDuringManagerAbsence(PortalService portal, User employee,
      User delegate, User manager, User hr) {
    LocalDate today = LocalDate.now();
    portal.addDelegation(hr, manager.getId(), delegate.getId(), today.minusDays(1), today.plusDays(3));
    LocalDate workDate = today.plusMonths(3).withDayOfMonth(15);
    portal.requestShiftChange(employee, workDate, "OFF", "E2E delegated approval");
    long requestId = id("SELECT MAX(id) id FROM shift_change_requests WHERE user_id=?", employee.getId());
    portal.decideShiftChange(delegate, requestId, true);
    check("APPROVED".equals(Sql.one("SELECT status FROM shift_change_requests WHERE id=?", requestId).get("status")),
        "E2E-004 active delegate approved request");
  }

  private static Map<String, Object> shift(User employee, LocalDate date) {
    return Sql.one("SELECT status,work_type_code FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), date);
  }

  private static BigDecimal remaining(long userId) {
    return (BigDecimal) Sql.one("SELECT days_remaining FROM leave_balances WHERE user_id=?", userId).get("days_remaining");
  }

  private static long id(String sql, Object... args) {
    return ((Number) Sql.one(sql, args).get("id")).longValue();
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
