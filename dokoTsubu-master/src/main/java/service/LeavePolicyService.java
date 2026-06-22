package service;

import dao.Sql;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import model.User;
import static util.DateUtil.toDate;

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

  public List<Map<String, Object>> rules(User user) {
    requireHr(user);
    return Sql.query("SELECT * FROM leave_rule_config ORDER BY effective_from DESC,id DESC");
  }

  public void addRule(User user, LocalDate effectiveFrom, BigDecimal attendanceThreshold,
      int hourlyLimitDays, int hoursPerDay, int expiryMonths, int mandatoryDays) {
    requireHr(user);
    validateRule(effectiveFrom, attendanceThreshold, hourlyLimitDays, hoursPerDay, expiryMonths, mandatoryDays);
    if (!Sql.query("SELECT id FROM leave_rule_config WHERE effective_from=?", effectiveFrom).isEmpty()) {
      throw new IllegalArgumentException("同じ施行日のルールが既にあります。");
    }
    long id = Sql.insert("INSERT INTO leave_rule_config(effective_from,attendance_threshold,hourly_limit_days,hours_per_day,expiry_months,mandatory_days) VALUES(?,?,?,?,?,?)",
        effectiveFrom, attendanceThreshold, hourlyLimitDays, hoursPerDay, expiryMonths, mandatoryDays);
    AuditService.record(user.getId(), "CREATE_LEAVE_RULE", "LEAVE_RULE", String.valueOf(id), null,
        effectiveFrom + ":" + attendanceThreshold + ":" + hourlyLimitDays + ":" + hoursPerDay + ":" + expiryMonths + ":" + mandatoryDays);
  }

  public void updateRule(User user, long id, LocalDate effectiveFrom, BigDecimal attendanceThreshold,
      int hourlyLimitDays, int hoursPerDay, int expiryMonths, int mandatoryDays, boolean active) {
    requireHr(user);
    validateRule(effectiveFrom, attendanceThreshold, hourlyLimitDays, hoursPerDay, expiryMonths, mandatoryDays);
    Map<String, Object> before = Sql.one("SELECT * FROM leave_rule_config WHERE id=?", id);
    if (before.isEmpty()) throw new IllegalArgumentException("有休付与ルールが見つかりません。");
    if (!Sql.query("SELECT id FROM leave_rule_config WHERE effective_from=? AND id<>?", effectiveFrom, id).isEmpty()) {
      throw new IllegalArgumentException("同じ施行日のルールが既にあります。");
    }
    Sql.update("UPDATE leave_rule_config SET effective_from=?,attendance_threshold=?,hourly_limit_days=?,hours_per_day=?,expiry_months=?,mandatory_days=?,active=? WHERE id=?",
        effectiveFrom, attendanceThreshold, hourlyLimitDays, hoursPerDay, expiryMonths, mandatoryDays, active, id);
    AuditService.record(user.getId(), "UPDATE_LEAVE_RULE", "LEAVE_RULE", String.valueOf(id), before.toString(),
        effectiveFrom + ":" + attendanceThreshold + ":" + hourlyLimitDays + ":" + hoursPerDay + ":" + expiryMonths + ":" + mandatoryDays + ":" + active);
  }

  private void validateRule(LocalDate effectiveFrom, BigDecimal threshold, int hourlyLimitDays,
      int hoursPerDay, int expiryMonths, int mandatoryDays) {
    if (effectiveFrom == null) throw new IllegalArgumentException("施行日を入力してください。");
    if (threshold == null || threshold.compareTo(BigDecimal.ZERO) < 0 || threshold.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("出勤率基準は0から1で入力してください。");
    if (hourlyLimitDays < 0 || hourlyLimitDays > 5) throw new IllegalArgumentException("時間単位上限は0日から5日で入力してください。");
    if (hoursPerDay < 1 || hoursPerDay > 24) throw new IllegalArgumentException("1日の時間数は1から24で入力してください。");
    if (expiryMonths < 1 || expiryMonths > 120) throw new IllegalArgumentException("有効期間は1か月から120か月で入力してください。");
    if (mandatoryDays < 0 || mandatoryDays > 20) throw new IllegalArgumentException("取得義務日数は0日から20日で入力してください。");
  }

  private void requireHr(User user) {
    if (user == null || !user.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
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
    Sql.update("MERGE INTO leave_balances AS t USING (SELECT CAST(? AS BIGINT) AS user_id, CAST(? AS DECIMAL(6,2)) AS days_remaining, COALESCE((SELECT hourly_used FROM leave_balances WHERE user_id=CAST(? AS BIGINT)),0) AS hourly_used, (SELECT MAX(grant_date) FROM leave_grants WHERE user_id=CAST(? AS BIGINT)) AS last_granted_on) AS s ON t.user_id = s.user_id WHEN MATCHED THEN UPDATE SET days_remaining = s.days_remaining, last_granted_on = s.last_granted_on WHEN NOT MATCHED THEN INSERT (user_id, days_remaining, hourly_used, last_granted_on) VALUES (s.user_id, s.days_remaining, s.hourly_used, s.last_granted_on)",
        userId, total.get("total"), userId, userId);
  }

  private boolean allowAssumedAttendance() {
    return Boolean.parseBoolean(System.getProperty("SHIFTFLOW_ASSUME_FULL_ATTENDANCE_WITHOUT_DATA",
        System.getenv().getOrDefault("SHIFTFLOW_ASSUME_FULL_ATTENDANCE_WITHOUT_DATA", "false")));
  }


}
