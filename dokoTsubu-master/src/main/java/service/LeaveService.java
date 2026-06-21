package service;

import dao.Sql;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import model.User;
import static util.DateUtil.*;

public class LeaveService {
  private final LeaveLedgerService leaveLedger = new LeaveLedgerService();
  private final SettingsService settings = new SettingsService();

  public List<Map<String, Object>> leaveRequests(User viewer) {
    String ownOrScope = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=?" : " AND l.user_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId()} : new Object[]{viewer.getId()};
    List<Map<String, Object>> rows = Sql.query("SELECT l.*,u.name,u.employee_number,u.role applicant_role,u.branch_id,u.department_id FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE 1=1"
        + ownOrScope + " ORDER BY l.created_at DESC", args);
    for (Map<String, Object> row : rows) row.put("can_decide_leave", canDecideLeave(viewer, row));
    return rows;
  }

  public List<Map<String, Object>> leaveApprovers(User user) {
    return leaveApproverRows(user.getId(), user.getRole(), user.getBranchId(), user.getDepartmentId());
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
    validateLeaveRequestDate(date);
    validateLeaveBalance(user.getId(), date, unit, hours);

    long id = Sql.insert("INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason) VALUES(?,?,?,?,?)",
        user.getId(), date, unit, hours, reason == null ? "" : reason.trim());
    NotificationService notificationService = new NotificationService();
    notificationService.notifyLeaveApprovers(user, "LEAVE_REQUEST", "有休申請", user.getName() + "さんから有休申請があります。", "/app/leave/approvals");
    AuditService.record(user.getId(), "REQUEST_LEAVE", "LEAVE_REQUEST", String.valueOf(id), null, unit + ":" + date);
  }

  private void validateLeaveRequestDate(LocalDate date) {
    if (!settings.bool("LEAVE_ALLOW_PAST", false) && date.isBefore(LocalDate.now())) {
      throw new IllegalArgumentException("過去日の有休は申請できません。");
    }
    if (date.isBefore(LocalDate.now().plusDays(settings.integer("LEAVE_MIN_NOTICE_DAYS", 0)))) {
      throw new IllegalArgumentException("有休申請の事前期限を満たしていません。");
    }
  }

  private void validateLeaveBalance(long userId, LocalDate date, String unit, Integer hours) {
    Map<String, Object> balance = leaveBalance(userId);
    BigDecimal days = (BigDecimal) balance.getOrDefault("days_remaining", BigDecimal.ZERO);
    int hoursPerDay = ((Number) balance.getOrDefault("hours_per_day", 8)).intValue();
    double needed = "FULL".equals(unit) ? 1 : ("AM".equals(unit) || "PM".equals(unit)) ? .5 : (hours == null ? 0 : hours / (double) hoursPerDay);
    if (days.doubleValue() < needed) {
      throw new IllegalArgumentException("有効期限内の有休残数が不足しています。");
    }
    if ("HOURLY".equals(unit)) {
      int remainingHours = ((Number) balance.getOrDefault("hourly_remaining", 0)).intValue();
      if (hours == null || hours < 1 || hours > remainingHours) {
        throw new IllegalArgumentException("時間単位有休の年間上限を超えています。");
      }
    }
  }

  public void decideLeave(User actor, long requestId, boolean approve) {
    decideLeave(actor, requestId, approve, null);
  }

  public void decideLeave(User actor, long requestId, boolean approve, String rejectionReason) {
    requireManager(actor);
    Map<String, Object> request = Sql.one("SELECT l.*,u.role applicant_role,u.branch_id,u.department_id,u.name FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.id=?", requestId);
    if (request.isEmpty()) throw new IllegalArgumentException("申請が見つかりません。");
    assertLeaveApprovalScope(actor, ((Number) request.get("user_id")).longValue(), String.valueOf(request.get("applicant_role")),
        ((Number) request.get("branch_id")).longValue(), ((Number) request.get("department_id")).longValue());
    if (!"PENDING".equals(request.get("status"))) throw new IllegalArgumentException("処理済みの申請です。");
    String decisionMessage = request.get("leave_date") + "の申請結果を確認してください。";
    if (!approve) {
      if (rejectionReason == null || rejectionReason.isBlank()) throw new IllegalArgumentException("却下理由を入力してください。");
      decisionMessage += " 却下理由: " + rejectionReason.trim();
    }
    if (approve) {
      leaveLedger.consume(requestId);
    }
    Sql.update("UPDATE leave_requests SET status=?,decided_by=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
        approve ? "APPROVED" : "REJECTED", actor.getId(), requestId);
    long userId = ((Number) request.get("user_id")).longValue();
    NotificationService notificationService = new NotificationService();
    notificationService.notify(userId, "LEAVE_DECISION", approve ? "有休申請が承認されました" : "有休申請が却下されました",
        decisionMessage, "/app/leave/history");
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
    NotificationService notificationService = new NotificationService();
    notificationService.notifyLeaveApprovers(actor, "LEAVE_CANCELLED", "有休申請の取消", actor.getName() + "さんが有休申請を取り消しました。", "/app/leave/approvals");
    AuditService.record(actor.getId(), "CANCEL_LEAVE", "LEAVE_REQUEST", String.valueOf(requestId), currentStatus, "CANCELLED");
  }

  // 権限・承認者選出関連のヘルパー
  private boolean canDecideLeave(User actor, Map<String, Object> request) {
    if (actor == null || request == null || request.isEmpty()) return false;
    return canDecideLeave(actor, ((Number) request.get("user_id")).longValue(), String.valueOf(request.get("applicant_role")),
        ((Number) request.get("branch_id")).longValue(), ((Number) request.get("department_id")).longValue());
  }

  private boolean canDecideLeave(User actor, long applicantId, String applicantRole, long branch, long department) {
    if (actor.getId() == applicantId) return false;
    return leaveApproverRows(applicantId, applicantRole, branch, department).stream()
        .anyMatch(approver -> ((Number) approver.get("id")).longValue() == actor.getId());
  }

  private void assertLeaveApprovalScope(User actor, long applicantId, String applicantRole, long branch, long department) {
    if (!canDecideLeave(actor, applicantId, applicantRole, branch, department)) throw new SecurityException("担当外のデータです。");
  }

  private List<Map<String, Object>> leaveApproverRows(long applicantId, String applicantRole, long branch, long department) {
    if ("MANAGER".equals(applicantRole)) return leaveApproverCandidates(
        "u.role='HR'", "人事", applicantId);
    if ("HR".equals(applicantRole)) {
      List<Map<String, Object>> sameDepartment = leaveApproverCandidates(
          "u.role='HR' AND u.department_id=?", "人事担当", applicantId, department);
      if (!sameDepartment.isEmpty()) return sameDepartment;
      List<Map<String, Object>> headOfficeManagers = leaveApproverCandidates(
          "u.role='MANAGER' AND u.branch_id=1", "本部管理者", applicantId);
      if (!headOfficeManagers.isEmpty()) return headOfficeManagers;
      return leaveApproverCandidates("u.role='HR'", "人事担当", applicantId);
    }
    return leaveApproverCandidates("u.role='MANAGER' AND u.branch_id=?", "店長", applicantId, branch);
  }

  private List<Map<String, Object>> leaveApproverCandidates(String condition, String approverType, long applicantId, Object... args) {
    Object[] queryArgs = new Object[args.length + 1];
    System.arraycopy(args, 0, queryArgs, 0, args.length);
    queryArgs[args.length] = applicantId;
    return Sql.query("SELECT u.id,u.name,u.role,? approver_type,COUNT(l.id) approval_count FROM users u "
        + "LEFT JOIN leave_requests l ON l.decided_by=u.id AND l.decided_at>=DATEADD('DAY',-90,CURRENT_TIMESTAMP) "
        + "WHERE u.active=TRUE AND " + condition + " AND u.id<>? "
        + "GROUP BY u.id,u.name,u.role ORDER BY approval_count,u.id LIMIT 1",
        join(new Object[]{approverType}, queryArgs));
  }

  private void requireManager(User user) {
    if (!user.isManager() && !user.isHr() && !isActiveDelegate(user)) throw new SecurityException("承認権限がありません。");
  }

  private boolean isActiveDelegate(User user) {
    return !Sql.query("SELECT d.id FROM delegations d JOIN users m ON m.id=d.manager_id WHERE d.delegate_id=? AND d.active=TRUE AND CURRENT_DATE BETWEEN d.starts_on AND d.ends_on AND m.branch_id=? AND m.department_id=?",
        user.getId(), user.getBranchId(), user.getDepartmentId()).isEmpty();
  }

  private Object[] join(Object[] first, Object[] second) {
    Object[] result = new Object[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
}
