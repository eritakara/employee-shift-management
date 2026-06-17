package service;

import dao.Sql;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;

public class LeavePolicyService {
  private static final int[] FULL_TIME = {10, 11, 12, 14, 16, 18, 20};
  private static final int[][] PROPORTIONAL = {
      {},
      {1, 2, 2, 2, 3, 3, 3},
      {3, 4, 4, 5, 6, 6, 7},
      {5, 6, 6, 8, 9, 10, 11},
      {7, 8, 9, 10, 12, 13, 15}
  };

  public record Rule(BigDecimal attendanceThreshold, int hourlyLimitDays,
      int hoursPerDay, int expiryMonths, int mandatoryDays) { }

  public Rule currentRule(LocalDate date) {
    Map<String, Object> row = Sql.one("SELECT * FROM leave_rule_config WHERE active=TRUE AND effective_from<=? ORDER BY effective_from DESC LIMIT 1", date);
    if (row.isEmpty()) return new Rule(new BigDecimal("0.800"), 5, 8, 24, 5);
    return new Rule((BigDecimal) row.get("attendance_threshold"),
        ((Number) row.get("hourly_limit_days")).intValue(),
        ((Number) row.get("hours_per_day")).intValue(),
        ((Number) row.get("expiry_months")).intValue(),
        ((Number) row.get("mandatory_days")).intValue());
  }

  public int statutoryGrantDays(int weeklyDays, BigDecimal weeklyHours, int grantSequence) {
    int index = Math.max(0, Math.min(grantSequence, 6));
    if (weeklyHours.compareTo(new BigDecimal("30")) >= 0 || weeklyDays >= 5) return FULL_TIME[index];
    if (weeklyDays < 1 || weeklyDays > 4) return 0;
    return PROPORTIONAL[weeklyDays][index];
  }

  public void runDaily(LocalDate today) {
    expire(today);
    for (Map<String, Object> user : Sql.query("SELECT id,hire_date,weekly_work_days,weekly_work_hours FROM users WHERE active=TRUE")) {
      LocalDate hire = toDate(user.get("hire_date"));
      LocalDate firstGrant = hire.plusMonths(6);
      if (today.isBefore(firstGrant)) continue;
      int sequence = Period.between(firstGrant, today).getYears();
      LocalDate due = firstGrant.plusYears(sequence);
      if (!due.equals(today)) continue;
      long userId = ((Number) user.get("id")).longValue();
      if (!Sql.query("SELECT id FROM leave_grants WHERE user_id=? AND grant_date=?", userId, due).isEmpty()) continue;
      LocalDate periodStart = sequence == 0 ? hire : due.minusYears(1);
      BigDecimal attendanceRate = attendanceRate(userId, periodStart, due.minusDays(1));
      Rule rule = currentRule(due);
      if (attendanceRate.compareTo(rule.attendanceThreshold()) < 0) {
        Sql.insert("INSERT INTO leave_history(user_id,event_type,event_date,note) VALUES(?,'INELIGIBLE',?,?)",
            userId, due, "attendance_rate=" + attendanceRate);
        continue;
      }
      int days = statutoryGrantDays(((Number) user.get("weekly_work_days")).intValue(),
          (BigDecimal) user.get("weekly_work_hours"), sequence);
      if (days <= 0) continue;
      long grantId = Sql.insert("INSERT INTO leave_grants(user_id,grant_date,expires_on,days_granted,days_remaining,attendance_rate,source) VALUES(?,?,?,?,?,?,'STATUTORY')",
          userId, due, due.plusMonths(rule.expiryMonths()), days, days, attendanceRate);
      Sql.insert("INSERT INTO leave_history(user_id,event_type,event_date,days,grant_id,note) VALUES(?,'GRANT',?,?,?,?)",
          userId, due, days, grantId, "statutory sequence=" + sequence);
      syncBalance(userId, due);
    }
  }

  public BigDecimal attendanceRate(long userId, LocalDate from, LocalDate to) {
    Map<String, Object> row = Sql.one("SELECT COUNT(*) scheduled,COUNT(CASE WHEN a.clock_in IS NOT NULL THEN 1 END) attended "
        + "FROM shifts s LEFT JOIN attendance a ON a.user_id=s.user_id AND a.work_date=s.work_date "
        + "WHERE s.user_id=? AND s.status='CONFIRMED' AND s.work_type_code IN('DAY','NIGHT','AM_LEAVE','PM_LEAVE') AND s.work_date BETWEEN ? AND ?",
        userId, from, to);
    int scheduled = ((Number) row.getOrDefault("scheduled", 0)).intValue();
    int attended = ((Number) row.getOrDefault("attended", 0)).intValue();
    if (scheduled == 0) return allowAssumedAttendance() ? BigDecimal.ONE : BigDecimal.ZERO;
    return BigDecimal.valueOf(attended).divide(BigDecimal.valueOf(scheduled), 4, RoundingMode.HALF_UP);
  }

  public void expire(LocalDate today) {
    List<Map<String, Object>> grants = Sql.query("SELECT id,user_id,days_remaining FROM leave_grants WHERE expires_on<? AND days_remaining>0", today);
    for (Map<String, Object> grant : grants) {
      Sql.update("UPDATE leave_grants SET days_remaining=0 WHERE id=?", grant.get("id"));
      Sql.insert("INSERT INTO leave_history(user_id,event_type,event_date,days,grant_id,note) VALUES(?,'EXPIRE',?,?,?,'statutory expiry')",
          grant.get("user_id"), today, grant.get("days_remaining"), grant.get("id"));
      syncBalance(((Number) grant.get("user_id")).longValue(), today);
    }
  }

  public void syncBalance(long userId, LocalDate asOf) {
    Map<String, Object> total = Sql.one("SELECT COALESCE(SUM(days_remaining),0) total FROM leave_grants WHERE user_id=? AND expires_on>=?", userId, asOf);
    Sql.update("MERGE INTO leave_balances(user_id,days_remaining,hourly_used,last_granted_on) KEY(user_id) VALUES(?,?,COALESCE((SELECT hourly_used FROM leave_balances WHERE user_id=?),0),(SELECT MAX(grant_date) FROM leave_grants WHERE user_id=?))",
        userId, total.get("total"), userId, userId);
  }

  private boolean allowAssumedAttendance() {
    return Boolean.parseBoolean(System.getProperty("SHIFTFLOW_ASSUME_FULL_ATTENDANCE_WITHOUT_DATA",
        System.getenv().getOrDefault("SHIFTFLOW_ASSUME_FULL_ATTENDANCE_WITHOUT_DATA", "false")));
  }

  private LocalDate toDate(Object value) {
    if (value instanceof LocalDate date) return date;
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    return LocalDate.parse(String.valueOf(value));
  }
}
