package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.util.Map;

public class AccountTokenSecurityTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-token-security-").toString());
    Database.initialize();

    AccountTokenService tokens = new AccountTokenService();
    AccountTokenService.IssueResult issued = tokens.issueWithMail(
        "employee@example.com", "RESET", "https://shiftflow.example");
    check(issued != null, "reset token issued");
    Map<String, Object> stored = Sql.one(
        "SELECT token FROM account_tokens WHERE token_type='RESET' AND used_at IS NULL");
    check(!stored.isEmpty(), "token row stored");
    check(!issued.token().equals(stored.get("token")), "plaintext token is not stored");
    check(AccountTokenService.tokenHash(issued.token()).equals(stored.get("token")), "token hash stored");
    check(tokens.isValid(issued.token(), "RESET"), "plaintext token validates through its hash");
    check(!tokens.isValid(issued.token(), "INVITE"), "token type is enforced");
    check(tokens.consume(issued.token(), "RESET", "NewPassword1!"), "token can be consumed once");
    check(!tokens.consume(issued.token(), "RESET", "AnotherPassword1!"), "consumed token cannot be reused");
    check(new UserDAO().authenticate("employee@example.com", "NewPassword1!") != null,
        "password update is committed with token consumption");
    System.out.println("AccountTokenSecurityTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
