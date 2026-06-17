package service;

import dao.Sql;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import model.User;

public class PortalService {
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
    result.put("leave", Sql.one("SELECT days_remaining AS metric_value FROM leave_balances WHERE user_id=?", user.getId()).getOrDefault("metric_value", 0));
    result.put("monthHours", monthHours(user));
    return result;
  }

  public List<Map<String, Object>> chart(User user) {
    String filter = user.isHr() ? "" : user.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND u.id=?";
    Object[] args = user.isHr() ? new Object[]{} : user.isManager()
        ? new Object[]{user.getBranchId(), user.getDepartmentId()} : new Object[]{user.getId()};
    return Sql.query("SELECT FORMATDATETIME(a.work_date,'yyyy-MM') AS month_label,"
        + "ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN DATEDIFF('MINUTE',a.clock_in,a.clock_out)/60.0 ELSE 0 END),1) AS total_hours,"
        + "ROUND(SUM(CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-480)/60.0 ELSE 0 END),1) AS overtime_hours "
        + "FROM attendance a JOIN users u ON u.id=a.user_id WHERE a.work_date>=DATEADD('MONTH',-5,CURRENT_DATE)" + filter
        + " GROUP BY FORMATDATETIME(a.work_date,'yyyy-MM') ORDER BY month_label", args);
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

  public List<Map<String, Object>> workTypes() {
    return Sql.query("SELECT * FROM work_types WHERE active=TRUE ORDER BY id");
  }

  public void saveShift(User actor, long userId, LocalDate date, String type, String status, String note) {
    assertCanManage(actor, userId);
    if (!workTypes().stream().anyMatch(r -> type.equals(r.get("code")))) throw new IllegalArgumentException("勤務区分が不正です。");
    Sql.update("MERGE INTO shifts(user_id,work_date,work_type_code,status,note,updated_by,updated_at) KEY(user_id,work_date) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
        userId, date, type, status, note, actor.getId());
    AuditService.record(actor.getId(), "SAVE_SHIFT", "SHIFT", userId + ":" + date, null, type + "/" + status);
  }

  public void confirmMonth(User actor, YearMonth month) {
    requireManager(actor);
    String filter = actor.isHr() ? "" : " AND user_id IN(SELECT id FROM users WHERE branch_id=? AND department_id=?)";
    Object[] args = actor.isHr() ? new Object[]{month.atDay(1), month.atEndOfMonth()}
        : new Object[]{month.atDay(1), month.atEndOfMonth(), actor.getBranchId(), actor.getDepartmentId()};
    Sql.update("UPDATE shifts SET status='CONFIRMED',updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE work_date BETWEEN ? AND ?" + filter,
        join(new Object[]{actor.getId()}, args));
    AuditService.record(actor.getId(), "CONFIRM_SHIFTS", "SHIFT_MONTH", month.toString(), null, "CONFIRMED");
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
    if (approve) saveShift(actor, ((Number) row.get("user_id")).longValue(), toDate(row.get("work_date")),
        String.valueOf(row.get("requested_work_type")), "CONFIRMED", "変更申請 #" + requestId);
    Sql.update("UPDATE shift_change_requests SET status=?,decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED", actor.getId(), requestId);
    notify(((Number) row.get("user_id")).longValue(), "SHIFT_CHANGE_DECISION",
        approve ? "シフト変更が承認されました" : "シフト変更が却下されました",
        row.get("work_date") + "の申請結果を確認してください。", "/app/shifts/history");
    AuditService.record(actor.getId(), approve ? "APPROVE_SHIFT_CHANGE" : "REJECT_SHIFT_CHANGE", "SHIFT_CHANGE", String.valueOf(requestId), "PENDING", approve ? "APPROVED" : "REJECTED");
  }

  public List<Map<String, Object>> shiftWarnings(User viewer, YearMonth month) {
    String filter = viewer.isHr() ? "" : " AND u.branch_id=? AND u.department_id=?";
    Object[] scope = viewer.isHr() ? new Object[]{} : new Object[]{viewer.getBranchId(), viewer.getDepartmentId()};
    List<Map<String, Object>> result = new java.util.ArrayList<>();
    result.addAll(Sql.query("SELECT 'STAFF_SHORTAGE' warning,s.work_date,wt.name_ja detail,wt.required_staff required,COUNT(s.id) actual "
        + "FROM work_types wt LEFT JOIN shifts s ON s.work_type_code=wt.code AND s.work_date BETWEEN ? AND ? LEFT JOIN users u ON u.id=s.user_id "
        + "WHERE wt.required_staff>0" + filter + " GROUP BY s.work_date,wt.name_ja,wt.required_staff HAVING COUNT(s.id)<wt.required_staff",
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scope)));
    result.addAll(Sql.query("SELECT 'NIGHT_REST' warning,s2.work_date,u.name detail,0 required,0 actual FROM shifts s1 JOIN shifts s2 ON s2.user_id=s1.user_id AND s2.work_date=DATEADD('DAY',1,s1.work_date) JOIN users u ON u.id=s1.user_id WHERE s1.work_type_code='NIGHT' AND s2.work_type_code NOT IN('OFF','LEAVE') AND s1.work_date BETWEEN ? AND ?" + filter,
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scope)));
    return result;
  }

  public List<Map<String, Object>> leaveRequests(User viewer) {
    String ownOrScope = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND l.user_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    return Sql.query("SELECT l.*,u.name,u.employee_number FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE 1=1"
        + ownOrScope + " ORDER BY l.created_at DESC", args);
  }

  public Map<String, Object> leaveBalance(long userId) {
    return Sql.one("SELECT days_remaining,hourly_used,40-hourly_used hourly_remaining,last_granted_on FROM leave_balances WHERE user_id=?", userId);
  }

  public void requestLeave(User user, LocalDate date, String unit, Integer hours, String reason) {
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("理由を入力してください。");
    BigDecimal days = (BigDecimal) leaveBalance(user.getId()).getOrDefault("days_remaining", BigDecimal.ZERO);
    double needed = "FULL".equals(unit) ? 1 : ("AM".equals(unit) || "PM".equals(unit)) ? .5 : (hours == null ? 0 : hours / 8.0);
    if (days.doubleValue() < needed) throw new IllegalArgumentException("有休残数が不足しています。");
    if ("HOURLY".equals(unit)) {
      int used = ((Number) leaveBalance(user.getId()).getOrDefault("hourly_used", 0)).intValue();
      if (hours == null || hours < 1 || used + hours > 40) throw new IllegalArgumentException("時間単位有休の上限を超えています。");
    }
    long id = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason) VALUES(?,?,?,?,?)",
        user.getId(), date, unit, hours, reason.trim());
    notifyManagers(user, "LEAVE_REQUEST", "有休申請", user.getName() + "さんから有休申請があります。", "/app/leave/approvals");
    AuditService.record(user.getId(), "REQUEST_LEAVE", "LEAVE_REQUEST", String.valueOf(id), null, unit + ":" + date);
  }

  public void decideLeave(User actor, long requestId, boolean approve) {
    requireManager(actor);
    Map<String, Object> request = Sql.one("SELECT l.*,u.branch_id,u.department_id,u.name FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.id=?", requestId);
    if (request.isEmpty()) throw new IllegalArgumentException("申請が見つかりません。");
    assertScope(actor, ((Number) request.get("branch_id")).longValue(), ((Number) request.get("department_id")).longValue());
    if (!"PENDING".equals(request.get("status"))) throw new IllegalArgumentException("処理済みの申請です。");
    if (approve) {
      double days = "FULL".equals(request.get("leave_unit")) ? 1 : ("HOURLY".equals(request.get("leave_unit"))
          ? ((Number) request.get("hours")).doubleValue() / 8.0 : .5);
      int hours = "HOURLY".equals(request.get("leave_unit")) ? ((Number) request.get("hours")).intValue() : 0;
      Sql.update("UPDATE leave_balances SET days_remaining=days_remaining-?,hourly_used=hourly_used+? WHERE user_id=? AND days_remaining>=?",
          days, hours, request.get("user_id"), days);
    }
    Sql.update("UPDATE leave_requests SET status=?,decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED", actor.getId(), requestId);
    long userId = ((Number) request.get("user_id")).longValue();
    notify(userId, "LEAVE_DECISION", approve ? "有休申請が承認されました" : "有休申請が却下されました",
        request.get("leave_date") + "の申請結果を確認してください。", "/app/leave/history");
    AuditService.record(actor.getId(), approve ? "APPROVE_LEAVE" : "REJECT_LEAVE", "LEAVE_REQUEST", String.valueOf(requestId), "PENDING", approve ? "APPROVED" : "REJECTED");
  }

  public List<Map<String, Object>> attendance(User viewer, YearMonth month) {
    String filter = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND a.user_id=?";
    Object[] scope = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    return Sql.query("SELECT a.*,u.name,u.employee_number,s.work_type_code,"
        + "CASE WHEN a.clock_in IS NOT NULL AND s.work_type_code='DAY' AND CAST(a.clock_in AS TIME)>TIME '08:00:00' THEN TRUE ELSE FALSE END late,"
        + "CASE WHEN a.clock_out IS NOT NULL AND s.work_type_code='DAY' AND CAST(a.clock_out AS TIME)<TIME '17:00:00' THEN TRUE ELSE FALSE END early,"
        + "CASE WHEN a.clock_in IS NOT NULL AND a.clock_out IS NOT NULL THEN GREATEST(0,DATEDIFF('MINUTE',a.clock_in,a.clock_out)-CASE WHEN s.work_type_code='NIGHT' THEN 780 ELSE 480 END) ELSE 0 END overtime_minutes "
        + "FROM attendance a JOIN users u ON u.id=a.user_id LEFT JOIN shifts s ON s.user_id=a.user_id AND s.work_date=a.work_date "
        + "WHERE a.work_date BETWEEN ? AND ?" + filter + " ORDER BY a.work_date DESC,u.name",
        join(new Object[]{month.atDay(1), month.atEndOfMonth()}, scope));
  }

  public void clock(User user, boolean clockIn, String lat, String lng, String locationStatus) {
    LocalDate workDate = LocalDate.now();
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
    assertScope(actor, ((Number) row.get("branch_id")).longValue(), ((Number) row.get("department_id")).longValue());
    Sql.update("UPDATE attendance SET finalized=? WHERE id=?", finalized, attendanceId);
    AuditService.record(actor.getId(), finalized ? "FINALIZE_ATTENDANCE" : "REOPEN_ATTENDANCE", "ATTENDANCE", String.valueOf(attendanceId), null, String.valueOf(finalized));
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
    AuditService.record(user.getId(), "REQUEST_ATTENDANCE_ADJUSTMENT", "ATTENDANCE_ADJUSTMENT", String.valueOf(id), null, reason);
  }

  public void decideAttendanceAdjustment(User actor, long requestId, boolean approve) {
    requireManager(actor);
    Map<String, Object> row = Sql.one("SELECT r.*,u.branch_id,u.department_id FROM attendance_adjustments r JOIN users u ON u.id=r.requested_by WHERE r.id=?", requestId);
    if (row.isEmpty() || !"PENDING".equals(row.get("status"))) throw new IllegalArgumentException("未処理の申請が見つかりません。");
    assertScope(actor, ((Number) row.get("branch_id")).longValue(), ((Number) row.get("department_id")).longValue());
    if (approve) Sql.update("UPDATE attendance SET clock_in=?,clock_out=?,status=CASE WHEN ? IS NOT NULL AND ? IS NOT NULL THEN 'COMPLETE' ELSE 'OPEN' END WHERE id=?",
        row.get("requested_in"), row.get("requested_out"), row.get("requested_in"), row.get("requested_out"), row.get("attendance_id"));
    Sql.update("UPDATE attendance_adjustments SET status=?,decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED", actor.getId(), requestId);
    notify(((Number) row.get("requested_by")).longValue(), "ATTENDANCE_ADJUSTMENT_DECISION",
        approve ? "打刻修正が承認されました" : "打刻修正が却下されました", "申請結果を確認してください。", "/app/attendance/history");
    AuditService.record(actor.getId(), approve ? "APPROVE_ATTENDANCE_ADJUSTMENT" : "REJECT_ATTENDANCE_ADJUSTMENT", "ATTENDANCE_ADJUSTMENT", String.valueOf(requestId), "PENDING", approve ? "APPROVED" : "REJECTED");
  }

  public List<Map<String, Object>> notifications(User user) {
    return Sql.query("SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC", user.getId());
  }

  public void markNotificationsRead(User user) {
    Sql.update("UPDATE notifications SET is_read=TRUE WHERE user_id=?", user.getId());
  }

  public List<Map<String, Object>> master(String type) {
    String table = switch (type) {
      case "branches" -> "branches"; case "departments" -> "departments";
      case "employment" -> "employment_types"; default -> "work_types";
    };
    return Sql.query("SELECT * FROM " + table + " ORDER BY id");
  }

  public List<Map<String, Object>> qualifications(User viewer) {
    return Sql.query("SELECT q.id,q.user_id,q.name qualification_name,q.expires_on,u.name employee_name,u.employee_number FROM qualifications q JOIN users u ON u.id=q.user_id WHERE 1=1"
        + scope(viewer, "u") + " ORDER BY q.expires_on,u.name", scopeArgs(viewer));
  }

  public List<Map<String, Object>> delegations(User viewer) {
    String filter = viewer.isHr() ? "" : " WHERE m.branch_id=? AND m.department_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : new Object[]{viewer.getBranchId(), viewer.getDepartmentId()};
    return Sql.query("SELECT d.*,m.name manager_name,u.name delegate_name FROM delegations d JOIN users m ON m.id=d.manager_id JOIN users u ON u.id=d.delegate_id" + filter + " ORDER BY d.starts_on DESC", args);
  }

  public List<Map<String, Object>> audit(User user) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ利用できます。");
    return Sql.query("SELECT a.*,u.name actor_name FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id ORDER BY a.created_at DESC LIMIT 300");
  }

  public void addQualification(User actor, long userId, String name, LocalDate expires) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    long id = Sql.insert("INSERT INTO qualifications(user_id,name,expires_on) VALUES(?,?,?)", userId, name, expires);
    AuditService.record(actor.getId(), "ADD_QUALIFICATION", "QUALIFICATION", String.valueOf(id), null, name);
  }

  public void addDelegation(User actor, long delegateId, LocalDate start, LocalDate end) {
    requireManager(actor);
    assertCanManage(actor, delegateId);
    long id = Sql.insert("INSERT INTO delegations(manager_id,delegate_id,starts_on,ends_on) VALUES(?,?,?,?)", actor.getId(), delegateId, start, end);
    AuditService.record(actor.getId(), "ADD_DELEGATION", "DELEGATION", String.valueOf(id), null, delegateId + ":" + start + ":" + end);
  }

  public void addEmployee(User actor, String number, String name, String email, LocalDate hireDate,
      long branch, long department, long employment, String role, String baseUrl) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String hash = util.PasswordUtil.hash("Password1!");
    long id = Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role) VALUES(?,?,?,?,?,?,?,?,?)",
        number, name, email, hash, hireDate, branch, department, employment, role);
    Sql.update("INSERT INTO leave_balances(user_id,days_remaining,hourly_used,last_granted_on) VALUES(?,10,0,CURRENT_DATE)", id);
    notify(id, "INVITATION", "アカウントが登録されました", "初期パスワードを変更して利用してください。", "/app/account");
    new AccountTokenService().issue(email, "INVITE", baseUrl);
    AuditService.record(actor.getId(), "CREATE_USER", "USER", String.valueOf(id), null, number + ":" + role);
  }

  public void updateEmployee(User actor, long id, String number, String name, String email, LocalDate hireDate,
      long branch, long department, long employment, String role, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    Map<String, Object> before = Sql.one("SELECT employee_number,name,email,role,active FROM users WHERE id=?", id);
    if (before.isEmpty()) throw new IllegalArgumentException("従業員が見つかりません。");
    Sql.update("UPDATE users SET employee_number=?,name=?,email=?,hire_date=?,branch_id=?,department_id=?,employment_type_id=?,role=?,active=? WHERE id=?",
        number, name, email, hireDate, branch, department, employment, role, active, id);
    AuditService.record(actor.getId(), "UPDATE_USER", "USER", String.valueOf(id), before.toString(), number + ":" + name + ":" + role + ":" + active);
  }

  public void addMaster(User actor, String type, String name) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String table = switch (type) { case "branches" -> "branches"; case "departments" -> "departments"; case "employment" -> "employment_types"; default -> throw new IllegalArgumentException("マスタ種別が不正です。"); };
    long id = Sql.insert("INSERT INTO " + table + "(name) VALUES(?)", name);
    AuditService.record(actor.getId(), "CREATE_MASTER", table, String.valueOf(id), null, name);
  }

  public void toggleMaster(User actor, String type, long id, boolean active) {
    if (!actor.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    String table = switch (type) { case "branches" -> "branches"; case "departments" -> "departments"; case "employment" -> "employment_types"; default -> throw new IllegalArgumentException("マスタ種別が不正です。"); };
    Sql.update("UPDATE " + table + " SET active=? WHERE id=?", active, id);
    AuditService.record(actor.getId(), "TOGGLE_MASTER", table, String.valueOf(id), null, String.valueOf(active));
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
    List<Map<String, Object>> managers = Sql.query("SELECT id FROM users WHERE active=TRUE AND ((role='MANAGER' AND branch_id=? AND department_id=?) OR role='HR')", user.getBranchId(), user.getDepartmentId());
    for (Map<String, Object> manager : managers) notify(((Number) manager.get("id")).longValue(), type, title, message, url);
  }

  private Object value(String sql, Object... args) { return Sql.one(sql, args).getOrDefault("metric_value", 0); }

  private double monthHours(User user) {
    Map<String, Object> row = Sql.one("SELECT COALESCE(SUM(CASE WHEN clock_in IS NOT NULL AND clock_out IS NOT NULL THEN DATEDIFF('MINUTE',clock_in,clock_out) ELSE 0 END),0) AS metric_value FROM attendance WHERE user_id=? AND work_date BETWEEN ? AND ?",
        user.getId(), YearMonth.now().atDay(1), YearMonth.now().atEndOfMonth());
    return ((Number) row.getOrDefault("metric_value", 0)).doubleValue() / 60.0;
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
    requireManager(actor);
    assertScope(actor, ((Number) target.get("branch_id")).longValue(), ((Number) target.get("department_id")).longValue());
  }

  private void assertScope(User actor, long branch, long department) {
    if (!actor.isHr() && (actor.getBranchId() != branch || actor.getDepartmentId() != department)) {
      throw new SecurityException("担当外のデータです。");
    }
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
}
