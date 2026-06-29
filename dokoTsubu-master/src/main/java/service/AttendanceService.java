package service;

import config.Database;
import dao.Sql;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.User;
import static util.DateUtil.*;

public class AttendanceService {
  private final SettingsService settings = new SettingsService();
  private final AttendanceCalculator attendanceCalculator = new AttendanceCalculator();
  private final NotificationService notificationService = new NotificationService();

  public List<Map<String, Object>> attendance(User viewer, YearMonth month) {
    String filter = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND a.user_id=?";
    Object[] scope = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    List<Map<String, Object>> rows = Sql.query("SELECT a.*,u.name,u.employee_number,s.work_type_code,wt.name_ja work_type,wt.start_time,wt.end_time,wt.crosses_midnight,wt.break_minutes "
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

  public Map<String, Object> attendanceClockSummary(User user) {
    Map<String, Object> result = new LinkedHashMap<>();
    Map<String, Object> today = Sql.one("SELECT a.id,a.work_date,a.clock_in,a.clock_out,a.status,a.finalized,a.location_status,"
        + "s.work_type_code,wt.name_ja work_type,wt.start_time,wt.end_time "
        + "FROM users u LEFT JOIN attendance a ON a.user_id=u.id AND a.work_date=CURRENT_DATE "
        + "LEFT JOIN shifts s ON s.user_id=u.id AND s.work_date=CURRENT_DATE "
        + "LEFT JOIN work_types wt ON wt.code=s.work_type_code WHERE u.id=?", user.getId());
    if (!today.isEmpty()) result.putAll(today);
    Map<String, Object> open = Sql.one("SELECT id,work_date,clock_in,finalized FROM attendance "
        + "WHERE user_id=? AND clock_in IS NOT NULL AND clock_out IS NULL ORDER BY clock_in DESC LIMIT 1", user.getId());
    boolean hasOpen = !open.isEmpty();
    boolean todayHasClockIn = result.get("clock_in") != null;
    boolean todayComplete = result.get("clock_in") != null && result.get("clock_out") != null;
    result.put("has_open_clock", hasOpen);
    result.put("open_attendance_id", open.get("id"));
    result.put("open_work_date", open.get("work_date"));
    result.put("open_clock_in", open.get("clock_in"));
    result.put("open_finalized", open.get("finalized"));
    result.put("can_clock_in", !hasOpen && !todayHasClockIn);
    result.put("can_clock_out", hasOpen && !Boolean.TRUE.equals(open.get("finalized")));
    result.put("today_complete", todayComplete);
    return result;
  }

  public void clock(User user, boolean clockIn, String lat, String lng, String locationStatus) {
    if (settings.bool("LOCATION_REQUIRED", false) && !"ACQUIRED".equals(locationStatus)) throw new IllegalArgumentException("位置情報を取得できないため打刻できません。");
    LocalDate workDate = LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"));
    java.time.LocalDateTime nowTokyo = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Tokyo"));
    if (!Sql.query("SELECT id FROM attendance WHERE user_id=? AND work_date=? AND finalized=TRUE", user.getId(), workDate).isEmpty()) {
      throw new IllegalArgumentException("確定済み勤怠は変更できません。店長へ確定解除を依頼してください。");
    }
    if (clockIn) {
      if (!Sql.query("SELECT id FROM attendance WHERE user_id=? AND clock_in IS NOT NULL AND clock_out IS NULL", user.getId()).isEmpty()) {
        throw new IllegalArgumentException("すでに出勤打刻済みです。退勤打刻を行ってください。");
      }
      Map<String, Object> today = Sql.one("SELECT clock_in,clock_out FROM attendance WHERE user_id=? AND work_date=?", user.getId(), workDate);
      if (today.get("clock_in") != null && today.get("clock_out") == null) {
        throw new IllegalArgumentException("すでに出勤打刻済みです。退勤打刻を行ってください。");
      }
      if (today.get("clock_in") != null) {
        throw new IllegalArgumentException("本日の打刻は完了しています。時刻の変更は打刻修正から申請してください。");
      }
      String sql = Database.isPostgres()
          ? "INSERT INTO attendance(user_id,work_date,clock_in,in_lat,in_lng,location_status,status) "
              + "VALUES(?,?,?,?,?,?,'OPEN') "
              + "ON CONFLICT (user_id,work_date) DO UPDATE SET clock_in=EXCLUDED.clock_in, "
              + "in_lat=EXCLUDED.in_lat, in_lng=EXCLUDED.in_lng, "
              + "location_status=EXCLUDED.location_status, status=EXCLUDED.status"
          : "MERGE INTO attendance(user_id,work_date,clock_in,in_lat,in_lng,location_status,status) "
              + "KEY(user_id,work_date) VALUES(?,?,?,?,?,?,'OPEN')";
      Sql.update(sql,
          user.getId(), workDate, nowTokyo, number(lat), number(lng), locationStatus);
    } else {
      Map<String, Object> open = Sql.one("SELECT id,work_date,finalized FROM attendance WHERE user_id=? AND clock_in IS NOT NULL AND clock_out IS NULL ORDER BY clock_in DESC LIMIT 1", user.getId());
      if (open.isEmpty()) throw new IllegalArgumentException("先に出勤打刻を行ってください。");
      if (Boolean.TRUE.equals(open.get("finalized"))) {
        throw new IllegalArgumentException("確定済み勤怠は変更できません。店長へ確定解除を依頼してください。");
      }
      int updated = Sql.update("UPDATE attendance SET clock_out=?,out_lat=?,out_lng=?,location_status=?,status='COMPLETE' WHERE id=? AND finalized=FALSE",
          nowTokyo, number(lat), number(lng), locationStatus, open.get("id"));
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

  public List<Map<String, Object>> attendanceAdjustments(User viewer, YearMonth month) {
    String filter = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND r.requested_by=?";
    Object[] scope = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    return Sql.query("SELECT r.*,a.work_date,a.clock_in current_in,a.clock_out current_out,u.name,u.employee_number FROM attendance_adjustments r JOIN attendance a ON a.id=r.attendance_id JOIN users u ON u.id=r.requested_by WHERE a.work_date BETWEEN ? AND ?"
        + filter + " ORDER BY r.created_at DESC", join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scope));
  }

  public void requestAttendanceAdjustment(User user, long attendanceId, LocalDateTime requestedIn,
      LocalDateTime requestedOut, String reason) {
    Map<String, Object> attendance = Sql.one("SELECT user_id,finalized FROM attendance WHERE id=?", attendanceId);
    if (attendance.isEmpty() || ((Number) attendance.get("user_id")).longValue() != user.getId()) throw new SecurityException("自分の勤怠だけ修正申請できます。");
    if (Boolean.TRUE.equals(attendance.get("finalized"))) throw new IllegalArgumentException("確定済み勤怠は、店長が確定解除してから申請してください。");
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("修正理由を入力してください。");
    long id = Sql.insert("INSERT INTO attendance_adjustments(attendance_id,requested_by,requested_in,requested_out,reason) VALUES(?,?,?,?,?)",
        attendanceId, user.getId(), requestedIn, requestedOut, reason.trim());
    notificationService.notifyManagers(user, "ATTENDANCE_ADJUSTMENT", "打刻修正申請", user.getName() + "さんから打刻修正申請があります。", "/app/attendance/manage");
    AuditService.record(user.getId(), "REQUEST_ATTENDANCE_ADJUSTMENT", "ATTENDANCE_ADJUSTMENT", String.valueOf(id), null,
        "attendance_id=" + attendanceId);
  }

  public void decideAttendanceAdjustment(User actor, long requestId, boolean approve) {
    decideAttendanceAdjustment(actor, requestId, approve, null);
  }

  public void decideAttendanceAdjustment(User actor, long requestId, boolean approve, String rejectionReason) {
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

    String decisionMessage = "申請結果を確認してください。";
    if (!approve && rejectionReason != null && !rejectionReason.isBlank()) {
      decisionMessage += " 却下理由: " + rejectionReason.trim();
      afterAudit += ";rejection_reason=" + rejectionReason.trim();
    }

    notificationService.notify(((Number) row.get("requested_by")).longValue(), "ATTENDANCE_ADJUSTMENT_DECISION",
        approve ? "打刻修正が承認されました" : "打刻修正が却下されました", decisionMessage, "/app/attendance/history");
    AuditService.record(actor.getId(), approve ? "APPROVE_ATTENDANCE_ADJUSTMENT" : "REJECT_ATTENDANCE_ADJUSTMENT",
        "ATTENDANCE_ADJUSTMENT", String.valueOf(requestId), beforeAudit, afterAudit);
  }

  private String attendanceAuditValue(Object clockIn, Object clockOut, Object status) {
    return "clock_in=" + clockIn + ";clock_out=" + clockOut + ";status=" + status;
  }

  private BigDecimal number(String value) {
    try { return value == null || value.isBlank() ? null : new BigDecimal(value); }
    catch (NumberFormatException e) { return null; }
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

  private void assertScope(User actor, long branch, long department) {
    if (!actor.isHr() && (actor.getBranchId() != branch || actor.getDepartmentId() != department)) {
      throw new SecurityException("担当外のデータです。");
    }
  }
}
