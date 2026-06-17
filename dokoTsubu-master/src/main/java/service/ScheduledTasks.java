package service;

import dao.Sql;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;

public class ScheduledTasks {
  public void runDaily() {
    LocalDate today = LocalDate.now();
    if (today.getDayOfMonth() == 14) sendShiftReminders(today);
    grantAnnualLeave(today);
    Sql.update("UPDATE account_tokens SET used_at=CURRENT_TIMESTAMP WHERE used_at IS NULL AND expires_at<=CURRENT_TIMESTAMP");
  }

  private void sendShiftReminders(LocalDate today) {
    LocalDate nextMonth = today.plusMonths(1).withDayOfMonth(1);
    List<Map<String, Object>> users = Sql.query("SELECT u.id,u.email,u.name FROM users u WHERE u.active=TRUE AND u.role='EMPLOYEE' AND NOT EXISTS(SELECT 1 FROM shifts s WHERE s.user_id=u.id AND s.work_date BETWEEN ? AND ? AND s.status IN('SUBMITTED','CONFIRMED'))",
        nextMonth, nextMonth.withDayOfMonth(nextMonth.lengthOfMonth()));
    for (Map<String, Object> user : users) {
      boolean sent = !Sql.query("SELECT id FROM notifications WHERE user_id=? AND type='SHIFT_DEADLINE' AND CAST(created_at AS DATE)=CURRENT_DATE", user.get("id")).isEmpty();
      if (!sent) {
        Sql.insert("INSERT INTO notifications(user_id,type,title,message,target_url,email_status) VALUES(?,'SHIFT_DEADLINE','希望シフト提出期限','翌月分の希望シフトは明日15日が提出期限です。','/app/shifts/request','QUEUED')", user.get("id"));
        Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES(?,?,?)", user.get("email"), "希望シフト提出期限のお知らせ", user.get("name") + " 様\n翌月分の希望シフトは明日15日が提出期限です。");
      }
    }
  }

  private void grantAnnualLeave(LocalDate today) {
    List<Map<String, Object>> users = Sql.query("SELECT u.id,u.hire_date,b.last_granted_on FROM users u JOIN leave_balances b ON b.user_id=u.id WHERE u.active=TRUE");
    for (Map<String, Object> user : users) {
      LocalDate hire = toDate(user.get("hire_date"));
      int months = Period.between(hire.withDayOfMonth(1), today.withDayOfMonth(1)).toTotalMonths() > Integer.MAX_VALUE
          ? 0 : (int) Period.between(hire.withDayOfMonth(1), today.withDayOfMonth(1)).toTotalMonths();
      if (months < 6 || (months - 6) % 12 != 0 || today.getDayOfMonth() != Math.min(hire.getDayOfMonth(), today.lengthOfMonth())) continue;
      LocalDate last = user.get("last_granted_on") == null ? null : toDate(user.get("last_granted_on"));
      if (last != null && !last.isBefore(today)) continue;
      int serviceYears = Math.max(0, (months - 6) / 12);
      int days = switch (serviceYears) { case 0 -> 10; case 1 -> 11; case 2 -> 12; case 3 -> 14; case 4 -> 16; case 5 -> 18; default -> 20; };
      Sql.update("UPDATE leave_balances SET days_remaining=days_remaining+?,last_granted_on=? WHERE user_id=?", days, today, user.get("id"));
      AuditService.record(null, "AUTO_GRANT_LEAVE", "USER", String.valueOf(user.get("id")), null, days + " days");
    }
  }

  private LocalDate toDate(Object value) {
    if (value instanceof LocalDate date) return date;
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    return LocalDate.parse(String.valueOf(value));
  }
}
