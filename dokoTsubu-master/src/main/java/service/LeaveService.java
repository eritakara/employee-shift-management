package service;

import config.Database;
import dao.Sql;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import model.User;
import static util.DateUtil.*;

public class LeaveService {
  private final LeaveLedgerService leaveLedger = new LeaveLedgerService();
  private final SettingsService settings = new SettingsService();

  public List<Map<String, Object>> leaveRequests(User viewer) {
    String ownOrScope = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND l.user_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    List<Map<String, Object>> rows = Sql.query("SELECT l.*,u.name,u.employee_number,u.role applicant_role,u.branch_id,u.department_id FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE 1=1"
        + ownOrScope + " ORDER BY l.created_at DESC", args);
    List<ApproverCandidate> all = loadAllCandidates();
    for (Map<String, Object> row : rows) row.put("can_decide_leave", canDecideLeave(viewer, row, all));
    return rows;
  }

  public List<Map<String, Object>> leaveApprovers(User user) {
    return leaveApproverRows(user.getId(), user.getRole(), user.getBranchId(), user.getDepartmentId());
  }

  public Map<String, Object> leaveBalance(long userId) {
    return leaveLedger.balance(userId, LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
  }

  public List<Map<String, Object>> leaveHistory(User viewer) {
    String filter = viewer.isHr() ? "" : viewer.isManager() ? " AND u.branch_id=? AND u.department_id=?" : " AND h.user_id=?";
    Object[] args = viewer.isHr() ? new Object[]{} : viewer.isManager()
        ? new Object[]{viewer.getBranchId(), viewer.getDepartmentId()} : new Object[]{viewer.getId()};
    return Sql.query("SELECT h.*,u.name,u.employee_number FROM leave_history h JOIN users u ON u.id=h.user_id WHERE 1=1" + filter + " ORDER BY h.event_date DESC,h.id DESC", args);
  }

  public void requestLeave(User user, LocalDate date, String unit, Integer hours, String reason) {
    requestLeave(user, List.of(date), unit, hours, reason);
  }

  public void requestLeave(User user, List<LocalDate> dates, String unit, Integer hours, String reason) {
    List<LocalDate> selectedDates = normalizeLeaveDates(dates);
    validateLeaveUnit(unit, hours);
    for (LocalDate date : selectedDates) validateLeaveRequestDate(date);
    validateNoActiveLeaveRequests(user.getId(), selectedDates);
    validateLeaveBalance(user.getId(), selectedDates, unit, hours);

    List<Long> ids = insertLeaveRequests(user.getId(), selectedDates, unit, hours, reason);
    NotificationService notificationService = new NotificationService();
    notificationService.notifyLeaveApprovers(user, "LEAVE_REQUEST", "有休申請", user.getName() + "さんから有休申請があります。", "/app/leave/approvals");
    for (int i = 0; i < ids.size(); i++) {
      AuditService.record(user.getId(), "REQUEST_LEAVE", "LEAVE_REQUEST", String.valueOf(ids.get(i)), null, unit + ":" + selectedDates.get(i));
    }
  }

  private List<LocalDate> normalizeLeaveDates(List<LocalDate> dates) {
    if (dates == null || dates.isEmpty()) throw new IllegalArgumentException("取得日を選択してください。");
    List<LocalDate> selectedDates = new ArrayList<>(new LinkedHashSet<>(dates));
    Collections.sort(selectedDates);
    if (selectedDates.isEmpty()) throw new IllegalArgumentException("取得日を選択してください。");
    return selectedDates;
  }

  private void validateLeaveUnit(String unit, Integer hours) {
    if (!List.of("FULL", "AM", "PM", "HOURLY").contains(unit)) throw new IllegalArgumentException("取得単位を選択してください。");
    if ("HOURLY".equals(unit) && (hours == null || hours < 1)) throw new IllegalArgumentException("時間数を入力してください。");
  }

  private void validateNoActiveLeaveRequests(long userId, List<LocalDate> dates) {
    for (LocalDate date : dates) {
      if (!Sql.query("SELECT id FROM leave_requests WHERE user_id=? AND leave_date=? AND status IN ('PENDING','APPROVED')", userId, date).isEmpty()) {
        throw new IllegalArgumentException(date + " は申請中または承認済みの有休申請があります。");
      }
    }
  }

  private List<Long> insertLeaveRequests(long userId, List<LocalDate> dates, String unit, Integer hours, String reason) {
    String normalizedReason = reason == null ? "" : reason.trim();
    List<Long> ids = new ArrayList<>();
    try (Connection c = Database.getConnection();
         PreparedStatement p = c.prepareStatement(
             "INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason) VALUES(?,?,?,?,?)",
             Statement.RETURN_GENERATED_KEYS)) {
      c.setAutoCommit(false);
      try {
        for (LocalDate date : dates) {
          p.setLong(1, userId);
          p.setObject(2, date);
          p.setString(3, unit);
          p.setObject(4, hours);
          p.setString(5, normalizedReason);
          p.executeUpdate();
          try (ResultSet rs = p.getGeneratedKeys()) {
            ids.add(rs.next() ? rs.getLong(1) : 0);
          }
        }
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("有休申請の登録に失敗しました。", e);
    }
    return ids;
  }

  private void validateLeaveRequestDate(LocalDate date) {
    if (!settings.bool("LEAVE_ALLOW_PAST", false) && date.isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")))) {
      throw new IllegalArgumentException("過去日の有休は申請できません。");
    }
    if (date.isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).plusDays(settings.integer("LEAVE_MIN_NOTICE_DAYS", 0)))) {
      throw new IllegalArgumentException("有休申請の事前期限を満たしていません。");
    }
  }

