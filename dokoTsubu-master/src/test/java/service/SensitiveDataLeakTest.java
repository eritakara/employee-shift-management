package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import model.User;

public class SensitiveDataLeakTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-sensitive-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    PortalService portal = new PortalService();
    String leaveReason = "SECRET_LEAVE_REASON_7f4a";
    String adjustmentReason = "SECRET_ADJUSTMENT_REASON_91bc";
    String latitude = "26.1234567";
    String longitude = "127.7654321";

    portal.requestLeave(employee, LocalDate.now().plusDays(10), "FULL", null, leaveReason);
    portal.clock(employee, true, latitude, longitude, "ACQUIRED");
    long attendanceId = ((Number) Sql.one("SELECT id FROM attendance WHERE user_id=? AND work_date=?", employee.getId(), LocalDate.now()).get("id")).longValue();
    portal.requestAttendanceAdjustment(employee, attendanceId, LocalDate.now().atTime(8, 0), LocalDate.now().atTime(17, 0), adjustmentReason);

    String auditText = joined("SELECT before_value,after_value FROM audit_logs");
    String mailText = joined("SELECT subject,body FROM mail_outbox");
    check(!auditText.contains(leaveReason) && !mailText.contains(leaveReason), "leave reason not leaked");
    check(!auditText.contains(adjustmentReason) && !mailText.contains(adjustmentReason), "adjustment reason not leaked");
    check(!auditText.contains(latitude) && !auditText.contains(longitude), "coordinates not logged");
    check(!mailText.contains(latitude) && !mailText.contains(longitude), "coordinates not mailed");

    String sensitiveName = "個人情報テスト氏名";
    String sensitiveEmail = "private-person@example.test";
    portal.updateEmployee(hr, employee.getId(), employee.getEmployeeNumber(), sensitiveName, sensitiveEmail, LocalDate.now().minusYears(1),
        employee.getBranchId(), employee.getDepartmentId(), 1, "EMPLOYEE", true);
    String updateAudit = joined("SELECT before_value,after_value FROM audit_logs WHERE action='UPDATE_USER'");
    check(!updateAudit.contains(sensitiveName) && !updateAudit.contains(sensitiveEmail) && !updateAudit.contains(employee.getEmail()),
        "name and email redacted from audit");
    System.out.println("SensitiveDataLeakTest: all checks passed");
  }

  private static String joined(String sql) {
    StringBuilder text = new StringBuilder();
    for (java.util.Map<String, Object> row : Sql.query(sql)) for (Object value : row.values()) if (value != null) text.append(value).append('\n');
    return text.toString();
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
