package service;

import config.Database;
import dao.Sql;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.User;
import static util.DateUtil.*;

public class ShiftService {
  private final LeaveLedgerService leaveLedger = new LeaveLedgerService();
  private final SettingsService settings = new SettingsService();
  private final ShiftSubmissionPolicy shiftSubmissionPolicy = new ShiftSubmissionPolicy();
  private final NotificationService notificationService = new NotificationService();

  public List<Map<String, Object>> shifts(User viewer, YearMonth month) {
    return Sql.query("SELECT s.id,s.work_date,s.work_type_code,wt.name_ja work_type,u.id user_id,u.name,u.employee_number,s.status,s.note "
        + "FROM shifts s JOIN users u ON u.id=s.user_id JOIN work_types wt ON wt.code=s.work_type_code "
        + "WHERE s.work_date BETWEEN ? AND ?" + scope(viewer, "u") + " ORDER BY s.work_date,u.name",
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scopeArgs(viewer)));
  }

  public List<Map<String, Object>> branchShifts(YearMonth month, long branchId) {
    return Sql.query("SELECT s.id,s.work_date,s.work_type_code,wt.name_ja work_type,u.id user_id,u.name,u.employee_number,s.status,s.note "
        + "FROM shifts s JOIN users u ON u.id=s.user_id JOIN work_types wt ON wt.code=s.work_type_code "
        + "WHERE s.work_date BETWEEN ? AND ? AND u.branch_id=? ORDER BY s.work_date,u.name",
        month.atDay(1), month.atEndOfMonth(), branchId);
  }

  public List<Map<String, Object>> scheduleBranches() {
    return Sql.query("SELECT id,CASE "
        + "WHEN name IN ('本社','本店') THEN '本社' "
        + "WHEN name IN ('北部支店','北部営業所') THEN '北部支店' "
        + "WHEN name IN ('中部支店','中部営業所') THEN '中部支店' "
        + "WHEN name IN ('那覇支店','那覇営業所') THEN '那覇支店' "
        + "WHEN name IN ('南部支店','南部営業所') THEN '南部支店' "
        + "WHEN name IN ('石垣支店','石垣営業所') THEN '石垣支店' "
        + "WHEN name IN ('宮古支店','宮古営業所') THEN '宮古支店' ELSE name END name "
        + "FROM branches WHERE active=TRUE ORDER BY CASE "
        + "WHEN name IN ('本社','本店') THEN 1 WHEN name IN ('北部支店','北部営業所') THEN 2 "
        + "WHEN name IN ('中部支店','中部営業所') THEN 3 WHEN name IN ('那覇支店','那覇営業所') THEN 4 "
        + "WHEN name IN ('南部支店','南部営業所') THEN 5 WHEN name IN ('石垣支店','石垣営業所') THEN 6 "
        + "WHEN name IN ('宮古支店','宮古営業所') THEN 7 ELSE 99 END,id");
  }

  public List<Map<String, Object>> dashboardShifts(User viewer, YearMonth month, long branchId) {
    return Sql.query("SELECT s.id,s.work_date,s.work_type_code,wt.name_ja work_type,u.id user_id,u.name,u.employee_number,s.status,s.note "
        + "FROM shifts s JOIN users u ON u.id=s.user_id JOIN work_types wt ON wt.code=s.work_type_code "
        + "WHERE s.work_date BETWEEN ? AND ? AND u.branch_id=? ORDER BY s.work_date,u.name",
        month.atDay(1), month.atEndOfMonth(), branchId);
  }

  public List<Map<String, Object>> dashboardBranches(YearMonth month) {
    return scheduleBranches();
  }

  public List<Map<String, Object>> workTypes() {
    return Sql.query("SELECT * FROM work_types WHERE active=TRUE ORDER BY id");
  }

  public void saveShift(User actor, long userId, LocalDate date, String type, String status, String note) {
    assertCanManage(actor, userId);
    if (!workTypes().stream().anyMatch(r -> type.equals(r.get("code")))) throw new IllegalArgumentException("勤務区分が不正です。");
    assertShiftLeaveBalance(userId, date, type);
    String sql = Database.isPostgres()
        ? "INSERT INTO shifts(user_id,work_date,work_type_code,status,note,updated_by,updated_at) "
            + "VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP) "
            + "ON CONFLICT (user_id,work_date) DO UPDATE SET work_type_code=EXCLUDED.work_type_code, "
            + "status=EXCLUDED.status, note=EXCLUDED.note, updated_by=EXCLUDED.updated_by, updated_at=CURRENT_TIMESTAMP"
        : "MERGE INTO shifts(user_id,work_date,work_type_code,status,note,updated_by,updated_at) "
            + "KEY(user_id,work_date) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)";
    Sql.update(sql,
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
      String reason = reasons.get(date);
      validatePreferenceEntry(date, type, reason, month);
      if (type == null || type.isBlank() || "NONE".equals(type)) continue;
      selected.put(date, type);
      if ("LEAVE".equals(type) && reason != null && !reason.isBlank()) {
        selectedReasons.put(date, reason.trim());
      }
    }
    try (Connection connection = Database.getConnection()) {
      connection.setAutoCommit(false);
      try {
        long submissionId;
        String upsertSql = Database.isPostgres()
            ? "INSERT INTO shift_preference_submissions(user_id,target_month,status,submitted_at,updated_at,reviewed_by,reviewed_at) "
                + "VALUES(?,?,'SUBMITTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL) "
                + "ON CONFLICT (user_id,target_month) DO UPDATE SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP,"
                + "updated_at=CURRENT_TIMESTAMP,reviewed_by=NULL,reviewed_at=NULL"
            : "MERGE INTO shift_preference_submissions(user_id,target_month,status,submitted_at,updated_at,reviewed_by,reviewed_at) "
                + "KEY(user_id,target_month) VALUES(?,?,'SUBMITTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL)";
        try (PreparedStatement upsert = connection.prepareStatement(upsertSql)) {
          upsert.setLong(1, actor.getId()); upsert.setObject(2, month.atDay(1)); upsert.executeUpdate();
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

    // 1. 承認済み有休の事前反映
    for (Map<String, Object> person : people) {
      long userId = ((Number) person.get("id")).longValue();
      for (int day = 1; day <= month.lengthOfMonth(); day++) {
        LocalDate date = month.atDay(day);
        if (shiftMissing(userId, date)) {
          Map<String, Object> leaveReq = Sql.one("SELECT leave_unit FROM leave_requests WHERE user_id=? AND leave_date=? AND status='APPROVED'", userId, date);
          if (!leaveReq.isEmpty()) {
            String leaveUnit = String.valueOf(leaveReq.get("leave_unit"));
            String leaveType = switch (leaveUnit) {
              case "FULL" -> "LEAVE";
              case "AM" -> "AM_LEAVE";
              case "PM" -> "PM_LEAVE";
              default -> null;
            };
            if (leaveType != null) {
              saveShift(actor, userId, date, leaveType, "DRAFT", "承認済み有休を優先して自動反映");
              assigned++;
            }
          }
        }
      }
    }

    // 2. 希望シフトの反映（すでに有休等が割り当てられている場合はスキップ）
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

    // 3. 人員必要数に応じた自動割り当て
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
    if (isShiftAlreadyAssigned(userId, date)) return false;
    if (hasApprovedLeave(userId, date)) return false; // 優先度1: 承認済み有休がある日は勤務割当不可
    if (wasOnNightShiftYesterday(userId, date)) return false;
    if (hasPreferredOffOrLeave(userId, date)) return false; // 優先度2: 希望休
    if (exceedsMaxConsecutiveWorkDays(userId, date, maxConsecutive)) return false;
    return true;
  }

  private boolean hasApprovedLeave(long userId, LocalDate date) {
    return !Sql.query("SELECT id FROM leave_requests WHERE user_id=? AND leave_date=? AND status='APPROVED' AND leave_unit IN ('FULL','AM','PM')", userId, date).isEmpty();
  }

  private boolean isShiftAlreadyAssigned(long userId, LocalDate date) {
    return !shiftMissing(userId, date);
  }

  private boolean wasOnNightShiftYesterday(long userId, LocalDate date) {
    return !Sql.query("SELECT id FROM shifts WHERE user_id=? AND work_date=? AND work_type_code='NIGHT'", userId, date.minusDays(1)).isEmpty();
  }

  private boolean hasPreferredOffOrLeave(long userId, LocalDate date) {
    return !Sql.query("SELECT p.id FROM shift_preferences p JOIN shift_preference_submissions s ON s.id=p.submission_id "
        + "WHERE s.user_id=? AND p.preference_date=? AND p.request_type IN('OFF','LEAVE')", userId, date).isEmpty();
  }

  private boolean exceedsMaxConsecutiveWorkDays(long userId, LocalDate date, int maxConsecutive) {
    int consecutive = ((Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts WHERE user_id=? AND work_date BETWEEN ? AND ? "
        + "AND work_type_code IN('DAY','NIGHT','AM_LEAVE','PM_LEAVE')", userId, date.minusDays(maxConsecutive), date.minusDays(1))
        .getOrDefault("metric_value", 0)).intValue();
    return consecutive >= maxConsecutive;
  }

  private boolean shiftMissing(long userId, LocalDate date) {
    return Sql.query("SELECT id FROM shifts WHERE user_id=? AND work_date=?", userId, date).isEmpty();
  }

  private int requiredStaff(String type) {
    return ((Number) Sql.one("SELECT required_staff metric_value FROM work_types WHERE code=?", type).getOrDefault("metric_value", 0)).intValue();
  }

  public Map<String, Object> shiftSubmissionWindow() {
    return shiftSubmissionWindow(shiftSubmissionPolicy.targetMonth(LocalDate.now()));
  }

  public Map<String, Object> shiftSubmissionWindow(YearMonth targetMonth) {
    LocalDate today = LocalDate.now();
    int submissionDay = settings.integer("SHIFT_SUBMISSION_DAY", 15);
    YearMonth deadlineMonth = targetMonth.minusMonths(1);
    LocalDate deadline = deadlineMonth.atDay(Math.min(Math.max(submissionDay, 1), deadlineMonth.lengthOfMonth()));
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("target_month", targetMonth);
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
      notificationService.notify(((Number) target.get("id")).longValue(), "SHIFT_CONFIRMED", "シフト確定", month + "のシフトが確定しました。", "/app/shifts/mine?month=" + month);
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
    if ("LEAVE".equals(type)) throw new IllegalArgumentException("有休は有休申請から申請してください。");
    boolean urgent = !date.isAfter(LocalDate.now());
    if (date.isBefore(LocalDate.now())) throw new IllegalArgumentException("過去日の変更は打刻修正から申請してください。");
    long id = Sql.insert("INSERT INTO shift_change_requests(user_id,work_date,requested_work_type,reason,urgent) VALUES(?,?,?,?,?)",
        user.getId(), date, type, reason.trim(), urgent);
    notificationService.notifyManagers(user, "SHIFT_CHANGE_REQUEST", urgent ? "緊急シフト変更申請" : "シフト変更申請",
        user.getName() + "さんから変更申請があります。", "/app/shifts/history");
    AuditService.record(user.getId(), "REQUEST_SHIFT_CHANGE", "SHIFT_CHANGE", String.valueOf(id), null, type + ":" + date);
  }

  public void decideShiftChange(User actor, long requestId, boolean approve) {
    decideShiftChange(actor, requestId, approve, null);
  }

  public void decideShiftChange(User actor, long requestId, boolean approve, String rejectionReason) {
    requireManager(actor);
    Map<String, Object> row = Sql.one("SELECT r.*,u.branch_id,u.department_id FROM shift_change_requests r JOIN users u ON u.id=r.user_id WHERE r.id=?", requestId);
    if (row.isEmpty() || !"PENDING".equals(row.get("status"))) throw new IllegalArgumentException("未処理の申請が見つかりません。");
    assertScope(actor, ((Number) row.get("branch_id")).longValue(), ((Number) row.get("department_id")).longValue());
    List<Map<String, Object>> recheckWarnings = List.of();
    String decisionMessage = row.get("work_date") + "の申請結果を確認してください。";
    if (approve) {
      LocalDate workDate = toDate(row.get("work_date"));
      saveShift(actor, ((Number) row.get("user_id")).longValue(), workDate,
          String.valueOf(row.get("requested_work_type")), "CONFIRMED", "変更申請 #" + requestId);
      recheckWarnings = shiftWarningsForDate(actor, workDate);
      if (!recheckWarnings.isEmpty()) {
        notificationService.notify(actor.getId(), "SHIFT_RECHECK", "シフト変更後の警告",
            workDate + "の変更反映後に" + recheckWarnings.size() + "件の警告があります。必要人数と勤務間隔を確認してください。",
            "/app/shifts/manage?month=" + YearMonth.from(workDate));
      }
    } else {
      if (rejectionReason == null || rejectionReason.isBlank()) throw new IllegalArgumentException("却下理由を入力してください。");
      decisionMessage += " 却下理由: " + rejectionReason.trim();
    }
    Sql.update("UPDATE shift_change_requests SET status=?,decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED", actor.getId(), requestId);
    notificationService.notify(((Number) row.get("user_id")).longValue(), "SHIFT_CHANGE_DECISION",
        approve ? "シフト変更が承認されました" : "シフト変更が却下されました",
        decisionMessage, "/app/shifts/history");
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
    String dateadd = config.Database.isPostgres() ? "(s1.work_date + INTERVAL '1 day')::date" : "DATEADD('DAY',1,s1.work_date)";
    result.addAll(Sql.query("SELECT 'NIGHT_REST' warning,s2.work_date,u.name detail,0 required,0 actual "
        + "FROM shifts s1 JOIN shifts s2 ON s2.user_id=s1.user_id AND s2.work_date=" + dateadd + " "
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
    if (config.Database.isPostgres()) {
      result.addAll(Sql.query("WITH RECURSIVE dates(work_date) AS (SELECT CAST(? AS DATE) UNION ALL SELECT (work_date + INTERVAL '1 day')::date FROM dates WHERE work_date<?) "
          + "SELECT 'STAFF_SHORTAGE' warning,dates.work_date,wt.name_ja detail,wt.required_staff required," + actualCount + " actual "
          + "FROM dates CROSS JOIN work_types wt LEFT JOIN shifts s ON s.work_date=dates.work_date AND s.work_type_code=wt.code "
          + scopedUserJoin + " WHERE wt.active=TRUE AND wt.required_staff>0 GROUP BY dates.work_date,wt.name_ja,wt.required_staff "
          + "HAVING " + actualCount + "<wt.required_staff ORDER BY dates.work_date,wt.name_ja",
          join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scopeArgs)));
      result.addAll(Sql.query("SELECT 'NIGHT_REST' warning,s2.work_date,u.name detail,0 required,0 actual FROM shifts s1 "
          + "JOIN shifts s2 ON s2.user_id=s1.user_id AND s2.work_date=(s1.work_date + INTERVAL '1 day')::date JOIN users u ON u.id=s1.user_id "
          + "WHERE s1.work_type_code='NIGHT' AND s2.work_type_code NOT IN('OFF','LEAVE') AND s1.work_date BETWEEN ? AND ?" + userScope,
          join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scopeArgs)));
    } else {
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
    }
    return result;
  }

  // 共通権限・スコープヘルパー
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

  private void validatePreferenceEntry(LocalDate date, String type, String reason, YearMonth targetMonth) {
    if (!YearMonth.from(date).equals(targetMonth)) {
      throw new IllegalArgumentException("対象月以外の日付は提出できません。");
    }
    if (type == null || type.isBlank() || "NONE".equals(type)) {
      return;
    }
    if (!List.of("DAY", "NIGHT", "OFF").contains(type)) {
      throw new IllegalArgumentException("希望勤務区分が不正です。");
    }
  }
}
