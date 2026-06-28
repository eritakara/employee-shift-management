package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.YearMonth;
import model.User;

public class ShiftAutoAssignmentPerformanceTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-auto-assign-perf-").toString());
    Database.initialize();
    User manager = new UserDAO().authenticate("manager@example.com", "Password1!");
    YearMonth month = YearMonth.of(2026, 7);

    long started = System.nanoTime();
    int assigned = new ShiftService().autoAssignShifts(manager, month);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
    int saved = ((Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts WHERE work_date BETWEEN ? AND ?",
        month.atDay(1), month.atEndOfMonth()).getOrDefault("metric_value", 0)).intValue();

    if (assigned <= 0 || saved <= 0) throw new AssertionError("Automatic assignment did not create shifts");
    System.out.println("ShiftAutoAssignmentPerformanceTest: assigned=" + assigned
        + ", saved=" + saved + ", elapsedMs=" + elapsedMillis);
  }
}
