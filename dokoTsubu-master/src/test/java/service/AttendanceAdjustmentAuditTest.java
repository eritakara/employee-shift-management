package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import model.User;

public class AttendanceAdjustmentAuditTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-adjustment-audit-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    LocalDate day = LocalDate.now();
    LocalDateTime originalIn = day.atTime(9, 0);
    LocalDateTime originalOut = day.atTime(17, 0);
    LocalDateTime correctedIn = day.atTime(8, 45);
    LocalDateTime correctedOut = day.atTime(17, 30);
    long attendanceId = Sql.insert(
        "INSERT INTO attendance(user_id,work_date,clock_in,clock_out,in_lat,in_lng,status) VALUES(?,?,?,?,35.0,139.0,'COMPLETE')",
        employee.getId(), day, originalIn, originalOut);
    PortalService portal = new PortalService();
    portal.requestAttendanceAdjustment(employee, attendanceId, correctedIn, correctedOut, "private correction reason");
    long requestId = ((Number) Sql.one("SELECT MAX(id) id FROM attendance_adjustments").get("id")).longValue();
    portal.decideAttendanceAdjustment(manager, requestId, true);

    Map<String, Object> audit = Sql.one(
        "SELECT before_value,after_value,target_user_id FROM audit_logs WHERE action='APPROVE_ATTENDANCE_ADJUSTMENT' AND target_id=?",
        String.valueOf(requestId));
    String before = String.valueOf(audit.get("before_value"));
    String after = String.valueOf(audit.get("after_value"));
    check(before.contains("clock_in=" + Timestamp.valueOf(originalIn))
            && before.contains("clock_out=" + Timestamp.valueOf(originalOut)),
        "audit records original clock values");
    check(after.contains("clock_in=" + Timestamp.valueOf(correctedIn))
            && after.contains("clock_out=" + Timestamp.valueOf(correctedOut)),
        "audit records corrected clock values");
    check(!before.contains("35.0") && !after.contains("139.0") && !after.contains("private"),
        "audit excludes location and correction reason");
    check(((Number) audit.get("target_user_id")).longValue() == employee.getId(),
        "audit identifies affected employee");
    System.out.println("AttendanceAdjustmentAuditTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
