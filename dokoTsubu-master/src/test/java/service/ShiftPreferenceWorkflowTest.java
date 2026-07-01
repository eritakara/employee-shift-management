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
    User unsubmittedEmployee = users.authenticate("sato@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    PortalService portal = new PortalService();
    ShiftService shiftService = new ShiftService();
    LocalDate policyToday = LocalDate.of(2026, 6, 10);
    YearMonth month = YearMonth.of(2026, 7);

    check(Boolean.TRUE.equals(shiftService.shiftSubmissionWindow(month, LocalDate.of(2026, 6, 14)).get("open")),
        "submission window is open before deadline");
    check(Boolean.TRUE.equals(shiftService.shiftSubmissionWindow(month, LocalDate.of(2026, 6, 15)).get("open")),
        "submission window is open on deadline");
    check(Boolean.FALSE.equals(shiftService.shiftSubmissionWindow(month, LocalDate.of(2026, 6, 16)).get("open")),
        "submission window is closed after deadline");

    Map<LocalDate, String> deadlinePreference = Map.of(month.atDay(4), "OFF");
    portal.submitMonthlyPreferences(unsubmittedEmployee, month, deadlinePreference, Map.of(), LocalDate.of(2026, 6, 15));
    check("SUBMITTED".equals(portal.preferenceSubmission(unsubmittedEmployee, month).get("status")),
        "submission succeeds on deadline");
    long deadlineSubmissionId = ((Number) portal.preferenceSubmission(unsubmittedEmployee, month).get("id")).longValue();
    Sql.update("DELETE FROM shift_preferences WHERE submission_id=?", deadlineSubmissionId);
    Sql.update("DELETE FROM shift_preference_submissions WHERE id=?", deadlineSubmissionId);
    expectFailure(() -> portal.submitMonthlyPreferences(unsubmittedEmployee, month, deadlinePreference, Map.of(), LocalDate.of(2026, 6, 16)),
        "new submission after deadline is blocked");
    check(portal.preferenceSubmission(unsubmittedEmployee, month).isEmpty(),
        "late rejected submission does not create a submission row");
    check(portal.preferences(unsubmittedEmployee, month).isEmpty(),
        "late rejected submission does not create preference rows");

    Map<LocalDate, String> preferences = new LinkedHashMap<>();
    preferences.put(month.atDay(2), "DAY");
    preferences.put(month.atDay(3), "NONE");
    preferences.put(month.atDay(8), "NIGHT");
    preferences.put(month.atDay(14), "OFF");
    Map<LocalDate, String> reasons = new LinkedHashMap<>();
    reasons.put(month.atDay(2), "日勤には保存しない理由");
    portal.submitMonthlyPreferences(employee, month, preferences, reasons, policyToday);

    Map<String, Object> submission = portal.preferenceSubmission(employee, month);
    check("SUBMITTED".equals(submission.get("status")), "monthly submission status");
    List<Map<String, Object>> saved = portal.preferences(employee, month);
    check(saved.size() == 3, "only selected preference days saved (excluding NONE and LEAVE)");
    check(saved.stream().noneMatch(row -> "NONE".equals(row.get("request_type"))), "none is not stored");
    check(saved.stream().allMatch(row -> row.get("note") == null), "reason is ignored for non-leave preferences");
    check(portal.preferenceSubmissionSummaries(manager, month).stream()
        .anyMatch(row -> employee.getEmployeeNumber().equals(row.get("employee_number"))
            && "SUBMITTED".equals(row.get("status")) && ((Number) row.get("preference_count")).intValue() == 3),
        "manager sees submission summary");
    List<Map<String, Object>> managerDetails = portal.preferenceDetails(manager, month);
    check(managerDetails.size() == 3, "manager sees preference details");

    Map<LocalDate, String> latePreference = Map.of(month.atDay(5), "OFF");
    expectFailure(() -> portal.submitMonthlyPreferences(employee, month, latePreference, Map.of(), LocalDate.of(2026, 6, 16)),
        "submitted preference edit after deadline is blocked", "提出済みの希望シフトは変更できません");
    check(portal.preferences(employee, month).size() == 3, "late rejected submission keeps preference details");
    check("SUBMITTED".equals(portal.preferenceSubmission(employee, month).get("status")),
        "late rejected submission keeps submission status");

    // 希望シフトとしてLEAVE（有休）を送信した場合はエラーになることの検証
    Map<LocalDate, String> leavePreference = Map.of(month.atDay(21), "LEAVE");
    expectFailure(() -> portal.submitMonthlyPreferences(employee, month, leavePreference, Map.of(), policyToday),
        "leave preference submission is blocked");

    // 有休の申請と承認
    portal.requestLeave(employee, month.atDay(21), "FULL", null, "家族行事のため");
    long leaveId = ((Number) portal.leaveRequests(manager).get(0).get("id")).longValue();
    portal.decideLeave(manager, leaveId, true);
    check("APPROVED".equals(portal.leaveRequests(employee).get(0).get("status")), "leave request approved");

    long submissionId = ((Number) submission.get("id")).longValue();
    portal.reviewPreferenceSubmission(manager, submissionId, true);
    check("APPROVED".equals(portal.preferenceSubmission(employee, month).get("status")), "manager approves monthly submission");

    Sql.update("UPDATE work_types SET required_staff=1 WHERE code IN('DAY','NIGHT')");
    portal.saveShift(manager, employee.getId(), month.atDay(1), "OFF", "CONFIRMED", "existing confirmed shift");
    portal.saveShift(manager, employee.getId(), month.atDay(14), "DAY", "DRAFT", "incorrect assignment before auto assign");
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
    check("LEAVE".equals(type(employee, month.atDay(21))), "leave from approved leave request prioritized");

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

  private static void expectFailure(Runnable action, String label, String expectedMessage) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      if (expected.getMessage() != null && expected.getMessage().contains(expectedMessage)) return;
      throw new AssertionError("Failed: " + label + " message: " + expected.getMessage());
    }
    throw new AssertionError("Failed: " + label);
  }
}
