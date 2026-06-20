package service;

import config.Database;
import dao.Sql;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.User;

public class PortalService {
  private final LeaveLedgerService leaveLedger = new LeaveLedgerService();
  private final SettingsService settings = new SettingsService();
  private final ShiftSubmissionPolicy shiftSubmissionPolicy = new ShiftSubmissionPolicy();
  private final AttendanceCalculator attendanceCalculator = new AttendanceCalculator();
  public Map<String, Object> dashboard(User user) {
    String scope = scope(user, "u");
    Object[] args = scopeArgs(user);
    String todayWorkers = "SELECT COUNT(*) AS metric_value FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE s.work_date=CURRENT_DATE AND s.work_type_code IN('DAY','NIGHT','AM_LEAVE','PM_LEAVE') AND s.status='CONFIRMED'" + scope;
    String pending = "SELECT COUNT(*) AS metric_value FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.status='PENDING'" + scope;
    String shortage = "SELECT COUNT(*) AS metric_value FROM (SELECT wt.code,wt.required_staff,COUNT(s.id) actual FROM work_types wt LEFT JOIN shifts s ON s.work_type_code=wt.code AND s.work_date=CURRENT_DATE AND s.status='CONFIRMED' LEFT JOIN users u ON u.id=s.user_id WHERE wt.required_staff>0"
        + (user.isHr() ? "" : " AND (u.id IS NULL OR (u.branch_id=? AND u.department_id=?))")
        + " GROUP BY wt.code,wt.required_staff HAVING COUNT(s.id)<wt.required_staff) x";
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("todayWorkers", value(todayWorkers, args));
    result.put("pending", value(pending, args));
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
    String filter = user.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?";
    Object[] args = user.isHr() ? new Object[]{workType}
        : new Object[]{workType, user.getBranchId(), user.getDepartmentId()};
    Number actualValue = (Number) Sql.one("SELECT COUNT(*) AS metric_value FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE s.work_date=CURRENT_DATE AND s.work_type_code=? AND s.status='CONFIRMED'" + filter, args)
        .getOrDefault("metric_value", 0);
    int missing = Math.max(0, required - actualValue.intValue());
    return (int) Math.round(missing * 100.0 / required);
  }

  public List<Map<String, Object>> chart(User user) {
    String filter = user.isHr() ? "" : user.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND u.id=?";
    Object[] args = user.isHr() ? new Object[]{} : user.isManager()
        ? new Object[]{user.getBranchId(), user.getDepartmentId()} : new Object[]{user.getId()};
    List<Map<String, Object>> attendance = Sql.query("SELECT FORMATDATETIME(a.work_date,'yyyy-MM') AS month_label,"
        + "ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-COALESCE(wt.break_minutes,0))/60.0 ELSE 0 END),1) AS total_hours,"
        + "ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL AND wt.start_time IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-CASE WHEN wt.crosses_midnight THEN 1440+DATEDIFF('MINUTE',wt.start_time,wt.end_time) ELSE DATEDIFF('MINUTE',wt.start_time,wt.end_time) END)/60.0 ELSE 0 END),1) AS overtime_hours "
        + "FROM attendance a JOIN users u ON u.id=a.user_id LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.work_date>=DATEADD('MONTH',-5,CURRENT_DATE)" + filter
        + " GROUP BY FORMATDATETIME(a.work_date,'yyyy-MM') ORDER BY month_label", args);
    List<Map<String, Object>> leave = Sql.query("SELECT FORMATDATETIME(l.leave_date,'yyyy-MM') AS month_label,"
        + "SUM(CASE l.leave_unit WHEN 'FULL' THEN 1.0 WHEN 'AM' THEN 0.5 WHEN 'PM' THEN 0.5 WHEN 'HOUR' THEN l.hours/8.0 ELSE 0 END) AS leave_days "
        + "FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.status='APPROVED' AND l.leave_date>=DATEADD('MONTH',-5,CURRENT_DATE)" + filter
        + " GROUP BY FORMATDATETIME(l.leave_date,'yyyy-MM') ORDER BY month_label", args);
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

  public List<Map<String, Object>> users(User viewer) {
    return Sql.query("SELECT u.id,u.employee_number,u.name,u.email,u.hire_date,u.role,u.active,u.branch_id,u.department_id,u.employment_type_id,b.name branch,d.name department,e.name employment "
        + "FROM users u JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id JOIN employment_types e ON e.id=u.employment_type_id WHERE 1=1"
        + scope(viewer, "u") + " ORDER BY u.employee_number", scopeArgs(viewer));
  }

  public List<Map<String, Object>> shifts(User viewer, YearMonth month) {
    return Sql.query("SELECT s.id,s.work_date,s.work_type_code,wt.name_ja work_type,u.id user_id,u.name,u.employee_number,s.status,s.note "
        + "FROM shifts s JOIN users u ON u.id=s.user_id JOIN work_types wt ON wt.code=s.work_type_code "
        + "WHERE s.work_date BETWEEN ? AND ?" + scope(viewer, "u") + " ORDER BY s.work_date,u.name",
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scopeArgs(viewer)));
  }

  public List<Map<String, Object>> dashboardShifts(User viewer, YearMonth month, long branchId) {
    if (!viewer.isManager() && !viewer.isHr()) return shifts(viewer, month);
    return Sql.query("SELECT s.id,s.work_date,s.work_type_code,wt.name_ja work_type,u.id user_id,u.name,u.employee_number,s.status,s.note "
        + "FROM shifts s JOIN users u ON u.id=s.user_id JOIN work_types wt ON wt.code=s.work_type_code "
        + "WHERE s.work_date BETWEEN ? AND ? AND u.branch_id=? ORDER BY s.work_date,u.name",
        month.atDay(1), month.atEndOfMonth(), branchId);
  }

  public List<Map<String, Object>> dashboardBranches(YearMonth month) {
    return Sql.query("SELECT DISTINCT b.id,b.name FROM branches b JOIN users u ON u.branch_id=b.id "
        + "JOIN shifts s ON s.user_id=u.id WHERE b.active=TRUE AND s.work_date BETWEEN ? AND ? ORDER BY b.name",
        month.atDay(1), month.atEndOfMonth());
  }

  public List<Map<String, Object>> workTypes() {
    return Sql.query("SELECT * FROM work_types WHERE active=TRUE ORDER BY id");
  }

  public void saveShift(User actor, long userId, LocalDate date, String type, String status, String note) {
    assertCanManage(actor, userId);
    if (!workTypes().stream().anyMatch(r -> type.equals(r.get("code")))) throw new IllegalArgumentException("勤務区分が不正です。");
    assertShiftLeaveBalance(userId, date, type);
    Sql.update("MERGE INTO shifts(user_id,work_date,work_type_code,status,note,updated_by,updated_at) KEY(user_id,work_date) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        userId, date, type, status, note, actor.getId());
    AuditService.record(actor.getId(), "SAVE_SHIFT", "SHIFT", userId + ":" + date, null, type + "/" + status);
  }

  private void assertShiftLeaveBalance(long userId, LocalDate date, String type) {
    BigDecimal needed = switch (type) {
      case "LEAVE" -> BigDecimal.ONE;
      case "AM_LEAVE", "PM_LEAVE" -> new BigDecimal("0.5");
      default -> BigDecimal.ZERO;
    };
    if (needed.signum() == 0) return;
    boolean approvedRequest = !Sql.query("SELECT id FROM leave_requests WHERE user_id=? AND leave_date=? AND status='APPROVED' "
        + "AND ((?='LEAVE' AND leave_unit='FULL') OR (?='AM_LEAVE' AND leave_unit='AM') OR (?='PM_LEAVE' AND leave_unit='PM'))",
        userId, date, type, type, type).isEmpty();
    if (approvedRequest) return;
    BigDecimal remaining = (BigDecimal) leaveLedger.balance(userId, date).getOrDefault("days_remaining", BigDecimal.ZERO);
    if (remaining.compareTo(needed) < 0) throw new IllegalArgumentException("有効期限内の有休残数が不足しています。");
  }

  public void submitPreferredShift(User actor, LocalDate date, String type, String note) {
    submitPreferredShift(actor, date, type, note, LocalDate.now());
  }

  void submitPreferredShift(User actor, LocalDate date, String type, String note, LocalDate today) {
    shiftSubmissionPolicy.validate(today, date, settings.integer("SHIFT_SUBMISSION_DAY", 15));
    saveShift(actor, actor.getId(), date, type, "SUBMITTED", note);
  }

  public void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences) {
    submitMonthlyPreferences(actor, month, preferences, Map.of(), LocalDate.now());
  }

