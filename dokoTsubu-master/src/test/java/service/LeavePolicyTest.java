package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import model.User;

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
    User employee = new UserDAO().authenticate("employee@example.com", "Password1!");
    new PortalService().requestLeave(employee, leaveDate.plusDays(20), "FULL", null, "");
    check("".equals(Sql.one("SELECT reason FROM leave_requests WHERE user_id=? AND leave_date=?", employeeId, leaveDate.plusDays(20)).get("reason")),
        "leave request reason is optional");
    new PortalService().requestLeave(employee, java.util.List.of(leaveDate.plusDays(21), leaveDate.plusDays(22), leaveDate.plusDays(23)), "FULL", null, "multi day");
    check(((Number) Sql.one("SELECT COUNT(*) count_value FROM leave_requests WHERE user_id=? AND reason='multi day'", employeeId).get("count_value")).intValue() == 3,
        "multi-day leave request creates one row per day");
    expectFailure(() -> new PortalService().requestLeave(employee, java.util.List.of(leaveDate.plusDays(21), leaveDate.plusDays(24)), "FULL", null, "duplicate"),
        "duplicate active leave request is rejected");
    expectFailure(() -> new PortalService().requestLeave(employee,
        java.util.List.of(leaveDate.plusDays(31), leaveDate.plusDays(32), leaveDate.plusDays(33), leaveDate.plusDays(34), leaveDate.plusDays(35),
            leaveDate.plusDays(36), leaveDate.plusDays(37), leaveDate.plusDays(38), leaveDate.plusDays(39), leaveDate.plusDays(40), leaveDate.plusDays(41)),
        "FULL", null, "too many days"), "multi-day leave balance shortage");

    long halfDayId = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason) VALUES(?,?,'AM','half day')", employeeId, leaveDate.plusDays(1));
    ledger.consume(halfDayId);
    Sql.update("UPDATE leave_requests SET status='APPROVED' WHERE id=?", halfDayId);
    check(((BigDecimal) ledger.balance(employeeId, today).get("days_remaining")).compareTo(new BigDecimal("9.50")) == 0, "half-day consumption");

    for (int i = 0; i < 5; i++) {
      long hourlyId = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason) VALUES(?,?,'HOURLY',8,'hourly')",
          employeeId, leaveDate.plusDays(2 + i));
      ledger.consume(hourlyId);
      Sql.update("UPDATE leave_requests SET status='APPROVED' WHERE id=?", hourlyId);
    }
    Map<String, Object> hourlyBalance = ledger.balance(employeeId, today);
    check(((Number) hourlyBalance.get("hourly_used")).intValue() == 40, "annual hourly usage");
    check(((Number) hourlyBalance.get("hourly_remaining")).intValue() == 0, "annual hourly limit reached");
    long overLimitId = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason) VALUES(?,?,'HOURLY',1,'over limit')",
        employeeId, leaveDate.plusDays(8));
    expectFailure(() -> ledger.consume(overLimitId), "annual 40-hour limit");

    Sql.update("UPDATE leave_grants SET expires_on=? WHERE user_id=?", today.minusDays(1), employeeId);
    policy.expire(today);
    check(((BigDecimal) Sql.one("SELECT days_remaining FROM leave_grants WHERE user_id=?", employeeId).get("days_remaining")).signum() == 0, "expired grant removed");
    check(((Number) Sql.one("SELECT COUNT(*) count_value FROM leave_history WHERE user_id=?", employeeId).get("count_value")).intValue() >= 4, "ledger history recorded");

    User manager = new UserDAO().authenticate("manager@example.com", "Password1!");
    expectFailure(() -> new PortalService().saveShift(manager, employeeId, today.plusDays(20), "LEAVE", "DRAFT", "insufficient"),
        "shift leave balance shortage");
    User hr = new UserDAO().authenticate("hr@example.com", "Password1!");
    LocalDate futureRuleDate = today.plusYears(1);
    policy.addRule(hr, futureRuleDate, new BigDecimal("0.850"), 4, 7, 18, 5);
    LeavePolicyService.Rule futureRule = policy.currentRule(futureRuleDate);
    check(futureRule.attendanceThreshold().compareTo(new BigDecimal("0.850")) == 0 && futureRule.hoursPerDay() == 7, "effective leave rule");
    long ruleId = ((Number) Sql.one("SELECT id FROM leave_rule_config WHERE effective_from=?", futureRuleDate).get("id")).longValue();
    policy.updateRule(hr, ruleId, futureRuleDate, new BigDecimal("0.900"), 5, 8, 24, 5, true);
    check(policy.currentRule(futureRuleDate).attendanceThreshold().compareTo(new BigDecimal("0.900")) == 0, "updated leave rule");
    expectFailure(() -> policy.addRule(hr, futureRuleDate.plusDays(1), new BigDecimal("1.100"), 5, 8, 24, 5), "invalid attendance threshold");
    System.out.println("LeavePolicyTest: all checks passed");
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
