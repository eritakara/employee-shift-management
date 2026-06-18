package service;

import config.Database;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import model.User;

public class ApprovedRequirementsTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-approved-requirements-").toString());
    Database.initialize();
    SettingsService settings = new SettingsService();
    check(settings.integer("MAX_CONCURRENT_USERS", 0) == 100, "approved concurrent-user default");
    check(settings.bool("ALLOW_CONFIRM_WITH_WARNINGS", false), "confirmation with reason is enabled");
    check(!settings.bool("LEAVE_ALLOW_PAST", true), "past leave requests are disabled");
    check(settings.integer("LEAVE_MIN_NOTICE_DAYS", 0) == 1, "leave requests require one day notice");
    check(settings.integer("MONTHLY_CLOSE_DAY", 0) == 5, "monthly close day default");
    check(settings.integer("RETENTION_YEARS", 0) == 5, "retention period default");
    check(!settings.bool("LOCATION_REQUIRED", true), "clocking is allowed when location is unavailable");

    User employee = new UserDAO().authenticate("employee@example.com", "Password1!");
    PortalService portal = new PortalService();
    expectFailure(() -> portal.requestLeave(employee, LocalDate.now(), "FULL", null, "same-day request"),
        "same-day leave request is rejected");
    portal.requestLeave(employee, LocalDate.now().plusDays(1), "FULL", null, "next-day request");
    System.out.println("ApprovedRequirementsTest: all checks passed");
  }

  private static void expectFailure(Runnable action, String label) {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}

