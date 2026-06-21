package service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import model.User;

public class PortalService {
  private final ShiftService shiftService = new ShiftService();
  private final LeaveService leaveService = new LeaveService();
  private final AttendanceService attendanceService = new AttendanceService();
  private final EmployeeService employeeService = new EmployeeService();
  private final MasterDataService masterDataService = new MasterDataService();
  private final NotificationService notificationService = new NotificationService();
  private final DashboardService dashboardService = new DashboardService();
  private final AuditLogService auditLogService = new AuditLogService();

  public Map<String, Object> dashboard(User user) {
    return dashboardService.dashboard(user);
  }

  public List<Map<String, Object>> chart(User user) {
    return dashboardService.chart(user);
  }

  public List<Map<String, Object>> findEmployees(User viewer) {
    return employeeService.findEmployees(viewer);
  }

  public List<Map<String, Object>> shifts(User viewer, YearMonth month) {
    return shiftService.shifts(viewer, month);
  }

  public List<Map<String, Object>> branchShifts(YearMonth month, long branchId) {
    return shiftService.branchShifts(month, branchId);
  }

  public List<Map<String, Object>> scheduleBranches() {
    return shiftService.scheduleBranches();
  }

  public List<Map<String, Object>> dashboardShifts(User viewer, YearMonth month, long branchId) {
    return shiftService.dashboardShifts(viewer, month, branchId);
  }

  public List<Map<String, Object>> dashboardBranches(YearMonth month) {
    return shiftService.dashboardBranches(month);
  }

  public List<Map<String, Object>> workTypes() {
    return shiftService.workTypes();
  }

  public void saveShift(User actor, long userId, LocalDate date, String type, String status, String note) {
    shiftService.saveShift(actor, userId, date, type, status, note);
  }

  public void submitPreferredShift(User actor, LocalDate date, String type, String note) {
    shiftService.submitPreferredShift(actor, date, type, note);
  }

  void submitPreferredShift(User actor, LocalDate date, String type, String note, LocalDate today) {
    shiftService.submitPreferredShift(actor, date, type, note, today);
  }

