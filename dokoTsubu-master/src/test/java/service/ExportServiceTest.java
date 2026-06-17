package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.User;

public class ExportServiceTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-export-test-").toString());
    Database.initialize();
    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    LocalDate date = LocalDate.now().plusDays(1);
    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by) VALUES(?,?,'DAY','DRAFT',?)", employee.getId(), date, manager.getId());
    Sql.update("INSERT INTO shifts(user_id,work_date,work_type_code,status,updated_by) VALUES(?,?,'DAY','DRAFT',?)", hr.getId(), date, hr.getId());
    ExportService exports = new ExportService();

    check(exports.rows(hr, "shifts", date, date, null, null, null).size() == 2, "unfiltered export");
    check(exports.rows(hr, "shifts", date, date, employee.getBranchId(), null, null).size() == 1, "branch filter");
    check(exports.rows(hr, "shifts", date, date, null, employee.getDepartmentId(), null).size() == 1, "department filter");
    List<Map<String, Object>> employeeRows = exports.rows(hr, "shifts", date, date, null, null, employee.getId());
    check(employeeRows.size() == 1 && employee.getEmployeeNumber().equals(employeeRows.get(0).get("employee_number")), "employee filter");
    expectDenied(() -> exports.rows(employee, "shifts", date, date, null, null, null), "HR-only export");
    expectInvalid(() -> exports.rows(hr, "shifts", date, date.minusDays(1), null, null, null), "invalid period");

    Map<String, Object> unsafe = new LinkedHashMap<>();
    unsafe.put("name", "山田 花子"); unsafe.put("note", "=SUM(1,1)"); unsafe.put("html", "<script>");
    String csv = exports.csv(List.of(unsafe));
    check(csv.startsWith("\ufeff") && csv.contains("山田 花子") && csv.contains("'=SUM(1,1)"), "safe UTF-8 CSV");
    String excel = exports.excelHtml(List.of(unsafe));
    check(excel.contains("山田 花子") && excel.contains("&lt;script&gt;"), "escaped Excel HTML");
    System.out.println("ExportServiceTest: all checks passed");
  }

  private static void expectDenied(Runnable action, String label) {
    try { action.run(); } catch (SecurityException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void expectInvalid(Runnable action, String label) {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
