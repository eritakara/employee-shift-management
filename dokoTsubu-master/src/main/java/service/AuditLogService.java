package service;

import dao.Sql;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import model.User;

public class AuditLogService {

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
    List<Map<String, Object>> list = new java.util.ArrayList<>();
    java.util.List<String> sortedActions = new java.util.ArrayList<>(util.AuditActionLabel.actions());
    java.util.Collections.sort(sortedActions);
    for (String action : sortedActions) {
      Map<String, Object> map = new java.util.HashMap<>();
      map.put("action", action);
      list.add(map);
    }
    return list;
  }
}
