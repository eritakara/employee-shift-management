package service;

import dao.Sql;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import model.User;

public class DashboardService {

  public Map<String, Object> dashboard(User user) {
    String scope = scope(user, "u");
    Object[] args = scopeArgs(user);
    String todayWorkers = "SELECT COUNT(*) AS metric_value FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE s.work_date=CURRENT_DATE AND s.work_type_code IN('DAY','NIGHT','AM_LEAVE','PM_LEAVE') AND s.status='CONFIRMED'" + scope;
    String pendingLeave = "SELECT COUNT(*) AS metric_value FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.status='PENDING'" + scope;
    String pendingShift = "SELECT COUNT(*) AS metric_value FROM shift_change_requests r JOIN users u ON u.id=r.user_id WHERE r.status='PENDING'" + scope;
    String pendingAttendance = "SELECT COUNT(*) AS metric_value FROM attendance_adjustments a JOIN users u ON u.id=a.requested_by WHERE a.status='PENDING'" + scope;
    String shortage = "SELECT COUNT(*) AS metric_value FROM (SELECT wt.code,wt.required_staff,COUNT(s.id) actual FROM work_types wt LEFT JOIN shifts s ON s.work_type_code=wt.code AND s.work_date=CURRENT_DATE AND s.status='CONFIRMED' LEFT JOIN users u ON u.id=s.user_id WHERE wt.required_staff>0"
        + (user.isHr() ? "" : " AND (u.id IS NULL OR (u.branch_id=CAST(? AS bigint) AND u.department_id=CAST(? AS bigint)))")
        + " GROUP BY wt.code,wt.required_staff HAVING COUNT(s.id)<wt.required_staff) x";
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("todayWorkers", value(todayWorkers, args));
    
    long pLeave = ((Number) value(pendingLeave, args)).longValue();
    long pShift = ((Number) value(pendingShift, args)).longValue();
    long pAttendance = ((Number) value(pendingAttendance, args)).longValue();
    result.put("pendingLeave", pLeave);
    result.put("pendingShift", pShift);
    result.put("pendingAttendance", pAttendance);
    result.put("pending", pLeave + pShift + pAttendance);

    Object[] shortageArgs = user.isHr() ? new Object[]{} : new Object[]{user.getBranchId(), user.getDepartmentId()};
    result.put("shortage", value(shortage, shortageArgs));
    result.put("dayShortagePercent", staffingShortagePercent(user, "DAY"));
    result.put("nightShortagePercent", staffingShortagePercent(user, "NIGHT"));
    result.put("leave", Sql.one("SELECT days_remaining AS metric_value FROM leave_balances WHERE user_id=?", user.getId()).getOrDefault("metric_value", 0));
    result.put("monthHours", monthHours(user));
    double overtimeHours = monthOvertimeHours(user);
    double overtimeLimit = 45.0;
    result.put("monthOvertimeHours", overtimeHours);
    result.put("overtimeAlertThreshold", overtimeLimit);
    result.put("overtimeAlertLevel", overtimeHours >= overtimeLimit ? "danger" : overtimeHours >= overtimeLimit * 0.8 ? "warning" : "safe");
    return result;
  }

  private int staffingShortagePercent(User user, String workType) {
    Number requiredValue = (Number) Sql.one("SELECT required_staff AS metric_value FROM work_types WHERE code=?", workType)
        .getOrDefault("metric_value", 0);
    int required = requiredValue.intValue();
    if (required <= 0) return 0;
    String filter = user.isHr() ? "" : " AND u.branch_id=CAST(? AS bigint) AND u.department_id=CAST(? AS bigint)";
    Object[] args = user.isHr() ? new Object[]{workType}
        : new Object[]{workType, user.getBranchId(), user.getDepartmentId()};
    Number actualValue = (Number) Sql.one("SELECT COUNT(*) AS metric_value FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE s.work_date=CURRENT_DATE AND s.work_type_code=? AND s.status='CONFIRMED'" + filter, args)
        .getOrDefault("metric_value", 0);
    int missing = Math.max(0, required - actualValue.intValue());
    return (int) Math.round(missing * 100.0 / required);
  }

