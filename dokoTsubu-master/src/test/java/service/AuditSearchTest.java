package service;

import config.Database;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import model.User;

public class AuditSearchTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-audit-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    PortalService portal = new PortalService();
    LocalDate today = LocalDate.now();

    portal.saveShift(manager, employee.getId(), today.plusDays(1), "DAY", "DRAFT", "audit test");
    List<Map<String, Object>> matched = portal.audit(hr, today, today, manager.getId(), "SAVE_SHIFT", employee.getId());
    check(matched.size() == 1, "combined filters");
    Map<String, Object> row = matched.get(0);
    check(manager.getId() == ((Number) row.get("actor_id")).longValue(), "actor recorded");
    check(employee.getId() == ((Number) row.get("target_user_id")).longValue(), "target employee recorded");
    check("SHIFT".equals(row.get("target_type")), "target type recorded");
    check(portal.audit(hr, today.plusDays(1), today.plusDays(1), null, null, null).isEmpty(), "date filter");
    check(portal.audit(hr, null, null, employee.getId(), "SAVE_SHIFT", employee.getId()).isEmpty(), "actor filter");
    expectDenied(() -> portal.audit(employee, null, null, null, null, null), "HR-only access");
    System.out.println("AuditSearchTest: all checks passed");
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
