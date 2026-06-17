package service;

import config.Database;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public class LeaveLedgerService {
  private final LeavePolicyService policy = new LeavePolicyService();

  public Map<String, Object> balance(long userId, LocalDate asOf) {
    Map<String, Object> result = new LinkedHashMap<>();
    Map<String, Object> total = dao.Sql.one("SELECT COALESCE(SUM(days_remaining),0) days_remaining FROM leave_grants WHERE user_id=? AND expires_on>=?", userId, asOf);
    LeavePolicyService.Rule rule = policy.currentRule(asOf);
    Map<String, Object> hourly = dao.Sql.one("SELECT COALESCE(SUM(hours),0) hourly_used FROM leave_requests WHERE user_id=? AND leave_unit='HOURLY' AND status='APPROVED' AND EXTRACT(YEAR FROM leave_date)=?",
        userId, asOf.getYear());
    int used = ((Number) hourly.getOrDefault("hourly_used", 0)).intValue();
    result.put("days_remaining", total.get("days_remaining"));
    result.put("hourly_used", used);
    result.put("hourly_remaining", rule.hourlyLimitDays() * rule.hoursPerDay() - used);
    result.put("hours_per_day", rule.hoursPerDay());
    return result;
  }

  public void consume(long requestId) {
    try (Connection c = Database.getConnection()) {
      c.setAutoCommit(false);
      try {
        Request request = loadRequest(c, requestId);
        BigDecimal needed = request.days(policy.currentRule(request.leaveDate()).hoursPerDay());
        if (request.hourly()) verifyHourlyLimit(c, request);
        BigDecimal remaining = needed;
        try (PreparedStatement p = c.prepareStatement("SELECT id,days_remaining FROM leave_grants WHERE user_id=? AND expires_on>=? AND days_remaining>0 ORDER BY expires_on,grant_date FOR UPDATE")) {
          p.setLong(1, request.userId()); p.setObject(2, request.leaveDate());
          try (ResultSet rs = p.executeQuery()) {
            while (rs.next() && remaining.signum() > 0) {
              long grantId = rs.getLong("id");
              BigDecimal available = rs.getBigDecimal("days_remaining");
              BigDecimal used = available.min(remaining);
              updateGrant(c, grantId, available.subtract(used));
              try (PreparedStatement insert = c.prepareStatement("INSERT INTO leave_consumptions(request_id,grant_id,days_used,hours_used) VALUES(?,?,?,?)")) {
                insert.setLong(1, requestId); insert.setLong(2, grantId); insert.setBigDecimal(3, used);
                insert.setInt(4, request.hourly() ? request.hours() : 0); insert.executeUpdate();
              }
              remaining = remaining.subtract(used);
            }
          }
        }
        if (remaining.signum() > 0) throw new IllegalArgumentException("有効期限内の有休残数が不足しています。");
        try (PreparedStatement history = c.prepareStatement("INSERT INTO leave_history(user_id,event_type,event_date,days,hours,request_id,note) VALUES(?,'USE',?,?,?,?,?)")) {
          history.setLong(1, request.userId()); history.setObject(2, request.leaveDate()); history.setBigDecimal(3, needed);
          history.setInt(4, request.hours()); history.setLong(5, requestId); history.setString(6, request.unit()); history.executeUpdate();
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        if (e instanceof IllegalArgumentException illegal) throw illegal;
        throw new IllegalStateException("有休台帳の更新に失敗しました。", e);
      }
    } catch (java.sql.SQLException e) { throw new IllegalStateException("有休台帳へ接続できません。", e); }
    Request request = request(requestId);
    policy.syncBalance(request.userId(), request.leaveDate());
  }

  public void restore(long requestId, LocalDate eventDate) {
    Request request = request(requestId);
    for (Map<String, Object> allocation : dao.Sql.query("SELECT grant_id,days_used FROM leave_consumptions WHERE request_id=? AND restored=FALSE", requestId)) {
      dao.Sql.update("UPDATE leave_grants SET days_remaining=days_remaining+? WHERE id=?", allocation.get("days_used"), allocation.get("grant_id"));
    }
    dao.Sql.update("UPDATE leave_consumptions SET restored=TRUE WHERE request_id=?", requestId);
    BigDecimal days = request.days(policy.currentRule(request.leaveDate()).hoursPerDay());
    dao.Sql.insert("INSERT INTO leave_history(user_id,event_type,event_date,days,hours,request_id,note) VALUES(?,'RESTORE',?,?,?,?,?)",
        request.userId(), eventDate, days, request.hours(), requestId, "cancelled");
    policy.syncBalance(request.userId(), eventDate);
  }

  private void verifyHourlyLimit(Connection c, Request request) throws Exception {
    LeavePolicyService.Rule rule = policy.currentRule(request.leaveDate());
    try (PreparedStatement p = c.prepareStatement("SELECT COALESCE(SUM(hours),0) FROM leave_requests WHERE user_id=? AND leave_unit='HOURLY' AND status='APPROVED' AND EXTRACT(YEAR FROM leave_date)=?")) {
      p.setLong(1, request.userId()); p.setInt(2, request.leaveDate().getYear());
      try (ResultSet rs = p.executeQuery()) { rs.next(); if (rs.getInt(1) + request.hours() > rule.hourlyLimitDays() * rule.hoursPerDay()) throw new IllegalArgumentException("時間単位有休の年間上限を超えています。"); }
    }
  }

  private Request loadRequest(Connection c, long id) throws Exception {
    try (PreparedStatement p = c.prepareStatement("SELECT user_id,leave_date,leave_unit,hours FROM leave_requests WHERE id=? FOR UPDATE")) {
      p.setLong(1, id); try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("有休申請が見つかりません。");
        return new Request(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getInt(4));
      }
    }
  }

  private Request request(long id) {
    Map<String, Object> row = dao.Sql.one("SELECT user_id,leave_date,leave_unit,hours FROM leave_requests WHERE id=?", id);
    LocalDate date = row.get("leave_date") instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) row.get("leave_date");
    return new Request(((Number) row.get("user_id")).longValue(), date, String.valueOf(row.get("leave_unit")), row.get("hours") == null ? 0 : ((Number) row.get("hours")).intValue());
  }

  private void updateGrant(Connection c, long grantId, BigDecimal remaining) throws Exception {
    try (PreparedStatement p = c.prepareStatement("UPDATE leave_grants SET days_remaining=? WHERE id=?")) { p.setBigDecimal(1, remaining); p.setLong(2, grantId); p.executeUpdate(); }
  }

  private record Request(long userId, LocalDate leaveDate, String unit, int hours) {
    boolean hourly() { return "HOURLY".equals(unit); }
    BigDecimal days(int hoursPerDay) {
      if ("FULL".equals(unit)) return BigDecimal.ONE;
      if ("AM".equals(unit) || "PM".equals(unit)) return new BigDecimal("0.5");
      return BigDecimal.valueOf(hours).divide(BigDecimal.valueOf(hoursPerDay), 3, RoundingMode.HALF_UP);
    }
  }
}
