package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import model.User;

public class ShiftWorkflowTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-shift-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User coworker = users.authenticate("sato@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    PortalService portal = new PortalService();
    SettingsService settings = new SettingsService();
    LocalDate actualToday = LocalDate.now();
    LocalDate beforeDeadline = actualToday.withDayOfMonth(Math.min(14, actualToday.lengthOfMonth()));
    LocalDate afterDeadline = actualToday.withDayOfMonth(Math.min(16, actualToday.lengthOfMonth()));
    YearMonth targetMonth = YearMonth.from(beforeDeadline.plusMonths(1));
    LocalDate targetDate = targetMonth.atDay(1);

    portal.submitPreferredShift(employee, targetDate, "DAY", "preferred", beforeDeadline);
    Map<String, Object> submitted = Sql.one("SELECT status,work_type_code FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), targetDate);
    check("SUBMITTED".equals(submitted.get("status")), "preferred shift submitted");
    expectFailure(() -> portal.submitPreferredShift(employee, targetMonth.atDay(2), "DAY", "late", afterDeadline), "submission deadline");
    expectFailure(() -> portal.submitPreferredShift(employee, targetMonth.minusMonths(2).atDay(1), "DAY", "past month", beforeDeadline), "past month block");

    settings.update(hr, "ALLOW_CONFIRM_WITH_WARNINGS", "false");
    expectFailure(() -> portal.confirmMonth(manager, targetMonth, "blocked"), "warnings block confirmation");
    settings.update(hr, "ALLOW_CONFIRM_WITH_WARNINGS", "true");
    expectFailure(() -> portal.confirmMonth(manager, targetMonth, ""), "warning reason required");
    portal.confirmMonth(manager, targetMonth, "人員不足を確認済み");
    check("CONFIRMED".equals(Sql.one("SELECT status FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), targetDate).get("status")), "month confirmed");

    portal.requestShiftChange(employee, targetDate, "OFF", "予定変更");
    long requestId = ((Number) Sql.one("SELECT MAX(id) id FROM shift_change_requests WHERE user_id=?", employee.getId()).get("id")).longValue();
    portal.decideShiftChange(manager, requestId, true);
    check("OFF".equals(Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), targetDate).get("work_type_code")), "approved change applied");

    Sql.update("UPDATE work_types SET required_staff=1 WHERE code IN('DAY','NIGHT')");
    LocalDate boundaryDate = targetMonth.atDay(Math.min(10, targetMonth.lengthOfMonth() - 1));
    List<Map<String, Object>> emptyWarnings = portal.shiftWarningsForDate(manager, boundaryDate);
    check(count(emptyWarnings, "STAFF_SHORTAGE") == 2, "zero-staff shortage by work type");
    portal.saveShift(manager, employee.getId(), boundaryDate, "NIGHT", "DRAFT", "night");
    portal.saveShift(manager, coworker.getId(), boundaryDate, "DAY", "DRAFT", "day");
    portal.saveShift(manager, employee.getId(), boundaryDate.plusDays(1), "DAY", "DRAFT", "overlap");
    check(count(portal.shiftWarningsForDate(manager, boundaryDate.plusDays(1)), "NIGHT_REST") == 1, "night-rest overlap detected");
    portal.saveShift(manager, employee.getId(), boundaryDate.plusDays(1), "OFF", "DRAFT", "rest");
    check(count(portal.shiftWarningsForDate(manager, boundaryDate.plusDays(1)), "NIGHT_REST") == 0, "off day clears night-rest warning");
    check(portal.shiftWarnings(manager, targetMonth).stream().noneMatch(row -> row.get("work_date") == null), "monthly warnings have dates");
    System.out.println("ShiftWorkflowTest: all checks passed");
  }

  private static long count(List<Map<String, Object>> rows, String warning) {
    return rows.stream().filter(row -> warning.equals(row.get("warning"))).count();
  }

  private static void expectFailure(Runnable action, String label) {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
