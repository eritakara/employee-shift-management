package util;

import config.Database;
import dao.UserDAO;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class PasswordUtilUpgradeTest {
  private static final int LEGACY_ITERATIONS = 120_000;

  public static void main(String[] args) throws Exception {
    String current = PasswordUtil.hash("CorrectHorseBatteryStaple1!");
    check(current.startsWith("pbkdf2-sha256$600000$"), "current hash includes algorithm and work factor");
    check(PasswordUtil.verify("CorrectHorseBatteryStaple1!", current), "current hash verifies");
    check(!PasswordUtil.verify("wrong", current), "incorrect password is rejected");
    check(!PasswordUtil.needsRehash(current), "current hash does not need upgrade");
    check(!PasswordUtil.verify("password", "pbkdf2-sha256$invalid$data$data"), "malformed hash is rejected");

    String legacy = legacyHash("Password1!");
    check(PasswordUtil.verify("Password1!", legacy), "legacy hash remains valid");
    check(PasswordUtil.needsRehash(legacy), "legacy hash is marked for upgrade");

    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-password-upgrade-").toString());
    Database.initialize();
    try (Connection connection = Database.getConnection();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE users SET password_hash=? WHERE LOWER(email)=LOWER(?)")) {
      update.setString(1, legacy);
      update.setString(2, "hr@example.com");
      check(update.executeUpdate() == 1, "legacy fixture is installed");
    }

    check(new UserDAO().authenticate("hr@example.com", "Password1!") != null,
        "legacy user can authenticate");
    String upgraded = storedHash("hr@example.com");
    check(upgraded.startsWith("pbkdf2-sha256$600000$"), "successful login upgrades stored hash");
    check(PasswordUtil.verify("Password1!", upgraded), "upgraded hash verifies");
    check(!legacy.equals(upgraded), "legacy hash is replaced");
    System.out.println("PasswordUtilUpgradeTest: all checks passed");
  }

  private static String legacyHash(String password) throws Exception {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, LEGACY_ITERATIONS, 256);
    try {
      byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
          .generateSecret(spec).getEncoded();
      return Base64.getEncoder().encodeToString(salt) + ":"
          + Base64.getEncoder().encodeToString(hash);
    } finally {
      spec.clearPassword();
    }
  }

  private static String storedHash(String email) throws Exception {
    try (Connection connection = Database.getConnection();
         PreparedStatement query = connection.prepareStatement(
             "SELECT password_hash FROM users WHERE LOWER(email)=LOWER(?)")) {
      query.setString(1, email);
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) throw new AssertionError("Failed: user exists");
        return result.getString(1);
      }
    }
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
