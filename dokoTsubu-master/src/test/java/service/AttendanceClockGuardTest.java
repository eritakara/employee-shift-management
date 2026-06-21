package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import model.User;

public class AttendanceClockGuardTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-clock-guard-test-").toString());
    Database.initialize();
    User employee = new UserDAO().authenticate("employee@example.com", "Password1!");
    PortalService portal = new PortalService();

    portal.clock(employee, true, null, null, "UNKNOWN");
    expectFailure(() -> portal.clock(employee, true, null, null, "UNKNOWN"), "duplicate clock-in blocked");
    check(Boolean.TRUE.equals(portal.attendanceClockSummary(employee).get("can_clock_out")), "clock-out is next action");

    long attendanceId = ((Number) Sql.one("SELECT id FROM attendance WHERE user_id=?", employee.getId()).get("id")).longValue();
    Sql.update("UPDATE attendance SET finalized=TRUE WHERE id=?", attendanceId);
    expectFailure(() -> portal.clock(employee, false, null, null, "UNKNOWN"), "finalized open attendance blocks clock-out");

    Sql.update("UPDATE attendance SET finalized=FALSE WHERE id=?", attendanceId);
    portal.clock(employee, false, null, null, "UNKNOWN");
    check("COMPLETE".equals(Sql.one("SELECT status FROM attendance WHERE id=?", attendanceId).get("status")), "clock-out completes attendance");
    check(Boolean.TRUE.equals(portal.attendanceClockSummary(employee).get("today_complete")), "summary marks completed day");
    System.out.println("AttendanceClockGuardTest: all checks passed");
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
