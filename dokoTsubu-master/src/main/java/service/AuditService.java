package service;

import config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class AuditService {
  private AuditService() { }

  public static void record(Long actorId, String action, String targetType,
      String targetId, String beforeValue, String afterValue) {
    String sql = "INSERT INTO audit_logs(actor_id,action,target_type,target_id,target_user_id,before_value,after_value) VALUES(?,?,?,?,?,?,?)";
    try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
      Long targetUserId = resolveTargetUser(c, targetType, targetId);
      if (actorId == null) p.setNull(1, java.sql.Types.BIGINT); else p.setLong(1, actorId);
      p.setString(2, action); p.setString(3, targetType); p.setString(4, targetId);
      if (targetUserId == null) p.setNull(5, java.sql.Types.BIGINT); else p.setLong(5, targetUserId);
      p.setString(6, beforeValue); p.setString(7, afterValue); p.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Could not write audit log", e);
    }
  }

  private static Long resolveTargetUser(Connection c, String targetType, String targetId) {
    try {
      String rawId = targetId == null ? "" : targetId.split(":", 2)[0];
      long id = Long.parseLong(rawId);
      if ("USER".equals(targetType) || "SHIFT".equals(targetType) || "ATTENDANCE_EMPLOYEE_MONTH".equals(targetType)) return id;
      if ("ATTENDANCE".equals(targetType) && targetId.contains(":")) return id;
      String sql = switch (targetType) {
        case "LEAVE_REQUEST", "SHIFT_CHANGE" -> "SELECT user_id FROM " + ("LEAVE_REQUEST".equals(targetType) ? "leave_requests" : "shift_change_requests") + " WHERE id=?";
        case "ATTENDANCE" -> "SELECT user_id FROM attendance WHERE id=?";
        case "ATTENDANCE_ADJUSTMENT" -> "SELECT requested_by FROM attendance_adjustments WHERE id=?";
        case "QUALIFICATION" -> "SELECT user_id FROM qualifications WHERE id=?";
        case "DELEGATION" -> "SELECT delegate_id FROM delegations WHERE id=?";
        default -> null;
      };
      if (sql == null) return null;
      try (PreparedStatement p = c.prepareStatement(sql)) {
        p.setLong(1, id);
        try (java.sql.ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
      }
    } catch (NumberFormatException | SQLException e) {
      return null;
    }
  }
}
