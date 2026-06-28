package service;

import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
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
    Sql.update("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
        employee.getId(), date, date.atTime(8, 0), date.atTime(17, 0));
    Sql.update("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,status) VALUES(?,?,?,?, 'COMPLETE')",
        hr.getId(), date, date.atTime(8, 0), date.atTime(17, 0));
    Sql.update("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason,status) VALUES(?,?,'FULL','export test','APPROVED')", employee.getId(), date);
    Sql.update("INSERT INTO leave_requests(user_id,leave_date,leave_unit,reason,status) VALUES(?,?,'FULL','export test','APPROVED')", hr.getId(), date);
    ExportService exports = new ExportService();

    check(exports.rows(hr, "shifts", date, date, null, null, null).size() == 2, "unfiltered export");
    check(exports.rows(hr, "shifts", date, date, employee.getBranchId(), null, null).size() == 1, "branch filter");
    check(exports.rows(hr, "shifts", date, date, null, employee.getDepartmentId(), null).size() == 1, "department filter");
    List<Map<String, Object>> employeeRows = exports.rows(hr, "shifts", date, date, null, null, employee.getId());
    check(employeeRows.size() == 1 && employee.getEmployeeNumber().equals(employeeRows.get(0).get("employee_number")), "employee filter");
    check(exports.rows(hr, "attendance", date, date, employee.getBranchId(), employee.getDepartmentId(), null).size() == 1, "attendance scope filters");
    check(exports.rows(hr, "leave", date, date, null, null, employee.getId()).size() == 1, "leave employee filter");
    expectDenied(() -> exports.rows(employee, "shifts", date, date, null, null, null), "HR-only export");
    expectInvalid(() -> exports.rows(hr, "shifts", date, date.minusDays(1), null, null, null), "invalid period");

    Map<String, Object> unsafe = new LinkedHashMap<>();
    unsafe.put("name", "山田 花子"); unsafe.put("note", "=SUM(1,1)"); unsafe.put("html", "<script>");
    String csv = exports.csv(List.of(unsafe));
    check(csv.startsWith("\ufeff") && csv.contains("山田 花子") && csv.contains("'=SUM(1,1)"), "safe UTF-8 CSV");
    String excel = exports.excelHtml(List.of(unsafe));
    check(excel.contains("山田 花子") && excel.contains("&lt;script&gt;"), "escaped Excel HTML");
    StringWriter streamedCsv = new StringWriter();
    exports.writeCsv(List.of(unsafe), streamedCsv);
    check(csv.equals(streamedCsv.toString()), "streamed CSV content");
    StringWriter streamedExcel = new StringWriter();
    exports.writeExcelHtml(List.of(unsafe), streamedExcel);
    check(excel.equals(streamedExcel.toString()), "streamed Excel content");

    for (String type : List.of("shifts", "attendance", "leave")) {
      List<Map<String, Object>> rows = exports.rows(hr, type, date, date, null, null, employee.getId());
      check(rows.size() == 1, type + " export content");
      check(exports.csv(rows).contains(employee.getEmployeeNumber()), type + " CSV content");
      check(exports.excelHtml(rows).contains(employee.getName()), type + " Excel content");
    }

    PortalService portal = new PortalService();
    YearMonth printMonth = YearMonth.from(date);
    check(portal.shifts(employee, printMonth).size() == 1, "employee print scope");
    check(portal.shifts(manager, printMonth).size() == 1, "manager print scope");
    check(portal.shifts(hr, printMonth).size() == 2, "HR print scope");
    String printCss = Files.readString(java.nio.file.Path.of("src/main/webapp/assets/app.css"));
    check(printCss.contains("@media print") && printCss.contains(".no-print"), "print stylesheet");
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
