package config;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class ProductionInitializationTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-production-init-").toString());
    System.setProperty("shiftapp.seedDemo", "false");
    Database.initialize();
    try (Connection connection = Database.getConnection(); Statement statement = connection.createStatement()) {
      check(count(statement, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'") > 0,
          "production schema is initialized");
      check(count(statement, "SELECT COUNT(*) FROM users") == 0, "demo users are not inserted");
      check(count(statement, "SELECT COUNT(*) FROM branches") == 0, "demo master data is not inserted");
    }
    System.out.println("ProductionInitializationTest: all checks passed");
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

