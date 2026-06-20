package config;

import dao.Sql;
import java.nio.file.Files;
import java.time.LocalDate;

public class DemoShiftCsvTest {
  public static void main(String[] args) throws Exception {
    System.setProperty("shiftapp.dataDir", Files.createTempDirectory("shiftflow-demo-csv-").toString());
    System.setProperty("shiftapp.seedDemoShifts", "true");
    Database.initialize();

    Number count = (Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE u.employee_number='EM001' AND s.work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30'")
        .get("metric_value");
    if (count == null || count.intValue() != 30) {
      throw new AssertionError("Expected 30 June demo shifts, got " + count);
    }
    Number addedUsers = (Number) Sql.one("SELECT COUNT(*) metric_value FROM users WHERE (employee_number BETWEEN 'EM003' AND 'EM015') OR employee_number='MG002'").get("metric_value");
    Number staff = (Number) Sql.one("SELECT COUNT(*) metric_value FROM users WHERE employee_number BETWEEN 'EM003' AND 'EM015' AND role='EMPLOYEE'").get("metric_value");
    Number managers = (Number) Sql.one("SELECT COUNT(*) metric_value FROM users WHERE employee_number='MG002' AND role='MANAGER'").get("metric_value");
    Number clonedShifts = (Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts s JOIN users u ON u.id=s.user_id "
        + "WHERE ((u.employee_number BETWEEN 'EM003' AND 'EM015') OR u.employee_number='MG002') "
        + "AND s.work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30'").get("metric_value");
    if (addedUsers.intValue() != 14 || staff.intValue() != 13 || managers.intValue() != 1 || clonedShifts.intValue() != 420) {
      throw new AssertionError("Unexpected Naha demo data: users=" + addedUsers + ", staff=" + staff
          + ", managers=" + managers + ", shifts=" + clonedShifts);
    }
    Number attendanceRows = (Number) Sql.one("SELECT COUNT(*) metric_value FROM attendance a JOIN users u ON u.id=a.user_id "
        + "WHERE u.employee_number BETWEEN 'EM001' AND 'EM010' AND a.work_date BETWEEN DATE '2026-06-17' AND DATE '2026-06-20'")
        .get("metric_value");
    Number openAttendance = (Number) Sql.one("SELECT COUNT(*) metric_value FROM attendance a JOIN users u ON u.id=a.user_id "
        + "WHERE u.employee_number='EM002' AND a.work_date=DATE '2026-06-20' AND a.clock_in IS NOT NULL AND a.clock_out IS NULL")
        .get("metric_value");
    Number finalizedAttendance = (Number) Sql.one("SELECT COUNT(*) metric_value FROM attendance a JOIN users u ON u.id=a.user_id "
        + "WHERE u.employee_number='EM008' AND a.work_date=DATE '2026-06-20' AND a.finalized=TRUE")
        .get("metric_value");
    if (attendanceRows.intValue() != 11 || openAttendance.intValue() != 1 || finalizedAttendance.intValue() != 1) {
      throw new AssertionError("Unexpected demo attendance data: rows=" + attendanceRows
          + ", open=" + openAttendance + ", finalized=" + finalizedAttendance);
    }
    int[] expectedBranchPeople = {6, 5, 6};
    for (int branch = 2; branch <= 4; branch++) {
      Number people = (Number) Sql.one("SELECT COUNT(*) metric_value FROM users WHERE branch_id=? AND employee_number IN "
          + "('MG001','MG002','EM001','EM002','EM003','EM004','EM005','EM006','EM007','EM008','EM009','EM010','EM011','EM012','EM013','EM014','EM015')", branch).get("metric_value");
      if (people.intValue() != expectedBranchPeople[branch - 2]) throw new AssertionError("Unexpected branch allocation: " + branch + "=" + people);
      for (int day = 1; day <= 30; day++) {
        LocalDate date = LocalDate.of(2026, 6, day);
        Number dayWorkers = (Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts s JOIN users u ON u.id=s.user_id WHERE u.branch_id=? AND s.work_date=? AND s.work_type_code='DAY'", branch, date).get("metric_value");
        Number nightWorkers = (Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts s JOIN users u ON u.id=s.user_id WHERE u.branch_id=? AND s.work_date=? AND s.work_type_code='NIGHT'", branch, date).get("metric_value");
        if (dayWorkers.intValue() < 1 || nightWorkers.intValue() < 1) throw new AssertionError("Missing branch staffing: branch=" + branch + ", date=" + date);
      }
    }
    Number leaveUsers = (Number) Sql.one("SELECT COUNT(DISTINCT user_id) metric_value FROM shifts WHERE work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30' AND work_type_code='LEAVE'").get("metric_value");
    Number leaveTotal = (Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts WHERE work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30' AND work_type_code='LEAVE'").get("metric_value");
    Number halfLeaveTotal = (Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts WHERE work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-30' AND work_type_code IN('AM_LEAVE','PM_LEAVE')").get("metric_value");
    Number nightRestViolations = (Number) Sql.one("SELECT COUNT(*) metric_value FROM shifts n JOIN shifts r ON r.user_id=n.user_id AND r.work_date=DATEADD('DAY',1,n.work_date) WHERE n.work_date BETWEEN DATE '2026-06-01' AND DATE '2026-06-29' AND n.work_type_code='NIGHT' AND r.work_type_code<>'NIGHT_OFF'").get("metric_value");
    if (leaveUsers.intValue() != 17 || leaveTotal.intValue() != 17 || halfLeaveTotal.intValue() < 1 || nightRestViolations.intValue() != 0) {
      throw new AssertionError("Unexpected leave/rest allocation: leaveUsers=" + leaveUsers + ", leaveTotal=" + leaveTotal
          + ", halfLeave=" + halfLeaveTotal + ", nightRestViolations=" + nightRestViolations);
    }
    System.out.println("DemoShiftCsvTest: all checks passed");
  }
}
