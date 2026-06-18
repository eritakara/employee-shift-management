package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import model.User;

public class PerformanceTest {
  private static final long LIMIT_MILLIS = 3_000;

  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-performance-").toString());
    Database.initialize();
    User manager = new UserDAO().authenticate("manager@example.com", "Password1!");
    PortalService portal = new PortalService();
    YearMonth month = YearMonth.now().plusMonths(1);
    LocalDate workDate = month.atDay(10);
    seedOneHundredEmployees(workDate);

    check(number("SELECT COUNT(*) count_value FROM users") == 100, "100 employee records prepared");
    check(number("SELECT COUNT(*) count_value FROM shifts WHERE work_date=?", workDate) == 100,
        "100 shift records prepared");

    measure("dashboard display", () -> check(!portal.dashboard(manager).isEmpty(), "dashboard result"));
    measure("employee list display", () -> check(portal.users(manager).size() == 99, "scoped employee list"));
    measure("shift list display", () -> check(portal.shifts(manager, month).size() == 99, "scoped shift list"));
    measure("employee search", () -> {
      List<Map<String, Object>> rows = Sql.query(
          "SELECT id FROM users WHERE active=TRUE AND (LOWER(name) LIKE LOWER(?) OR LOWER(employee_number) LIKE LOWER(?))",
          "%Performance 050%", "%Performance 050%");
      check(rows.size() == 1, "employee search result");
    });
    long employeeId = ((Number) Sql.one("SELECT id FROM users WHERE employee_number='PERF050'").get("id")).longValue();
    measure("shift save", () -> portal.saveShift(manager, employeeId, workDate.plusDays(1), "DAY", "DRAFT", "performance test"));
    System.out.println("PerformanceTest: all checks passed");
  }

  private static void seedOneHundredEmployees(LocalDate workDate) {
    String passwordHash = String.valueOf(Sql.one("SELECT password_hash FROM users WHERE email='employee@example.com'").get("password_hash"));
    for (int i = 1; i <= 96; i++) {
      String number = String.format("PERF%03d", i);
      long id = Sql.insert("INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role) "
              + "VALUES(?,?,?,?,?,4,1,1,'EMPLOYEE')",
          number, String.format("Performance %03d", i), number.toLowerCase() + "@example.test", passwordHash,
          LocalDate.of(2024, 4, 1));
      Sql.update("INSERT INTO leave_balances(user_id,days_remaining,hourly_used,last_granted_on) VALUES(?,10,0,CURRENT_DATE)", id);
    }
    for (Map<String, Object> row : Sql.query("SELECT id FROM users")) {
      long id = ((Number) row.get("id")).longValue();
      Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by) VALUES(?,?,'DAY','DRAFT',?)",
          id, workDate, id);
    }
  }

  private static int number(String sql, Object... args) {
    return ((Number) Sql.one(sql, args).get("count_value")).intValue();
  }

  private static void measure(String label, Runnable action) {
    long started = System.nanoTime();
    action.run();
    long elapsed = (System.nanoTime() - started) / 1_000_000;
    check(elapsed < LIMIT_MILLIS, label + " completed within 3 seconds (actual: " + elapsed + " ms)");
    System.out.println("PerformanceTest: " + label + " = " + elapsed + " ms");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}

