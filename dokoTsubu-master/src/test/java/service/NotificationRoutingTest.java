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
    portal.requestLeave(employee, today.plusDays(4), "FULL", null, "leave notification routing");
    check(notificationCount(manager.getId(), "LEAVE_REQUEST") == 1, "branch manager notified for leave");
    check(notificationCount(hr.getId(), "LEAVE_REQUEST") == 0, "HR not notified as employee leave approver");
    check(notificationCount(delegate.getId(), "LEAVE_REQUEST") == 0, "delegate not notified as employee leave approver");
    check(notificationCount(employee.getId(), "LEAVE_REQUEST") == 0, "requester not notified for own leave");
    check(mailCount(manager.getEmail(), "有休申請") == 1, "branch manager leave mail queued");

    Sql.update("DELETE FROM notifications");
    Sql.update("DELETE FROM mail_outbox");
    LocalDate managerLeaveDate = today.plusDays(6);
    portal.requestLeave(manager, managerLeaveDate, "FULL", null, "manager leave routing");
    long managerLeaveId = ((Number) Sql.one("SELECT id FROM leave_requests WHERE user_id=? AND leave_date=?", manager.getId(), managerLeaveDate).get("id")).longValue();
    check(notificationCount(hr.getId(), "LEAVE_REQUEST") == 1, "HR notified for manager leave");
    check(notificationCount(manager.getId(), "LEAVE_REQUEST") == 0, "manager not notified for own leave approval");
    expectDenied(() -> portal.decideLeave(manager, managerLeaveId, true), "manager cannot approve own leave");
    portal.decideLeave(hr, managerLeaveId, false, "人事確認のため");
    check(notificationMessage(manager.getId(), "LEAVE_DECISION").contains("却下理由: 人事確認のため"), "manager leave rejection reason notified");

    Sql.update("DELETE FROM notifications");
    Sql.update("DELETE FROM mail_outbox");
    String passwordHash = String.valueOf(Sql.one("SELECT password_hash FROM users WHERE email='hr@example.com'").get("password_hash"));
    long hr2 = Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role) VALUES('HR002','人事 承認多','hr2@example.com',?,CURRENT_DATE,1,4,1,'HR')", passwordHash);
    long hr3 = Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role) VALUES('HR003','人事 承認少','hr3@example.com',?,CURRENT_DATE,1,4,1,'HR')", passwordHash);
    Sql.update("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason,status,decided_by,decided_at) VALUES(?,?,'FULL','count','APPROVED',?,CURRENT_TIMESTAMP)", employee.getId(), today.minusDays(2), hr2);
    Sql.update("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason,status,decided_by,decided_at) VALUES(?,?,'FULL','count','APPROVED',?,CURRENT_TIMESTAMP)", employee.getId(), today.minusDays(1), hr2);
    check(((Number) portal.leaveApprovers(hr).get(0).get("id")).longValue() == hr3, "HR leave approver uses lowest recent approval count");
    LocalDate hrLeaveDate = today.plusDays(7);
    portal.requestLeave(hr, hrLeaveDate, "FULL", null, "HR leave routing");
    check(notificationCount(hr3, "LEAVE_REQUEST") == 1, "selected HR approver notified");
    check(notificationCount(hr.getId(), "LEAVE_REQUEST") == 0, "HR requester not self-notified");

    Sql.update("DELETE FROM notifications");
    Sql.update("DELETE FROM mail_outbox");
    LocalDate rejectedLeaveDate = today.plusDays(5);
    portal.requestLeave(employee, rejectedLeaveDate, "FULL", null, "reject routing");
    long leaveId = ((Number) Sql.one("SELECT id FROM leave_requests WHERE user_id=? AND leave_date=?", employee.getId(), rejectedLeaveDate).get("id")).longValue();
    expectInvalid(() -> portal.decideLeave(manager, leaveId, false, ""), "leave rejection reason required");
    check("PENDING".equals(Sql.one("SELECT status FROM leave_requests WHERE id=?", leaveId).get("status")), "blank leave rejection stays pending");
    Sql.update("DELETE FROM notifications");
    Sql.update("DELETE FROM mail_outbox");
    portal.decideLeave(manager, leaveId, false, "繁忙日のため");
    check(notificationMessage(employee.getId(), "LEAVE_DECISION").contains("却下理由: 繁忙日のため"), "leave rejection reason notified");

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

  private static String notificationMessage(long userId, String type) {
    return String.valueOf(Sql.one("SELECT message FROM notifications WHERE user_id=? AND type=? ORDER BY id DESC LIMIT 1", userId, type).get("message"));
  }

  private static void expectInvalid(Runnable action, String label) {
    try { action.run(); }
    catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void expectDenied(Runnable action, String label) {
    try { action.run(); }
    catch (SecurityException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
