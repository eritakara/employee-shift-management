package config;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.YearMonth;
import java.util.Map;
import model.User;
import service.AttendanceService;

public class DemoAttendanceSeederTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-demo-attendance-").toString());
    System.setProperty("shiftapp.demoSeed", "true");
    System.setProperty("shiftapp.demoAttendanceReset", "false");
    System.setProperty("shiftapp.demoAttendanceResetConfirm", "RESET_DEMO_TRANSACTIONS");
    System.setProperty("shiftapp.demoAttendanceResetToken", "test-run-1");
    System.setProperty("shiftapp.demoScenarioYear", "2026");
    System.setProperty("shiftapp.demoOperationDate", "2026-08-10");
    Database.initialize();

    try (Connection c = Database.getConnection()) {
      ensureRosterUsersInEveryBranch(c);
      ensureOkinawaTaro(c);
      System.setProperty("shiftapp.demoAttendanceReset", "true");
      DemoAttendanceSeeder.runIfEnabled(c);
      check(count(c, "SELECT COUNT(*) FROM attendance WHERE work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND finalized=FALSE") == 0, "May is finalized");
      check(count(c, "SELECT COUNT(*) FROM attendance WHERE work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31'") > 0, "May has attendance");
      check(count(c, "SELECT COUNT(*) FROM attendance WHERE work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30' AND finalized=TRUE") == 0, "June is open");
      check(count(c, "SELECT COUNT(*) FROM attendance WHERE work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30' AND clock_in IS NOT NULL AND clock_out IS NULL") == 1, "one June missing clock-out");
      check(count(c, "SELECT COUNT(*) FROM attendance_adjustments a JOIN attendance t ON t.id=a.attendance_id WHERE t.work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30' AND a.status='PENDING'") == 1, "one June pending adjustment");
      check(count(c, "SELECT COUNT(*) FROM leave_requests WHERE leave_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30' AND status='APPROVED'") == 1, "June approved leave");
      check(count(c, "SELECT COUNT(*) FROM leave_requests WHERE leave_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30' AND status='PENDING'") == 1, "June pending leave");
      for (String branch : new String[]{"本社", "北部支店", "中部支店", "那覇支店", "南部支店", "石垣支店", "宮古支店"}) {
        check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name='" + branch + "' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31'") > 0,
            "May shifts exist for " + branch);
      }
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM branches b JOIN users u ON u.branch_id=b.id JOIN shifts s ON s.user_id=u.id WHERE s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31'") == 7, "May shifts cover all branches");
      check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name='本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND EXTRACT(DAY_OF_WEEK FROM s.work_date) IN (1,7) AND s.work_type_code='OFF'") > 0, "head office weekends are explicit days off");
      check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name='本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND EXTRACT(DAY_OF_WEEK FROM s.work_date) IN (1,7) AND s.work_type_code<>'OFF'") == 0, "head office weekend entries are only days off");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name<>'本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND EXTRACT(DAY_OF_WEEK FROM s.work_date) IN (1,7)") == 6, "all branches have weekend shifts");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name<>'本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND s.work_type_code='NIGHT'") == 6, "all branches have night shifts");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name<>'本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND s.work_type_code='NIGHT_OFF'") == 6, "all branches have post-night shifts");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name<>'本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND s.work_type_code='DAY'") == 6, "all branches have day shifts");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name<>'本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND s.work_type_code='OFF'") == 6, "all branches have days off");
      check(count(c, "SELECT COUNT(*) FROM shifts s1 LEFT JOIN shifts s2 ON s2.user_id=s1.user_id AND s2.work_date=DATEADD('DAY',1,s1.work_date) WHERE s1.work_type_code='NIGHT' AND s1.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-07-30' AND (s2.id IS NULL OR s2.work_type_code<>'NIGHT_OFF')") == 0, "every night shift is followed by post-night rest");
      check(count(c, "SELECT COUNT(*) FROM (SELECT b.id,s.work_date,SUM(CASE WHEN s.work_type_code='DAY' THEN 1 ELSE 0 END) day_count,SUM(CASE WHEN s.work_type_code='NIGHT' THEN 1 ELSE 0 END) night_count FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name<>'本社' AND u.role='EMPLOYEE' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' GROUP BY b.id,s.work_date HAVING SUM(CASE WHEN s.work_type_code='DAY' THEN 1 ELSE 0 END)<1 OR SUM(CASE WHEN s.work_type_code='NIGHT' THEN 1 ELSE 0 END)<1) coverage_gaps") == 0, "every branch day has at least one day and one night employee");
      check(count(c, "SELECT COUNT(*) FROM users u JOIN branches b ON b.id=u.branch_id WHERE u.active=TRUE AND u.role='EMPLOYEE' AND b.name IN ('本社','北部支店','中部支店','那覇支店','南部支店','石垣支店','宮古支店') AND (SELECT COUNT(*) FROM shifts s WHERE s.user_id=u.id AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31')<>31") == 0, "every demo employee has a complete May roster without blanks");
      check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE b.name<>'本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND s.work_type_code NOT IN ('DAY','NIGHT','NIGHT_OFF','OFF','LEAVE')") == 0, "branch roster contains no work type rendered as dash");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE u.role='MANAGER' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31'") == 7, "May manager rosters cover all branches");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE u.role='MANAGER' AND s.work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30'") == 7, "June manager rosters cover all branches");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE u.role='MANAGER' AND s.work_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-31'") == 7, "July manager rosters cover all branches");
      check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id WHERE u.role='MANAGER' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-07-31' AND s.work_type_code NOT IN ('DAY','OFF')") == 0, "managers only have day or off shifts");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE u.role='MANAGER' AND b.name<>'本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND EXTRACT(DAY_OF_WEEK FROM s.work_date) IN (1,7) AND s.work_type_code='DAY'") == 6, "branch managers include weekend day shifts");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE u.role='MANAGER' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND s.work_type_code='OFF'") == 7, "manager days off are explicit in all branches");
      for (String name : new String[]{"人事担当", "沖縄太郎"}) {
        check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id WHERE REPLACE(REPLACE(u.name,' ',''),'　','')='" + name + "' AND b.name='本社' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31'") == 31, name + " has complete May HQ roster");
        check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id WHERE REPLACE(REPLACE(u.name,' ',''),'　','')='" + name + "' AND s.work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30'") == 30, name + " has complete June roster");
        check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id WHERE REPLACE(REPLACE(u.name,' ',''),'　','')='" + name + "' AND s.work_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-31'") == 31, name + " has complete July roster");
        check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id WHERE REPLACE(REPLACE(u.name,' ',''),'　','')='" + name + "' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-07-31' AND s.work_type_code NOT IN ('DAY','OFF')") == 0, name + " only has day or off shifts");
        check(count(c, "SELECT COUNT(*) FROM shifts s JOIN users u ON u.id=s.user_id WHERE REPLACE(REPLACE(u.name,' ',''),'　','')='" + name + "' AND s.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31' AND EXTRACT(DAY_OF_WEEK FROM s.work_date) IN (1,7) AND s.work_type_code<>'OFF'") == 0, name + " weekends are explicit days off");
      }
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM branches b JOIN users u ON u.branch_id=b.id JOIN attendance a ON a.user_id=u.id WHERE a.work_date BETWEEN DATE '2026-05-01' AND DATE '2026-05-31'") == 7, "May attendance covers all branches");
      int before = count(c, "SELECT COUNT(*) FROM attendance");
      DemoAttendanceSeeder.runIfEnabled(c);
      check(count(c, "SELECT COUNT(*) FROM attendance") == before, "rerun is idempotent");
      check(count(c, "SELECT COUNT(*) FROM attendance WHERE work_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-31'") > 0, "July has a few records");
      check(count(c, "SELECT COUNT(DISTINCT b.id) FROM branches b JOIN users u ON u.branch_id=b.id JOIN shifts s ON s.user_id=u.id WHERE s.work_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-31'") == 7, "July shifts cover all branches");
      check(count(c, "SELECT COUNT(*) FROM leave_requests WHERE leave_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-31'") > 0, "July has leave operation data");
      check(count(c, "SELECT COUNT(*) FROM attendance_adjustments a JOIN attendance t ON t.id=a.attendance_id WHERE t.work_date BETWEEN DATE '2026-07-01' AND DATE '2026-07-31'") > 0, "July has adjustment operation data");
      check(count(c, "SELECT COUNT(*) FROM attendance WHERE work_date BETWEEN DATE '2026-08-01' AND DATE '2026-08-31'") > 0, "execution month has recent records");
      check(count(c, "SELECT COUNT(*) FROM shifts s LEFT JOIN attendance a ON a.user_id=s.user_id AND a.work_date=s.work_date WHERE s.work_date=DATE '2026-08-10' AND a.id IS NULL") == 1, "today is ready for clock-in");
      check(count(c, "SELECT COUNT(*) FROM app_settings WHERE setting_key='DEMO_ATTENDANCE_RESET_TOKEN' AND setting_value='test-run-1'") == 1, "successful token recorded");
      verifyJuneAdjustmentApproval(c);
    }
    System.out.println("DemoAttendanceSeederTest: all checks passed");
  }

  private static void verifyJuneAdjustmentApproval(Connection c) throws Exception {
    String requestSql = "SELECT r.id request_id,r.requested_out,a.id attendance_id,a.clock_out,u.branch_id,u.department_id "
        + "FROM attendance_adjustments r JOIN attendance a ON a.id=r.attendance_id JOIN users u ON u.id=r.requested_by "
        + "WHERE r.status='PENDING' AND a.work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30'";
    long requestId;
    long attendanceId;
    long branchId;
    long departmentId;
    try (PreparedStatement p = c.prepareStatement(requestSql); ResultSet r = p.executeQuery()) {
      check(r.next(), "June pending adjustment is visible to approval query");
      requestId = r.getLong("request_id"); attendanceId = r.getLong("attendance_id");
      branchId = r.getLong("branch_id"); departmentId = r.getLong("department_id");
      check(r.getObject("clock_out") == null, "June adjustment target has missing clock-out");
      check(r.getObject("requested_out") != null, "June adjustment proposes a clock-out");
      check(!r.next(), "only one June pending adjustment exists");
    }
    User manager;
    try (PreparedStatement p = c.prepareStatement("SELECT u.id,u.employee_number,u.name,u.email,b.name branch_name,d.name department_name FROM users u JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id WHERE u.active=TRUE AND u.role='MANAGER' AND u.branch_id=? AND u.department_id=? ORDER BY u.id LIMIT 1")) {
      p.setLong(1, branchId); p.setLong(2, departmentId);
      try (ResultSet r = p.executeQuery()) {
        check(r.next(), "same-scope manager exists for June approval demo");
        manager = new User(r.getLong("id"), r.getString("employee_number"), r.getString("name"), r.getString("email"),
            "MANAGER", branchId, departmentId, r.getString("branch_name"), r.getString("department_name"), "ja");
      }
    }
    User hr;
    try (PreparedStatement p = c.prepareStatement("SELECT u.id,u.employee_number,u.name,u.email,u.branch_id,u.department_id,b.name branch_name,d.name department_name FROM users u JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id WHERE u.active=TRUE AND u.role='HR' ORDER BY u.id LIMIT 1"); ResultSet r = p.executeQuery()) {
      check(r.next(), "HR exists for June approval demo");
      hr = new User(r.getLong("id"), r.getString("employee_number"), r.getString("name"), r.getString("email"), "HR",
          r.getLong("branch_id"), r.getLong("department_id"), r.getString("branch_name"), r.getString("department_name"), "ja");
    }
    AttendanceService service = new AttendanceService();
    Map<String, Object> visible = service.attendanceAdjustments(manager, YearMonth.of(2026, 6)).stream()
        .filter(row -> ((Number) row.get("id")).longValue() == requestId).findFirst().orElseThrow();
    check(AttendanceService.canDecide(manager, visible), "June request can be approved from manager approval screen");
    Map<String, Object> hrVisible = service.attendanceAdjustments(hr, YearMonth.of(2026, 6)).stream()
        .filter(row -> ((Number) row.get("id")).longValue() == requestId).findFirst().orElseThrow();
    check(AttendanceService.canDecide(hr, hrVisible), "June request can be approved from HR approval screen");
    check(count(c, "SELECT COUNT(*) FROM notifications n JOIN users u ON u.id=n.user_id WHERE n.type='ATTENDANCE_ADJUSTMENT' AND n.target_url='/app/attendance/manage?month=2026-06' AND (u.role='HR' OR (u.role='MANAGER' AND u.branch_id=" + branchId + " AND u.department_id=" + departmentId + "))") >= 2,
        "June adjustment notifies HR and same-scope manager");
    service.decideAttendanceAdjustment(manager, requestId, true);
    check(count(c, "SELECT COUNT(*) FROM attendance WHERE id=" + attendanceId + " AND clock_out IS NOT NULL AND status='COMPLETE'") == 1,
        "approval resolves June missing clock-out");
    check(count(c, "SELECT COUNT(*) FROM attendance_adjustments WHERE id=" + requestId + " AND status='APPROVED'") == 1,
        "June adjustment becomes approved");
    Map<String, Object> approved = service.attendanceAdjustments(manager, YearMonth.of(2026, 6)).stream()
        .filter(row -> ((Number) row.get("id")).longValue() == requestId).findFirst().orElseThrow();
    check(!AttendanceService.canDecide(manager, approved), "approved request no longer shows decision actions");
    Map<String, Object> july = service.attendanceAdjustments(hr, YearMonth.of(2026, 7)).stream()
        .filter(row -> "PENDING".equals(row.get("status"))).findFirst().orElseThrow();
    long julyRequestId = ((Number) july.get("id")).longValue();
    service.decideAttendanceAdjustment(hr, julyRequestId, false, "デモ却下");
    check(count(c, "SELECT COUNT(*) FROM attendance_adjustments r JOIN attendance a ON a.id=r.attendance_id WHERE r.id=" + julyRequestId + " AND r.status='REJECTED' AND a.clock_out<>r.requested_out") == 1,
        "rejection leaves attendance unchanged and marks request rejected");
  }

  private static void ensureRosterUsersInEveryBranch(Connection c) throws Exception {
    ensureRoleInEveryBranch(c, "EMPLOYEE", "TEST-EM-", "支店テスト社員", "employee", 4);
    ensureRoleInEveryBranch(c, "MANAGER", "TEST-MG-", "支店テスト店長", "manager", 1);
  }

  private static void ensureOkinawaTaro(Connection c) throws Exception {
    if (count(c, "SELECT COUNT(*) FROM users WHERE REPLACE(REPLACE(name,' ',''),'　','')='沖縄太郎'") > 0) return;
    String sql = "INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role,locale,active) "
        + "SELECT 'TEST-HQ-TARO','沖縄 太郎','okinawa.taro@test.invalid',password_hash,hire_date,b.id,department_id,employment_type_id,'EMPLOYEE',locale,TRUE "
        + "FROM users u CROSS JOIN branches b WHERE u.role='EMPLOYEE' AND b.name='本社' ORDER BY u.id LIMIT 1";
    try (PreparedStatement p = c.prepareStatement(sql)) { p.executeUpdate(); }
  }

  private static void ensureRoleInEveryBranch(Connection c, String role, String numberPrefix,
      String namePrefix, String emailPrefix, int minimum) throws Exception {
    String sql = "INSERT INTO users(employee_number,name,email,password_hash,hire_date,branch_id,department_id,employment_type_id,role,locale,active) "
        + "SELECT ?,?,?,password_hash,hire_date,?,department_id,employment_type_id,?,locale,TRUE FROM users WHERE role=? ORDER BY id LIMIT 1";
    try (PreparedStatement find = c.prepareStatement("SELECT b.id,COUNT(u.id) user_count FROM branches b LEFT JOIN users u ON u.branch_id=b.id AND u.role=? AND u.active=TRUE GROUP BY b.id ORDER BY b.id");
         PreparedStatement insert = c.prepareStatement(sql)) {
      find.setString(1, role);
      try (ResultSet missing = find.executeQuery()) {
        while (missing.next()) {
          long branchId = missing.getLong(1);
          int existing = missing.getInt(2);
          for (int index = existing; index < minimum; index++) {
            insert.setString(1, numberPrefix + branchId + "-" + index); insert.setString(2, namePrefix + branchId + "-" + index);
            insert.setString(3, emailPrefix + branchId + "-" + index + "@test.invalid"); insert.setLong(4, branchId);
            insert.setString(5, role); insert.setString(6, role); insert.addBatch();
          }
        }
      }
      insert.executeBatch();
    }
  }

  private static int count(Connection c, String sql) throws Exception {
    try (PreparedStatement p = c.prepareStatement(sql); ResultSet r = p.executeQuery()) { r.next(); return r.getInt(1); }
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
