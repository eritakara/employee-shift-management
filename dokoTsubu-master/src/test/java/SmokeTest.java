import config.Database;
import dao.Sql;
import dao.UserDAO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import model.User;
import service.PortalService;
import service.SettingsService;

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
    check(!portal.shiftWarningsForDate(manager, tomorrow).isEmpty(), "shift warning recheck");
    check(!Sql.query("SELECT id FROM notifications WHERE user_id=? AND type='SHIFT_RECHECK'", manager.getId()).isEmpty(), "shift recheck notification");
    portal.confirmMonth(manager, YearMonth.from(tomorrow), "テスト環境の人員不足を確認済み");
    check(!Sql.query("SELECT id FROM notifications WHERE user_id=? AND type='SHIFT_CONFIRMED'", employee.getId()).isEmpty(), "shift confirmation notification");
    check(!Sql.query("SELECT id FROM mail_outbox WHERE recipient=? AND subject='シフト確定'", employee.getEmail()).isEmpty(), "shift confirmation mail queued");

    portal.requestLeave(employee, tomorrow.plusDays(1), "FULL", null, "family matter");
    long leaveId = ((Number) portal.leaveRequests(manager).get(0).get("id")).longValue();
    portal.decideLeave(manager, leaveId, true);
    check("APPROVED".equals(portal.leaveRequests(employee).get(0).get("status")), "leave approval");

    portal.clock(employee, true, "26.2124", "127.6809", "ACQUIRED");
    check(!portal.attendance(employee, YearMonth.now()).isEmpty(), "clock in");
    check(!portal.dashboard(employee).isEmpty(), "dashboard");

    SettingsService settings = new SettingsService();
    settings.update(hr, "SHIFT_SUBMISSION_DAY", "20");
    check(settings.integer("SHIFT_SUBMISSION_DAY", 0) == 20, "HR setting update");

    portal.addMaster(hr, "qualifications", "テスト資格");
    long qualificationTypeId = ((Number) portal.getMasterData("qualifications").get(0).get("id")).longValue();
    portal.updateMaster(hr, "qualifications", qualificationTypeId, "更新資格", false);
    Map<String, Object> qualificationType = Sql.one("SELECT name,active FROM qualification_types WHERE id=?", qualificationTypeId);
    check("更新資格".equals(qualificationType.get("name")) && Boolean.FALSE.equals(qualificationType.get("active")), "qualification type update");
    portal.updateMaster(hr, "qualifications", qualificationTypeId, "更新資格", true);
    portal.addQualification(hr, employee.getId(), "更新資格", tomorrow.plusYears(1));
    long qualificationId = ((Number) Sql.one("SELECT MAX(id) id FROM qualifications WHERE user_id=?", employee.getId()).get("id")).longValue();
    portal.updateQualification(hr, qualificationId, "更新資格", tomorrow.plusYears(2), false);
    Map<String, Object> qualification = Sql.one("SELECT expires_on,active FROM qualifications WHERE id=?", qualificationId);
    check(Boolean.FALSE.equals(qualification.get("active")) && tomorrow.plusYears(2).toString().equals(String.valueOf(qualification.get("expires_on"))), "qualification history update");

    portal.addDelegation(manager, manager.getId(), employee.getId(), tomorrow, tomorrow.plusDays(5));
    long delegationId = ((Number) Sql.one("SELECT MAX(id) id FROM delegations WHERE manager_id=? AND delegate_id=?", manager.getId(), employee.getId()).get("id")).longValue();
    portal.updateDelegation(hr, delegationId, tomorrow.plusDays(1), tomorrow.plusDays(6), false);
    Map<String, Object> delegation = Sql.one("SELECT starts_on,ends_on,active FROM delegations WHERE id=?", delegationId);
    check(Boolean.FALSE.equals(delegation.get("active")) && tomorrow.plusDays(1).toString().equals(String.valueOf(delegation.get("starts_on"))), "delegation update");

    check(!Sql.query("SELECT * FROM audit_logs").isEmpty(), "audit log");
    System.out.println("SmokeTest: all checks passed");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
