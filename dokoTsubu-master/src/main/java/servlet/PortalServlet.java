package servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import model.User;

@WebServlet("/app/*")
public class PortalServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private final service.ShiftService shiftService = new service.ShiftService();
  private final service.LeaveService leaveService = new service.LeaveService();
  private final service.AttendanceService attendanceService = new service.AttendanceService();
  private final service.EmployeeService employeeService = new service.EmployeeService();
  private final service.MasterDataService masterDataService = new service.MasterDataService();
  private final service.NotificationService notificationService = new service.NotificationService();
  private final service.DashboardService dashboardService = new service.DashboardService();
  private final service.AuditLogService auditLogService = new service.AuditLogService();
  private final service.SettingsService settings = new service.SettingsService();
  private final service.LeavePolicyService leavePolicy = new service.LeavePolicyService();

  private static final Map<String, String> TITLES = new LinkedHashMap<>();
  static {
    TITLES.put("dashboard", "ダッシュボード");
    TITLES.put("notifications", "通知"); TITLES.put("account", "アカウント設定");
    TITLES.put("shifts/mine", "シフト"); TITLES.put("shifts/request", "希望シフト提出");
    TITLES.put("shifts/team", "月間シフト表"); TITLES.put("shifts/change", "シフト変更申請");
    TITLES.put("shifts/history", "シフト申請履歴"); TITLES.put("shifts/manage", "シフト調整");
    TITLES.put("shifts/confirm", "シフト確定確認"); TITLES.put("shifts/print", "シフト印刷");
    TITLES.put("leave", "有休");
    TITLES.put("leave/balance", "有休残数・取得履歴"); TITLES.put("leave/request", "有休申請");
    TITLES.put("leave/history", "有休申請履歴"); TITLES.put("leave/approvals", "有休承認");
    TITLES.put("attendance/clock", "出勤・退勤打刻"); TITLES.put("attendance/mine", "勤怠実績");
    TITLES.put("attendance/adjust", "打刻修正申請"); TITLES.put("attendance/history", "打刻修正履歴");
    TITLES.put("attendance/manage", "勤怠確認・月次確定"); TITLES.put("attendance/company", "全社勤怠確認");
    TITLES.put("employees", "従業員一覧"); TITLES.put("employees/edit", "従業員登録・編集");
    TITLES.put("qualifications", "資格情報管理"); TITLES.put("delegations", "代理店長設定");
    TITLES.put("masters/branches", "営業所管理"); TITLES.put("masters/departments", "部署管理");
    TITLES.put("masters/work-types", "勤務区分・休憩時間管理"); TITLES.put("masters/staffing", "必要人数管理");
    TITLES.put("masters/catalogs", "雇用形態・資格名称管理"); TITLES.put("exports", "データ出力");
    TITLES.put("audit", "操作履歴");
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {
    req.setCharacterEncoding("UTF-8");
    User user = current(req);
    String page = page(req);
    if (!TITLES.containsKey(page)) { res.sendError(404); return; }
    if (!allowed(user, page)) { res.sendError(403); return; }
    YearMonth month = parseMonth(req.getParameter("month"));
    if ("shifts/request".equals(page) && req.getParameter("month") == null) month = YearMonth.now().plusMonths(1);
    req.setAttribute("page", page);
    req.setAttribute("pageTitle", TITLES.get(page));
    req.setAttribute("month", month);
    req.setAttribute("workTypes", shiftService.workTypes());
    req.setAttribute("flash", takeFlash(req, "flash"));
    req.setAttribute("error", takeFlash(req, "error"));

    if ("dashboard".equals(page)) {
      long dashboardBranchId = longValue(req, "branchId", user.getBranchId());
      java.util.List<Map<String, Object>> dashboardBranches = shiftService.dashboardBranches(month);
      boolean dashboardBranchExists = false;
      for (Map<String, Object> branch : dashboardBranches) {
        if (((Number) branch.get("id")).longValue() == dashboardBranchId) dashboardBranchExists = true;
      }
      if (!dashboardBranchExists && !dashboardBranches.isEmpty()) dashboardBranchId = ((Number) dashboardBranches.get(0).get("id")).longValue();
      req.setAttribute("stats", dashboardService.dashboard(user));
      req.setAttribute("chart", dashboardService.chart(user));
      req.setAttribute("rows", shiftService.dashboardShifts(user, month, dashboardBranchId));
      req.setAttribute("dashboardBranches", dashboardBranches);
      req.setAttribute("dashboardBranchId", dashboardBranchId);
    } else if (page.startsWith("shifts/")) {
      if ("shifts/mine".equals(page) || "shifts/print".equals(page)) {
        java.util.List<Map<String, Object>> shiftBranches = shiftService.scheduleBranches();
        long shiftBranchId = longValue(req, "branchId", user.getBranchId());
        boolean shiftBranchExists = false;
        for (Map<String, Object> branch : shiftBranches) {
          if (((Number) branch.get("id")).longValue() == shiftBranchId) shiftBranchExists = true;
        }
        if (!shiftBranchExists && !shiftBranches.isEmpty()) {
          shiftBranchId = ((Number) shiftBranches.get(0).get("id")).longValue();
        }
        req.setAttribute("rows", shiftBranches.isEmpty() ? java.util.List.of() : shiftService.branchShifts(month, shiftBranchId));
        req.setAttribute("shiftBranches", shiftBranches);
        req.setAttribute("shiftBranchId", shiftBranchId);
      } else {
        req.setAttribute("rows", shiftService.shifts(user, month));
      }
      req.setAttribute("people", employeeService.findEmployees(user));
      req.setAttribute("requests", shiftService.shiftChangeRequests(user));
      if ("shifts/confirm".equals(page) || "shifts/manage".equals(page)) req.setAttribute("warnings", shiftService.shiftWarnings(user, month));
      if ("shifts/request".equals(page)) {
        req.setAttribute("submissionWindow", shiftService.shiftSubmissionWindow(month));
        req.setAttribute("preferenceRows", shiftService.preferences(user, month));
        req.setAttribute("preferenceSubmission", shiftService.preferenceSubmission(user, month));
      }
      if ("shifts/manage".equals(page)) {
        req.setAttribute("preferenceSubmissions", shiftService.preferenceSubmissionSummaries(user, month));
        req.setAttribute("preferenceDetails", shiftService.preferenceDetails(user, month));
      }
    } else if ("leave".equals(page) || page.startsWith("leave/")) {
      req.setAttribute("rows", leaveService.leaveRequests(user));
      req.setAttribute("balance", leaveService.leaveBalance(user.getId()));
      req.setAttribute("leaveLedger", leaveService.leaveHistory(user));
      req.setAttribute("leaveApprovers", leaveService.leaveApprovers(user));
    } else if (page.startsWith("attendance/")) {
      req.setAttribute("rows", attendanceService.attendance(user, month));
      req.setAttribute("adjustments", attendanceService.attendanceAdjustments(user));
      if ("attendance/clock".equals(page)) req.setAttribute("clockSummary", attendanceService.attendanceClockSummary(user));
      if ("attendance/manage".equals(page)) req.setAttribute("people", employeeService.findEmployees(user));
    } else if ("notifications".equals(page)) {
      req.setAttribute("rows", notificationService.notifications(user));
      if (user.isHr()) req.setAttribute("mailRows", notificationService.mailOutbox(user));
    } else if ("employees".equals(page) || "employees/edit".equals(page)) {
      java.util.List<Map<String,Object>> employeeRows = employeeService.findEmployees(user);
      req.setAttribute("rows", employeeRows);
      if (req.getParameter("id") != null) {
        long selectedId = longValue(req, "id", 0);
        employeeRows.stream().filter(row -> ((Number) row.get("id")).longValue() == selectedId).findFirst().ifPresent(row -> req.setAttribute("selectedEmployee", row));
      }
      req.setAttribute("branches", masterDataService.getMasterData("branches"));
      req.setAttribute("departments", masterDataService.getMasterData("departments"));
      req.setAttribute("employment", masterDataService.getMasterData("employment"));
    } else if ("qualifications".equals(page)) {
      req.setAttribute("rows", employeeService.qualifications(user));
      req.setAttribute("people", employeeService.findEmployees(user));
      req.setAttribute("qualificationTypes", masterDataService.getMasterData("qualifications"));
    } else if ("delegations".equals(page)) {
      req.setAttribute("rows", employeeService.delegations(user));
      req.setAttribute("people", employeeService.findEmployees(user));
    } else if (page.startsWith("masters/")) {
      String type = page.endsWith("branches") ? "branches" : page.endsWith("departments")
          ? "departments" : page.endsWith("catalogs") ? "employment" : "work_types";
      req.setAttribute("masterType", type);
      req.setAttribute("rows", masterDataService.getMasterData(type));
      if (page.endsWith("catalogs")) {
        req.setAttribute("qualificationTypes", masterDataService.getMasterData("qualifications"));
        req.setAttribute("appSettings", settings.all(user));
        req.setAttribute("leaveRules", leavePolicy.rules(user));
      }
    } else if ("audit".equals(page)) {
      req.setAttribute("rows", auditLogService.audit(user, localDate(req.getParameter("from")), localDate(req.getParameter("to")),
          nullableLong(req.getParameter("actorId")), req.getParameter("operation"), nullableLong(req.getParameter("targetUserId"))));
      req.setAttribute("people", employeeService.findEmployees(user));
      req.setAttribute("auditActions", auditLogService.auditActions(user));
    } else if ("exports".equals(page)) {
      req.setAttribute("branches", masterDataService.getMasterData("branches"));
      req.setAttribute("departments", masterDataService.getMasterData("departments"));
      req.setAttribute("people", employeeService.findEmployees(user));
    }
    req.getRequestDispatcher("/WEB-INF/jsp/app.jsp").forward(req, res);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    req.setCharacterEncoding("UTF-8");
    User user = current(req);
    String returnPage = value(req, "returnPage", "dashboard");
    try {
      String action = req.getParameter("action");
      switch (action == null ? "" : action) {
        case "saveShift" -> {
          LocalDate date = LocalDate.parse(req.getParameter("date"));
          if (user.isManager() || user.isHr()) shiftService.saveShift(user, longValue(req, "userId", user.getId()), date,
              req.getParameter("workType"), value(req, "status", "DRAFT"), req.getParameter("note"));
          else shiftService.submitPreferredShift(user, date, req.getParameter("workType"), req.getParameter("note"));
        }
        case "submitMonthlyPreferences" -> {
          YearMonth preferenceMonth = parseMonth(req.getParameter("month"));
          Map<LocalDate, String> preferences = new LinkedHashMap<>();
          Map<LocalDate, String> reasons = new LinkedHashMap<>();
          for (int day = 1; day <= preferenceMonth.lengthOfMonth(); day++) {
            LocalDate date = preferenceMonth.atDay(day);
            String selected = req.getParameter("preference_" + date);
            if (selected != null && !selected.isBlank() && !"NONE".equals(selected)) {
              preferences.put(date, selected);
              if ("LEAVE".equals(selected)) reasons.put(date, req.getParameter("reason_" + date));
            }
          }
          shiftService.submitMonthlyPreferences(user, preferenceMonth, preferences, reasons);
        }
        case "autoAssignShifts" -> shiftService.autoAssignShifts(user, parseMonth(req.getParameter("month")));
        case "reviewShiftPreferences" -> shiftService.reviewPreferenceSubmission(user, Long.parseLong(req.getParameter("id")), "approve".equals(req.getParameter("decision")));
        case "confirmShifts" -> shiftService.confirmMonth(user, parseMonth(req.getParameter("month")), req.getParameter("warningReason"));
        case "requestShiftChange" -> shiftService.requestShiftChange(user, LocalDate.parse(req.getParameter("date")), req.getParameter("workType"), req.getParameter("reason"));
        case "decideShiftChange" -> shiftService.decideShiftChange(user, Long.parseLong(req.getParameter("id")),
            "approve".equals(req.getParameter("decision")), req.getParameter("rejectionReason"));
        case "requestLeave" -> leaveService.requestLeave(user, parseLeaveDates(req),
            req.getParameter("unit"), integer(req.getParameter("hours")), req.getParameter("reason"));
        case "decideLeave" -> leaveService.decideLeave(user, Long.parseLong(req.getParameter("id")),
            "approve".equals(req.getParameter("decision")), req.getParameter("rejectionReason"));
        case "cancelLeave" -> leaveService.cancelLeave(user, Long.parseLong(req.getParameter("id")));
        case "clock" -> attendanceService.clock(user, "in".equals(req.getParameter("direction")), req.getParameter("lat"), req.getParameter("lng"), value(req, "locationStatus", "UNKNOWN"));
        case "finalizeAttendance" -> attendanceService.finalizeAttendance(user, Long.parseLong(req.getParameter("id")), Boolean.parseBoolean(req.getParameter("finalized")));
        case "finalizeAttendanceMonth" -> attendanceService.finalizeAttendanceMonth(user, parseMonth(req.getParameter("month")), Boolean.parseBoolean(req.getParameter("finalized")));
        case "finalizeAttendanceEmployeeMonth" -> attendanceService.finalizeAttendanceEmployeeMonth(user, Long.parseLong(req.getParameter("userId")),
            parseMonth(req.getParameter("month")), Boolean.parseBoolean(req.getParameter("finalized")));
        case "requestAttendanceAdjustment" -> attendanceService.requestAttendanceAdjustment(user, Long.parseLong(req.getParameter("attendanceId")),
            LocalDateTime.parse(req.getParameter("requestedIn")), LocalDateTime.parse(req.getParameter("requestedOut")), req.getParameter("reason"));
        case "decideAttendanceAdjustment" -> attendanceService.decideAttendanceAdjustment(user, Long.parseLong(req.getParameter("id")), "approve".equals(req.getParameter("decision")));
        case "markNotificationsRead" -> notificationService.markNotificationsRead(user);
        case "retryMail" -> notificationService.retryMail(user, Long.parseLong(req.getParameter("id")));
        case "addEmployee" -> employeeService.addEmployee(user, req.getParameter("employeeNumber"), req.getParameter("name"), req.getParameter("email"),
            LocalDate.parse(req.getParameter("hireDate")), Long.parseLong(req.getParameter("branchId")), Long.parseLong(req.getParameter("departmentId")),
            Long.parseLong(req.getParameter("employmentId")), req.getParameter("role"), util.ServletUtil.baseUrl(req));
        case "reissueInvite" -> employeeService.reissueInvite(user, Long.parseLong(req.getParameter("id")), util.ServletUtil.baseUrl(req));
        case "updateEmployee" -> employeeService.updateEmployee(user, Long.parseLong(req.getParameter("id")), req.getParameter("employeeNumber"), req.getParameter("name"), req.getParameter("email"),
            LocalDate.parse(req.getParameter("hireDate")), Long.parseLong(req.getParameter("branchId")), Long.parseLong(req.getParameter("departmentId")),
            Long.parseLong(req.getParameter("employmentId")), req.getParameter("role"), Boolean.parseBoolean(req.getParameter("active")));
        case "addQualification" -> employeeService.addQualification(user, Long.parseLong(req.getParameter("userId")), req.getParameter("name"),
            req.getParameter("expiresOn").isBlank() ? null : LocalDate.parse(req.getParameter("expiresOn")));
        case "updateQualification" -> employeeService.updateQualification(user, Long.parseLong(req.getParameter("id")), req.getParameter("name"),
            req.getParameter("expiresOn").isBlank() ? null : LocalDate.parse(req.getParameter("expiresOn")), Boolean.parseBoolean(req.getParameter("active")));
        case "addDelegation" -> employeeService.addDelegation(user, longValue(req, "managerId", user.getId()), Long.parseLong(req.getParameter("delegateId")),
            LocalDate.parse(req.getParameter("startsOn")), LocalDate.parse(req.getParameter("endsOn")));
        case "updateDelegation" -> employeeService.updateDelegation(user, Long.parseLong(req.getParameter("id")),
            LocalDate.parse(req.getParameter("startsOn")), LocalDate.parse(req.getParameter("endsOn")), Boolean.parseBoolean(req.getParameter("active")));
        case "addMaster" -> masterDataService.addMaster(user, req.getParameter("type"), req.getParameter("name"));
        case "toggleMaster" -> masterDataService.toggleMaster(user, req.getParameter("type"), Long.parseLong(req.getParameter("id")), Boolean.parseBoolean(req.getParameter("active")));
        case "updateMaster" -> masterDataService.updateMaster(user, req.getParameter("type"), Long.parseLong(req.getParameter("id")),
            req.getParameter("name"), Boolean.parseBoolean(req.getParameter("active")));
        case "updateWorkType" -> masterDataService.updateWorkType(user, req.getParameter("code"), req.getParameter("nameJa"), req.getParameter("nameEn"), req.getParameter("start"), req.getParameter("end"),
            Integer.parseInt(req.getParameter("breakMinutes")), Integer.parseInt(req.getParameter("requiredStaff")), Boolean.parseBoolean(req.getParameter("active")));
        case "updateSetting" -> settings.update(user, req.getParameter("key"), req.getParameter("value"));
        case "addLeaveRule" -> leavePolicy.addRule(user, LocalDate.parse(req.getParameter("effectiveFrom")),
            new java.math.BigDecimal(req.getParameter("attendanceThreshold")), Integer.parseInt(req.getParameter("hourlyLimitDays")),
            Integer.parseInt(req.getParameter("hoursPerDay")), Integer.parseInt(req.getParameter("expiryMonths")), Integer.parseInt(req.getParameter("mandatoryDays")));
        case "updateLeaveRule" -> leavePolicy.updateRule(user, Long.parseLong(req.getParameter("id")), LocalDate.parse(req.getParameter("effectiveFrom")),
            new java.math.BigDecimal(req.getParameter("attendanceThreshold")), Integer.parseInt(req.getParameter("hourlyLimitDays")),
            Integer.parseInt(req.getParameter("hoursPerDay")), Integer.parseInt(req.getParameter("expiryMonths")), Integer.parseInt(req.getParameter("mandatoryDays")),
            Boolean.parseBoolean(req.getParameter("active")));
        default -> throw new IllegalArgumentException("操作が指定されていません。");
      }
      req.getSession().setAttribute("flash", "保存しました。");
    } catch (IllegalArgumentException | SecurityException e) {
      req.getSession().setAttribute("error", e.getMessage());
    } catch (Exception e) {
      getServletContext().log("Portal action failed", e);
      req.getSession().setAttribute("error", "処理に失敗しました。入力内容を確認してください。");
    }
    String returnMonth = req.getParameter("returnMonth");
    String monthQuery = returnMonth != null && returnMonth.matches("\\d{4}-\\d{2}") ? "?month=" + returnMonth : "";
    res.sendRedirect(req.getContextPath() + "/app/" + returnPage + monthQuery);
  }

  private boolean allowed(User user, String page) {
    if (page.startsWith("employees") || page.startsWith("masters/") || "audit".equals(page)
        || "attendance/company".equals(page) || "exports".equals(page)) return user.isHr();
    if (page.equals("shifts/manage") || page.equals("shifts/confirm") || page.equals("leave/approvals")
        || page.equals("attendance/manage") || page.equals("delegations")) return user.isHr() || user.isManager();
    return true;
  }

  private User current(HttpServletRequest req) { return (User) req.getSession().getAttribute("loginUser"); }
  private String page(HttpServletRequest req) {
    String path = req.getPathInfo();
    return path == null || path.equals("/") ? "dashboard" : path.substring(1);
  }
  private YearMonth parseMonth(String value) {
    try { return value == null || value.isBlank() ? YearMonth.now() : YearMonth.parse(value); }
    catch (Exception e) { return YearMonth.now(); }
  }
  private List<LocalDate> parseLeaveDates(HttpServletRequest req) {
    LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
    String multiple = req.getParameter("dates");
    if (multiple != null && !multiple.isBlank()) {
      for (String value : multiple.split(",")) {
        if (!value.isBlank()) dates.add(LocalDate.parse(value.trim()));
      }
    }
    String single = req.getParameter("date");
    if (single != null && !single.isBlank()) dates.add(LocalDate.parse(single.trim()));
    return new ArrayList<>(dates);
  }
  private String value(HttpServletRequest req, String name, String fallback) {
    String value = req.getParameter(name); return value == null || value.isBlank() ? fallback : value;
  }
  private long longValue(HttpServletRequest req, String name, long fallback) {
    try { return Long.parseLong(req.getParameter(name)); } catch (Exception e) { return fallback; }
  }
  private Integer integer(String value) {
    try { return value == null || value.isBlank() ? null : Integer.valueOf(value); } catch (Exception e) { return null; }
  }
  private Long nullableLong(String value) {
    try { return value == null || value.isBlank() ? null : Long.valueOf(value); } catch (Exception e) { return null; }
  }
  private LocalDate localDate(String value) {
    try { return value == null || value.isBlank() ? null : LocalDate.parse(value); } catch (Exception e) { return null; }
  }
  private Object takeFlash(HttpServletRequest req, String key) {
    Object value = req.getSession().getAttribute(key); req.getSession().removeAttribute(key); return value;
  }

}
