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
import java.util.LinkedHashMap;
import java.util.Map;
import model.User;
import service.PortalService;

@WebServlet("/app/*")
public class PortalServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private final PortalService portal = new PortalService();
  private final service.SettingsService settings = new service.SettingsService();

  private static final Map<String, String> TITLES = new LinkedHashMap<>();
  static {
    TITLES.put("dashboard", "ダッシュボード");
    TITLES.put("notifications", "通知"); TITLES.put("account", "アカウント設定");
    TITLES.put("shifts/mine", "自分のシフト"); TITLES.put("shifts/request", "希望シフト提出");
    TITLES.put("shifts/team", "月間シフト表"); TITLES.put("shifts/change", "シフト変更・休み申請");
    TITLES.put("shifts/history", "シフト申請履歴"); TITLES.put("shifts/manage", "シフト調整");
    TITLES.put("shifts/confirm", "シフト確定確認"); TITLES.put("shifts/print", "月間シフト印刷");
    TITLES.put("leave/balance", "有休残数・取得履歴"); TITLES.put("leave/request", "有休申請");
    TITLES.put("leave/history", "有休申請履歴"); TITLES.put("leave/approvals", "有休承認");
    TITLES.put("attendance/clock", "出勤・退勤打刻"); TITLES.put("attendance/mine", "自分の勤怠");
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
    req.setAttribute("workTypes", portal.workTypes());
    req.setAttribute("flash", takeFlash(req, "flash"));
    req.setAttribute("error", takeFlash(req, "error"));

    if ("dashboard".equals(page)) {
      req.setAttribute("stats", portal.dashboard(user));
      req.setAttribute("chart", portal.chart(user));
      req.setAttribute("rows", portal.shifts(user, month));
    } else if (page.startsWith("shifts/")) {
      req.setAttribute("rows", portal.shifts(user, month));
      req.setAttribute("people", portal.users(user));
      req.setAttribute("requests", portal.shiftChangeRequests(user));
      if ("shifts/confirm".equals(page) || "shifts/manage".equals(page)) req.setAttribute("warnings", portal.shiftWarnings(user, month));
      if ("shifts/request".equals(page)) req.setAttribute("submissionWindow", portal.shiftSubmissionWindow());
    } else if (page.startsWith("leave/")) {
      req.setAttribute("rows", portal.leaveRequests(user));
      req.setAttribute("balance", portal.leaveBalance(user.getId()));
      req.setAttribute("leaveLedger", portal.leaveHistory(user));
    } else if (page.startsWith("attendance/")) {
      req.setAttribute("rows", portal.attendance(user, month));
      req.setAttribute("adjustments", portal.attendanceAdjustments(user));
      if ("attendance/manage".equals(page)) req.setAttribute("people", portal.users(user));
    } else if ("notifications".equals(page)) {
      req.setAttribute("rows", portal.notifications(user));
      if (user.isHr()) req.setAttribute("mailRows", portal.mailOutbox(user));
    } else if ("employees".equals(page) || "employees/edit".equals(page)) {
      java.util.List<Map<String,Object>> employeeRows = portal.users(user);
      req.setAttribute("rows", employeeRows);
      if (req.getParameter("id") != null) {
        long selectedId = longValue(req, "id", 0);
        employeeRows.stream().filter(row -> ((Number) row.get("id")).longValue() == selectedId).findFirst().ifPresent(row -> req.setAttribute("selectedEmployee", row));
      }
      req.setAttribute("branches", portal.master("branches"));
      req.setAttribute("departments", portal.master("departments"));
      req.setAttribute("employment", portal.master("employment"));
    } else if ("qualifications".equals(page)) {
      req.setAttribute("rows", portal.qualifications(user));
      req.setAttribute("people", portal.users(user));
      req.setAttribute("qualificationTypes", portal.master("qualifications"));
    } else if ("delegations".equals(page)) {
      req.setAttribute("rows", portal.delegations(user));
      req.setAttribute("people", portal.users(user));
    } else if (page.startsWith("masters/")) {
      String type = page.endsWith("branches") ? "branches" : page.endsWith("departments")
          ? "departments" : page.endsWith("catalogs") ? "employment" : "work_types";
      req.setAttribute("masterType", type);
      req.setAttribute("rows", portal.master(type));
      if (page.endsWith("catalogs")) {
        req.setAttribute("qualificationTypes", portal.master("qualifications"));
        req.setAttribute("appSettings", settings.all(user));
      }
    } else if ("audit".equals(page)) {
      req.setAttribute("rows", portal.audit(user));
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
          if (user.isManager() || user.isHr()) portal.saveShift(user, longValue(req, "userId", user.getId()), date,
              req.getParameter("workType"), value(req, "status", "DRAFT"), req.getParameter("note"));
          else portal.submitPreferredShift(user, date, req.getParameter("workType"), req.getParameter("note"));
        }
        case "confirmShifts" -> portal.confirmMonth(user, parseMonth(req.getParameter("month")), req.getParameter("warningReason"));
        case "requestShiftChange" -> portal.requestShiftChange(user, LocalDate.parse(req.getParameter("date")), req.getParameter("workType"), req.getParameter("reason"));
        case "decideShiftChange" -> portal.decideShiftChange(user, Long.parseLong(req.getParameter("id")), "approve".equals(req.getParameter("decision")));
        case "requestLeave" -> portal.requestLeave(user, LocalDate.parse(req.getParameter("date")),
            req.getParameter("unit"), integer(req.getParameter("hours")), req.getParameter("reason"));
        case "decideLeave" -> portal.decideLeave(user, Long.parseLong(req.getParameter("id")), "approve".equals(req.getParameter("decision")));
        case "cancelLeave" -> portal.cancelLeave(user, Long.parseLong(req.getParameter("id")));
        case "clock" -> portal.clock(user, "in".equals(req.getParameter("direction")), req.getParameter("lat"), req.getParameter("lng"), value(req, "locationStatus", "UNKNOWN"));
        case "finalizeAttendance" -> portal.finalizeAttendance(user, Long.parseLong(req.getParameter("id")), Boolean.parseBoolean(req.getParameter("finalized")));
        case "finalizeAttendanceMonth" -> portal.finalizeAttendanceMonth(user, parseMonth(req.getParameter("month")), Boolean.parseBoolean(req.getParameter("finalized")));
        case "finalizeAttendanceEmployeeMonth" -> portal.finalizeAttendanceEmployeeMonth(user, Long.parseLong(req.getParameter("userId")),
            parseMonth(req.getParameter("month")), Boolean.parseBoolean(req.getParameter("finalized")));
        case "requestAttendanceAdjustment" -> portal.requestAttendanceAdjustment(user, Long.parseLong(req.getParameter("attendanceId")),
            LocalDateTime.parse(req.getParameter("requestedIn")), LocalDateTime.parse(req.getParameter("requestedOut")), req.getParameter("reason"));
        case "decideAttendanceAdjustment" -> portal.decideAttendanceAdjustment(user, Long.parseLong(req.getParameter("id")), "approve".equals(req.getParameter("decision")));
        case "markNotificationsRead" -> portal.markNotificationsRead(user);
        case "retryMail" -> portal.retryMail(user, Long.parseLong(req.getParameter("id")));
        case "addEmployee" -> portal.addEmployee(user, req.getParameter("employeeNumber"), req.getParameter("name"), req.getParameter("email"),
            LocalDate.parse(req.getParameter("hireDate")), Long.parseLong(req.getParameter("branchId")), Long.parseLong(req.getParameter("departmentId")),
            Long.parseLong(req.getParameter("employmentId")), req.getParameter("role"), baseUrl(req));
        case "updateEmployee" -> portal.updateEmployee(user, Long.parseLong(req.getParameter("id")), req.getParameter("employeeNumber"), req.getParameter("name"), req.getParameter("email"),
            LocalDate.parse(req.getParameter("hireDate")), Long.parseLong(req.getParameter("branchId")), Long.parseLong(req.getParameter("departmentId")),
            Long.parseLong(req.getParameter("employmentId")), req.getParameter("role"), Boolean.parseBoolean(req.getParameter("active")));
        case "addQualification" -> portal.addQualification(user, Long.parseLong(req.getParameter("userId")), req.getParameter("name"),
            req.getParameter("expiresOn").isBlank() ? null : LocalDate.parse(req.getParameter("expiresOn")));
        case "updateQualification" -> portal.updateQualification(user, Long.parseLong(req.getParameter("id")), req.getParameter("name"),
            req.getParameter("expiresOn").isBlank() ? null : LocalDate.parse(req.getParameter("expiresOn")), Boolean.parseBoolean(req.getParameter("active")));
        case "addDelegation" -> portal.addDelegation(user, longValue(req, "managerId", user.getId()), Long.parseLong(req.getParameter("delegateId")),
            LocalDate.parse(req.getParameter("startsOn")), LocalDate.parse(req.getParameter("endsOn")));
        case "updateDelegation" -> portal.updateDelegation(user, Long.parseLong(req.getParameter("id")),
            LocalDate.parse(req.getParameter("startsOn")), LocalDate.parse(req.getParameter("endsOn")), Boolean.parseBoolean(req.getParameter("active")));
        case "addMaster" -> portal.addMaster(user, req.getParameter("type"), req.getParameter("name"));
        case "toggleMaster" -> portal.toggleMaster(user, req.getParameter("type"), Long.parseLong(req.getParameter("id")), Boolean.parseBoolean(req.getParameter("active")));
        case "updateMaster" -> portal.updateMaster(user, req.getParameter("type"), Long.parseLong(req.getParameter("id")),
            req.getParameter("name"), Boolean.parseBoolean(req.getParameter("active")));
        case "updateWorkType" -> portal.updateWorkType(user, req.getParameter("code"), req.getParameter("nameJa"), req.getParameter("nameEn"), req.getParameter("start"), req.getParameter("end"),
            Integer.parseInt(req.getParameter("breakMinutes")), Integer.parseInt(req.getParameter("requiredStaff")), Boolean.parseBoolean(req.getParameter("active")));
        case "updateSetting" -> settings.update(user, req.getParameter("key"), req.getParameter("value"));
        default -> throw new IllegalArgumentException("操作が指定されていません。");
      }
      req.getSession().setAttribute("flash", "保存しました。");
    } catch (IllegalArgumentException | SecurityException e) {
      req.getSession().setAttribute("error", e.getMessage());
    } catch (Exception e) {
      getServletContext().log("Portal action failed", e);
      req.getSession().setAttribute("error", "処理に失敗しました。入力内容を確認してください。");
    }
    res.sendRedirect(req.getContextPath() + "/app/" + returnPage);
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
  private String value(HttpServletRequest req, String name, String fallback) {
    String value = req.getParameter(name); return value == null || value.isBlank() ? fallback : value;
  }
  private long longValue(HttpServletRequest req, String name, long fallback) {
    try { return Long.parseLong(req.getParameter(name)); } catch (Exception e) { return fallback; }
  }
  private Integer integer(String value) {
    try { return value == null || value.isBlank() ? null : Integer.valueOf(value); } catch (Exception e) { return null; }
  }
  private Object takeFlash(HttpServletRequest req, String key) {
    Object value = req.getSession().getAttribute(key); req.getSession().removeAttribute(key); return value;
  }
  private String baseUrl(HttpServletRequest req) {
    int port = req.getServerPort();
    String portPart = ("http".equals(req.getScheme()) && port == 80) || ("https".equals(req.getScheme()) && port == 443) ? "" : ":" + port;
    return req.getScheme() + "://" + req.getServerName() + portPart + req.getContextPath();
  }
}
