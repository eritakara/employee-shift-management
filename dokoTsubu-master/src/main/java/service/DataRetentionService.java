package service;

import config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;

public class DataRetentionService {
  private final SettingsService settings = new SettingsService();

  public int run(LocalDate today) {
    int years = settings.integer("RETENTION_YEARS", 5);
    LocalDate cutoff = today.minusYears(years);
    try (Connection c = Database.getConnection()) {
      c.setAutoCommit(false);
      try {
        int changed = 0;
        changed += update(c, "DELETE FROM attendance_adjustments WHERE attendance_id IN(SELECT id FROM attendance WHERE work_date<?)", cutoff);
        changed += update(c, "DELETE FROM attendance WHERE work_date<?", cutoff);
        changed += update(c, "DELETE FROM shift_change_requests WHERE work_date<?", cutoff);
        changed += update(c, "DELETE FROM shifts WHERE work_date<?", cutoff);
        changed += update(c, "DELETE FROM leave_consumptions WHERE request_id IN(SELECT id FROM leave_requests WHERE leave_date<?)", cutoff);
        changed += update(c, "DELETE FROM leave_requests WHERE leave_date<?", cutoff);
        changed += update(c, "DELETE FROM leave_history WHERE event_date<?", cutoff);
        changed += update(c, "DELETE FROM notifications WHERE created_at<?", cutoff.atStartOfDay());
        changed += update(c, "DELETE FROM mail_outbox WHERE created_at<?", cutoff.atStartOfDay());
        changed += update(c, "DELETE FROM account_tokens WHERE created_at<?", cutoff.atStartOfDay());
        changed += update(c, "DELETE FROM audit_logs WHERE created_at<?", cutoff.atStartOfDay());
        changed += update(c, "UPDATE users SET employee_number='DELETED-'||id,name='退職者 #'||id,email='deleted+'||id||'@invalid.local',password_hash='!',locale='ja' "
            + "WHERE active=FALSE AND deactivated_at IS NOT NULL AND deactivated_at<? AND email NOT LIKE 'deleted+%@invalid.local'", cutoff.atStartOfDay());
        c.commit();
        dao.Sql.insert("INSERT INTO audit_logs(action,target_type,target_id,after_value,created_at) VALUES('RUN_DATA_RETENTION','SYSTEM',?,?,?)",
            cutoff.toString(), "rows=" + changed, today.atStartOfDay());
        return changed;
      } catch (Exception e) {
        c.rollback();
        throw new IllegalStateException("保存期限処理に失敗しました。", e);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("保存期限処理に接続できません。", e);
    }
  }

  private int update(Connection c, String sql, Object value) throws Exception {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setObject(1, value);
      return p.executeUpdate();
    }
  }
}
