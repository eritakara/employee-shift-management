package service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class BackupRestoreTest {
  public static void main(String[] args) throws Exception {
    Class.forName("org.h2.Driver");
    Path root = Files.createTempDirectory("shiftflow-backup-restore-");
    Path source = root.resolve("source/shiftapp");
    Path backup = root.resolve("backup.sql");
    Path restored = root.resolve("restored/shiftapp");
    Files.createDirectories(source.getParent());
    Files.createDirectories(restored.getParent());

    try (Connection c = connect(source); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE restore_probe(id INT PRIMARY KEY, probe_text VARCHAR(80) NOT NULL)");
      s.execute("INSERT INTO restore_probe VALUES(1, 'consistent-script-backup')");
      s.execute("SCRIPT TO '" + sqlPath(backup) + "'");
      check(Files.size(backup) > 0, "SCRIPT creates a non-empty backup");
      check(count(s, "SELECT COUNT(*) FROM restore_probe") == 1, "source remains available");
    }

    check(!Files.exists(Path.of(restored + ".mv.db")), "restore target starts empty");
    try (Connection c = connect(restored); Statement s = c.createStatement()) {
      s.execute("RUNSCRIPT FROM '" + sqlPath(backup) + "'");
      check(count(s, "SELECT COUNT(*) FROM restore_probe WHERE probe_text='consistent-script-backup'") == 1,
          "restored data matches backup");
    }
    check(Files.exists(Path.of(restored + ".mv.db")), "restore creates only the separate database");
    check(Files.exists(Path.of(source + ".mv.db")), "source database is not overwritten");
    System.out.println("BackupRestoreTest: all checks passed");
  }

  private static Connection connect(Path database) throws Exception {
    return DriverManager.getConnection("jdbc:h2:file:" + database.toAbsolutePath(), "sa", "");
  }

  private static int count(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getInt(1);
    }
  }

  private static String sqlPath(Path path) {
    return path.toAbsolutePath().toString().replace("\\", "/").replace("'", "''");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
