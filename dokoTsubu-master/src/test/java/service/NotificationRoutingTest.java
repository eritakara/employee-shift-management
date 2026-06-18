package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import model.User;

public class NotificationRoutingTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-notification-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User delegate = users.authenticate("sato@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    PortalService portal = new PortalService();
    LocalDate today = LocalDate.now();
    portal.addDelegation(manager, manager.getId(), delegate.getId(), today.minusDays(1), today.plusDays(1));

    portal.requestShiftChange(employee, today.plusDays(3), "OFF", "notification routing");
    check(notificationCount(manager.getId(), "SHIFT_CHANGE_REQUEST") == 1, "manager notified");
    check(notificationCount(hr.getId(), "SHIFT_CHANGE_REQUEST") == 1, "HR notified");
    check(notificationCount(delegate.getId(), "SHIFT_CHANGE_REQUEST") == 1, "active delegate notified");
    check(notificationCount(employee.getId(), "SHIFT_CHANGE_REQUEST") == 0, "requester not notified as approver");
    check(mailCount(manager.getEmail(), "シフト変更申請") == 1, "manager mail queued");
    check(mailCount(hr.getEmail(), "シフト変更申請") == 1, "HR mail queued");
    check(mailCount(delegate.getEmail(), "シフト変更申請") == 1, "delegate mail queued");

    Sql.update("DELETE FROM notifications");
    Sql.update("DELETE FROM mail_outbox");
    LocalDate reminderDay = LocalDate.of(2026, 6, 14);
    LocalDate submittedDate = reminderDay.plusMonths(1).withDayOfMonth(1);
    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by) VALUES(?,?,'DAY','SUBMITTED',?)",
        employee.getId(), submittedDate, employee.getId());
    ScheduledTasks tasks = new ScheduledTasks();
    tasks.runDaily(reminderDay);
    tasks.runDaily(reminderDay);
    check(notificationCount(employee.getId(), "SHIFT_DEADLINE") == 0, "submitted employee excluded");
    check(notificationCount(delegate.getId(), "SHIFT_DEADLINE") == 1, "unsubmitted employee reminded once");
    check(mailCount(delegate.getEmail(), "希望シフト提出期限のお知らせ") == 1, "deadline mail queued once");
    System.out.println("NotificationRoutingTest: all checks passed");
  }

  private static int notificationCount(long userId, String type) {
    return ((Number) Sql.one("SELECT COUNT(*) count_value FROM notifications WHERE user_id=? AND type=?", userId, type).get("count_value")).intValue();
  }

  private static int mailCount(String email, String subject) {
    return ((Number) Sql.one("SELECT COUNT(*) count_value FROM mail_outbox WHERE recipient=? AND subject=?", email, subject).get("count_value")).intValue();
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
