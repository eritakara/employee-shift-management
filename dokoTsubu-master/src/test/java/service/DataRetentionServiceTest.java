package service;

import config.Database;
import dao.Sql;
import java.nio.file.Files;
import java.time.LocalDate;

public class DataRetentionServiceTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-retention-test-").toString());
    Database.initialize();
    LocalDate today = LocalDate.of(2032, 1, 1);
    LocalDate oldDate = LocalDate.of(2026, 12, 31);
    LocalDate recentDate = LocalDate.of(2031, 12, 1);
    long employeeId = ((Number) Sql.one("SELECT id FROM users WHERE email='employee@example.com'").get("id")).longValue();
    long coworkerId = ((Number) Sql.one("SELECT id FROM users WHERE email='sato@example.com'").get("id")).longValue();
    Sql.update("UPDATE app_settings SET setting_value='5' WHERE setting_key='RETENTION_YEARS'");

    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by) VALUES(?,?,'DAY','CONFIRMED',?)", employeeId, oldDate, employeeId);
    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by) VALUES(?,?,'DAY','CONFIRMED',?)", employeeId, recentDate, employeeId);
    long attendanceId = Sql.insert("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
        employeeId, oldDate, oldDate.atTime(8, 0), oldDate.atTime(17, 0));
    Sql.update("INSERT INTO attendance_adjustments(attendance_id,requested_by,requested_in,requested_out,reason) VALUES(?,?,?,?, 'old')",
        attendanceId, employeeId, oldDate.atTime(8, 5), oldDate.atTime(17, 5));
    long leaveId = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason,status) VALUES(?,?,'FULL','old','APPROVED')", employeeId, oldDate);
    long grantId = ((Number) Sql.one("SELECT id FROM leave_grants WHERE user_id=? ORDER BY id LIMIT 1", employeeId).get("id")).longValue();
    Sql.update("INSERT INTO leave_consumptions(request_id,grant_id,days_used) VALUES(?,?,1)", leaveId, grantId);
    Sql.update("INSERT INTO leave_history(user_id,event_type,event_date,days,note) VALUES(?,'USE',?,1,'old')", employeeId, oldDate);
    long notificationId = Sql.insert("INSERT INTO notifications(user_id,type,title,message) VALUES(?,'OLD','old','old')", employeeId);
    Sql.update("UPDATE notifications SET created_at=? WHERE id=?", oldDate.atStartOfDay(), notificationId);
    long mailId = Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES('old@example.test','old','old')");
    Sql.update("UPDATE mail_outbox SET created_at=? WHERE id=?", oldDate.atStartOfDay(), mailId);
    AuditService.record(employeeId, "OLD_EVENT", "USER", String.valueOf(employeeId), null, null);
    Sql.update("UPDATE audit_logs SET created_at=? WHERE action='OLD_EVENT'", oldDate.atStartOfDay());
    Sql.update("UPDATE users SET active=FALSE,deactivated_at=? WHERE id=?", oldDate.atStartOfDay(), coworkerId);

    int changed = new DataRetentionService().run(today);
    check(changed >= 9, "old records processed");
    check(count("shifts", "work_date", oldDate) == 0 && count("shifts", "work_date", recentDate) == 1, "only expired shifts removed");
    check(Sql.one("SELECT id FROM attendance WHERE id=?", attendanceId).isEmpty(), "old attendance removed");
    check(Sql.one("SELECT id FROM attendance_adjustments WHERE attendance_id=?", attendanceId).isEmpty(), "old adjustment removed");
    check(Sql.one("SELECT id FROM leave_requests WHERE id=?", leaveId).isEmpty(), "old leave request removed");
    check(Sql.one("SELECT id FROM leave_consumptions WHERE request_id=?", leaveId).isEmpty(), "old leave allocation removed");
    check(Sql.one("SELECT id FROM notifications WHERE id=?", notificationId).isEmpty(), "old notification removed");
    check(Sql.one("SELECT id FROM mail_outbox WHERE id=?", mailId).isEmpty(), "old mail removed");
    check(Sql.one("SELECT id FROM audit_logs WHERE action='OLD_EVENT'").isEmpty(), "old audit removed");
    String anonymizedEmail = String.valueOf(Sql.one("SELECT email FROM users WHERE id=?", coworkerId).get("email"));
    check(anonymizedEmail.equals("deleted+" + coworkerId + "@invalid.local"), "inactive user anonymized");
    check(new DataRetentionService().run(today) == 0, "retention is idempotent");
    System.out.println("DataRetentionServiceTest: all checks passed");
  }

  private static int count(String table, String column, Object value) {
    return ((Number) Sql.one("SELECT COUNT(*) count_value FROM " + table + " WHERE " + column + "=?", value).get("count_value")).intValue();
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
