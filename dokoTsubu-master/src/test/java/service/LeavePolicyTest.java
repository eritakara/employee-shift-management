package service;

import config.Database;
import dao.Sql;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class LeavePolicyTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-leave-test-").toString());
    Database.initialize();
    LeavePolicyService policy = new LeavePolicyService();
    int[] full = {10, 11, 12, 14, 16, 18, 20};
    int[] fourDays = {7, 8, 9, 10, 12, 13, 15};
    for (int i = 0; i < full.length; i++) {
      check(policy.statutoryGrantDays(5, new BigDecimal("40"), i) == full[i], "full-time sequence " + i);
      check(policy.statutoryGrantDays(4, new BigDecimal("24"), i) == fourDays[i], "proportional sequence " + i);
    }
    check(policy.statutoryGrantDays(4, new BigDecimal("30"), 0) == 10, "30 hours uses full-time table");

    long employeeId = ((Number) Sql.one("SELECT id FROM users WHERE email='employee@example.com'").get("id")).longValue();
    LocalDate today = LocalDate.of(2026, 6, 17);
    LocalDate hire = today.minusMonths(6);
    Sql.update("UPDATE users SET hire_date=?,weekly_work_days=5,weekly_work_hours=40 WHERE id=?", hire, employeeId);
    Sql.update("DELETE FROM leave_consumptions");
    Sql.update("DELETE FROM leave_history WHERE user_id=?", employeeId);
    Sql.update("DELETE FROM leave_grants WHERE user_id=?", employeeId);
    for (int i = 1; i <= 10; i++) {
      LocalDate day = today.minusDays(i);
      Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by) VALUES(?,?,'DAY','CONFIRMED',?)", employeeId, day, employeeId);
      if (i <= 8) Sql.update("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
          employeeId, day, day.atTime(8, 0), day.atTime(17, 0));
    }
    check(policy.attendanceRate(employeeId, hire, today.minusDays(1)).compareTo(new BigDecimal("0.8000")) == 0, "80 percent attendance");
    policy.runDaily(today);
    check(((BigDecimal) Sql.one("SELECT days_granted FROM leave_grants WHERE user_id=? AND grant_date=?", employeeId, today).get("days_granted")).compareTo(BigDecimal.TEN) == 0, "initial statutory grant");

    LocalDate leaveDate = today.plusDays(10);
    long requestId = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason) VALUES(?,?,'FULL','test')", employeeId, leaveDate);
    LeaveLedgerService ledger = new LeaveLedgerService();
    ledger.consume(requestId);
    Sql.update("UPDATE leave_requests SET status='APPROVED' WHERE id=?", requestId);
    check(((BigDecimal) ledger.balance(employeeId, today).get("days_remaining")).compareTo(new BigDecimal("9.00")) == 0, "FIFO consumption");
    ledger.restore(requestId, today.plusDays(1));
    Sql.update("UPDATE leave_requests SET status='CANCELLED' WHERE id=?", requestId);
    check(((BigDecimal) ledger.balance(employeeId, today).get("days_remaining")).compareTo(new BigDecimal("10.00")) == 0, "cancellation restores balance");

    Sql.update("UPDATE leave_grants SET expires_on=? WHERE user_id=?", today.minusDays(1), employeeId);
    policy.expire(today);
    check(((BigDecimal) Sql.one("SELECT days_remaining FROM leave_grants WHERE user_id=?", employeeId).get("days_remaining")).signum() == 0, "expired grant removed");
    check(((Number) Sql.one("SELECT COUNT(*) count_value FROM leave_history WHERE user_id=?", employeeId).get("count_value")).intValue() >= 4, "ledger history recorded");
    System.out.println("LeavePolicyTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
