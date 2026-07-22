package service;

import dao.Sql;
import java.util.List;
import java.util.Map;
import model.User;

public class NotificationService {
  public List<Map<String, Object>> notifications(User user) {
    return notifications(user, true);
  }

  public List<Map<String, Object>> notifications(User user, boolean includeRead) {
    return Sql.query("SELECT * FROM notifications WHERE user_id=?"
        + (includeRead ? "" : " AND is_read=FALSE") + " ORDER BY created_at DESC", user.getId());
  }

  public int unreadCount(User user) {
    return ((Number) Sql.one("SELECT COUNT(*) unread_count FROM notifications WHERE user_id=? AND is_read=FALSE",
        user.getId()).get("unread_count")).intValue();
  }

  public List<Map<String, Object>> mailOutbox(User user) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ利用できます。");
    return Sql.query("SELECT id,recipient,subject,status,attempts,last_error,created_at,sent_at,next_attempt_at FROM mail_outbox ORDER BY created_at DESC LIMIT 300");
  }

  public void retryMail(User user, long id) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    new MailDeliveryService().retry(id);
    AuditService.record(user.getId(), "RETRY_MAIL", "MAIL_OUTBOX", String.valueOf(id), "FAILED", "QUEUED");
  }

  public void markNotificationsRead(User user) {
    Sql.update("UPDATE notifications SET is_read=TRUE WHERE user_id=? AND is_read=FALSE", user.getId());
  }

  public String markNotificationRead(User user, long notificationId) {
    Map<String, Object> notification = Sql.one(
        "SELECT target_url FROM notifications WHERE id=? AND user_id=?", notificationId, user.getId());
    if (notification.isEmpty()) throw new IllegalArgumentException("通知が見つかりません。");
    String target = notification.get("target_url") == null ? null : String.valueOf(notification.get("target_url"));
    if (target == null || !target.startsWith("/app/")) {
      throw new IllegalArgumentException("この通知には詳細画面がありません。");
    }
    Sql.update("UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?", notificationId, user.getId());
    return target;
  }

  public void notify(long userId, String type, String title, String message, String url) {
    Sql.insert("INSERT INTO notifications(user_id,type,title,message,target_url,email_status) VALUES(?,?,?,?,?,'QUEUED')", userId, type, title, message, url);
    Map<String, Object> user = Sql.one("SELECT email,name FROM users WHERE id=?", userId);
    if (!user.isEmpty()) Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES(?,?,?)", user.get("email"), title, user.get("name") + " 様\n\n" + message);
  }

  public void notifyManagers(User user, String type, String title, String message, String url) {
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"));
    List<Map<String, Object>> managers = Sql.query("SELECT id FROM users WHERE active=TRUE AND ((role='MANAGER' AND branch_id=? AND department_id=?) OR role='HR') "
        + "UNION SELECT d.delegate_id id FROM delegations d JOIN users m ON m.id=d.manager_id JOIN users u ON u.id=d.delegate_id "
        + "WHERE d.active=TRUE AND u.active=TRUE AND ? BETWEEN d.starts_on AND d.ends_on AND m.branch_id=? AND m.department_id=?",
        user.getBranchId(), user.getDepartmentId(), today, user.getBranchId(), user.getDepartmentId());
    for (Map<String, Object> manager : managers) notify(((Number) manager.get("id")).longValue(), type, title, message, url);
  }

  public void notifyLeaveApprovers(User user, String type, String title, String message, String url) {
    LeaveService leaveService = new LeaveService();
    for (Map<String, Object> approver : leaveService.leaveApprovers(user)) notify(((Number) approver.get("id")).longValue(), type, title, message, url);
  }
}
