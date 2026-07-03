package config;

import dao.Sql;
import java.math.BigDecimal;
import java.nio.file.Files;

public class LeaveGrantBackfillTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-leave-backfill-").toString());
    Database.initialize();
    long employeeId = ((Number) Sql.one("SELECT id FROM users WHERE email='employee@example.com'").get("id")).longValue();
    Sql.update("DELETE FROM leave_grants WHERE user_id=?", employeeId);
    Sql.update("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason,status) VALUES(?,CURRENT_DATE,'AM','backfill test','APPROVED')", employeeId);
    Sql.update("UPDATE leave_balances SET days_remaining=10,hourly_used=0,last_granted_on=CURRENT_DATE WHERE user_id=?", employeeId);

    resetDatabase();
    Database.initialize();

    BigDecimal activeDays = (BigDecimal) Sql.one("SELECT COALESCE(SUM(days_remaining),0) days_remaining "
        + "FROM leave_grants WHERE user_id=? AND expires_on>=CURRENT_DATE", employeeId).get("days_remaining");
    check(activeDays.compareTo(new BigDecimal("9.5")) == 0, "missing active grant is backfilled after approved leave use");
    check(count("SELECT COUNT(*) count_value FROM leave_grants WHERE user_id=? AND grant_date=CURRENT_DATE", employeeId) == 1,
        "backfill does not duplicate same-day migration grant");

    Sql.update("DELETE FROM leave_consumptions WHERE grant_id IN (SELECT id FROM leave_grants WHERE user_id=?)", employeeId);
    Sql.update("DELETE FROM leave_grants WHERE user_id=?", employeeId);
    long grantId = Sql.insert("INSERT INTO leave_grants(user_id,grant_date,expires_on,days_granted,days_remaining,attendance_rate,source) "
        + "VALUES(?,CURRENT_DATE,DATEADD('MONTH',24,CURRENT_DATE),10,10.01,1.0,'TEST')", employeeId);
    long requestId = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason,status) "
        + "VALUES(?,CURRENT_DATE,'HOURLY',1,'precision test','CANCELLED')", employeeId);
    Sql.update("INSERT INTO leave_consumptions(request_id,grant_id,days_used,hours_used,restored) VALUES(?,?,0.125,1,TRUE)",
        requestId, grantId);
    Sql.update("UPDATE leave_balances SET days_remaining=10.01 WHERE user_id=?", employeeId);
    try (java.sql.Connection connection = Database.getConnection(); java.sql.Statement statement = connection.createStatement()) {
      Database.migrateLeaveDayPrecision(statement);
      Database.migrateLeaveDayPrecision(statement);
    }
    check(((BigDecimal) Sql.one("SELECT days_remaining FROM leave_grants WHERE id=?", grantId).get("days_remaining"))
        .compareTo(new BigDecimal("10.000")) == 0, "10.01 grant balance is corrected idempotently");
    check(((BigDecimal) Sql.one("SELECT days_remaining FROM leave_balances WHERE user_id=?", employeeId).get("days_remaining"))
        .compareTo(new BigDecimal("10.000")) == 0, "10.01 summary balance is corrected idempotently");
    System.out.println("LeaveGrantBackfillTest: all checks passed");
  }

  private static int count(String sql, Object... args) {
    return ((Number) Sql.one(sql, args).get("count_value")).intValue();
  }

  private static void resetDatabase() throws Exception {
    java.lang.reflect.Field urlField = Database.class.getDeclaredField("jdbcUrl");
    urlField.setAccessible(true);
    urlField.set(null, null);

    java.lang.reflect.Field userField = Database.class.getDeclaredField("jdbcUser");
    userField.setAccessible(true);
    userField.set(null, null);

    java.lang.reflect.Field passField = Database.class.getDeclaredField("jdbcPassword");
    passField.setAccessible(true);
    passField.set(null, null);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
