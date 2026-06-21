package service;

import dao.Sql;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import model.User;

public class EmployeeService {
  private final NotificationService notificationService = new NotificationService();

  public List<Map<String, Object>> findEmployees(User viewer) {
    return Sql.query("SELECT u.id,u.employee_number,u.name,u.email,u.hire_date,u.role,u.active,u.branch_id,u.department_id,u.employment_type_id,b.name branch,d.name department,e.name employment "
        + "FROM users u JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id JOIN employment_types e ON e.id=u.employment_type_id WHERE 1=1"
        + scope(viewer, "u") + " ORDER BY u.employee_number", scopeArgs(viewer));
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
    notificationService.notify(id, "INVITATION", "アカウントが登録されました", "初期パスワードを変更して利用してください。", "/app/account");
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

  public void assertCanManage(User actor, long targetId) {
    if (actor.isHr() || actor.getId() == targetId) return;
    Map<String, Object> target = Sql.one("SELECT branch_id,department_id FROM users WHERE id=?", targetId);
    if (target.isEmpty()) throw new IllegalArgumentException("対象の従業員が見つかりません。");
    requireManager(actor);
    assertScope(actor, ((Number) target.get("branch_id")).longValue(), ((Number) target.get("department_id")).longValue());
  }

  public void assertScope(User actor, long branch, long department) {
    if (!actor.isHr() && (actor.getBranchId() != branch || actor.getDepartmentId() != department)) {
      throw new SecurityException("担当外のデータです。");
    }
  }

  // スコープ・権限ヘルパー
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

  private void requireManager(User user) {
    if (!user.isManager() && !user.isHr() && !isActiveDelegate(user)) throw new SecurityException("承認権限がありません。");
  }

  private boolean isActiveDelegate(User user) {
    return !Sql.query("SELECT d.id FROM delegations d JOIN users m ON m.id=d.manager_id WHERE d.delegate_id=? AND d.active=TRUE AND CURRENT_DATE BETWEEN d.starts_on AND d.ends_on AND m.branch_id=? AND m.department_id=?",
        user.getId(), user.getBranchId(), user.getDepartmentId()).isEmpty();
  }
}