  public List<Map<String, Object>> chart(User user) {
    String filter = user.isHr() ? "" : user.isManager() ? " AND u.branch_id=CAST(? AS bigint) AND u.department_id=CAST(? AS bigint)" : " AND u.id=CAST(? AS bigint)";
    Object[] args = user.isHr() ? new Object[]{} : user.isManager()
        ? new Object[]{user.getBranchId(), user.getDepartmentId()} : new Object[]{user.getId()};
    List<Map<String, Object>> attendance;
    List<Map<String, Object>> leave;
    if (config.Database.isPostgres()) {
      attendance = Sql.query("SELECT TO_CHAR(a.work_date,'YYYY-MM') AS month_label,"
          + "ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN GREATEST(0,EXTRACT(EPOCH FROM (a.clock_out - a.clock_in))/60.0-COALESCE(wt.break_minutes,0))/60.0 ELSE 0 END)::numeric,1) AS total_hours,"
          + "ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL AND wt.start_time IS NOT NULL THEN GREATEST(0,EXTRACT(EPOCH FROM (a.clock_out - a.clock_in))/60.0-CASE WHEN wt.crosses_midnight THEN 1440+EXTRACT(EPOCH FROM (wt.end_time - wt.start_time))/60.0 ELSE EXTRACT(EPOCH FROM (wt.end_time - wt.start_time))/60.0 END)/60.0 ELSE 0 END)::numeric,1) AS overtime_hours "
          + "FROM attendance a JOIN users u ON u.id=a.user_id LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.work_date>=CURRENT_DATE - INTERVAL '5 month'" + filter
          + " GROUP BY TO_CHAR(a.work_date,'YYYY-MM') ORDER BY month_label", args);
      leave = Sql.query("SELECT TO_CHAR(l.leave_date,'YYYY-MM') AS month_label,"
          + "SUM(CASE l.leave_unit WHEN 'FULL' THEN 1.0 WHEN 'AM' THEN 0.5 WHEN 'PM' THEN 0.5 WHEN 'HOUR' THEN l.hours/8.0 ELSE 0 END) AS leave_days "
          + "FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.status='APPROVED' AND l.leave_date>=CURRENT_DATE - INTERVAL '5 month'" + filter
          + " GROUP BY TO_CHAR(l.leave_date,'YYYY-MM') ORDER BY month_label", args);
    } else {
      attendance = Sql.query("SELECT FORMATDATETIME(a.work_date,'yyyy-MM') AS month_label,"
          + "ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-COALESCE(wt.break_minutes,0))/60.0 ELSE 0 END),1) AS total_hours,"
          + "ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL AND wt.start_time IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-CASE WHEN wt.crosses_midnight THEN 1440+DATEDIFF('MINUTE',wt.start_time,wt.end_time) ELSE DATEDIFF('MINUTE',wt.start_time,wt.end_time) END)/60.0 ELSE 0 END),1) AS overtime_hours "
          + "FROM attendance a JOIN users u ON u.id=a.user_id LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.work_date>=DATEADD('MONTH',-5,CURRENT_DATE)" + filter
          + " GROUP BY FORMATDATETIME(a.work_date,'yyyy-MM') ORDER BY month_label", args);
      leave = Sql.query("SELECT FORMATDATETIME(l.leave_date,'yyyy-MM') AS month_label,"
          + "SUM(CASE l.leave_unit WHEN 'FULL' THEN 1.0 WHEN 'AM' THEN 0.5 WHEN 'PM' THEN 0.5 WHEN 'HOUR' THEN l.hours/8.0 ELSE 0 END) AS leave_days "
          + "FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.status='APPROVED' AND l.leave_date>=DATEADD('MONTH',-5,CURRENT_DATE)" + filter
          + " GROUP BY FORMATDATETIME(l.leave_date,'yyyy-MM') ORDER BY month_label", args);
    }
    java.util.SortedMap<String, Map<String, Object>> months = new java.util.TreeMap<>();
    for (Map<String, Object> row : attendance) {
      row.put("leave_days", BigDecimal.ZERO);
      months.put(String.valueOf(row.get("month_label")), row);
    }
    for (Map<String, Object> row : leave) {
      String key = String.valueOf(row.get("month_label"));
      Map<String, Object> month = months.computeIfAbsent(key, ignored -> {
        Map<String, Object> empty = new java.util.LinkedHashMap<>();
        empty.put("month_label", key);
        empty.put("total_hours", BigDecimal.ZERO);
        empty.put("overtime_hours", BigDecimal.ZERO);
        return empty;
      });
      month.put("leave_days", row.get("leave_days"));
    }
    return new java.util.ArrayList<>(months.values());
  }

