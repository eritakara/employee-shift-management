package service;

import config.MailConfig;
import dao.Sql;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class MailDeliveryService {
  private final MailConfig config;
  private final SmtpClient smtp;

  public MailDeliveryService() { this(MailConfig.load(), new SmtpClient()); }
  MailDeliveryService(MailConfig config, SmtpClient smtp) { this.config = config; this.smtp = smtp; }

  public int deliverPending() {
    if (!config.enabled()) return 0;
    List<Map<String, Object>> pending = Sql.query("SELECT * FROM mail_outbox WHERE status IN('QUEUED','RETRY') AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP) AND attempts<? ORDER BY created_at LIMIT 50", config.maxAttempts());
    int delivered = 0;
    for (Map<String, Object> mail : pending) if (deliver(mail)) delivered++;
    return delivered;
  }

  public void retry(long id) {
    Sql.update("UPDATE mail_outbox SET status='QUEUED',next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=? AND status='FAILED'", id);
  }

  public boolean deliverNow(long id) {
    if (!config.enabled()) {
      System.err.println("[MAIL] delivery skipped: SMTP_HOST or SMTP_FROM is not configured");
      return false;
    }
    Map<String, Object> mail = Sql.one("SELECT * FROM mail_outbox WHERE id=? AND status IN('QUEUED','RETRY')", id);
    return !mail.isEmpty() && deliver(mail);
  }

  private boolean deliver(Map<String, Object> mail) {
    long id = ((Number) mail.get("id")).longValue();
    int attempts = ((Number) mail.get("attempts")).intValue() + 1;
    if (Sql.update("UPDATE mail_outbox SET status='SENDING',attempts=? WHERE id=? AND status IN('QUEUED','RETRY')", attempts, id) != 1) return false;
    try {
      smtp.send(config, String.valueOf(mail.get("recipient")), String.valueOf(mail.get("subject")), String.valueOf(mail.get("body")));
      Sql.update("UPDATE mail_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP,last_error=NULL,next_attempt_at=NULL WHERE id=?", id);
      Sql.update("UPDATE notifications SET email_status='SENT' WHERE id=(SELECT MAX(id) FROM notifications WHERE user_id=(SELECT id FROM users WHERE email=?) AND title=?)", mail.get("recipient"), mail.get("subject"));
      return true;
    } catch (Exception e) {
      System.err.println("[MAIL] delivery failed: mailId=" + id + ", attempt=" + attempts
          + ", errorType=" + e.getClass().getSimpleName());
      boolean failed = attempts >= config.maxAttempts();
      int delayMinutes = attempts == 1 ? 1 : attempts == 2 ? 5 : 15;
      Sql.update("UPDATE mail_outbox SET status=?,last_error=?,next_attempt_at=? WHERE id=?",
          failed ? "FAILED" : "RETRY", safeMessage(e), failed ? null : LocalDateTime.now().plusMinutes(delayMinutes), id);
      return false;
    }
  }

  private String safeMessage(Exception e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    String sanitized = message.replaceAll("(?i)(password|authorization|auth)[^ ]*", "[redacted]");
    return sanitized.substring(0, Math.min(sanitized.length(), 1900));
  }
}
