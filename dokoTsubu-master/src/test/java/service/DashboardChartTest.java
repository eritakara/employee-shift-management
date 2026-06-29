package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Map;
import model.User;

public class DashboardChartTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-chart-test-").toString());
    Database.initialize();
    User employee = new UserDAO().authenticate("employee@example.com", "Password1!");
    LocalDate monthDate = LocalDate.now().withDayOfMonth(10);
    Sql.update("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
        employee.getId(), monthDate, monthDate.atTime(8, 0), monthDate.atTime(17, 0));
    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by) VALUES(?,?,'DAY','CONFIRMED',?)",
        employee.getId(), monthDate, employee.getId());
    Sql.update("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason,status) VALUES(?,?,'FULL','chart test','APPROVED')",
        employee.getId(), monthDate.plusDays(1));
    Sql.update("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason,status) VALUES(?,?,'AM','chart test','APPROVED')",
        employee.getId(), monthDate.plusDays(2));

    String month = monthDate.toString().substring(0, 7);
    Map<String, Object> row = new PortalService().chart(employee).stream()
        .filter(item -> month.equals(item.get("month_label"))).findFirst().orElseThrow();
    check(((BigDecimal) row.get("leave_days")).compareTo(new BigDecimal("1.5")) == 0,
        "monthly chart includes approved leave days");
    check(((Number) row.get("total_hours")).doubleValue() == 8.0, "monthly chart includes working hours");

    // 店長ユーザーが未所属 (0L) の場合のダッシュボード表示テスト (Postgres/H2 0Lバインド検証)
    User manager = new UserDAO().authenticate("manager@example.com", "Password1!");
    User unassignedManager = new User(manager.getId(), manager.getEmployeeNumber(), manager.getName(),
        manager.getEmail(), "MANAGER", 0L, 0L, null, null, manager.getLocale());

    Map<String, Object> stats = new DashboardService().dashboard(unassignedManager);
    check(stats != null, "dashboard stats retrieval for unassigned (0L) manager is successful");
    check(stats.containsKey("pendingLeave"), "stats contains pendingLeave");
    check(stats.containsKey("pendingShift"), "stats contains pendingShift");
    check(stats.containsKey("pendingAttendance"), "stats contains pendingAttendance");

    System.out.println("DashboardChartTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}

