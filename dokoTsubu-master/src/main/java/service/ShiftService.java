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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    submitPreferredShift(actor, date, type, note, LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
  }

  void submitPreferredShift(User actor, LocalDate date, String type, String note, LocalDate today) {
    shiftSubmissionPolicy.validate(today, date, settings.integer("SHIFT_SUBMISSION_DAY", 15));
    saveShift(actor, actor.getId(), date, type, "SUBMITTED", note);
  }

  public void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences) {
    submitMonthlyPreferences(actor, month, preferences, Map.of(), LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
  }

  void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences, LocalDate today) {
    submitMonthlyPreferences(actor, month, preferences, Map.of(), today);
  }

  public void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences,
      Map<LocalDate, String> reasons) {
    submitMonthlyPreferences(actor, month, preferences, reasons, LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
  }

  void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences,
      Map<LocalDate, String> reasons, LocalDate today) {
    int submissionDay = settings.integer("SHIFT_SUBMISSION_DAY", 15);
    LocalDate deadline = preferenceDeadline(month, submissionDay);
    rejectSubmittedPreferenceAfterDeadline(actor, month, today, deadline);
    shiftSubmissionPolicy.validate(today, month.atDay(1), submissionDay);
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
    AutoAssignmentState state = loadAutoAssignmentState(actor, month, people);

    // 1. 承認済み有休の事前反映
    for (Map<String, Object> person : people) {
      long userId = ((Number) person.get("id")).longValue();
      for (int day = 1; day <= month.lengthOfMonth(); day++) {
        LocalDate date = month.atDay(day);
        if (!state.hasShift(userId, date)) {
          String leaveUnit = state.approvedLeaves.get(new UserDate(userId, date));
          if (leaveUnit != null) {
            String leaveType = switch (leaveUnit) {
              case "FULL" -> "LEAVE";
              case "AM" -> "AM_LEAVE";
              case "PM" -> "PM_LEAVE";
              default -> null;
            };
            if (leaveType != null) {
              state.add(userId, date, leaveType, "承認済み有休を優先して自動反映");
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
      LocalDate date = toDate(preference.get("preference_date"));
      if (!state.hasShift(userId, date)) {
        String requestType = String.valueOf(preference.get("request_type"));
        state.add(userId, date, requestType, "希望シフトから自動反映");
        if ("NIGHT".equals(requestType) && YearMonth.from(date.plusDays(1)).equals(month) && !state.hasShift(userId, date.plusDays(1))) {
          state.add(userId, date.plusDays(1), "NIGHT_OFF", "夜勤明け自動設定");
        }
      }
    }

    // 3. 人員必要数に応じた自動割り当て
    int dayRequired = requiredStaff("DAY");
    int nightRequired = requiredStaff("NIGHT");
    for (int day = 1; day <= month.lengthOfMonth(); day++) {
      LocalDate date = month.atDay(day);
      fillWorkType(state, people, date, "DAY", dayRequired);
      fillWorkType(state, people, date, "NIGHT", nightRequired);
    }
    saveAutoAssignments(actor, state.pending);
    int assigned = state.pending.size();
    AuditService.record(actor.getId(), "AUTO_ASSIGN_SHIFTS", "SHIFT_MONTH", month.toString(), null, assigned + " shifts");
    return assigned;
  }

  public int autoFillShifts(User actor, YearMonth month) {
    requireManager(actor);
    
    // 確定済みチェック：もし対象月に対象スコープの確定シフト (CONFIRMED) が1件でもあれば、補完処理を制限する。
    String scope = actor.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?";
    Object[] args = actor.isHr() ? new Object[]{month.atDay(1), month.atEndOfMonth()} 
                                 : new Object[]{month.atDay(1), month.atEndOfMonth(), actor.getBranchId(), actor.getDepartmentId()};
    boolean isConfirmed = !Sql.query("SELECT s.id FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE s.work_date BETWEEN ? AND ? AND s.status='CONFIRMED'" + scope + " LIMIT 1", args).isEmpty();
    if (isConfirmed) {
      throw new IllegalArgumentException("確定済みの月は自動補完を実行できません。");
    }

    String userScope = actor.isHr() ? "" : " AND branch_id=? AND department_id=?";
    Object[] userArgs = actor.isHr() ? new Object[]{} : new Object[]{actor.getBranchId(), actor.getDepartmentId()};
    List<Map<String, Object>> people = Sql.query("SELECT id,employee_number,role,weekly_work_days FROM users WHERE active=TRUE AND role<>'HR'" + userScope
        + " ORDER BY CASE WHEN role='MANAGER' THEN 0 ELSE 1 END,employee_number", userArgs);
    AutoAssignmentState state = loadAutoAssignmentState(actor, month, people);

    int dayRequired = requiredStaff("DAY");
    int nightRequired = requiredStaff("NIGHT");

    // 日ごとに未割り当てセルへ補完
    for (int day = 1; day <= month.lengthOfMonth(); day++) {
      LocalDate date = month.atDay(day);
      fillWorkTypeForFill(state, people, date, "DAY", dayRequired);
      fillWorkTypeForFill(state, people, date, "NIGHT", nightRequired);
    }

    saveAutoAssignments(actor, state.pending);
    int assigned = state.pending.size();
    AuditService.record(actor.getId(), "AUTO_FILL_SHIFTS", "SHIFT_MONTH", month.toString(), null, assigned + " shifts filled");
    return assigned;
  }

  private void fillWorkTypeForFill(AutoAssignmentState state, List<Map<String, Object>> people, LocalDate date, String type, int required) {
    int actual = state.count(date, type);
    if (actual >= required) return;

    // 「同じ従業員に偏りすぎないようにする」ため、現在の総勤務日数（DAY + NIGHT）が少ない順にソートして割り当てる
    List<Map<String, Object>> sortedPeople = new ArrayList<>(people);
    sortedPeople.sort((p1, p2) -> {
      long u1 = ((Number) p1.get("id")).longValue();
      long u2 = ((Number) p2.get("id")).longValue();
      int count1 = state.totalAssignedWorkDays(u1);
      int count2 = state.totalAssignedWorkDays(u2);
      if (count1 != count2) {
        return Integer.compare(count1, count2);
      }
      String role1 = String.valueOf(p1.get("role"));
      String role2 = String.valueOf(p2.get("role"));
      if (!role1.equals(role2)) {
        return "MANAGER".equals(role1) ? -1 : 1;
      }
      return String.valueOf(p1.get("employee_number")).compareTo(String.valueOf(p2.get("employee_number")));
    });

    for (Map<String, Object> person : sortedPeople) {
      if (actual >= required) break;
      long userId = ((Number) person.get("id")).longValue();
      int maxConsecutive = Math.max(1, ((Number) person.get("weekly_work_days")).intValue());
      if (!state.canAssignForFill(userId, date, type, maxConsecutive)) continue;
      state.add(userId, date, type, "未割り当ての自動補完");
      actual++;
      if ("NIGHT".equals(type) && date.plusDays(1).getMonthValue() == date.getMonthValue() && !state.hasShift(userId, date.plusDays(1))) {
        state.add(userId, date.plusDays(1), "NIGHT_OFF", "夜勤明け自動設定（補完）");
      }
    }
  }

  private void fillWorkType(AutoAssignmentState state, List<Map<String, Object>> people, LocalDate date, String type, int required) {
    int actual = state.count(date, type);
    for (Map<String, Object> person : people) {
      if (actual >= required) break;
      long userId = ((Number) person.get("id")).longValue();
      int maxConsecutive = Math.max(1, ((Number) person.get("weekly_work_days")).intValue());
      if (!state.canAssign(userId, date, maxConsecutive)) continue;
      state.add(userId, date, type, "希望なしの自動割当"); actual++;
      if ("NIGHT".equals(type) && date.plusDays(1).getMonthValue() == date.getMonthValue() && !state.hasShift(userId, date.plusDays(1))) {
        state.add(userId, date.plusDays(1), "NIGHT_OFF", "夜勤明け自動設定");
      }
    }
  }

  private AutoAssignmentState loadAutoAssignmentState(User actor, YearMonth month, List<Map<String, Object>> people) {
    int lookback = people.stream().mapToInt(p -> Math.max(1, ((Number) p.get("weekly_work_days")).intValue())).max().orElse(1);
    Map<UserDate, String> shifts = new HashMap<>();
    for (Map<String, Object> row : Sql.query("SELECT s.user_id,s.work_date,s.work_type_code FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE s.work_date BETWEEN ? AND ?" + scope(actor, "u"),
        join(new Object[]{month.atDay(1).minusDays(lookback), month.atEndOfMonth()}, scopeArgs(actor)))) {
      shifts.put(new UserDate(((Number) row.get("user_id")).longValue(), toDate(row.get("work_date"))), String.valueOf(row.get("work_type_code")));
    }
    Map<UserDate, String> approvedLeaves = new HashMap<>();
    for (Map<String, Object> row : Sql.query("SELECT lr.user_id,lr.leave_date,lr.leave_unit FROM leave_requests lr JOIN users u ON u.id=lr.user_id "
        + "WHERE lr.status='APPROVED' AND lr.leave_unit IN('FULL','AM','PM') AND lr.leave_date BETWEEN ? AND ?" + scope(actor, "u"),
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scopeArgs(actor)))) {
      approvedLeaves.put(new UserDate(((Number) row.get("user_id")).longValue(), toDate(row.get("leave_date"))), String.valueOf(row.get("leave_unit")));
    }
    Set<UserDate> preferredOffOrLeave = new HashSet<>();
    for (Map<String, Object> row : Sql.query("SELECT s.user_id,p.preference_date FROM shift_preferences p "
        + "JOIN shift_preference_submissions s ON s.id=p.submission_id JOIN users u ON u.id=s.user_id "
        + "WHERE p.preference_date BETWEEN ? AND ? AND p.request_type IN('OFF','LEAVE')" + scope(actor, "u"),
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scopeArgs(actor)))) {
      preferredOffOrLeave.add(new UserDate(((Number) row.get("user_id")).longValue(), toDate(row.get("preference_date"))));
    }
    return new AutoAssignmentState(shifts, approvedLeaves, preferredOffOrLeave);
  }

  private void saveAutoAssignments(User actor, List<PendingShift> assignments) {
    if (assignments.isEmpty()) return;
    String shiftSql = Database.isPostgres()
        ? "INSERT INTO shifts(user_id,work_date,work_type_code,status,note,updated_by,updated_at) VALUES(?,?,?,'DRAFT',?,?,CURRENT_TIMESTAMP) "
            + "ON CONFLICT (user_id,work_date) DO UPDATE SET work_type_code=EXCLUDED.work_type_code,status=EXCLUDED.status,note=EXCLUDED.note,updated_by=EXCLUDED.updated_by,updated_at=CURRENT_TIMESTAMP"
        : "MERGE INTO shifts(user_id,work_date,work_type_code,status,note,updated_by,updated_at) KEY(user_id,work_date) VALUES(?,?,?,'DRAFT',?,?,CURRENT_TIMESTAMP)";
    String auditSql = "INSERT INTO audit_logs(actor_id,action,target_type,target_id,target_user_id,before_value,after_value) VALUES(?,'SAVE_SHIFT','SHIFT',?,?,NULL,?)";
    try (Connection connection = Database.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement shifts = connection.prepareStatement(shiftSql);
           PreparedStatement audits = connection.prepareStatement(auditSql)) {
        for (PendingShift assignment : assignments) {
          shifts.setLong(1, assignment.userId()); shifts.setObject(2, assignment.date()); shifts.setString(3, assignment.type());
          shifts.setString(4, assignment.note()); shifts.setLong(5, actor.getId()); shifts.addBatch();
          audits.setLong(1, actor.getId()); audits.setString(2, assignment.userId() + ":" + assignment.date());
          audits.setLong(3, assignment.userId()); audits.setString(4, assignment.type() + "/DRAFT"); audits.addBatch();
        }
        shifts.executeBatch(); audits.executeBatch(); connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not save automatic shift assignments", e);
    }
  }

  private record UserDate(long userId, LocalDate date) { }
  private record PendingShift(long userId, LocalDate date, String type, String note) { }

  private static final class AutoAssignmentState {
    private final Map<UserDate, String> shifts;
    private final Map<UserDate, String> approvedLeaves;
    private final Set<UserDate> preferredOffOrLeave;
    private final List<PendingShift> pending = new ArrayList<>();

    private AutoAssignmentState(Map<UserDate, String> shifts, Map<UserDate, String> approvedLeaves, Set<UserDate> preferredOffOrLeave) {
      this.shifts = shifts; this.approvedLeaves = approvedLeaves; this.preferredOffOrLeave = preferredOffOrLeave;
    }

    private boolean hasShift(long userId, LocalDate date) { return shifts.containsKey(new UserDate(userId, date)); }
    private int count(LocalDate date, String type) {
      return (int) shifts.entrySet().stream().filter(e -> e.getKey().date().equals(date) && type.equals(e.getValue())).count();
    }
    private boolean canAssign(long userId, LocalDate date, int maxConsecutive) {
      if (hasShift(userId, date) || approvedLeaves.containsKey(new UserDate(userId, date))) return false;
      if ("NIGHT".equals(shifts.get(new UserDate(userId, date.minusDays(1))))) return false;
      if (preferredOffOrLeave.contains(new UserDate(userId, date))) return false;
      int consecutive = 0;
      for (LocalDate previous = date.minusDays(maxConsecutive); previous.isBefore(date); previous = previous.plusDays(1)) {
        String type = shifts.get(new UserDate(userId, previous));
        if ("DAY".equals(type) || "NIGHT".equals(type) || "AM_LEAVE".equals(type) || "PM_LEAVE".equals(type)) consecutive++;
      }
      return consecutive < maxConsecutive;
    }
    private void add(long userId, LocalDate date, String type, String note) {
      shifts.put(new UserDate(userId, date), type); pending.add(new PendingShift(userId, date, type, note));
    }

    private int totalAssignedWorkDays(long userId) {
      return (int) shifts.entrySet().stream()
          .filter(e -> e.getKey().userId() == userId && ("DAY".equals(e.getValue()) || "NIGHT".equals(e.getValue())))
          .count();
    }

    private boolean canAssignForFill(long userId, LocalDate date, String type, int maxConsecutive) {
      if (!canAssign(userId, date, maxConsecutive)) return false;

      // NIGHTを割り当てる場合、翌日が今月内であれば、翌日へ安全にNIGHT_OFFを割り当てられることを確認する
      if ("NIGHT".equals(type)) {
        LocalDate tomorrow = date.plusDays(1);
        if (tomorrow.getMonthValue() == date.getMonthValue()) {
          if (hasShift(userId, tomorrow) || approvedLeaves.containsKey(new UserDate(userId, tomorrow)) || preferredOffOrLeave.contains(new UserDate(userId, tomorrow))) {
            return false;
          }
        }
      }
      return true;
    }
  }

  private int requiredStaff(String type) {
    return ((Number) Sql.one("SELECT required_staff metric_value FROM work_types WHERE code=?", type).getOrDefault("metric_value", 0)).intValue();
  }

  public Map<String, Object> shiftSubmissionWindow() {
    return shiftSubmissionWindow(shiftSubmissionPolicy.targetMonth(LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"))));
  }

  public Map<String, Object> shiftSubmissionWindow(YearMonth targetMonth) {
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"));
    int submissionDay = settings.integer("SHIFT_SUBMISSION_DAY", 15);
    LocalDate deadline = preferenceDeadline(targetMonth, submissionDay);
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("target_month", targetMonth);
    result.put("deadline", deadline);
    result.put("open", !today.isAfter(deadline));
    return result;
  }

  private LocalDate preferenceDeadline(YearMonth targetMonth, int submissionDay) {
    YearMonth deadlineMonth = targetMonth.minusMonths(1);
    return deadlineMonth.atDay(Math.min(Math.max(submissionDay, 1), deadlineMonth.lengthOfMonth()));
  }

  private void rejectSubmittedPreferenceAfterDeadline(User actor, YearMonth month, LocalDate today, LocalDate deadline) {
    if (!today.isAfter(deadline)) return;
    Map<String, Object> submission = preferenceSubmission(actor, month);
    if (!"SUBMITTED".equals(submission.get("status"))) return;
    throw new IllegalArgumentException(deadlineClosedSubmittedMessage(month, deadline));
  }

  private String deadlineClosedSubmittedMessage(YearMonth month, LocalDate deadline) {
    return String.format(
        "%d年%02d月の希望シフト提出期限は %d年%02d月%02d日 で終了しています。提出済みの希望シフトは変更できません。変更が必要な場合は、店長または人事へ相談してください。",
        month.getYear(), month.getMonthValue(), deadline.getYear(), deadline.getMonthValue(), deadline.getDayOfMonth());
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
    return Sql.query("SELECT r.*,u.name,u.employee_number,wt.name_ja requested_name,COALESCE(current_wt.name_ja,s.work_type_code) current_type "
        + "FROM shift_change_requests r JOIN users u ON u.id=r.user_id JOIN work_types wt ON wt.code=r.requested_work_type "
        + "LEFT JOIN shifts s ON s.user_id=r.user_id AND s.work_date=r.work_date "
        + "LEFT JOIN work_types current_wt ON current_wt.code=s.work_type_code WHERE 1=1" + filter
        + " ORDER BY r.created_at DESC", args);
  }

  public void requestShiftChange(User user, LocalDate date, String type, String reason) {
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("変更理由を入力してください。");
    if ("LEAVE".equals(type)) throw new IllegalArgumentException("有休は有休申請から申請してください。");
    boolean urgent = !date.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
    if (date.isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")))) throw new IllegalArgumentException("過去日の変更は打刻修正から申請してください。");
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
