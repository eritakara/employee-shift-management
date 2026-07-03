package config;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class ProductionInitializationTest {
  public static void main(String[] args) throws Exception {
    verifyDemoSeedProductionGuard();
    expectCredentialFailure("admin@example.com", "Password1!", "default production HR password is rejected independently");
    expectCredentialFailure("hr@example.com", "A-secure-production-password", "default production HR email is rejected independently");
    expectCredentialFailure("admin@example.com", "", "blank production HR password is rejected");
    expectCredentialFailure("", "A-secure-production-password", "blank production HR email is rejected");
    Database.validateInitialHrCredentials(true, "admin@example.com", "A-secure-production-password");
    Database.validateInitialHrCredentials(false, "hr@example.com", "Password1!");
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-production-init-").toString());
    System.setProperty("shiftapp.seedDemo", "false");
    System.setProperty("shiftapp.demoSeed", "false");
    Database.initialize();
    try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
      check(count(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'") > 0,
          "production schema is initialized");
      check(count(statement, "SELECT COUNT(*) FROM users") == 0, "demo users are not inserted");
      check(count(statement, "SELECT COUNT(*) FROM branches") == 0, "demo master data is not inserted");
    }
    System.out.println("ProductionInitializationTest: all checks passed");
  }

  private static void verifyDemoSeedProductionGuard() {
    Database.validateDemoSeedConfiguration(false, true);
    Database.validateDemoSeedConfiguration(true, false);
    try {
      Database.validateDemoSeedConfiguration(true, true);
    } catch (IllegalStateException expected) {
      check("DEMO_SEED must be disabled in production.".equals(expected.getMessage()),
          "demo seed guard uses a fixed safe error message");
      return;
    }
    throw new AssertionError("Failed: production rejects DEMO_SEED=true");
  }

  private static void expectCredentialFailure(String email, String password, String label) {
    try {
      Database.validateInitialHrCredentials(true, email, password);
    } catch (IllegalStateException expected) {
      check(password == null || password.isEmpty() || !expected.getMessage().contains(password), label + " without credential disclosure");
      return;
    }
    throw new AssertionError("Failed: " + label);
  }

  private static int count(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getInt(1);
    }
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}

