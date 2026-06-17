import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import model.User;
import service.PortalService;

public class SmokeTest {
  public static void main(String[] args) throws Exception {
    Path data = Files.createTempDirectory("shiftflow-test-");
    System.setProperty("shiftapp.dataDir", data.toString());
    Database.initialize();

    UserDAO users = new UserDAO();
    User employee = users.authenticate("employee@example.com", "Password1!");
    User manager = users.authenticate("manager@example.com", "Password1!");
    User hr = users.authenticate("hr@example.com", "Password1!");
    check(employee != null && "EMPLOYEE".equals(employee.getRole()), "employee login");
    check(manager != null && manager.isManager(), "manager login");
    check(hr != null && hr.isHr(), "HR login");

    PortalService portal = new PortalService();
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    portal.saveShift(employee, employee.getId(), tomorrow, "DAY", "DRAFT", "test");
    check(portal.shifts(employee, YearMonth.from(tomorrow)).size() == 1, "shift save");

    portal.requestShiftChange(employee, tomorrow, "OFF", "private appointment");
    long changeId = ((Number) portal.shiftChangeRequests(manager).get(0).get("id")).longValue();
    portal.decideShiftChange(manager, changeId, true);
    check("OFF".equals(portal.shifts(employee, YearMonth.from(tomorrow)).get(0).get("work_type_code")), "shift approval");

    portal.requestLeave(employee, tomorrow.plusDays(1), "FULL", null, "family matter");
    long leaveId = ((Number) portal.leaveRequests(manager).get(0).get("id")).longValue();
    portal.decideLeave(manager, leaveId, true);
    check("APPROVED".equals(portal.leaveRequests(employee).get(0).get("status")), "leave approval");

    portal.clock(employee, true, "26.2124", "127.6809", "ACQUIRED");
    check(!portal.attendance(employee, YearMonth.now()).isEmpty(), "clock in");
    check(!portal.dashboard(employee).isEmpty(), "dashboard");
    check(!Sql.query("SELECT * FROM audit_logs").isEmpty(), "audit log");
    System.out.println("SmokeTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