  void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences, LocalDate today) {
    submitMonthlyPreferences(actor, month, preferences, Map.of(), today);
  }

  public void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences,
      Map<LocalDate, String> reasons) {
    submitMonthlyPreferences(actor, month, preferences, reasons, LocalDate.now());
  }

  void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences,
      Map<LocalDate, String> reasons, LocalDate today) {
    shiftSubmissionPolicy.validate(today, month.atDay(1), settings.integer("SHIFT_SUBMISSION_DAY", 15));
    Map<LocalDate, String> selected = new LinkedHashMap<>();
    Map<LocalDate, String> selectedReasons = new LinkedHashMap<>();
    for (Map.Entry<LocalDate, String> entry : preferences.entrySet()) {
      LocalDate date = entry.getKey();
      String type = entry.getValue();
      if (!YearMonth.from(date).equals(month)) throw new IllegalArgumentException("対象月以外の日付は提出できません。");
      if (type == null || type.isBlank() || "NONE".equals(type)) continue;
      if (!List.of("DAY", "NIGHT", "OFF", "LEAVE").contains(type)) throw new IllegalArgumentException("希望勤務区分が不正です。");
      selected.put(date, type);
      String reason = reasons.get(date);
      if ("LEAVE".equals(type) && reason != null && !reason.isBlank()) {
        reason = reason.trim();
        if (reason.length() > 500) throw new IllegalArgumentException("有休希望の理由は500文字以内で入力してください。");
        selectedReasons.put(date, reason);
      }
    }
    try (Connection connection = Database.getConnection()) {
      connection.setAutoCommit(false);
      try {
        long submissionId;
        try (PreparedStatement merge = connection.prepareStatement(
            "MERGE INTO shift_preference_submissions(user_id,target_month,status,submitted_at,updated_at,reviewed_by,reviewed_at) KEY(user_id,target_month) VALUES(?,?,'SUBMITTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL)")) {
          merge.setLong(1, actor.getId()); merge.setObject(2, month.atDay(1)); merge.executeUpdate();
        }
        try (PreparedStatement find = connection.prepareStatement(
            "SELECT id FROM shift_preference_submissions WHERE user_id=? AND target_month=?")) {
          find.setLong(1, actor.getId()); find.setObject(2, month.atDay(1));
          try (ResultSet result = find.executeQuery()) { result.next(); submissionId = result.getLong(1); }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM shift_preferences WHERE submission_id=?")) {
          delete.setLong(1, submissionId); delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO shift_preferences(submission_id,preference_date,request_type,note) VALUES(?,?,?,?)")) {
          for (Map.Entry<LocalDate, String> entry : selected.entrySet()) {
            insert.setLong(1, submissionId); insert.setObject(2, entry.getKey()); insert.setString(3, entry.getValue());
            insert.setString(4, selectedReasons.get(entry.getKey())); insert.addBatch();
          }
          insert.executeBatch();
        }
        connection.commit();
      } catch (SQLException error) {
        connection.rollback(); throw error;
      } catch (RuntimeException error) {
        connection.rollback(); throw error;
      }
    } catch (SQLException error) {
      throw new IllegalStateException("希望シフトの保存に失敗しました。", error);
    }
    AuditService.record(actor.getId(), "SUBMIT_SHIFT_PREFERENCES", "SHIFT_PREFERENCE", actor.getId() + ":" + month, null, selected.size() + " days");
  }

  public Map<String, Object> preferenceSubmission(User user, YearMonth month) {
    return Sql.one("SELECT id,status,submitted_at,updated_at FROM shift_preference_submissions WHERE user_id=? AND target_month=?",
        user.getId(), month.atDay(1));
  }

  public List<Map<String, Object>> preferences(User user, YearMonth month) {
    return Sql.query("SELECT p.preference_date,p.request_type,p.note FROM shift_preferences p "
        + "JOIN shift_preference_submissions s ON s.id=p.submission_id WHERE s.user_id=? AND s.target_month=? ORDER BY p.preference_date",
        user.getId(), month.atDay(1));
  }

  public List<Map<String, Object>> preferenceSubmissionSummaries(User viewer, YearMonth month) {
    requireManager(viewer);
    String scope = viewer.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?";
    Object[] args = viewer.isHr() ? new Object[]{month.atDay(1)} : new Object[]{month.atDay(1), viewer.getBranchId(), viewer.getDepartmentId()};
    return Sql.query("SELECT u.id user_id,s.id submission_id,u.employee_number,u.name,b.name branch_name,COALESCE(s.status,'NOT_SUBMITTED') status,s.submitted_at,"
        + "COUNT(p.id) preference_count FROM users u JOIN branches b ON b.id=u.branch_id "
        + "LEFT JOIN shift_preference_submissions s ON s.user_id=u.id AND s.target_month=? "
        + "LEFT JOIN shift_preferences p ON p.submission_id=s.id WHERE u.active=TRUE AND u.role<>'HR'" + scope
        + " GROUP BY u.id,s.id,u.employee_number,u.name,b.name,s.status,s.submitted_at ORDER BY b.name,u.employee_number", args);
  }

  public List<Map<String, Object>> preferenceDetails(User viewer, YearMonth month) {
    requireManager(viewer);
    String scope = viewer.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?";
    Object[] args = viewer.isHr() ? new Object[]{month.atDay(1)} : new Object[]{month.atDay(1), viewer.getBranchId(), viewer.getDepartmentId()};
    return Sql.query("SELECT u.employee_number,u.name,p.preference_date,p.request_type,p.note FROM shift_preferences p "
        + "JOIN shift_preference_submissions s ON s.id=p.submission_id JOIN users u ON u.id=s.user_id "
        + "WHERE s.target_month=? AND s.status IN('SUBMITTED','APPROVED')" + scope + " ORDER BY p.preference_date,u.employee_number", args);
  }

  public void reviewPreferenceSubmission(User actor, long submissionId, boolean approved) {
    requireManager(actor);
    Map<String, Object> submission = Sql.one("SELECT s.user_id,s.target_month,u.branch_id,u.department_id FROM shift_preference_submissions s "
        + "JOIN users u ON u.id=s.user_id WHERE s.id=?", submissionId);
    if (submission.isEmpty()) throw new IllegalArgumentException("対象の希望シフト提出が見つかりません。");
    assertCanManage(actor, ((Number) submission.get("user_id")).longValue());
    String status = approved ? "APPROVED" : "RETURNED";
    Sql.update("UPDATE shift_preference_submissions SET status=?,reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
        status, actor.getId(), submissionId);
    AuditService.record(actor.getId(), "REVIEW_SHIFT_PREFERENCES", "SHIFT_PREFERENCE_SUBMISSION", String.valueOf(submissionId), null, status);
  }

  public int autoAssignShifts(User actor, YearMonth month) {
    requireManager(actor);
    String userScope = actor.isHr() ? "" : " AND branch_id=? AND department_id=?";
    Object[] userArgs = actor.isHr() ? new Object[]{} : new Object[]{actor.getBranchId(), actor.getDepartmentId()};
    List<Map<String, Object>> people = Sql.query("SELECT id,employee_number,role,weekly_work_days FROM users WHERE active=TRUE AND role<>'HR'" + userScope
        + " ORDER BY CASE WHEN role='MANAGER' THEN 0 ELSE 1 END,employee_number", userArgs);
    int assigned = 0;
    List<Map<String, Object>> preferred = preferenceDetails(actor, month);
    Map<String, Long> employeeIds = new LinkedHashMap<>();
    for (Map<String, Object> person : people) employeeIds.put(String.valueOf(person.get("employee_number")), ((Number) person.get("id")).longValue());
    for (Map<String, Object> preference : preferred) {
      Long userId = employeeIds.get(String.valueOf(preference.get("employee_number")));
      if (userId == null) continue;
      LocalDate date = LocalDate.parse(String.valueOf(preference.get("preference_date")));
      if (shiftMissing(userId, date)) {
        String requestType = String.valueOf(preference.get("request_type"));
        saveShift(actor, userId, date, requestType, "DRAFT", "希望シフトから自動反映"); assigned++;
        if ("NIGHT".equals(requestType) && YearMonth.from(date.plusDays(1)).equals(month) && shiftMissing(userId, date.plusDays(1))) {
          saveShift(actor, userId, date.plusDays(1), "NIGHT_OFF", "DRAFT", "夜勤明け自動設定"); assigned++;
        }
      }
    }
    int dayRequired = requiredStaff("DAY");
    int nightRequired = requiredStaff("NIGHT");
    for (int day = 1; day <= month.lengthOfMonth(); day++) {
      LocalDate date = month.atDay(day);
      assigned += fillWorkType(actor, people, date, "DAY", dayRequired);
      assigned += fillWorkType(actor, people, date, "NIGHT", nightRequired);
    }
    AuditService.record(actor.getId(), "AUTO_ASSIGN_SHIFTS", "SHIFT_MONTH", month.toString(), null, assigned + " shifts");
    return assigned;
  }

  private int fillWorkType(User actor, List<Map<String, Object>> people, LocalDate date, String type, int required) {
    int actual = ((Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts s JOIN users u ON u.id=s.user_id WHERE s.work_date=? AND s.work_type_code=?"
        + (actor.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?"), actor.isHr() ? new Object[]{date, type} : new Object[]{date, type, actor.getBranchId(), actor.getDepartmentId()})
        .getOrDefault("metric_value", 0)).intValue();
    int added = 0;
    for (Map<String, Object> person : people) {
      if (actual >= required) break;
      long userId = ((Number) person.get("id")).longValue();
      int maxConsecutive = Math.max(1, ((Number) person.get("weekly_work_days")).intValue());
      if (!canAutoAssign(userId, date, maxConsecutive)) continue;
      saveShift(actor, userId, date, type, "DRAFT", "希望なしの自動割当"); actual++; added++;
      if ("NIGHT".equals(type) && date.plusDays(1).getMonthValue() == date.getMonthValue() && shiftMissing(userId, date.plusDays(1))) {
        saveShift(actor, userId, date.plusDays(1), "NIGHT_OFF", "DRAFT", "夜勤明け自動設定"); added++;
      }
    }
    return added;
  }

  private boolean canAutoAssign(long userId, LocalDate date, int maxConsecutive) {
    if (!shiftMissing(userId, date)) return false;
    if (!Sql.query("SELECT id FROM shifts WHERE user_id=? AND work_date=? AND work_type_code='NIGHT'", userId, date.minusDays(1)).isEmpty()) return false;
    if (!Sql.query("SELECT p.id FROM shift_preferences p JOIN shift_preference_submissions s ON s.id=p.submission_id "
        + "WHERE s.user_id=? AND p.preference_date=? AND p.request_type IN('OFF','LEAVE')", userId, date).isEmpty()) return false;
    int consecutive = ((Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts WHERE user_id=? AND work_date BETWEEN ? AND ? "
        + "AND work_type_code IN('DAY','NIGHT','AM_LEAVE','PM_LEAVE')", userId, date.minusDays(maxConsecutive), date.minusDays(1))
        .getOrDefault("metric_value", 0)).intValue();
    return consecutive < maxConsecutive;
  }

  private boolean shiftMissing(long userId, LocalDate date) {
    return Sql.query("SELECT id FROM shifts WHERE user_id=? AND work_date=?", userId, date).isEmpty();
  }

  private int requiredStaff(String type) {
    return ((Number) Sql.one("SELECT required_staff metric_value FROM work_types WHERE code=?", type).getOrDefault("metric_value", 0)).intValue();
  }

  public Map<String, Object> shiftSubmissionWindow() {
    LocalDate today = LocalDate.now();
    LocalDate deadline = shiftSubmissionPolicy.deadline(today, settings.integer("SHIFT_SUBMISSION_DAY", 15));
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("target_month", shiftSubmissionPolicy.targetMonth(today));
    result.put("deadline", deadline);
    result.put("open", !today.isAfter(deadline));
    return result;
  }

  public void confirmMonth(User actor, YearMonth month, String warningReason) {
    requireManager(actor);
    List<Map<String, Object>> warnings = shiftWarnings(actor, month);
    if (!warnings.isEmpty()) {
      if (!settings.bool("ALLOW_CONFIRM_WITH_WARNINGS", true)) throw new IllegalArgumentException("警告が残っているため確定できません。");
      if (warningReason == null || warningReason.isBlank()) throw new IllegalArgumentException("警告が残る状態で確定する理由を入力してください。");
    }
    String filter = actor.isHr() ? "" : " AND user_id IN(SELECT id FROM users WHERE branch_id=? AND department_id=?)";
    Object[] args = actor.isHr() ? new Object[]{month.atDay(1), month.atEndOfMonth()}
        : new Object[]{month.atDay(1), month.atEndOfMonth(), actor.getBranchId(), actor.getDepartmentId()};
    Sql.update("UPDATE shifts SET status='CONFIRMED',updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE work_date BETWEEN ? AND ?" + filter,
        join(new Object[]{actor.getId()}, args));
    String userFilter = actor.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?";
    Object[] userArgs = actor.isHr() ? new Object[]{month.atDay(1), month.atEndOfMonth()}
        : new Object[]{month.atDay(1), month.atEndOfMonth(), actor.getBranchId(), actor.getDepartmentId()};
    for (Map<String, Object> target : Sql.query("SELECT DISTINCT u.id FROM users u JOIN shifts s ON s.user_id=u.id WHERE s.work_date BETWEEN ? AND ?" + userFilter, userArgs)) {
      notify(((Number) target.get("id")).longValue(), "SHIFT_CONFIRMED", "シフト確定", month + "のシフトが確定しました。", "/app/shifts/mine?month=" + month);
    }
    AuditService.record(actor.getId(), "CONFIRM_SHIFTS", "SHIFT_MONTH", month.toString(), null, "CONFIRMED reason=" + (warningReason == null ? "" : warningReason));
  }

  public List<Map<String, Object>> shiftChangeRequests(User viewer) {
    String filter = viewer.isHr() ? "" : viewer.isManager()
        ? " AND u.branch_id=? AND u.department_id=?" : " AND r.user_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    return Sql.query("SELECT r.*,u.name,u.employee_number,wt.name_ja requested_name,s.work_type_code current_type "
        + "FROM shift_change_requests r JOIN users u ON u.id=r.user_id JOIN work_types wt ON wt.code=r.requested_work_type "
        + "LEFT JOIN shifts s ON s.user_id=r.user_id AND s.work_date=r.work_date WHERE 1=1" + filter
        + " ORDER BY r.created_at DESC", args);
  }

  public void requestShiftChange(User user, LocalDate date, String type, String reason) {
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("変更理由を入力してください。");
    boolean urgent = !date.isAfter(LocalDate.now());
    if (date.isBefore(LocalDate.now())) throw new IllegalArgumentException("過去日の変更は打刻修正から申請してください。");
    long id = Sql.insert("INSERT INTO shift_change_requests(user_id,work_date,requested_work_type,reason,urgent) VALUES(?,?,?,?,?)",
        user.getId(), date, type, reason.trim(), urgent);
    notifyManagers(user, "SHIFT_CHANGE_REQUEST", urgent ? "緊急シフト変更申請" : "シフト変更申請",
        user.getName() + "さんから変更申請があります。", "/app/shifts/history");
    AuditService.record(user.getId(), "REQUEST_SHIFT_CHANGE", "SHIFT_CHANGE", String.valueOf(id), null, type + ":" + date);
  }

  public void decideShiftChange(User actor, long requestId, boolean approve) {
    requireManager(actor);
    Map<String, Object> row = Sql.one("SELECT r.*,u.branch_id,u.department_id FROM shift_change_requests r JOIN users u ON u.id=r.user_id WHERE r.id=?", requestId);
    if (row.isEmpty() || !"PENDING".equals(row.get("status"))) throw new IllegalArgumentException("未処理の申請が見つかりません。");
    assertScope(actor, ((Number) row.get("branch_id")).longValue(), ((Number) row.get("department_id")).longValue());
    List<Map<String, Object>> recheckWarnings = List.of();
    if (approve) {
      LocalDate workDate = toDate(row.get("work_date"));
      saveShift(actor, ((Number) row.get("user_id")).longValue(), workDate,
          String.valueOf(row.get("requested_work_type")), "CONFIRMED", "変更申請 #" + requestId);
      recheckWarnings = shiftWarningsForDate(actor, workDate);
      if (!recheckWarnings.isEmpty()) {
        notify(actor.getId(), "SHIFT_RECHECK", "シフト変更後の警告",
            workDate + "の変更反映後に" + recheckWarnings.size() + "件の警告があります。必要人数と勤務間隔を確認してください。",
            "/app/shifts/manage?month=" + YearMonth.from(workDate));
      }
    }
    Sql.update("UPDATE shift_change_requests SET status=?,decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED", actor.getId(), requestId);
    notify(((Number) row.get("user_id")).longValue(), "SHIFT_CHANGE_DECISION",
        approve ? "シフト変更が承認されました" : "シフト変更が却下されました",
        row.get("work_date") + "の申請結果を確認してください。", "/app/shifts/history");
    AuditService.record(actor.getId(), approve ? "APPROVE_SHIFT_CHANGE" : "REJECT_SHIFT_CHANGE", "SHIFT_CHANGE", String.valueOf(requestId), "PENDING",
        approve ? "APPROVED warnings=" + recheckWarnings.size() : "REJECTED");
  }

  public List<Map<String, Object>> shiftWarningsForDate(User viewer, LocalDate date) {
    String userScope = viewer.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?";
    Object[] scopeArgs = viewer.isHr() ? new Object[]{} : new Object[]{viewer.getBranchId(), viewer.getDepartmentId()};
    String scopedUserJoin = "LEFT JOIN users u ON u.id=s.user_id" + (viewer.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?");
    String actualCount = viewer.isHr() ? "COUNT(s.id)" : "COUNT(u.id)";
    List<Map<String, Object>> result = new java.util.ArrayList<>();
    result.addAll(Sql.query("SELECT 'STAFF_SHORTAGE' warning,? work_date,wt.name_ja detail,wt.required_staff required," + actualCount + " actual "
        + "FROM work_types wt LEFT JOIN shifts s ON s.work_type_code=wt.code AND s.work_date=? "
        + scopedUserJoin + " WHERE wt.active=TRUE AND wt.required_staff>0"
        + " GROUP BY wt.name_ja,wt.required_staff HAVING " + actualCount + "<wt.required_staff",
        join(new Object[]{date, date}, scopeArgs)));
    result.addAll(Sql.query("SELECT 'NIGHT_REST' warning,s2.work_date,u.name detail,0 required,0 actual "
        + "FROM shifts s1 JOIN shifts s2 ON s2.user_id=s1.user_id AND s2.work_date=DATEADD('DAY',1,s1.work_date) "
        + "JOIN users u ON u.id=s1.user_id WHERE s1.work_type_code='NIGHT' AND s2.work_type_code NOT IN('OFF','LEAVE') "
        + "AND (s1.work_date=? OR s2.work_date=?)" + userScope,
        join(new Object[]{date, date}, scopeArgs)));
    return result;
  }

  public List<Map<String, Object>> shiftWarnings(User viewer, YearMonth month) {
    String userScope = viewer.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?";
    Object[] scopeArgs = viewer.isHr() ? new Object[]{} : new Object[]{viewer.getBranchId(), viewer.getDepartmentId()};
    String scopedUserJoin = "LEFT JOIN users u ON u.id=s.user_id" + (viewer.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?");
    String actualCount = viewer.isHr() ? "COUNT(s.id)" : "COUNT(u.id)";
    List<Map<String, Object>> result = new java.util.ArrayList<>();
    result.addAll(Sql.query("WITH RECURSIVE dates(work_date) AS (SELECT CAST(? AS DATE) UNION ALL SELECT DATEADD('DAY',1,work_date) FROM dates WHERE work_date<?) "
        + "SELECT 'STAFF_SHORTAGE' warning,dates.work_date,wt.name_ja detail,wt.required_staff required," + actualCount + " actual "
        + "FROM dates CROSS JOIN work_types wt LEFT JOIN shifts s ON s.work_date=dates.work_date AND s.work_type_code=wt.code "
        + scopedUserJoin + " WHERE wt.active=TRUE AND wt.required_staff>0 GROUP BY dates.work_date,wt.name_ja,wt.required_staff "
        + "HAVING " + actualCount + "<wt.required_staff ORDER BY dates.work_date,wt.name_ja",
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scopeArgs)));
    result.addAll(Sql.query("SELECT 'NIGHT_REST' warning,s2.work_date,u.name detail,0 required,0 actual FROM shifts s1 "
        + "JOIN shifts s2 ON s2.user_id=s1.user_id AND s2.work_date=DATEADD('DAY',1,s1.work_date) JOIN users u ON u.id=s1.user_id "
        + "WHERE s1.work_type_code='NIGHT' AND s2.work_type_code NOT IN('OFF','LEAVE') AND s1.work_date BETWEEN ? AND ?" + userScope,
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scopeArgs)));
    return result;
  }

  public List<Map<String, Object>> leaveRequests(User viewer) {
    String ownOrScope = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=?" : " AND l.user_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId()} : new Object[]{viewer.getId()};
    return Sql.query("SELECT l.*,u.name,u.employee_number FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE 1=1"
        + ownOrScope + " ORDER BY l.created_at DESC", args);
  }

  public List<Map<String, Object>> leaveApprovers(User user) {
    return Sql.query("SELECT id,name,role,'店長' approver_type FROM users "
        + "WHERE active=TRUE AND role='MANAGER' AND branch_id=? AND id<>? ORDER BY employee_number",
        user.getBranchId(), user.getId());
  }

  public Map<String, Object> leaveBalance(long userId) {
    return leaveLedger.balance(userId, LocalDate.now());
  }

  public List<Map<String, Object>> leaveHistory(User viewer) {
    String filter = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=?" : " AND h.user_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId()} : new Object[]{viewer.getId()};
    return Sql.query("SELECT h.*,u.name,u.employee_number FROM leave_history h JOIN users u ON u.id=h.user_id WHERE 1=1" + filter + " ORDER BY h.event_date DESC,h.id DESC", args);
  }

  public void requestLeave(User user, LocalDate date, String unit, Integer hours, String reason) {
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("理由を入力してください。");
    if (!settings.bool("LEAVE_ALLOW_PAST", false) && date.isBefore(LocalDate.now())) throw new IllegalArgumentException("過去日の有休は申請できません。");
    if (date.isBefore(LocalDate.now().plusDays(settings.integer("LEAVE_MIN_NOTICE_DAYS", 0)))) throw new IllegalArgumentException("有休申請の事前期限を満たしていません。");
    Map<String, Object> balance = leaveBalance(user.getId());
    BigDecimal days = (BigDecimal) balance.getOrDefault("days_remaining", BigDecimal.ZERO);
    int hoursPerDay = ((Number) balance.getOrDefault("hours_per_day", 8)).intValue();
    double needed = "FULL".equals(unit) ? 1 : ("AM".equals(unit) || "PM".equals(unit)) ? .5 : (hours == null ? 0 : hours / (double) hoursPerDay);
    if (days.doubleValue() < needed) throw new IllegalArgumentException("有効期限内の有休残数が不足しています。");
    if ("HOURLY".equals(unit)) {
      int remainingHours = ((Number) balance.getOrDefault("hourly_remaining", 0)).intValue();
      if (hours == null || hours < 1 || hours > remainingHours) throw new IllegalArgumentException("時間単位有休の年間上限を超えています。");
    }
    long id = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason) VALUES(?,?,?,?,?)",
        user.getId(), date, unit, hours, reason.trim());
    notifyLeaveApprovers(user, "LEAVE_REQUEST", "有休申請", user.getName() + "さんから有休申請があります。", "/app/leave/approvals");
    AuditService.record(user.getId(), "REQUEST_LEAVE", "LEAVE_REQUEST", String.valueOf(id), null, unit + ":" + date);
  }

  public void decideLeave(User actor, long requestId, boolean approve) {
    requireManager(actor);
    Map<String, Object> request = Sql.one("SELECT l.*,u.branch_id,u.department_id,u.name FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.id=?", requestId);
    if (request.isEmpty()) throw new IllegalArgumentException("申請が見つかりません。");
    assertLeaveApprovalScope(actor, ((Number) request.get("branch_id")).longValue());
    if (!"PENDING".equals(request.get("status"))) throw new IllegalArgumentException("処理済みの申請です。");
    if (approve) {
      leaveLedger.consume(requestId);
    }
    Sql.update("UPDATE leave_requests SET status=?,decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED", actor.getId(), requestId);
    long userId = ((Number) request.get("user_id")).longValue();
    notify(userId, "LEAVE_DECISION", approve ? "有休申請が承認されました" : "有休申請が却下されました",
        request.get("leave_date") + "の申請結果を確認してください。", "/app/leave/history");
    AuditService.record(actor.getId(), approve ? "APPROVE_LEAVE" : "REJECT_LEAVE", "LEAVE_REQUEST", String.valueOf(requestId), "PENDING", approve ? "APPROVED" : "REJECTED");
  }

  public void cancelLeave(User actor, long requestId) {
    Map<String, Object> request = Sql.one("SELECT * FROM leave_requests WHERE id=?", requestId);
    if (request.isEmpty() || ((Number) request.get("user_id")).longValue() != actor.getId()) throw new SecurityException("自分の申請だけ取り消せます。");
    String currentStatus = String.valueOf(request.get("status"));
    if (!"PENDING".equals(currentStatus) && !"APPROVED".equals(currentStatus)) throw new IllegalArgumentException("この申請は取り消せません。");
    LocalDate leaveDate = toDate(request.get("leave_date"));
    if (!leaveDate.isAfter(LocalDate.now())) throw new IllegalArgumentException("当日・過去日の有休は取り消せません。");
    if ("APPROVED".equals(currentStatus)) leaveLedger.restore(requestId, LocalDate.now());
    Sql.update("UPDATE leave_requests SET status='CANCELLED' WHERE id=?", requestId);
    notifyLeaveApprovers(actor, "LEAVE_CANCELLED", "有休申請の取消", actor.getName() + "さんが有休申請を取り消しました。", "/app/leave/approvals");
    AuditService.record(actor.getId(), "CANCEL_LEAVE", "LEAVE_REQUEST", String.valueOf(requestId), currentStatus, "CANCELLED");
  }

  public List<Map<String, Object>> attendance(User viewer, YearMonth month) {
    String filter = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND a.user_id=?";
    Object[] scope = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    List<Map<String, Object>> rows = Sql.query("SELECT a.*,u.name,u.employee_number,s.work_type_code,wt.start_time,wt.end_time,wt.crosses_midnight,wt.break_minutes "
        + "FROM attendance a JOIN users u ON u.id=a.user_id LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date LEFT JOIN work_types wt ON wt.code=s.work_type_code "
        + "WHERE a.work_date BETWEEN ? AND ?" + filter + " ORDER BY a.work_date DESC,u.name",
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scope));
    for (Map<String, Object> row : rows) {
      Object breakValue = row.get("break_minutes");
      AttendanceCalculator.Result result = attendanceCalculator.calculate(toDate(row.get("work_date")), toDateTime(row.get("clock_in")), toDateTime(row.get("clock_out")),
          new AttendanceCalculator.Schedule(toTime(row.get("start_time")), toTime(row.get("end_time")), Boolean.TRUE.equals(row.get("crosses_midnight")), breakValue instanceof Number number ? number.intValue() : 0));
      row.put("worked_minutes", result.workedMinutes()); row.put("overtime_minutes", result.overtimeMinutes());
      row.put("late", result.late()); row.put("early", result.early());
    }
    return rows;
  }

  public void clock(User user, boolean clockIn, String lat, String lng, String locationStatus) {
    if (settings.bool("LOCATION_REQUIRED", false) && !"ACQUIRED".equals(locationStatus)) throw new IllegalArgumentException("位置情報を取得できないため打刻できません。");
    LocalDate workDate = LocalDate.now();
    if (!Sql.query("SELECT id FROM attendance WHERE user_id=? AND work_date=? AND finalized=TRUE", user.getId(), workDate).isEmpty()) {
      throw new IllegalArgumentException("確定済み勤怠は変更できません。店長へ確定解除を依頼してください。");
    }
    if (clockIn) {
      Sql.update("MERGE INTO attendance(user_id,work_date,clock_in,in_lat,in_lng,location_status,status) KEY(user_id,work_date) VALUES(?,?,CURRENT_TIMESTAMP,?,?,?,'OPEN')",
          user.getId(), workDate, number(lat), number(lng), locationStatus);
    } else {
      Map<String, Object> open = Sql.one("SELECT id,work_date FROM attendance WHERE user_id=? AND clock_in IS NOT NULL AND clock_out IS NULL ORDER BY clock_in DESC LIMIT 1", user.getId());
      if (open.isEmpty()) throw new IllegalArgumentException("先に出勤打刻を行ってください。");
      int updated = Sql.update("UPDATE attendance SET clock_out=CURRENT_TIMESTAMP,out_lat=?,out_lng=?,location_status=?,status='COMPLETE' WHERE id=?",
          number(lat), number(lng), locationStatus, open.get("id"));
      workDate = toDate(open.get("work_date"));
      if (updated == 0) throw new IllegalArgumentException("先に出勤打刻を行ってください。");
    }
    AuditService.record(user.getId(), clockIn ? "CLOCK_IN" : "CLOCK_OUT", "ATTENDANCE", user.getId() + ":" + workDate, null, locationStatus);
  }

  public void finalizeAttendance(User actor, long attendanceId, boolean finalized) {
    requireManager(actor);
    Map<String, Object> row = Sql.one("SELECT a.id,u.branch_id,u.department_id FROM attendance a JOIN users u ON u.id=a.user_id WHERE a.id=?", attendanceId);
    if (row.isEmpty()) throw new IllegalArgumentException("勤怠データが見つかりません。");
    assertScope(actor, ((Number) row.get("branch_id")).longValue(), ((Number) row.get("department_id")).longValue());
    Sql.update("UPDATE attendance SET finalized=? WHERE id=?", finalized, attendanceId);
    AuditService.record(actor.getId(), finalized ? "FINALIZE_ATTENDANCE" : "REOPEN_ATTENDANCE", "ATTENDANCE", String.valueOf(attendanceId), null, String.valueOf(finalized));
  }

  public void finalizeAttendanceMonth(User actor, YearMonth month, boolean finalized) {
    requireManager(actor);
    String filter = actor.isHr() ? "" : " AND user_id IN(SELECT id FROM users WHERE branch_id=? AND department_id=?)";
    Object[] args = actor.isHr() ? new Object[]{finalized, month.atDay(1), month.atEndOfMonth()}
        : new Object[]{finalized, month.atDay(1), month.atEndOfMonth(), actor.getBranchId(), actor.getDepartmentId()};
    Sql.update("UPDATE attendance SET finalized=? WHERE work_date BETWEEN ? AND ?" + filter, args);
    AuditService.record(actor.getId(), finalized ? "FINALIZE_ATTENDANCE_MONTH" : "REOPEN_ATTENDANCE_MONTH", "ATTENDANCE_MONTH", month.toString(), null, String.valueOf(finalized));
  }

  public void finalizeAttendanceEmployeeMonth(User actor, long userId, YearMonth month, boolean finalized) {
    requireManager(actor);
    Map<String, Object> target = Sql.one("SELECT branch_id,department_id FROM users WHERE id=?", userId);
    if (target.isEmpty()) throw new IllegalArgumentException("従業員が見つかりません。");
    assertScope(actor, ((Number) target.get("branch_id")).longValue(), ((Number) target.get("department_id")).longValue());
    Sql.update("UPDATE attendance SET finalized=? WHERE user_id=? AND work_date BETWEEN ? AND ?",
        finalized, userId, month.atDay(1), month.atEndOfMonth());
    AuditService.record(actor.getId(), finalized ? "FINALIZE_ATTENDANCE_EMPLOYEE_MONTH" : "REOPEN_ATTENDANCE_EMPLOYEE_MONTH",
        "ATTENDANCE_EMPLOYEE_MONTH", userId + ":" + month, null, String.valueOf(finalized));
  }

  public List<Map<String, Object>> attendanceAdjustments(User viewer) {
    String filter = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND r.requested_by=?";
    Object[] args = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    return Sql.query("SELECT r.*,a.work_date,a.clock_in current_in,a.clock_out current_out,u.name,u.employee_number FROM attendance_adjustments r JOIN attendance a ON a.id=r.attendance_id JOIN users u ON u.id=r.requested_by WHERE 1=1"
        + filter + " ORDER BY r.created_at DESC", args);
  }

  public void requestAttendanceAdjustment(User user, long attendanceId, LocalDateTime requestedIn,
      LocalDateTime requestedOut, String reason) {
    Map<String, Object> attendance = Sql.one("SELECT user_id,finalized FROM attendance WHERE id=?", attendanceId);
    if (attendance.isEmpty() || ((Number) attendance.get("user_id")).longValue() != user.getId()) throw new SecurityException("自分の勤怠だけ修正申請できます。");
    if (Boolean.TRUE.equals(attendance.get("finalized"))) throw new IllegalArgumentException("確定済み勤怠は、店長が確定解除してから申請してください。");
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("修正理由を入力してください。");
    long id = Sql.insert("INSERT INTO attendance_adjustments(attendance_id,requested_by,requested_in,requested_out,reason) VALUES(?,?,?,?,?)",
        attendanceId, user.getId(), requestedIn, requestedOut, reason.trim());
    notifyManagers(user, "ATTENDANCE_ADJUSTMENT", "打刻修正申請", user.getName() + "さんから打刻修正申請があります。", "/app/attendance/manage");
    AuditService.record(user.getId(), "REQUEST_ATTENDANCE_ADJUSTMENT", "ATTENDANCE_ADJUSTMENT", String.valueOf(id), null,
        "attendance_id=" + attendanceId);
  }

  public void decideAttendanceAdjustment(User actor, long requestId, boolean approve) {
    requireManager(actor);
    Map<String, Object> row = Sql.one("SELECT r.*,u.branch_id,u.department_id FROM attendance_adjustments r JOIN users u ON u.id=r.requested_by WHERE r.id=?", requestId);
    if (row.isEmpty() || !"PENDING".equals(row.get("status"))) throw new IllegalArgumentException("未処理の申請が見つかりません。");
    assertScope(actor, ((Number) row.get("branch_id")).longValue(), ((Number) row.get("department_id")).longValue());
    Map<String, Object> attendance = Sql.one("SELECT clock_in,clock_out,status,finalized FROM attendance WHERE id=?", row.get("attendance_id"));
    if (approve && Boolean.TRUE.equals(attendance.get("finalized"))) {
      throw new IllegalArgumentException("確定済み勤怠は、確定解除してから修正してください。");
    }
    String beforeAudit = approve
        ? attendanceAuditValue(attendance.get("clock_in"), attendance.get("clock_out"), attendance.get("status"))
        : "request_status=PENDING";
    String updatedStatus = row.get("requested_in") != null && row.get("requested_out") != null ? "COMPLETE" : "OPEN";
    String afterAudit = approve
        ? attendanceAuditValue(row.get("requested_in"), row.get("requested_out"), updatedStatus)
        : "request_status=REJECTED";
    if (approve) Sql.update("UPDATE attendance SET clock_in=?,clock_out=?,status=CASE WHEN ? IS NOT NULL AND ? IS NOT NULL THEN 'COMPLETE' ELSE 'OPEN' END WHERE id=?",
        row.get("requested_in"), row.get("requested_out"), row.get("requested_in"), row.get("requested_out"), row.get("attendance_id"));
    Sql.update("UPDATE attendance_adjustments SET status=?,decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED", actor.getId(), requestId);
    notify(((Number) row.get("requested_by")).longValue(), "ATTENDANCE_ADJUSTMENT_DECISION",
        approve ? "打刻修正が承認されました" : "打刻修正が却下されました", "申請結果を確認してください。", "/app/attendance/history");
    AuditService.record(actor.getId(), approve ? "APPROVE_ATTENDANCE_ADJUSTMENT" : "REJECT_ATTENDANCE_ADJUSTMENT",
        "ATTENDANCE_ADJUSTMENT", String.valueOf(requestId), beforeAudit, afterAudit);
  }

  private String attendanceAuditValue(Object clockIn, Object clockOut, Object status) {
    return "clock_in=" + clockIn + ";clock_out=" + clockOut + ";status=" + status;
  }

  public List<Map<String, Object>> notifications(User user) {
    return Sql.query("SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC", user.getId());
  }

  public List<Map<String, Object>> mailOutbox(User user) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ利用できます。");
    return Sql.query("SELECT id,recipient,subject,status,attempts,last_error,created_at,sent_at,next_attempt_at FROM mail_outbox ORDER BY created_at DESC LIMIT 300");
  }

  public void retryMail(User user, long id) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    new MailDeliveryService().retry(id);
    AuditService.record(user.getId(), "RETRY_MAIL", "MAIL_OUTBOX", String.valueOf(id), "FAILED", "QUEUED");
  }

  public void markNotificationsRead(User user) {
    Sql.update("UPDATE notifications SET is_read=TRUE WHERE user_id=?", user.getId());
  }

  public List<Map<String, Object>> master(String type) {
    String table = switch (type) {
      case "branches" -> "branches"; case "departments" -> "departments";
      case "employment" -> "employment_types"; case "qualifications" -> "qualification_types"; default -> "work_types";
    };
    return Sql.query("SELECT * FROM " + table + " ORDER BY id");
  }

  public List<Map<String, Object>> qualifications(User viewer) {
    return Sql.query("SELECT q.id,q.user_id,q.name qualification_name,q.expires_on,q.active,u.name employee_name,u.employee_number FROM qualifications q JOIN users u ON u.id=q.user_id WHERE 1=1"
        + scope(viewer, "u") + " ORDER BY q.expires_on,u.name", scopeArgs(viewer));
  }

  public List<Map<String, Object>> delegations(User viewer) {
    String filter = viewer.isHr() ? "" : " WHERE m.branch_id=? AND m.department_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : new Object[]{viewer.getBranchId(), viewer.getDepartmentId()};
    return Sql.query("SELECT d.*,m.name manager_name,u.name delegate_name FROM delegations d JOIN users m ON m.id=d.manager_id JOIN users u ON u.id=d.delegate_id" + filter + " ORDER BY d.starts_on DESC", args);
  }

  public List<Map<String, Object>> audit(User user) {
    return audit(user, null, null, null, null, null);
  }

  public List<Map<String, Object>> audit(User user, LocalDate from, LocalDate to, Long actorId, String action, Long targetUserId) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ利用できます。");
    StringBuilder sql = new StringBuilder("SELECT a.*,u.name actor_name,t.name target_user_name,t.employee_number target_employee_number FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id LEFT JOIN users t ON t.id=a.target_user_id WHERE 1=1");
    List<Object> args = new java.util.ArrayList<>();
    if (from != null) { sql.append(" AND a.created_at>=?"); args.add(from.atStartOfDay()); }
    if (to != null) { sql.append(" AND a.created_at<?"); args.add(to.plusDays(1).atStartOfDay()); }
    if (actorId != null) { sql.append(" AND a.actor_id=?"); args.add(actorId); }
    if (action != null && !action.isBlank()) { sql.append(" AND a.action=?"); args.add(action); }
    if (targetUserId != null) { sql.append(" AND a.target_user_id=?"); args.add(targetUserId); }
    sql.append(" ORDER BY a.created_at DESC LIMIT 300");
    return Sql.query(sql.toString(), args.toArray());
  }

  public List<Map<String, Object>> auditActions(User user) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ利用できます。");
    return Sql.query("SELECT DISTINCT action FROM audit_logs ORDER BY action");
  }

  public void addQualification(User actor, long userId, String name, LocalDate expires) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String normalized = validateQualificationType(name, null);
    long id = Sql.insert("INSERT INTO qualifications(user_id,name,expires_on) VALUES(?,?,?)", userId, normalized, expires);
    AuditService.record(actor.getId(), "ADD_QUALIFICATION", "QUALIFICATION", String.valueOf(id), null, normalized + ":" + expires);
  }

  public void updateQualification(User actor, long id, String name, LocalDate expires, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    Map<String, Object> before = Sql.one("SELECT name,expires_on,active FROM qualifications WHERE id=?", id);
    if (before.isEmpty()) throw new IllegalArgumentException("資格情報が見つかりません。");
    String normalized = validateQualificationType(name, String.valueOf(before.get("name")));
    Sql.update("UPDATE qualifications SET name=?,expires_on=?,active=? WHERE id=?", normalized, expires, active, id);
    AuditService.record(actor.getId(), "UPDATE_QUALIFICATION", "QUALIFICATION", String.valueOf(id), before.toString(), normalized + ":" + expires + ":" + active);
  }

  private String validateQualificationType(String name, String currentName) {
    String normalized = name == null ? "" : name.trim();
    if (normalized.isEmpty() || (!normalized.equals(currentName) && Sql.one("SELECT id FROM qualification_types WHERE name=? AND active=TRUE", normalized).isEmpty())) {
      throw new IllegalArgumentException("有効な資格名称を選択してください。");
    }
    return normalized;
  }

  public void addDelegation(User actor, long managerId, long delegateId, LocalDate start, LocalDate end) {
    requireManager(actor);
    if (end.isBefore(start)) throw new IllegalArgumentException("終了日は開始日以降にしてください。");
    if (!actor.isHr() && managerId != actor.getId()) throw new SecurityException("担当外のデータです。");
    Map<String, Object> manager = Sql.one("SELECT role,branch_id,department_id,active FROM users WHERE id=?", managerId);
    Map<String, Object> delegate = Sql.one("SELECT branch_id,department_id,active FROM users WHERE id=?", delegateId);
    if (manager.isEmpty() || !"MANAGER".equals(manager.get("role")) || !Boolean.TRUE.equals(manager.get("active"))) throw new IllegalArgumentException("有効な店長を選択してください。");
    if (delegate.isEmpty() || !Boolean.TRUE.equals(delegate.get("active")) || managerId == delegateId) throw new IllegalArgumentException("有効な代理者を選択してください。");
    if (!manager.get("branch_id").equals(delegate.get("branch_id")) || !manager.get("department_id").equals(delegate.get("department_id"))) throw new IllegalArgumentException("代理者は店長と同じ営業所・部署から選択してください。");
    assertScope(actor, ((Number) manager.get("branch_id")).longValue(), ((Number) manager.get("department_id")).longValue());
    long id = Sql.insert("INSERT INTO delegations(manager_id,delegate_id,starts_on,ends_on) VALUES(?,?,?,?)", managerId, delegateId, start, end);
    AuditService.record(actor.getId(), "ADD_DELEGATION", "DELEGATION", String.valueOf(id), null, delegateId + ":" + start + ":" + end);
  }

  public void updateDelegation(User actor, long id, LocalDate start, LocalDate end, boolean active) {
    requireManager(actor);
    Map<String, Object> row = Sql.one("SELECT d.id,m.branch_id,m.department_id FROM delegations d JOIN users m ON m.id=d.manager_id WHERE d.id=?", id);
    if (row.isEmpty()) throw new IllegalArgumentException("代理設定が見つかりません。");
    assertScope(actor, ((Number) row.get("branch_id")).longValue(), ((Number) row.get("department_id")).longValue());
    if (end.isBefore(start)) throw new IllegalArgumentException("終了日は開始日以降にしてください。");
    Sql.update("UPDATE delegations SET starts_on=?,ends_on=?,active=? WHERE id=?", start, end, active, id);
    AuditService.record(actor.getId(), "UPDATE_DELEGATION", "DELEGATION", String.valueOf(id), null, start + ":" + end + ":" + active);
  }

  public void addEmployee(User actor, String number, String name, String email, LocalDate hireDate,
      long branch, long department, long employment, String role, String baseUrl) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String hash = util.PasswordUtil.hash(java.util.UUID.randomUUID().toString());
    long id = Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role) VALUES(?,?,?,?,?,?,?,?,?)",
        number, name, email, hash, hireDate, branch, department, employment, role);
    Sql.update("INSERT INTO leave_balances(user_id,days_remaining,hourly_used,last_granted_on) VALUES(?,10,0,CURRENT_DATE)", id);
    notify(id, "INVITATION", "アカウントが登録されました", "初期パスワードを変更して利用してください。", "/app/account");
    new AccountTokenService().issue(email, "INVITE", baseUrl);
    AuditService.record(actor.getId(), "CREATE_USER", "USER", String.valueOf(id), null, number + ":" + role);
  }

  public void reissueInvite(User actor, long userId, String baseUrl) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    Map<String, Object> target = Sql.one("SELECT email,active FROM users WHERE id=?", userId);
    if (target.isEmpty() || !Boolean.TRUE.equals(target.get("active"))) {
      throw new IllegalArgumentException("有効な従業員が見つかりません。");
    }
    new AccountTokenService().issue(String.valueOf(target.get("email")), "INVITE", baseUrl);
    AuditService.record(actor.getId(), "REISSUE_INVITE", "USER", String.valueOf(userId), null, "expires in 24h");
  }

  public void updateEmployee(User actor, long id, String number, String name, String email, LocalDate hireDate,
      long branch, long department, long employment, String role, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    Map<String, Object> before = Sql.one("SELECT employee_number,role,active FROM users WHERE id=?", id);
    if (before.isEmpty()) throw new IllegalArgumentException("従業員が見つかりません。");
    Sql.update("UPDATE users SET employee_number=?,name=?,email=?,hire_date=?,branch_id=?,department_id=?,employment_type_id=?,role=?,"
        + "deactivated_at=CASE WHEN ?=FALSE AND active=TRUE THEN CURRENT_TIMESTAMP WHEN ?=TRUE THEN NULL ELSE deactivated_at END,active=? WHERE id=?",
        number, name, email, hireDate, branch, department, employment, role, active, active, active, id);
    AuditService.record(actor.getId(), "UPDATE_USER", "USER", String.valueOf(id), before.toString(), number + ":" + role + ":" + active);
  }

  public void addMaster(User actor, String type, String name) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String table = switch (type) { case "branches" -> "branches"; case "departments" -> "departments"; case "employment" -> "employment_types"; case "qualifications" -> "qualification_types"; default -> throw new IllegalArgumentException("マスタ種別が不正です。"); };
    long id = Sql.insert("INSERT INTO " + table + "(name) VALUES(?)", name);
    AuditService.record(actor.getId(), "CREATE_MASTER", table, String.valueOf(id), null, name);
  }

  public void toggleMaster(User actor, String type, long id, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String table = switch (type) { case "branches" -> "branches"; case "departments" -> "departments"; case "employment" -> "employment_types"; case "qualifications" -> "qualification_types"; default -> throw new IllegalArgumentException("マスタ種別が不正です。"); };
    Sql.update("UPDATE " + table + " SET active=? WHERE id=?", active, id);
    AuditService.record(actor.getId(), "TOGGLE_MASTER", table, String.valueOf(id), null, String.valueOf(active));
  }

  public void updateMaster(User actor, String type, long id, String name, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String table = switch (type) { case "branches" -> "branches"; case "departments" -> "departments"; case "employment" -> "employment_types"; case "qualifications" -> "qualification_types"; default -> throw new IllegalArgumentException("マスタ種別が不正です。"); };
    String normalized = name == null ? "" : name.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException("名称を入力してください。");
    Map<String, Object> before = Sql.one("SELECT name,active FROM " + table + " WHERE id=?", id);
    if (before.isEmpty()) throw new IllegalArgumentException("対象のマスタが見つかりません。");
    Sql.update("UPDATE " + table + " SET name=?,active=? WHERE id=?", normalized, active, id);
    AuditService.record(actor.getId(), "UPDATE_MASTER", table, String.valueOf(id), before.toString(), normalized + ":" + active);
  }

  public void updateWorkType(User actor, String code, String nameJa, String nameEn,
      String start, String end, int breakMinutes, int requiredStaff, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    Sql.update("UPDATE work_types SET name_ja=?,name_en=?,start_time=?,end_time=?,break_minutes=?,required_staff=?,active=? WHERE code=?",
        nameJa, nameEn, start == null || start.isBlank() ? null : java.time.LocalTime.parse(start),
        end == null || end.isBlank() ? null : java.time.LocalTime.parse(end), breakMinutes, requiredStaff, active, code);
    AuditService.record(actor.getId(), "UPDATE_WORK_TYPE", "WORK_TYPE", code, null, nameJa + ":" + breakMinutes + ":" + requiredStaff);
  }

  public void notify(long userId, String type, String title, String message, String url) {
    Sql.insert("INSERT INTO notifications(user_id,type,title,message,target_url,email_status) VALUES(?,?,?,?,?,'QUEUED')", userId, type, title, message, url);
    Map<String, Object> user = Sql.one("SELECT email,name FROM users WHERE id=?", userId);
    if (!user.isEmpty()) Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES(?,?,?)", user.get("email"), title, user.get("name") + " 様\n\n" + message);
  }

  private void notifyManagers(User user, String type, String title, String message, String url) {
    List<Map<String, Object>> managers = Sql.query("SELECT id FROM users WHERE active=TRUE AND ((role='MANAGER' AND branch_id=? AND department_id=?) OR role='HR') "
        + "UNION SELECT d.delegate_id id FROM delegations d JOIN users m ON m.id=d.manager_id JOIN users u ON u.id=d.delegate_id "
        + "WHERE d.active=TRUE AND u.active=TRUE AND CURRENT_DATE BETWEEN d.starts_on AND d.ends_on AND m.branch_id=? AND m.department_id=?",
        user.getBranchId(), user.getDepartmentId(), user.getBranchId(), user.getDepartmentId());
    for (Map<String, Object> manager : managers) notify(((Number) manager.get("id")).longValue(), type, title, message, url);
  }

  private void notifyLeaveApprovers(User user, String type, String title, String message, String url) {
    List<Map<String, Object>> managers = Sql.query("SELECT id FROM users WHERE active=TRUE AND role='MANAGER' AND branch_id=? AND id<>?",
        user.getBranchId(), user.getId());
    for (Map<String, Object> manager : managers) notify(((Number) manager.get("id")).longValue(), type, title, message, url);
  }

  private Object value(String sql, Object... args) { return Sql.one(sql, args).getOrDefault("metric_value", 0); }

  private double monthHours(User user) {
    Map<String, Object> row = Sql.one("SELECT COALESCE(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-COALESCE(wt.break_minutes,0)) ELSE 0 END),0) AS metric_value FROM attendance a LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.user_id=? AND a.work_date BETWEEN ? AND ?",
        user.getId(), YearMonth.now().atDay(1), YearMonth.now().atEndOfMonth());
    return ((Number) row.getOrDefault("metric_value", 0)).doubleValue() / 60.0;
  }

  private double monthOvertimeHours(User user) {
    YearMonth month = YearMonth.now();
    Map<String, Object> row = Sql.one("SELECT COALESCE(ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL "
        + "AND wt.start_time IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-CASE WHEN wt.crosses_midnight "
        + "THEN 1440+DATEDIFF('MINUTE',wt.start_time,wt.end_time) ELSE DATEDIFF('MINUTE',wt.start_time,wt.end_time) END) "
        + "ELSE 0 END)/60.0,1),0) AS metric_value FROM attendance a "
        + "LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date "
        + "LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE a.user_id=? AND a.work_date BETWEEN ? AND ?",
        user.getId(), month.atDay(1), month.atEndOfMonth());
    return ((Number) row.getOrDefault("metric_value", 0)).doubleValue();
  }

  private String scope(User user, String alias) {
    if (user.isHr()) return "";
    if (user.isManager()) return " AND " + alias + ".branch_id=? AND " + alias + ".department_id=?";
    return " AND " + alias + ".id=?";
  }

  private Object[] scopeArgs(User user) {
    if (user.isHr()) return new Object[]{};
    if (user.isManager()) return new Object[]{user.getBranchId(), user.getDepartmentId()};
    return new Object[]{user.getId()};
  }

  private Object[] join(Object[] first, Object[] second) {
    Object[] result = new Object[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private void requireManager(User user) {
    if (!user.isManager() && !user.isHr() && !isActiveDelegate(user)) throw new SecurityException("承認権限がありません。");
  }

  private boolean isActiveDelegate(User user) {
    return !Sql.query("SELECT d.id FROM delegations d JOIN users m ON m.id=d.manager_id WHERE d.delegate_id=? AND d.active=TRUE AND CURRENT_DATE BETWEEN d.starts_on AND d.ends_on AND m.branch_id=? AND m.department_id=?",
        user.getId(), user.getBranchId(), user.getDepartmentId()).isEmpty();
  }

  private void assertCanManage(User actor, long targetId) {
    if (actor.isHr() || actor.getId() == targetId) return;
    Map<String, Object> target = Sql.one("SELECT branch_id,department_id FROM users WHERE id=?", targetId);
    if (target.isEmpty()) throw new IllegalArgumentException("対象の従業員が見つかりません。");
    requireManager(actor);
    assertScope(actor, ((Number) target.get("branch_id")).longValue(), ((Number) target.get("department_id")).longValue());
  }

  private void assertScope(User actor, long branch, long department) {
    if (!actor.isHr() && (actor.getBranchId() != branch || actor.getDepartmentId() != department)) {
      throw new SecurityException("担当外のデータです。");
    }
  }

  private void assertLeaveApprovalScope(User actor, long branch) {
    if (actor.isHr()) return;
    if (!actor.isManager() || actor.getBranchId() != branch) throw new SecurityException("担当外のデータです。");
  }

  private BigDecimal number(String value) {
    try { return value == null || value.isBlank() ? null : new BigDecimal(value); }
    catch (NumberFormatException e) { return null; }
  }

  private LocalDate toDate(Object value) {
    if (value instanceof LocalDate date) return date;
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    return LocalDate.parse(String.valueOf(value));
  }

  private LocalDateTime toDateTime(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime dateTime) return dateTime;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
    return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
  }

  private java.time.LocalTime toTime(Object value) {
    if (value == null) return null;
    if (value instanceof java.time.LocalTime time) return time;
    if (value instanceof java.sql.Time time) return time.toLocalTime();
    return java.time.LocalTime.parse(String.valueOf(value));
  }
}
