package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import model.User;

public class SecurityAuthorizationTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-security-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User coworker = users.authenticate("sato@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    check(employee != null && coworker != null && manager != null && hr != null, "all roles authenticate");
    check(users.authenticate("employee@example.com", "wrong-password") == null, "wrong password rejected");
    Sql.update("UPDATE users SET active=FALSE WHERE id=?", coworker.getId());
    check(users.authenticate("sato@example.com", "Password1!") == null, "inactive account rejected");
    Sql.update("UPDATE users SET active=TRUE WHERE id=?", coworker.getId());

    PortalService portal = new PortalService();
    LocalDate targetDate = LocalDate.now().plusDays(10);
    expectDenied(() -> portal.saveShift(employee, coworker.getId(), targetDate, "DAY", "DRAFT", "unauthorized"),
        "employee cannot edit coworker shift");
    expectDenied(() -> portal.saveShift(manager, hr.getId(), targetDate, "DAY", "DRAFT", "outside scope"),
        "manager cannot edit outside scope");
    portal.saveShift(hr, coworker.getId(), targetDate, "DAY", "DRAFT", "HR scope");
    check(!Sql.query("SELECT id FROM shifts WHERE user_id=? AND work_date=?", coworker.getId(), targetDate).isEmpty(), "HR company scope");
    expectDenied(() -> new SettingsService().all(employee), "HR settings protected");
    expectDenied(() -> portal.audit(employee), "audit protected");

    portal.addDelegation(manager, manager.getId(), employee.getId(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
    long delegationId = ((Number) Sql.one("SELECT MAX(id) id FROM delegations WHERE delegate_id=?", employee.getId()).get("id")).longValue();
    LocalDate delegatedDate = targetDate.plusDays(1);
    portal.saveShift(employee, coworker.getId(), delegatedDate, "DAY", "DRAFT", "active delegate");
    check(!Sql.query("SELECT id FROM shifts WHERE user_id=? AND work_date=?", coworker.getId(), delegatedDate).isEmpty(), "active delegate allowed");

    portal.updateDelegation(manager, delegationId, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), false);
    expectDenied(() -> portal.saveShift(employee, coworker.getId(), targetDate.plusDays(2), "DAY", "DRAFT", "inactive delegate"),
        "inactive delegate rejected");
    portal.updateDelegation(manager, delegationId, LocalDate.now().plusDays(1), LocalDate.now().plusDays(2), true);
    expectDenied(() -> portal.saveShift(employee, coworker.getId(), targetDate.plusDays(3), "DAY", "DRAFT", "future delegate"),
        "future delegate rejected");
    portal.updateDelegation(manager, delegationId, LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), true);
    expectDenied(() -> portal.saveShift(employee, coworker.getId(), targetDate.plusDays(4), "DAY", "DRAFT", "expired delegate"),
        "expired delegate rejected");
    System.out.println("SecurityAuthorizationTest: all checks passed");
  }

  private static void expectDenied(Runnable action, String label) {
    try { action.run(); } catch (SecurityException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
