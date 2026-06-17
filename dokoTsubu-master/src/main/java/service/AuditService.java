package service;

import config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class AuditService {
  private AuditService() { }

  public static void record(Long actorId, String action, String targetType,
      String targetId, String beforeValue, String afterValue) {
    String sql = "INSERT INTO audit_logs(actor_id,action,target_type,target_id,before_value,after_value) VALUES(?,?,?,?,?,?)";
    try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
      if (actorId == null) p.setNull(1, java.sql.Types.BIGINT); else p.setLong(1, actorId);
      p.setString(2, action); p.setString(3, targetType); p.setString(4, targetId);
      p.setString(5, beforeValue); p.setString(6, afterValue); p.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Could not write audit log", e);
    }
  }
}