  public void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences) {
    shiftService.submitMonthlyPreferences(actor, month, preferences);
  }

  void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences, LocalDate today) {
    shiftService.submitMonthlyPreferences(actor, month, preferences, today);
  }

  public void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences, Map<LocalDate, String> reasons) {
    shiftService.submitMonthlyPreferences(actor, month, preferences, reasons);
  }

  void submitMonthlyPreferences(User actor, YearMonth month, Map<LocalDate, String> preferences, Map<LocalDate, String> reasons, LocalDate today) {
    shiftService.submitMonthlyPreferences(actor, month, preferences, reasons, today);
  }

  public Map<String, Object> preferenceSubmission(User user, YearMonth month) {
    return shiftService.preferenceSubmission(user, month);
  }

  public List<Map<String, Object>> preferences(User user, YearMonth month) {
    return shiftService.preferences(user, month);
  }

  public List<Map<String, Object>> preferenceSubmissionSummaries(User viewer, YearMonth month) {
    return shiftService.preferenceSubmissionSummaries(viewer, month);
  }

  public List<Map<String, Object>> preferenceDetails(User viewer, YearMonth month) {
    return shiftService.preferenceDetails(viewer, month);
  }

  public void reviewPreferenceSubmission(User actor, long submissionId, boolean approved) {
    shiftService.reviewPreferenceSubmission(actor, submissionId, approved);
  }

  public int autoAssignShifts(User actor, YearMonth month) {
    return shiftService.autoAssignShifts(actor, month);
  }

  public Map<String, Object> shiftSubmissionWindow() {
    return shiftService.shiftSubmissionWindow();
  }

  public void confirmMonth(User actor, YearMonth month, String warningReason) {
    shiftService.confirmMonth(actor, month, warningReason);
  }

  public List<Map<String, Object>> shiftChangeRequests(User viewer) {
    return shiftService.shiftChangeRequests(viewer);
  }

  public void requestShiftChange(User user, LocalDate date, String type, String reason) {
    shiftService.requestShiftChange(user, date, type, reason);
  }

  public void decideShiftChange(User actor, long requestId, boolean approve) {
    shiftService.decideShiftChange(actor, requestId, approve);
  }

  public List<Map<String, Object>> shiftWarningsForDate(User viewer, LocalDate date) {
    return shiftService.shiftWarningsForDate(viewer, date);
  }

  public List<Map<String, Object>> shiftWarnings(User viewer, YearMonth month) {
    return shiftService.shiftWarnings(viewer, month);
  }

  public List<Map<String, Object>> leaveRequests(User viewer) {
    return leaveService.leaveRequests(viewer);
  }

  public List<Map<String, Object>> leaveApprovers(User user) {
    return leaveService.leaveApprovers(user);
  }

  public Map<String, Object> leaveBalance(long userId) {
    return leaveService.leaveBalance(userId);
  }

  public List<Map<String, Object>> leaveHistory(User viewer) {
    return leaveService.leaveHistory(viewer);
  }

  public void requestLeave(User user, LocalDate date, String unit, Integer hours, String reason) {
    leaveService.requestLeave(user, date, unit, hours, reason);
  }

  public void decideLeave(User actor, long requestId, boolean approve) {
    leaveService.decideLeave(actor, requestId, approve);
  }

  public void decideLeave(User actor, long requestId, boolean approve, String rejectionReason) {
    leaveService.decideLeave(actor, requestId, approve, rejectionReason);
  }

  public void cancelLeave(User actor, long requestId) {
    leaveService.cancelLeave(actor, requestId);
  }

  public List<Map<String, Object>> attendance(User viewer, YearMonth month) {
    return attendanceService.attendance(viewer, month);
  }

  public Map<String, Object> attendanceClockSummary(User user) {
    return attendanceService.attendanceClockSummary(user);
  }

  public void clock(User user, boolean clockIn, String lat, String lng, String locationStatus) {
    attendanceService.clock(user, clockIn, lat, lng, locationStatus);
  }

  public void finalizeAttendance(User actor, long attendanceId, boolean finalized) {
    attendanceService.finalizeAttendance(actor, attendanceId, finalized);
  }

  public void finalizeAttendanceMonth(User actor, YearMonth month, boolean finalized) {
    attendanceService.finalizeAttendanceMonth(actor, month, finalized);
  }

  public void finalizeAttendanceEmployeeMonth(User actor, long userId, YearMonth month, boolean finalized) {
    attendanceService.finalizeAttendanceEmployeeMonth(actor, userId, month, finalized);
  }

  public List<Map<String, Object>> attendanceAdjustments(User viewer) {
    return attendanceService.attendanceAdjustments(viewer);
  }

  public void requestAttendanceAdjustment(User user, long attendanceId, LocalDateTime requestedIn, LocalDateTime requestedOut, String reason) {
    attendanceService.requestAttendanceAdjustment(user, attendanceId, requestedIn, requestedOut, reason);
  }

  public void decideAttendanceAdjustment(User actor, long requestId, boolean approve) {
    attendanceService.decideAttendanceAdjustment(actor, requestId, approve);
  }

  public List<Map<String, Object>> notifications(User user) {
    return notificationService.notifications(user);
  }

  public List<Map<String, Object>> mailOutbox(User user) {
    return notificationService.mailOutbox(user);
  }

  public void retryMail(User user, long id) {
    notificationService.retryMail(user, id);
  }

  public void markNotificationsRead(User user) {
    notificationService.markNotificationsRead(user);
  }

  public List<Map<String, Object>> getMasterData(String type) {
    return masterDataService.getMasterData(type);
  }

  public List<Map<String, Object>> qualifications(User viewer) {
    return employeeService.qualifications(viewer);
  }

  public List<Map<String, Object>> delegations(User viewer) {
    return employeeService.delegations(viewer);
  }

  public List<Map<String, Object>> audit(User user) {
    return auditLogService.audit(user);
  }

  public List<Map<String, Object>> audit(User user, LocalDate from, LocalDate to, Long actorId, String action, Long targetUserId) {
    return auditLogService.audit(user, from, to, actorId, action, targetUserId);
  }

  public List<Map<String, Object>> auditActions(User user) {
    return auditLogService.auditActions(user);
  }

  public void addQualification(User actor, long userId, String name, LocalDate expires) {
    employeeService.addQualification(actor, userId, name, expires);
  }

  public void updateQualification(User actor, long id, String name, LocalDate expires, boolean active) {
    employeeService.updateQualification(actor, id, name, expires, active);
  }

  public void addDelegation(User actor, long managerId, long delegateId, LocalDate start, LocalDate end) {
    employeeService.addDelegation(actor, managerId, delegateId, start, end);
  }

  public void updateDelegation(User actor, long id, LocalDate start, LocalDate end, boolean active) {
    employeeService.updateDelegation(actor, id, start, end, active);
  }

  public void addEmployee(User actor, String number, String name, String email, LocalDate hireDate, long branch, long department, long employment, String role, String baseUrl) {
    employeeService.addEmployee(actor, number, name, email, hireDate, branch, department, employment, role, baseUrl);
  }

  public void reissueInvite(User actor, long userId, String baseUrl) {
    employeeService.reissueInvite(actor, userId, baseUrl);
  }

  public void updateEmployee(User actor, long id, String number, String name, String email, LocalDate hireDate, long branch, long department, long employment, String role, boolean active) {
    employeeService.updateEmployee(actor, id, number, name, email, hireDate, branch, department, employment, role, active);
  }

  public void addMaster(User actor, String type, String name) {
    masterDataService.addMaster(actor, type, name);
  }

  public void toggleMaster(User actor, String type, long id, boolean active) {
    masterDataService.toggleMaster(actor, type, id, active);
  }

  public void updateMaster(User actor, String type, long id, String name, boolean active) {
    masterDataService.updateMaster(actor, type, id, name, active);
  }

  public void updateWorkType(User actor, String code, String nameJa, String nameEn, String start, String end, int breakMinutes, int requiredStaff, boolean active) {
    masterDataService.updateWorkType(actor, code, nameJa, nameEn, start, end, breakMinutes, requiredStaff, active);
  }

  public void notify(long userId, String type, String title, String message, String url) {
    notificationService.notify(userId, type, title, message, url);
  }
}