  private Object value(String sql, Object... args) { return Sql.one(sql, args).getOrDefault("metric_value", 0); }

  private double monthHours(User user) {
    String sql;
    if (config.Database.isPostgres()) {
      sql = "SELECT COALESCE(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN GREATEST(0,EXTRACT(EPOCH FROM (a.clock_out - a.clock_in))/60.0-COALESCE(wt.break_minutes,0)) ELSE 0 END),0) AS metric_value FROM attendance a LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.user_id=? AND a.work_date BETWEEN ? AND ?";
    } else {
      sql = "SELECT COALESCE(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-COALESCE(wt.break_minutes,0)) ELSE 0 END),0) AS metric_value FROM attendance a LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.user_id=? AND a.work_date BETWEEN ? AND ?";
    }
    Map<String, Object> row = Sql.one(sql, user.getId(), java.time.YearMonth.now(java.time.ZoneId.of("Asia/Tokyo")).atDay(1), java.time.YearMonth.now(java.time.ZoneId.of("Asia/Tokyo")).atEndOfMonth());
    return ((Number) row.getOrDefault("metric_value", 0)).doubleValue() / 60.0;
  }

  private double monthOvertimeHours(User user) {
    java.time.YearMonth month = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Tokyo"));
    String sql;
    if (config.Database.isPostgres()) {
      sql = "SELECT COALESCE(ROUND(CAST(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL "
          + "AND wt.start_time IS NOT NULL THEN GREATEST(0,EXTRACT(EPOCH FROM (a.clock_out - a.clock_in))/60.0-CASE WHEN wt.crosses_midnight "
          + "THEN 1440+EXTRACT(EPOCH FROM (wt.end_time - wt.start_time))/60.0 ELSE EXTRACT(EPOCH FROM (wt.end_time - wt.start_time))/60.0 END) "
          + "ELSE 0 END)/60.0 AS numeric),1),0) AS metric_value FROM attendance a "
          + "LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date "
          + "LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.user_id=? AND a.work_date BETWEEN ? AND ?";
    } else {
      sql = "SELECT COALESCE(ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL "
          + "AND wt.start_time IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-CASE WHEN wt.crosses_midnight "
          + "THEN 1440+DATEDIFF('MINUTE',wt.start_time,wt.end_time) ELSE DATEDIFF('MINUTE',wt.start_time,wt.end_time) END) "
          + "ELSE 0 END)/60.0,1),0) AS metric_value FROM attendance a "
          + "LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date "
          + "LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.user_id=? AND a.work_date BETWEEN ? AND ?";
    }
    Map<String, Object> row = Sql.one(sql, user.getId(), month.atDay(1), month.atEndOfMonth());
    return ((Number) row.getOrDefault("metric_value", 0)).doubleValue();
  }

  private String scope(User user, String alias) {
    if (user.isHr()) return "";
    if (user.isManager()) return " AND " + alias + ".branch_id=CAST(? AS bigint) AND " + alias + ".department_id=CAST(? AS bigint)";
    return " AND " + alias + ".id=CAST(? AS bigint)";
  }

  private Object[] scopeArgs(User user) {
    if (user.isHr()) return new Object[]{};
    if (user.isManager()) return new Object[]{user.getBranchId(), user.getDepartmentId()};
    return new Object[]{user.getId()};
  }
}