  private void validateLeaveBalance(long userId, LocalDate date, String unit, Integer hours) {
    validateLeaveBalance(userId, List.of(date), unit, hours);
  }

  private void validateLeaveBalance(long userId, List<LocalDate> dates, String unit, Integer hours) {
    Map<String, Object> balance = leaveBalance(userId);
    BigDecimal days = (BigDecimal) balance.getOrDefault("days_remaining", BigDecimal.ZERO);
    int hoursPerDay = ((Number) balance.getOrDefault("hours_per_day", 8)).intValue();
    BigDecimal neededPerDay = "FULL".equals(unit) ? BigDecimal.ONE
        : ("AM".equals(unit) || "PM".equals(unit)) ? new BigDecimal("0.5")
        : BigDecimal.valueOf(hours == null ? 0 : hours)
            .divide(BigDecimal.valueOf(hoursPerDay), 3, java.math.RoundingMode.HALF_UP);
    BigDecimal needed = neededPerDay.multiply(BigDecimal.valueOf(dates.size()));
    if (days.compareTo(needed) < 0) {
      throw new IllegalArgumentException("有効期限内の有休残数が不足しています。");
    }
    if ("HOURLY".equals(unit)) {
      int remainingHours = ((Number) balance.getOrDefault("hourly_remaining", 0)).intValue();
      int totalHours = (hours == null ? 0 : hours) * dates.size();
      if (hours == null || hours < 1 || totalHours > remainingHours) {
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
      
      // 有休承認時のシフト上書きと警告処理
      LocalDate leaveDate = toDate(request.get("leave_date"));
      long userId = ((Number) request.get("user_id")).longValue();
      String leaveUnit = String.valueOf(request.get("leave_unit"));
      String leaveType = switch (leaveUnit) {
        case "FULL" -> "LEAVE";
        case "AM" -> "AM_LEAVE";
        case "PM" -> "PM_LEAVE";
        default -> null;
      };
      
      if (leaveType != null) {
        Map<String, Object> existingShift = Sql.one("SELECT id, work_type_code, status FROM shifts WHERE user_id=? AND work_date=?", userId, leaveDate);
        if (!existingShift.isEmpty()) {
          String currentTypeCode = String.valueOf(existingShift.get("work_type_code"));
          if (!leaveType.equals(currentTypeCode)) {
            Sql.update("UPDATE shifts SET work_type_code=?, updated_by=?, updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND work_date=?",
                leaveType, actor.getId(), userId, leaveDate);
            
            // 元のシフトが勤務（DAY/NIGHT）だった場合、必要人員の不足が発生する可能性があるため管理者に警告通知を送信する
            if (List.of("DAY", "NIGHT").contains(currentTypeCode)) {
              NotificationService warningNotification = new NotificationService();
              warningNotification.notify(actor.getId(), "SHIFT_RECHECK", "有休承認に伴うシフト再調整警告",
                  request.get("name") + "さんの有休承認に伴い、" + leaveDate + " のシフトが有休に更新されました。人員不足等の警告がないか確認し、必要に応じて再調整を行ってください。",
                  "/app/shifts/manage?month=" + java.time.YearMonth.from(leaveDate));
            }
          }
        } else {
          // シフトがまだ登録されていなければ、有休シフトを新規登録する
          Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by,updated_at) VALUES(?,?,?,'DRAFT',?,CURRENT_TIMESTAMP)",
              userId, leaveDate, leaveType, actor.getId());
        }
      }
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
    if (!leaveDate.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")))) throw new IllegalArgumentException("当日・過去日の有休は取り消せません。");
    if ("APPROVED".equals(currentStatus)) leaveLedger.restore(requestId, LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
    Sql.update("UPDATE leave_requests SET status='CANCELLED' WHERE id=?", requestId);
    NotificationService notificationService = new NotificationService();
    notificationService.notifyLeaveApprovers(actor, "LEAVE_CANCELLED", "有休申請の取消", actor.getName() + "さんが有休申請を取り消しました。", "/app/leave/approvals");
    AuditService.record(actor.getId(), "CANCEL_LEAVE", "LEAVE_REQUEST", String.valueOf(requestId), currentStatus, "CANCELLED");
  }

  private static class ApproverCandidate {
    long id;
    String name;
    String role;
    long branchId;
    long departmentId;
    int approvalCount;

    ApproverCandidate(Map<String, Object> row) {
      this.id = ((Number) row.get("id")).longValue();
      this.name = String.valueOf(row.get("name"));
      this.role = String.valueOf(row.get("role"));
      this.branchId = ((Number) row.get("branch_id")).longValue();
      this.departmentId = ((Number) row.get("department_id")).longValue();
      this.approvalCount = ((Number) row.get("approval_count")).intValue();
    }
  }

  private List<ApproverCandidate> loadAllCandidates() {
    String dateadd = config.Database.isPostgres() ? "(CURRENT_TIMESTAMP - INTERVAL '90 day')" : "DATEADD('DAY',-90,CURRENT_TIMESTAMP)";
    List<Map<String, Object>> rows = Sql.query("SELECT u.id, u.name, u.role, u.branch_id, u.department_id, COUNT(l.id) approval_count "
        + "FROM users u "
        + "LEFT JOIN leave_requests l ON l.decided_by=u.id AND l.decided_at>=" + dateadd + " "
        + "WHERE u.active=TRUE "
        + "GROUP BY u.id, u.name, u.role, u.branch_id, u.department_id");
    List<ApproverCandidate> list = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      list.add(new ApproverCandidate(row));
    }
    return list;
  }

  private List<Map<String, Object>> findCandidate(List<ApproverCandidate> all, String approverType, long applicantId, java.util.function.Predicate<ApproverCandidate> filter) {
    ApproverCandidate best = null;
    for (ApproverCandidate c : all) {
      if (c.id == applicantId) continue;
      if (filter.test(c)) {
        if (best == null) {
          best = c;
        } else {
          int cmp = Integer.compare(c.approvalCount, best.approvalCount);
          if (cmp < 0 || (cmp == 0 && c.id < best.id)) {
            best = c;
          }
        }
      }
    }
    if (best == null) return Collections.emptyList();
    Map<String, Object> map = new java.util.HashMap<>();
    map.put("id", best.id);
    map.put("name", best.name);
    map.put("role", best.role);
    map.put("approver_type", approverType);
    map.put("approval_count", best.approvalCount);
    return List.of(map);
  }

  // 権限・承認者選出関連のヘルパー
  private boolean canDecideLeave(User actor, Map<String, Object> request) {
    if (actor == null || request == null || request.isEmpty()) return false;
    return canDecideLeave(actor, ((Number) request.get("user_id")).longValue(), String.valueOf(request.get("applicant_role")),
        ((Number) request.get("branch_id")).longValue(), ((Number) request.get("department_id")).longValue(), loadAllCandidates());
  }

  private boolean canDecideLeave(User actor, Map<String, Object> request, List<ApproverCandidate> all) {
    if (actor == null || request == null || request.isEmpty()) return false;
    return canDecideLeave(actor, ((Number) request.get("user_id")).longValue(), String.valueOf(request.get("applicant_role")),
        ((Number) request.get("branch_id")).longValue(), ((Number) request.get("department_id")).longValue(), all);
  }

  private boolean canDecideLeave(User actor, long applicantId, String applicantRole, long branch, long department) {
    return canDecideLeave(actor, applicantId, applicantRole, branch, department, loadAllCandidates());
  }

  private boolean canDecideLeave(User actor, long applicantId, String applicantRole, long branch, long department, List<ApproverCandidate> all) {
    if (actor.getId() == applicantId) return false;
    return leaveApproverRows(applicantId, applicantRole, branch, department, all).stream()
        .anyMatch(approver -> ((Number) approver.get("id")).longValue() == actor.getId());
  }

  private void assertLeaveApprovalScope(User actor, long applicantId, String applicantRole, long branch, long department) {
    if (!canDecideLeave(actor, applicantId, applicantRole, branch, department)) throw new SecurityException("担当外のデータです。");
  }

  private List<Map<String, Object>> leaveApproverRows(long applicantId, String applicantRole, long branch, long department) {
    return leaveApproverRows(applicantId, applicantRole, branch, department, loadAllCandidates());
  }

  private List<Map<String, Object>> leaveApproverRows(long applicantId, String applicantRole, long branch, long department, List<ApproverCandidate> all) {
    if ("MANAGER".equals(applicantRole)) return findCandidate(all, "人事", applicantId, c -> "HR".equals(c.role));
    if ("HR".equals(applicantRole)) {
      List<Map<String, Object>> sameDepartment = findCandidate(all, "人事担当", applicantId, c -> "HR".equals(c.role) && c.departmentId == department);
      if (!sameDepartment.isEmpty()) return sameDepartment;
      List<Map<String, Object>> headOfficeManagers = findCandidate(all, "本部管理者", applicantId, c -> "MANAGER".equals(c.role) && c.branchId == 1);
      if (!headOfficeManagers.isEmpty()) return headOfficeManagers;
      return findCandidate(all, "人事担当", applicantId, c -> "HR".equals(c.role));
    }
    return findCandidate(all, "店長", applicantId, c -> "MANAGER".equals(c.role) && c.branchId == branch);
  }

  private void requireManager(User user) {
    if (!user.isManager() && !user.isHr() && !isActiveDelegate(user)) throw new SecurityException("承認権限がありません。");
  }

  private boolean isActiveDelegate(User user) {
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"));
    return !Sql.query("SELECT d.id FROM delegations d JOIN users m ON m.id=d.manager_id WHERE d.delegate_id=? AND d.active=TRUE AND ? BETWEEN d.starts_on AND d.ends_on AND m.branch_id=? AND m.department_id=?",
        user.getId(), today, user.getBranchId(), user.getDepartmentId()).isEmpty();
  }

  private Object[] join(Object[] first, Object[] second) {
    Object[] result = new Object[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }
}
