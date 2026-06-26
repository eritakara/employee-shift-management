package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Map;
import model.User;

public class InvitationWorkflowTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-invitation-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User hr = users.authenticate("hr@example.com", "Password1!");
    User employee = users.authenticate("employee@example.com", "Password1!");
    PortalService portal = new PortalService();
    String email = "new.employee@example.test";
    portal.addEmployee(hr, "NEW001", "New Employee", email, LocalDate.now(), 4, 1, 1, "EMPLOYEE", "https://shiftflow.example");
    check(users.authenticate(email, "Password1!") == null, "known demo password cannot access invited account");
    Map<String, Object> first = Sql.one("SELECT id,token FROM account_tokens WHERE token_type='INVITE' AND used_at IS NULL AND user_id=(SELECT id FROM users WHERE email=?)", email);
    check(!first.isEmpty(), "registration creates an invitation token");
    long userId = ((Number) Sql.one("SELECT id FROM users WHERE email=?", email).get("id")).longValue();
    portal.reissueInvite(hr, userId, "https://shiftflow.example");
    check(Sql.query("SELECT id FROM account_tokens WHERE id=? AND used_at IS NOT NULL", first.get("id")).size() == 1,
        "reissue invalidates the previous link");
    check(count("SELECT COUNT(*) count_value FROM account_tokens WHERE user_id=? AND token_type='INVITE' AND used_at IS NULL", userId) == 1,
        "only one invitation link remains valid");
    check(count("SELECT COUNT(*) count_value FROM mail_outbox WHERE recipient=? AND subject='ShiftFlowへのご招待'", email) == 2,
        "registration and reissue each queue one invitation mail");
    check(count("SELECT COUNT(*) count_value FROM leave_grants WHERE user_id=? AND expires_on>=CURRENT_DATE AND days_remaining>0", userId) == 1,
        "new employee receives an active migration leave grant");
    expectDenied(() -> portal.reissueInvite(employee, userId, "https://shiftflow.example"), "non-HR reissue");
    check(count("SELECT COUNT(*) count_value FROM audit_logs WHERE action='REISSUE_INVITE' AND target_user_id=?", userId) == 1,
        "reissue is audited");
    System.out.println("InvitationWorkflowTest: all checks passed");
  }

  private static int count(String sql, Object... args) {
    return ((Number) Sql.one(sql, args).get("count_value")).intValue();
  }

  private static void expectDenied(Runnable action, String label) {
    try { action.run(); } catch (SecurityException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
