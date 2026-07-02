package config;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the destructive, opt-in attendance-close demonstration scenario. */
final class DemoAttendanceSeeder {
  private static final String ENABLE_PROPERTY = "shiftapp.demoAttendanceReset";
  private static final String ENABLE_ENV = "DEMO_ATTENDANCE_RESET";
  private static final int DEFAULT_YEAR = 2026;
  private static final String CONFIRMATION = "RESET_DEMO_TRANSACTIONS";
  private static final String MARKER_KEY = "DEMO_ATTENDANCE_RESET_TOKEN";
  private static final Set<String> REQUIRED_BRANCHES = Set.of(
      "本社", "北部支店", "中部支店", "那覇支店", "南部支店", "石垣支店", "宮古支店");

  private record BranchStaff(long branchId, String branchName, boolean headOffice,
      List<Long> employees, List<Long> managers) { }

  private DemoAttendanceSeeder() { }

  static void runIfEnabled(Connection connection) throws SQLException {
    if (!enabled()) return;
    String environment = value("shiftapp.appEnv", "APP_ENV", "development");
    if ("production".equalsIgnoreCase(environment) || "true".equalsIgnoreCase(System.getenv("RENDER"))) {
      throw new IllegalStateException("DEMO_ATTENDANCE_RESET is destructive and cannot run in production/Render.");
    }
    if (!CONFIRMATION.equals(value("shiftapp.demoAttendanceResetConfirm", "DEMO_ATTENDANCE_RESET_CONFIRM", ""))) {
      throw new IllegalStateException("DEMO_ATTENDANCE_RESET_CONFIRM must equal " + CONFIRMATION + ".");
    }
    String token = value("shiftapp.demoAttendanceResetToken", "DEMO_ATTENDANCE_RESET_TOKEN", "");
    if (token.isBlank()) throw new IllegalStateException("DEMO_ATTENDANCE_RESET_TOKEN is required and must be unique for each intentional reset.");
    if (token.equals(setting(connection, MARKER_KEY))) {
      System.out.println("Attendance demo reset skipped: the configured reset token has already completed successfully.");
      return;
    }
    int year = scenarioYear();
    System.out.println("=== Attendance close demo reset: START (year=" + year + ") ===");
    boolean originalAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      List<BranchStaff> branches = branchStaff(connection);
      List<Long> employees = branches.stream().flatMap(branch -> branch.employees().stream()).toList();
      if (employees.size() < 7) throw new IllegalStateException("Attendance demo requires employees in all seven demo branches.");
      List<Long> hqRequiredUsers = requiredHqUsers(connection);
      List<Long> hqEmployeeIds = branches.stream().filter(BranchStaff::headOffice)
          .flatMap(branch -> branch.employees().stream()).filter(user -> !hqRequiredUsers.contains(user)).toList();
      List<Long> scenarioEmployees = hqEmployeeIds;
      if (scenarioEmployees.size() < 4) {
        throw new IllegalStateException("Attendance demo requires at least four 本社 employees other than 人事担当 and 沖縄太郎.");
      }
      Set<Long> rosterUsers = new java.util.HashSet<>();
      branches.forEach(branch -> { rosterUsers.addAll(branch.employees()); rosterUsers.addAll(branch.managers()); });
      List<Long> hqExtraUsers = hqRequiredUsers.stream().filter(user -> !rosterUsers.contains(user)).toList();
      long branchOperationUser = branches.stream().filter(branch -> !branch.headOffice()).findFirst().orElseThrow().employees().get(0);
      long approver = approverId(connection);
      Set<String> workTypes = activeWorkTypes(connection);
      Map<String, Integer> deleted = deleteTransactions(connection);
      seedBalances(connection, activeUserIds(connection), year);
      int shifts = seedShifts(connection, branches, scenarioEmployees, hqExtraUsers, workTypes, year);
      int attendance = seedAttendance(connection, branches, scenarioEmployees, hqExtraUsers, workTypes, year);
      int leaveRequests = seedLeaveRequests(connection, scenarioEmployees, branchOperationUser, approver, year);
      int adjustments = seedAdjustments(connection, scenarioEmployees, branchOperationUser, year);
      int adjustmentNotifications = seedJuneAdjustmentNotifications(connection, year);
      int currentMonthRows = seedCurrentMonthOperations(connection, scenarioEmployees, year);
      saveMarker(connection, token);
      connection.commit();
      System.out.println("Attendance demo deleted: " + deleted);
      System.out.println("Attendance demo inserted: shifts=" + shifts + ", attendance=" + attendance
          + ", leave_requests=" + leaveRequests + ", attendance_adjustments=" + adjustments
          + ", adjustment_notifications=" + adjustmentNotifications + ", current_month_rows=" + currentMonthRows);
      System.out.println("=== Attendance close demo reset: COMPLETE ===");
    } catch (Exception e) {
      connection.rollback();
      if (e instanceof SQLException sql) throw sql;
      throw new IllegalStateException("Attendance demo reset rolled back.", e);
    } finally {
      connection.setAutoCommit(originalAutoCommit);
    }
  }

  private static boolean enabled() {
    return Boolean.parseBoolean(value(ENABLE_PROPERTY, ENABLE_ENV, "false"));
  }

  private static int scenarioYear() {
    String raw = value("shiftapp.demoScenarioYear", "DEMO_SCENARIO_YEAR", String.valueOf(DEFAULT_YEAR));
    try { return Integer.parseInt(raw); }
    catch (NumberFormatException e) { throw new IllegalStateException("DEMO_SCENARIO_YEAR must be a four-digit year.", e); }
  }

  private static String setting(Connection c, String key) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key=?")) {
      p.setString(1, key);
      try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
    }
  }

  private static void saveMarker(Connection c, String token) throws SQLException {
    String sql = Database.isPostgres()
        ? "INSERT INTO app_settings(setting_key,setting_value,description,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value,description=EXCLUDED.description,updated_at=CURRENT_TIMESTAMP"
        : "MERGE INTO app_settings(setting_key,setting_value,description,updated_at) KEY(setting_key) VALUES(?,?,?,CURRENT_TIMESTAMP)";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, MARKER_KEY); p.setString(2, token); p.setString(3, "Last successfully applied destructive attendance demo reset token");
      p.executeUpdate();
    }
  }

  private static String value(String property, String environment, String fallback) {
    String result = System.getProperty(property);
    if (result == null || result.isBlank()) result = System.getenv(environment);
    return result == null || result.isBlank() ? fallback : result.trim();
  }

  private static List<BranchStaff> branchStaff(Connection c) throws SQLException {
    Map<Long, BranchStaff> branches = new LinkedHashMap<>();
    try (PreparedStatement p = c.prepareStatement("SELECT id,name FROM branches WHERE active=TRUE ORDER BY id");
         ResultSet r = p.executeQuery()) {
      while (r.next()) {
        String name = r.getString("name");
        if (REQUIRED_BRANCHES.contains(name)) branches.put(r.getLong("id"),
            new BranchStaff(r.getLong("id"), name, "本社".equals(name), new ArrayList<>(), new ArrayList<>()));
      }
    }
    if (branches.size() != REQUIRED_BRANCHES.size()) {
      throw new IllegalStateException("Attendance demo requires these active branches: " + REQUIRED_BRANCHES);
    }
    try (PreparedStatement p = c.prepareStatement(
        "SELECT id,branch_id FROM users WHERE active=TRUE AND role='EMPLOYEE' ORDER BY employee_number");
         ResultSet r = p.executeQuery()) {
      while (r.next()) {
        BranchStaff branch = branches.get(r.getLong("branch_id"));
        if (branch != null) branch.employees().add(r.getLong("id"));
      }
    }
    try (PreparedStatement p = c.prepareStatement(
        "SELECT id,branch_id FROM users WHERE active=TRUE AND role='MANAGER' ORDER BY employee_number");
         ResultSet r = p.executeQuery()) {
      while (r.next()) {
        BranchStaff branch = branches.get(r.getLong("branch_id"));
        if (branch != null) branch.managers().add(r.getLong("id"));
      }
    }
    for (BranchStaff branch : branches.values()) {
      int minimumEmployees = branch.headOffice() ? 1 : 4;
      if (branch.employees().size() < minimumEmployees) {
        throw new IllegalStateException("Attendance demo requires at least " + minimumEmployees
            + " active EMPLOYEE users in branch: " + branch.branchName() + "; found " + branch.employees().size());
      }
      if (branch.managers().isEmpty()) {
        throw new IllegalStateException("Attendance demo requires at least one active MANAGER in branch: " + branch.branchName());
      }
    }
    return new ArrayList<>(branches.values());
  }

  private static Set<String> activeWorkTypes(Connection c) throws SQLException {
    Set<String> codes = new java.util.HashSet<>();
    try (PreparedStatement p = c.prepareStatement("SELECT code FROM work_types WHERE active=TRUE"); ResultSet r = p.executeQuery()) {
      while (r.next()) codes.add(r.getString(1));
    }
    for (String required : List.of("DAY", "NIGHT", "NIGHT_OFF", "OFF", "LEAVE")) {
      if (!codes.contains(required)) throw new IllegalStateException("Attendance demo requires active work type: " + required);
    }
    return codes;
  }

  private static List<Long> requiredHqUsers(Connection c) throws SQLException {
    Map<String, List<Long>> matches = new LinkedHashMap<>();
    matches.put("人事担当", new ArrayList<>());
    matches.put("沖縄太郎", new ArrayList<>());
    try (PreparedStatement p = c.prepareStatement(
        "SELECT u.id,u.name,b.name branch_name FROM users u JOIN branches b ON b.id=u.branch_id WHERE u.active=TRUE");
         ResultSet r = p.executeQuery()) {
      while (r.next()) {
        String normalized = r.getString("name").replace(" ", "").replace("　", "");
        List<Long> ids = matches.get(normalized);
        if (ids != null) {
          if (!"本社".equals(r.getString("branch_name"))) {
            throw new IllegalStateException(normalized + " must belong to 本社 before attendance demo reset.");
          }
          ids.add(r.getLong("id"));
        }
      }
    }
    List<Long> result = new ArrayList<>();
    for (Map.Entry<String, List<Long>> entry : matches.entrySet()) {
      if (entry.getValue().size() != 1) {
        throw new IllegalStateException("Attendance demo requires exactly one active 本社 user named " + entry.getKey()
            + "; found " + entry.getValue().size());
      }
      result.add(entry.getValue().get(0));
    }
    return result;
  }

  private static List<Long> activeUserIds(Connection c) throws SQLException {
    List<Long> ids = new ArrayList<>();
    try (PreparedStatement p = c.prepareStatement("SELECT id FROM users WHERE active=TRUE ORDER BY id");
         ResultSet r = p.executeQuery()) {
      while (r.next()) ids.add(r.getLong(1));
    }
    return ids;
  }

  private static long approverId(Connection c) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(
        "SELECT id FROM users WHERE active=TRUE AND role IN ('HR','MANAGER') ORDER BY CASE WHEN role='HR' THEN 0 ELSE 1 END,id");
        ResultSet r = p.executeQuery()) {
      if (r.next()) return r.getLong(1);
    }
    throw new IllegalStateException("Attendance demo requires an active HR or MANAGER user.");
  }

  private static Map<String, Integer> deleteTransactions(Connection c) throws SQLException {
    Map<String, Integer> counts = new LinkedHashMap<>();
    String[] tables = {"leave_consumptions", "leave_history", "attendance_adjustments", "shift_preferences",
        "shift_preference_submissions", "shift_change_requests", "leave_requests", "attendance", "shifts",
        "notifications", "mail_outbox", "audit_logs", "leave_grants", "leave_balances"};
    try (Statement s = c.createStatement()) {
      for (String table : tables) counts.put(table, s.executeUpdate("DELETE FROM " + table));
    }
    return counts;
  }

  private static void seedBalances(Connection c, List<Long> users, int year) throws SQLException {
    try (PreparedStatement balance = c.prepareStatement(
        "INSERT INTO leave_balances(user_id,days_remaining,hourly_used,last_granted_on) VALUES(?,10,0,?)");
         PreparedStatement grant = c.prepareStatement("INSERT INTO leave_grants(user_id,grant_date,expires_on,days_granted,days_remaining,attendance_rate,source) VALUES(?,?,?,?,?,1.0,'DEMO')")) {
      for (long user : users) {
        LocalDate granted = LocalDate.of(year - 1, 10, 1);
        balance.setLong(1, user); balance.setObject(2, granted); balance.addBatch();
        grant.setLong(1, user); grant.setObject(2, granted); grant.setObject(3, granted.plusMonths(24));
        grant.setBigDecimal(4, BigDecimal.TEN); grant.setBigDecimal(5, BigDecimal.TEN); grant.addBatch();
      }
      balance.executeBatch(); grant.executeBatch();
    }
  }

  private static int seedShifts(Connection c, List<BranchStaff> branches, List<Long> demoUsers,
      List<Long> hqExtraUsers,
      Set<String> workTypes, int year) throws SQLException {
    int count = 0;
    try (PreparedStatement p = c.prepareStatement("INSERT INTO shifts(user_id,work_date,work_type_code,status,note,updated_by) VALUES(?,?,?,'CONFIRMED','attendance-close demo',?)")) {
      long updater = demoUsers.get(0);
      for (int month : new int[]{5, 6, 7}) {
        YearMonth target = YearMonth.of(year, month);
        int lastDay = target.lengthOfMonth();
        for (int dayNumber = 1; dayNumber <= lastDay; dayNumber++) {
          LocalDate day = target.atDay(dayNumber);
          for (long hqUser : hqExtraUsers) {
            p.setLong(1, hqUser); p.setObject(2, day); p.setString(3, isWeekend(day) ? "OFF" : "DAY");
            p.setLong(4, updater); p.addBatch(); count++;
          }
          for (BranchStaff branch : branches) {
            for (int index = 0; index < branch.managers().size(); index++) {
              long manager = branch.managers().get(index);
              p.setLong(1, manager); p.setObject(2, day); p.setString(3, managerCode(branch, index, day));
              p.setLong(4, updater); p.addBatch(); count++;
            }
            for (int index = 0; index < branch.employees().size(); index++) {
              long user = branch.employees().get(index);
              String code = scheduledCode(branch, user, index, day, demoUsers, workTypes, year);
              p.setLong(1, user); p.setObject(2, day); p.setString(3, code);
              p.setLong(4, updater); p.addBatch(); count++;
            }
          }
        }
      }
      p.executeBatch();
    }
    return count;
  }

  private static int seedAttendance(Connection c, List<BranchStaff> branches, List<Long> demoUsers,
      List<Long> hqExtraUsers,
      Set<String> workTypes, int year) throws SQLException {
    int count = 0;
    LocalDate operationDate = operationDate();
    String sql = "INSERT INTO attendance(user_id,work_date,clock_in,clock_out,location_status,status,finalized) VALUES(?,?,?,?, 'ACQUIRED',?,?)";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      for (int month : new int[]{5, 6, 7}) {
        YearMonth target = YearMonth.of(year, month);
        int lastDay = month == 7 ? Math.min(3, target.lengthOfMonth()) : target.lengthOfMonth();
        for (int dayNumber = 1; dayNumber <= lastDay; dayNumber++) {
          LocalDate day = target.atDay(dayNumber);
          if (!isWeekend(day)) {
            for (long hqUser : hqExtraUsers) {
              p.setLong(1, hqUser); p.setObject(2, day); p.setObject(3, day.atTime(8, 0));
              p.setObject(4, day.atTime(17, 5)); p.setString(5, "COMPLETE"); p.setBoolean(6, month == 5);
              p.addBatch(); count++;
            }
          }
          for (BranchStaff branch : branches) {
            for (int index = 0; index < branch.managers().size(); index++) {
              long manager = branch.managers().get(index);
              String code = managerCode(branch, index, day);
              if (!isWorked(code)) continue;
              LocalDateTime in = clockIn(day, code, index);
              LocalDateTime out = clockOut(day, code, index, false);
              p.setLong(1, manager); p.setObject(2, day); p.setObject(3, in); p.setObject(4, out);
              p.setString(5, "COMPLETE"); p.setBoolean(6, month == 5); p.addBatch(); count++;
            }
            for (int index = 0; index < branch.employees().size(); index++) {
              long user = branch.employees().get(index);
              if (user == demoUsers.get(0) && day.equals(operationDate)) continue;
              String code = scheduledCode(branch, user, index, day, demoUsers, workTypes, year);
              if (!isWorked(code)) continue;
              boolean missing = month == 6 && user == demoUsers.get(2) && day.equals(LocalDate.of(year, 6, 18));
              LocalDateTime in = clockIn(day, code, index);
              LocalDateTime out = missing ? null : clockOut(day, code, index,
                  user == demoUsers.get(1) && day.getDayOfMonth() % 7 == 0);
              p.setLong(1, user); p.setObject(2, day); p.setObject(3, in); p.setObject(4, out);
              p.setString(5, missing ? "OPEN" : "COMPLETE"); p.setBoolean(6, month == 5); p.addBatch(); count++;
            }
          }
        }
      }
      p.executeBatch();
    }
    return count;
  }

  private static int seedLeaveRequests(Connection c, List<Long> users, long branchOperationUser, long approver, int year) throws SQLException {
    String sql = "INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason,status,decided_by,decided_at,created_at) VALUES(?,?,'FULL',NULL,?,?,?,?,?)";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      addLeave(p, users.get(0), LocalDate.of(year, 5, 14), "5月デモ有休", "APPROVED", approver);
      addLeave(p, users.get(1), LocalDate.of(year, 6, 12), "6月デモ有休", "APPROVED", approver);
      addLeave(p, users.get(3), LocalDate.of(year, 6, 25), "確定前に承認するデモ", "PENDING", null);
      addLeave(p, branchOperationUser, LocalDate.of(year, 7, 6), "7月支店申請画面デモ", "PENDING", null);
      p.executeBatch(); return 4;
    }
  }

  private static void addLeave(PreparedStatement p, long user, LocalDate date, String reason, String status, Long approver) throws SQLException {
    p.setLong(1, user); p.setObject(2, date); p.setString(3, reason); p.setString(4, status);
    if (approver == null) { p.setNull(5, java.sql.Types.BIGINT); p.setNull(6, java.sql.Types.TIMESTAMP); }
    else { p.setLong(5, approver); p.setObject(6, date.minusDays(5).atTime(10, 0)); }
    p.setObject(7, date.minusDays(7).atTime(9, 0)); p.addBatch();
  }

  private static int seedAdjustments(Connection c, List<Long> users, long branchOperationUser, int year) throws SQLException {
    String sql = "INSERT INTO attendance_adjustments(attendance_id,requested_by,requested_in,requested_out,reason,status,created_at) "
        + "SELECT a.id,a.user_id,?,?,?,'PENDING',? FROM attendance a WHERE a.user_id=? AND a.work_date=?";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      LocalDate june = LocalDate.of(year, 6, 18);
      p.setObject(1, june.atTime(8, 2)); p.setObject(2, june.atTime(17, 15)); p.setString(3, "退勤打刻漏れの修正デモ");
      p.setObject(4, june.plusDays(1).atTime(9, 0)); p.setLong(5, users.get(2)); p.setObject(6, june); p.addBatch();
      LocalDate july = LocalDate.of(year, 7, 2);
      p.setObject(1, july.atTime(8, 0)); p.setObject(2, july.atTime(17, 30)); p.setString(3, "7月の打刻修正申請デモ");
      p.setObject(4, july.plusDays(1).atTime(9, 0)); p.setLong(5, branchOperationUser); p.setObject(6, july); p.addBatch();
      int[] inserted = p.executeBatch();
      int count = 0;
      for (int result : inserted) if (result > 0 || result == Statement.SUCCESS_NO_INFO) count++;
      if (count != 2) {
        throw new IllegalStateException("Attendance demo requires both June close-demo and July operation-demo adjustment requests; inserted " + count);
      }
      return count;
    }
  }

  private static int seedJuneAdjustmentNotifications(Connection c, int year) throws SQLException {
    String sql = "INSERT INTO notifications(user_id,type,title,message,target_url,email_status) "
        + "SELECT approver.id,'ATTENDANCE_ADJUSTMENT','6月の打刻修正申請',applicant.name || 'さんの6月勤怠に未承認の打刻修正申請があります。',"
        + "'/app/attendance/manage?month=' || ?,'QUEUED' "
        + "FROM attendance_adjustments r JOIN attendance a ON a.id=r.attendance_id "
        + "JOIN users applicant ON applicant.id=r.requested_by JOIN users approver ON approver.active=TRUE "
        + "AND (approver.role='HR' OR (approver.role='MANAGER' AND approver.branch_id=applicant.branch_id AND approver.department_id=applicant.department_id)) "
        + "WHERE r.status='PENDING' AND a.work_date BETWEEN ? AND ?";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      YearMonth june = YearMonth.of(year, 6);
      p.setString(1, june.toString()); p.setObject(2, june.atDay(1)); p.setObject(3, june.atEndOfMonth());
      int inserted = p.executeUpdate();
      if (inserted == 0) throw new IllegalStateException("No approver notification was created for the June adjustment demo.");
      return inserted;
    }
  }

  private static int seedCurrentMonthOperations(Connection c, List<Long> users, int scenarioYear) throws SQLException {
    LocalDate today = operationDate();
    YearMonth current = YearMonth.from(today);
    if (current.getYear() == scenarioYear && current.getMonthValue() >= 5 && current.getMonthValue() <= 7) return 0;

    List<LocalDate> prior = new ArrayList<>();
    for (LocalDate day = today.minusDays(1); prior.size() < 3 && YearMonth.from(day).equals(current); day = day.minusDays(1)) {
      if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) prior.add(0, day);
    }
    int count = 0;
    try (PreparedStatement shift = c.prepareStatement("INSERT INTO shifts(user_id,work_date,work_type_code,status,note,updated_by) VALUES(?,?,'DAY','CONFIRMED','current-month operation demo',?)");
         PreparedStatement attendance = c.prepareStatement("INSERT INTO attendance(user_id,work_date,clock_in,clock_out,location_status,status,finalized) VALUES(?,?,?,?, 'ACQUIRED','COMPLETE',FALSE)")) {
      for (LocalDate day : prior) for (int index = 0; index < 2; index++) {
        shift.setLong(1, users.get(index)); shift.setObject(2, day); shift.setLong(3, users.get(0)); shift.addBatch();
        attendance.setLong(1, users.get(index)); attendance.setObject(2, day); attendance.setObject(3, day.atTime(8, index));
        attendance.setObject(4, day.atTime(17, 10 + index)); attendance.addBatch(); count += 2;
      }
      // The primary demo employee deliberately has a shift but no attendance today, so clock-in can be demonstrated.
      shift.setLong(1, users.get(0)); shift.setObject(2, today); shift.setLong(3, users.get(0)); shift.addBatch(); count++;
      shift.executeBatch(); attendance.executeBatch();
    }
    LocalDate requestDate = today.plusDays(1).getMonth() == today.getMonth() ? today.plusDays(1) : today;
    try (PreparedStatement leave = c.prepareStatement("INSERT INTO leave_requests(user_id,leave_date,leave_unit,hours,reason,status,created_at) VALUES(?,?,'FULL',NULL,?,'PENDING',CURRENT_TIMESTAMP)")) {
      leave.setLong(1, users.get(1)); leave.setObject(2, requestDate); leave.setString(3, "現在月の有休申請デモ");
      count += leave.executeUpdate();
    }
    if (!prior.isEmpty()) {
      LocalDate adjustmentDay = prior.get(0);
      try (PreparedStatement adjustment = c.prepareStatement("INSERT INTO attendance_adjustments(attendance_id,requested_by,requested_in,requested_out,reason,status,created_at) "
          + "SELECT id,user_id,?,?,?,'PENDING',CURRENT_TIMESTAMP FROM attendance WHERE user_id=? AND work_date=?")) {
        adjustment.setObject(1, adjustmentDay.atTime(8, 0)); adjustment.setObject(2, adjustmentDay.atTime(17, 30));
        adjustment.setString(3, "現在月の打刻修正申請デモ"); adjustment.setLong(4, users.get(0)); adjustment.setObject(5, adjustmentDay);
        count += adjustment.executeUpdate();
      }
    }
    return count;
  }

  private static LocalDate operationDate() {
    return LocalDate.parse(value("shiftapp.demoOperationDate", "DEMO_OPERATION_DATE",
        LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()));
  }

  private static String scheduledCode(BranchStaff branch, long user, int userIndex, LocalDate day,
      List<Long> demoUsers, Set<String> workTypes, int year) {
    if (user == demoUsers.get(0) && day.equals(LocalDate.of(year, 5, 14))) return "LEAVE";
    if (user == demoUsers.get(1) && day.equals(LocalDate.of(year, 6, 12))) return "LEAVE";
    if (user == demoUsers.get(2) && day.equals(LocalDate.of(year, 6, 18))) return "DAY";
    if (branch.headOffice()) return isWeekend(day) ? "OFF" : "DAY";
    String[] coreRotation = {"NIGHT", "NIGHT_OFF", "DAY", "OFF"};
    String code = userIndex < 4
        ? coreRotation[Math.floorMod(day.getDayOfYear() + (int) branch.branchId() - userIndex, coreRotation.length)]
        : (Math.floorMod(day.getDayOfYear() + userIndex, 3) == 0 ? "OFF" : "DAY");
    boolean tomorrowIsOverride = (user == demoUsers.get(0) && day.plusDays(1).equals(LocalDate.of(year, 5, 14)))
        || (user == demoUsers.get(1) && day.plusDays(1).equals(LocalDate.of(year, 6, 12)))
        || (user == demoUsers.get(2) && day.plusDays(1).equals(LocalDate.of(year, 6, 18)))
        || (userIndex == 0 && day.plusDays(1).equals(LocalDate.of(year, 7, 2)));
    if (tomorrowIsOverride && "NIGHT".equals(code)) return "DAY";
    if (userIndex == 0 && day.equals(LocalDate.of(year, 7, 2))) return "DAY";
    // July is the final displayed scenario month; avoid an orphaned night shift on its last day.
    if (day.getMonthValue() == 7 && day.getDayOfMonth() == day.lengthOfMonth() && "NIGHT".equals(code)) return "DAY";
    return code;
  }

  private static String managerCode(BranchStaff branch, int managerIndex, LocalDate day) {
    if (branch.headOffice()) return isWeekend(day) ? "OFF" : "DAY";
    int cycle = Math.floorMod(day.getDayOfYear() + managerIndex * 2 + (int) branch.branchId(), 7);
    return cycle == 1 || cycle == 5 ? "OFF" : "DAY";
  }

  private static boolean isWorked(String code) {
    return !Set.of("OFF", "NIGHT_OFF", "LEAVE").contains(code);
  }

  private static boolean isWeekend(LocalDate day) {
    return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
  }

  private static LocalDateTime clockIn(LocalDate day, String code, int userIndex) {
    if ("NIGHT".equals(code)) return day.atTime(17, userIndex % 5);
    return day.atTime(8, userIndex % 5);
  }

  private static LocalDateTime clockOut(LocalDate day, String code, int userIndex, boolean overtime) {
    if ("NIGHT".equals(code)) return day.plusDays(1).atTime(8, 5 + userIndex % 5);
    return day.atTime(overtime ? 19 : 17, 5 + userIndex % 5);
  }

  private static List<LocalDate> weekdays(YearMonth month, int maxDay) {
    List<LocalDate> result = new ArrayList<>();
    for (int day = 1; day <= Math.min(month.lengthOfMonth(), maxDay); day++) {
      LocalDate date = month.atDay(day);
      if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) result.add(date);
    }
    return result;
  }
}
