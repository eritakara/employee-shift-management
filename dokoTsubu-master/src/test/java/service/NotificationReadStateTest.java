package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import model.User;

public class NotificationReadStateTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-notification-read-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    NotificationService notifications = new NotificationService();

    Sql.update("DELETE FROM notifications");
    Sql.update("DELETE FROM mail_outbox");
    notifications.notify(employee.getId(), "FIRST", "最初", "最初の通知", "/app/attendance/history");
    notifications.notify(employee.getId(), "SECOND", "次", "次の通知", "/app/shifts/mine");
    notifications.notify(manager.getId(), "OTHER", "別利用者", "別利用者の通知", "/app/dashboard");

    List<Map<String, Object>> unread = notifications.notifications(employee, false);
    check(unread.size() == 2, "unread view initially contains employee notifications");
    check(notifications.unreadCount(employee) == 2, "unread count initially matches");

    long firstId = ((Number) Sql.one(
        "SELECT id FROM notifications WHERE user_id=? AND type='FIRST'", employee.getId()).get("id")).longValue();
    String target = notifications.markNotificationRead(employee, firstId);
    check("/app/attendance/history".equals(target), "owned notification returns stored app target");
    check(notifications.notifications(employee, false).size() == 1, "read notification leaves unread view");
    check(notifications.notifications(employee, true).size() == 2, "read notification remains in all view");

    long managerNotificationId = ((Number) Sql.one(
        "SELECT id FROM notifications WHERE user_id=?", manager.getId()).get("id")).longValue();
    expectInvalid(() -> notifications.markNotificationRead(employee, managerNotificationId),
        "another user's notification cannot be marked read");
    check(!Boolean.TRUE.equals(Sql.one("SELECT is_read FROM notifications WHERE id=?", managerNotificationId).get("is_read")),
        "another user's notification remains unread");

    long externalTargetId = Sql.insert(
        "INSERT INTO notifications(user_id,type,title,message,target_url) VALUES(?,'EXTERNAL','外部','外部','https://example.com')",
        employee.getId());
    expectInvalid(() -> notifications.markNotificationRead(employee, externalTargetId),
        "external redirect target is rejected");
    check(!Boolean.TRUE.equals(Sql.one("SELECT is_read FROM notifications WHERE id=?", externalTargetId).get("is_read")),
        "rejected target remains unread");

    notifications.markNotificationsRead(employee);
    check(notifications.unreadCount(employee) == 0, "mark all clears employee unread count");
    check(notifications.notifications(employee, true).size() == 3, "mark all does not delete notifications");
    check(notifications.unreadCount(manager) == 1, "mark all does not affect another user");

    String jsp = Files.readString(Path.of("src/main/webapp/WEB-INF/jsp/app.jsp"));
    check(jsp.contains("/app/notifications?view=all"), "all notifications tab is rendered");
    check(jsp.contains("value=\"markNotificationRead\""), "details action marks one notification read");
    check(jsp.contains("未読の通知はありません。過去の通知は「すべて」から確認できます。"),
        "unread empty-state guidance is rendered");
    System.out.println("NotificationReadStateTest: all checks passed");
  }

  private static void expectInvalid(Runnable action, String label) {
    try { action.run(); }
    catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
