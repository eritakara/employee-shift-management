package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.User;

public class ShiftPreferenceWorkflowTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-preference-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    PortalService portal = new PortalService();
    LocalDate policyToday = LocalDate.of(2026, 6, 10);
    YearMonth month = YearMonth.of(2026, 7);

    Map<LocalDate, String> preferences = new LinkedHashMap<>();
    preferences.put(month.atDay(2), "DAY");
    preferences.put(month.atDay(3), "NONE");
    preferences.put(month.atDay(8), "NIGHT");
    preferences.put(month.atDay(14), "OFF");
    preferences.put(month.atDay(21), "LEAVE");
    Map<LocalDate, String> reasons = new LinkedHashMap<>();
    reasons.put(month.atDay(2), "日勤には保存しない理由");
    reasons.put(month.atDay(21), "家族行事のため");
    portal.submitMonthlyPreferences(employee, month, preferences, reasons, policyToday);

    Map<String, Object> submission = portal.preferenceSubmission(employee, month);
    check("SUBMITTED".equals(submission.get("status")), "monthly submission status");
    List<Map<String, Object>> saved = portal.preferences(employee, month);
    check(saved.size() == 4, "only selected preference days saved");
    check(saved.stream().noneMatch(row -> "NONE".equals(row.get("request_type"))), "none is not stored");
    check(saved.stream().anyMatch(row -> "LEAVE".equals(row.get("request_type")) && "家族行事のため".equals(row.get("note"))),
        "optional leave reason saved");
    check(saved.stream().filter(row -> !"LEAVE".equals(row.get("request_type"))).allMatch(row -> row.get("note") == null),
        "reason is ignored for non-leave preferences");
    check(portal.preferenceSubmissionSummaries(manager, month).stream()
        .anyMatch(row -> employee.getEmployeeNumber().equals(row.get("employee_number"))
            && "SUBMITTED".equals(row.get("status")) && ((Number) row.get("preference_count")).intValue() == 4),
        "manager sees submission summary");
    List<Map<String, Object>> managerDetails = portal.preferenceDetails(manager, month);
    check(managerDetails.size() == 4, "manager sees preference details: " + managerDetails);
    check(managerDetails.stream().anyMatch(row -> "LEAVE".equals(row.get("request_type")) && "家族行事のため".equals(row.get("note"))),
        "manager sees optional leave reason");
    Map<LocalDate, String> tooLongPreference = Map.of(month.atDay(22), "LEAVE");
    Map<LocalDate, String> tooLongReason = Map.of(month.atDay(22), "あ".repeat(501));
    expectFailure(() -> portal.submitMonthlyPreferences(employee, month, tooLongPreference, tooLongReason, policyToday),
        "leave reason length limit");
    long submissionId = ((Number) submission.get("id")).longValue();
    portal.reviewPreferenceSubmission(manager, submissionId, true);
    check("APPROVED".equals(portal.preferenceSubmission(employee, month).get("status")), "manager approves monthly submission");

    Sql.update("UPDATE work_types SET required_staff=1 WHERE code IN('DAY','NIGHT')");
    portal.saveShift(manager, employee.getId(), month.atDay(1), "OFF", "CONFIRMED", "existing confirmed shift");
    int assigned = portal.autoAssignShifts(manager, month);
    check(assigned > 0, "automatic assignment created shifts");
    check("CONFIRMED".equals(Sql.one("SELECT status FROM shifts WHERE user_id=? AND work_date=?", employee.getId(), month.atDay(1)).get("status")),
        "existing confirmed shift preserved");
    String dayPreferenceResult = type(employee, month.atDay(2));
    String nightPreferenceResult = type(employee, month.atDay(8));
    check("DAY".equals(dayPreferenceResult), "day preference prioritized: " + dayPreferenceResult + " / "
        + Sql.query("SELECT action,target_id,after_value FROM audit_logs WHERE target_id=? ORDER BY id", employee.getId() + ":" + month.atDay(2)));
    check("NIGHT".equals(nightPreferenceResult), "night preference prioritized: " + nightPreferenceResult);
    check("NIGHT_OFF".equals(type(employee, month.atDay(9))), "night is followed by night-off");
    check("OFF".equals(type(employee, month.atDay(14))), "off preference prioritized");
    check("LEAVE".equals(type(employee, month.atDay(21))), "leave preference prioritized");

    Map<LocalDate, String> revised = new LinkedHashMap<>();
    revised.put(month.atDay(5), "OFF");
    portal.submitMonthlyPreferences(employee, month, revised, policyToday);
    check(portal.preferences(employee, month).size() == 1, "resubmission replaces preference details");
    check("SUBMITTED".equals(portal.preferenceSubmission(employee, month).get("status")), "resubmission resets review status");
    System.out.println("ShiftPreferenceWorkflowTest: all checks passed");
  }

  private static String type(User user, LocalDate date) {
    return String.valueOf(Sql.one("SELECT work_type_code FROM shifts WHERE user_id=? AND work_date=?", user.getId(), date).get("work_type_code"));
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }

  private static void expectFailure(Runnable action, String label) {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }
}
