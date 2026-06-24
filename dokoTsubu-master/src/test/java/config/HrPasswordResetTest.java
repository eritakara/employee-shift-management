package config;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import util.PasswordUtil;

public class HrPasswordResetTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-hr-reset-").toString());
    System.setProperty("shiftapp.seedDemo", "true"); // デモユーザーも作成する（HR以外もテストするため）

    // 1. 初回の初期化を実行して初期ユーザーを作成する
    Database.initialize();
    
    String hrEmail = "hr@example.com";
    String employeeEmail = "employee@example.com";
    String newPassword = "NewSecurePassword123!";
    
    // 初期パスワードが機能することを確認
    try (Connection connection = Database.getConnection()) {
      check(verifyPassword(connection, hrEmail, "Password1!"), "Initial password is Password1!");
    }

    // 2. パスワードを正常に変更するテスト
    System.setProperty("shiftapp.resetHrPasswordEnabled", "true");
    System.setProperty("shiftapp.resetHrEmail", hrEmail);
    System.setProperty("shiftapp.resetHrPassword", newPassword);
    resetDatabase();
    Database.initialize();
    try (Connection connection = Database.getConnection()) {
      check(verifyPassword(connection, hrEmail, newPassword), "Password is updated to NewSecurePassword123!");
      check(!verifyPassword(connection, hrEmail, "Password1!"), "Old password no longer works");
    }

    // 3. パスワードが脆弱（Password1!）な場合に例外が発生することを確認
    System.setProperty("shiftapp.resetHrPassword", "Password1!");
    resetDatabase();
    try {
      Database.initialize();
      throw new AssertionError("Should fail for weak password");
    } catch (IllegalStateException e) {
      check(e.getMessage().contains("RESET_HR_PASSWORD environment variable must be configured with a secure value"), 
          "Fails with expected weak password message");
    }

    // 4. 対象ユーザーが存在しない場合に例外が発生することを確認
    System.setProperty("shiftapp.resetHrEmail", "nonexistent@example.com");
    System.setProperty("shiftapp.resetHrPassword", "NewSecurePassword123!");
    resetDatabase();
    try {
      Database.initialize();
      throw new AssertionError("Should fail for nonexistent user");
    } catch (IllegalStateException e) {
      check(e.getMessage().contains("Target user for password reset was not found"), 
          "Fails with expected nonexistent user message");
    }

    // 5. 対象ユーザーが HR ロールではない場合に例外が発生することを確認
    System.setProperty("shiftapp.resetHrEmail", employeeEmail);
    System.setProperty("shiftapp.resetHrPassword", "NewSecurePassword123!");
    resetDatabase();
    try {
      Database.initialize();
      throw new AssertionError("Should fail for non-HR user");
    } catch (IllegalStateException e) {
      check(e.getMessage().contains("Target user is not an HR user"), 
          "Fails with expected non-HR user message");
    }

    System.out.println("HrPasswordResetTest: all checks passed");
  }

  private static void resetDatabase() throws Exception {
    java.lang.reflect.Field urlField = Database.class.getDeclaredField("jdbcUrl");
    urlField.setAccessible(true);
    urlField.set(null, null);
    
    java.lang.reflect.Field userField = Database.class.getDeclaredField("jdbcUser");
    userField.setAccessible(true);
    userField.set(null, null);

    java.lang.reflect.Field passField = Database.class.getDeclaredField("jdbcPassword");
    passField.setAccessible(true);
    passField.set(null, null);
  }

  private static boolean verifyPassword(Connection connection, String email, String password) throws Exception {
    String sql = "SELECT password_hash FROM users WHERE email=?";
    try (PreparedStatement p = connection.prepareStatement(sql)) {
      p.setString(1, email);
      try (ResultSet r = p.executeQuery()) {
        if (r.next()) {
          String stored = r.getString("password_hash");
          return PasswordUtil.verify(password, stored);
        }
      }
    }
    return false;
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
