<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.*,java.time.*,model.User" %>
<%!
  private String e(Object value) {
    return util.HtmlEscaper.escape(value);
  }
  private String days(Object value) {
    if (value == null) return "0";
    try { return new java.math.BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString(); }
    catch (NumberFormatException ex) { return e(value); }
  }
  private String shiftClass(Object code) {
    String value = String.valueOf(code);
    if ("DAY".equals(value)) return "day";
    if ("NIGHT".equals(value)) return "night";
    if ("NIGHT_OFF".equals(value)) return "night-off";
    if ("OFF".equals(value)) return "off";
    if ("LEAVE".equals(value)) return "leave";
    if ("AM_LEAVE".equals(value) || "PM_LEAVE".equals(value)) return "half-leave";
    return "other";
  }
  private String shiftShort(Object code) {
    String value = String.valueOf(code);
    if ("DAY".equals(value)) return "日";
    if ("NIGHT".equals(value)) return "夜";
    if ("NIGHT_OFF".equals(value)) return "明";
    if ("OFF".equals(value)) return "休";
    if ("LEAVE".equals(value)) return "有";
    if ("AM_LEAVE".equals(value) || "PM_LEAVE".equals(value)) return "半";
    return "-";
  }
  private String preferenceLabel(Object code) {
    String value = String.valueOf(code);
    if ("DAY".equals(value)) return "日勤";
    if ("NIGHT".equals(value)) return "夜勤";
    if ("OFF".equals(value)) return "休日希望";
    if ("LEAVE".equals(value)) return "有休希望";
    return "希望なし";
  }
  private String workTypeLabel(Object code, Object name) {
    if (name != null && !String.valueOf(name).isBlank()) return e(name);
    String value = String.valueOf(code);
    if ("DAY".equals(value)) return "日勤";
    if ("NIGHT".equals(value)) return "夜勤";
    if ("NIGHT_OFF".equals(value)) return "夜勤明け";
    if ("OFF".equals(value)) return "休み";
    if ("LEAVE".equals(value)) return "有休";
    if ("AM_LEAVE".equals(value)) return "午前休";
    if ("PM_LEAVE".equals(value)) return "午後休";
    return e(code);
  }
  private String locationLabel(Object status) {
    String value = String.valueOf(status);
    if ("ACQUIRED".equals(value)) return "取得済み";
    if ("DENIED".equals(value)) return "拒否";
    if ("UNAVAILABLE".equals(value)) return "利用不可";
    if ("UNKNOWN".equals(value) || status == null) return "不明";
    return e(status);
  }
  private String timestampLabel(Object value) {
    if (value == null) return "-";
    String text = String.valueOf(value).replace('T', ' ');
    return e(text.length() >= 19 ? text.substring(0, 19) : text);
  }
  private String status(Object value) { return value == null ? "" : String.valueOf(value).toLowerCase(); }
  private String statusLabel(Object value) {
    String status = String.valueOf(value);
    if ("PENDING".equals(status)) return "申請中";
    if ("APPROVED".equals(status)) return "承認済み";
    if ("REJECTED".equals(status)) return "却下";
    if ("CANCELLED".equals(status)) return "取消済み";
    return e(value);
  }
  private String shiftStatusLabel(Object value) {
    if (value == null) return "-";
    String status = String.valueOf(value);
    if ("DRAFT".equals(status)) return "下書き";
    if ("CONFIRMED".equals(status)) return "確定";
    if ("SUBMITTED".equals(status)) return "提出済み";
    return e(value);
  }
  private String submissionStatusLabel(Object value) {
    if (value == null || String.valueOf(value).isBlank()) return "未提出";
    String status = String.valueOf(value);
    if ("SUBMITTED".equals(status)) return "提出済み";
    if ("APPROVED".equals(status)) return "承認済み";
    if ("RETURNED".equals(status)) return "差戻し";
    if ("DRAFT".equals(status)) return "下書き";
    return e(value);
  }
  private String leaveUnitLabel(Object value) {
    String unit = String.valueOf(value);
    if ("FULL".equals(unit)) return "1日";
    if ("AM".equals(unit)) return "午前休";
    if ("PM".equals(unit)) return "午後休";
    if ("HOURLY".equals(unit)) return "時間単位";
    return e(value);
  }
  private String leaveEventTypeLabel(Object value) {
    String type = String.valueOf(value);
    if ("GRANT".equals(type)) return "付与";
    if ("USE".equals(type)) return "取得";
    if ("RESTORE".equals(type)) return "取消戻し";
    if ("EXPIRE".equals(type)) return "失効";
    if ("INELIGIBLE".equals(type)) return "付与対象外";
    return e(value);
  }
  private String leaveNoteLabel(Object value) {
    String note = String.valueOf(value);
    if ("FULL".equals(note) || "AM".equals(note) || "PM".equals(note) || "HOURLY".equals(note)) return leaveUnitLabel(note);
    if ("statutory expiry".equals(note)) return "法定失効";
    return e(value);
  }
  private boolean pageIs(String page, String prefix) { return page.equals(prefix) || page.startsWith(prefix + "/"); }
%>
<%
User user = (User) session.getAttribute("loginUser");
String pageKey = (String) request.getAttribute("page");
String pageTitle = (String) request.getAttribute("pageTitle");
YearMonth month = (YearMonth) request.getAttribute("month");
List<Map<String,Object>> rows = (List<Map<String,Object>>) request.getAttribute("rows");
if (rows == null) rows = Collections.emptyList();
List<Map<String,Object>> people = (List<Map<String,Object>>) request.getAttribute("people");
if (people == null) people = Collections.emptyList();
List<Map<String,Object>> workTypes = (List<Map<String,Object>>) request.getAttribute("workTypes");
boolean manager = user.isManager() || user.isHr();
boolean en = "en".equals(user.getLocale());
String ctx = request.getContextPath();
%>
<!DOCTYPE html>
<html lang="<%= en ? "en" : "ja" %>">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title><%= e(pageTitle) %> | ShiftFlow</title>
  <link rel="stylesheet" href="<%= ctx %>/assets/app.css?v=20260620-5">
</head>
<body>
<a class="skip-link" href="#main-content"><%=en?"Skip to main content":"本文へ移動"%></a>
<div class="app-shell">
  <aside class="sidebar" id="main-navigation" aria-label="メインナビゲーション">
    <a class="brand" href="<%= ctx %>/app/dashboard"><span class="brand-mark">SF</span><span><strong>ShiftFlow</strong><small><%= en ? "Workforce portal" : "シフト・有休管理" %></small></span></a>
    <nav>
      <p class="nav-label"><%= en ? "Overview" : "概要" %></p>
      <a class="nav-link <%= pageKey.equals("notifications") ? "active" : "" %>" href="<%=ctx%>/app/notifications"><span aria-hidden="true">🔔</span> <%= en ? "Notifications" : "通知" %></a>
      <a class="nav-link <%= pageKey.equals("dashboard") ? "active" : "" %>" href="<%=ctx%>/app/dashboard">▦ <%= en ? "Dashboard" : "ダッシュボード" %></a>
      <p class="nav-label"><%= en ? "Schedule" : "シフト" %></p>
      <a class="nav-link <%= List.of("shifts/mine","shifts/request","shifts/change").contains(pageKey) ? "active" : "" %>" href="<%=ctx%>/app/shifts/mine">□ <%= en ? "Schedule" : "シフト" %></a>
      <% if (manager) { %>
      <a class="nav-link <%= pageKey.equals("shifts/manage") ? "active" : "" %>" href="<%=ctx%>/app/shifts/manage">☷ <%= en ? "Schedule editor" : "シフト調整" %></a>
      <a class="nav-link <%= pageKey.equals("shifts/confirm") ? "active" : "" %>" href="<%=ctx%>/app/shifts/confirm">✓ <%= en ? "Confirm schedule" : "シフト確定" %></a>
      <% } %>
      <p class="nav-label"><%= en ? "Leave" : "有休" %></p>
      <a class="nav-link <%= (pageKey.equals("leave") || pageKey.equals("leave/balance") || pageKey.equals("leave/request") || pageKey.equals("leave/history")) ? "active" : "" %>" href="<%=ctx%>/app/leave">◷ <%= en ? "Leave" : "有休" %></a>
      <% if (manager) { %><a class="nav-link <%= pageKey.equals("leave/approvals") ? "active" : "" %>" href="<%=ctx%>/app/leave/approvals">✓ <%= en ? "Approvals" : "承認" %></a><% } %>
      <p class="nav-label"><%= en ? "Attendance" : "勤怠" %></p>
      <a class="nav-link <%= (pageKey.equals("attendance/clock") || pageKey.equals("attendance/mine") || pageKey.equals("attendance/adjust") || pageKey.equals("attendance/history")) ? "active" : "" %>" href="<%=ctx%>/app/attendance/clock">◉ <%= en ? "Time clock" : "出退勤打刻" %></a>
      <% if (manager) { %><a class="nav-link <%= pageKey.equals("attendance/manage") ? "active" : "" %>" href="<%=ctx%>/app/attendance/manage">✓ <%= en ? "Monthly close" : "月次確定" %></a><% } %>
      <% if (user.isHr()) { %>
      <p class="nav-label"><%= en ? "Administration" : "管理" %></p>
      <a class="nav-link <%= pageIs(pageKey,"employees") ? "active" : "" %>" href="<%=ctx%>/app/employees">♙ <%= en ? "Employees" : "従業員" %></a>
      <a class="nav-link <%= pageIs(pageKey,"masters") ? "active" : "" %>" href="<%=ctx%>/app/masters/branches">⚙ <%= en ? "Master data" : "マスタ管理" %></a>
      <a class="nav-link <%= pageKey.equals("exports") ? "active" : "" %>" href="<%=ctx%>/app/exports">⇩ <%= en ? "Exports" : "データ出力" %></a>
      <a class="nav-link <%= pageKey.equals("audit") ? "active" : "" %>" href="<%=ctx%>/app/audit">≡ <%= en ? "Audit log" : "操作履歴" %></a>
      <% } %>
    </nav>
  </aside>
  <div class="main-column">
    <header class="topbar">
      <div class="actions"><button type="button" class="menu-button" data-menu aria-label="メニュー" aria-controls="main-navigation" aria-expanded="false">☰</button><h1><%= e(pageTitle) %></h1></div>
      <div class="topbar-meta">
        <a class="button" href="<%=ctx%>/app/notifications" aria-label="<%=en?"Notifications":"通知"%>"><span aria-hidden="true">🔔</span></a>
        <div class="user-chip"><strong><%=e(user.getName())%></strong><small><%=e(user.getBranchName())%> / <%=e(user.getDepartmentName())%></small></div>
        <a class="button" href="<%=ctx%>/app/account" aria-label="アカウント設定">⚙</a>
        <form action="<%=ctx%>/logout" method="post"><button class="button" type="submit"><%= en ? "Sign out" : "ログアウト" %></button></form>
      </div>
    </header>
    <main class="content" id="main-content" tabindex="-1">
      <% if (request.getAttribute("flash") != null) { %><div class="alert"><%=e(request.getAttribute("flash"))%></div><% } %>
      <% if (request.getAttribute("error") != null) { %><div class="alert danger"><%=e(request.getAttribute("error"))%></div><% } %>

      <% if (List.of("shifts/mine","shifts/request","shifts/change").contains(pageKey)) { %>
      <nav class="shift-tabs" aria-label="シフトメニュー">
        <a class="shift-tab schedule <%=pageKey.equals("shifts/mine")?"active":""%>" href="<%=ctx%>/app/shifts/mine?month=<%=month%>" <%=pageKey.equals("shifts/mine")?"aria-current=\"page\"":""%>>
          <span aria-hidden="true">▦</span><span><strong><%=en?"Schedule":"シフト"%></strong><small><%=en?"View monthly schedule":"月間シフトを確認"%></small></span>
        </a>
        <a class="shift-tab preference <%=pageKey.equals("shifts/request")?"active":""%>" href="<%=ctx%>/app/shifts/request?month=<%=month%>" <%=pageKey.equals("shifts/request")?"aria-current=\"page\"":""%>>
          <span aria-hidden="true">＋</span><span><strong><%=en?"Shift preferences":"希望シフト提出"%></strong><small><%=en?"Submit monthly preferences":"希望日をまとめて提出"%></small></span>
        </a>
        <a class="shift-tab change <%=pageKey.equals("shifts/change")?"active":""%>" href="<%=ctx%>/app/shifts/change?month=<%=month%>" <%=pageKey.equals("shifts/change")?"aria-current=\"page\"":""%>>
          <span aria-hidden="true">↻</span><span><strong><%=en?"Change / day off":"変更・休み申請"%></strong><small><%=en?"Request a schedule change":"確定後の変更を申請"%></small></span>
        </a>
      </nav>
      <% } %>

      <% if (pageKey.equals("dashboard")) {
        Map<String,Object> stats = (Map<String,Object>) request.getAttribute("stats");
        List<Map<String,Object>> chart = (List<Map<String,Object>>) request.getAttribute("chart");
        double overtimeHours = ((Number)stats.get("monthOvertimeHours")).doubleValue();
        double overtimeThreshold = ((Number)stats.get("overtimeAlertThreshold")).doubleValue();
        String overtimeLevel = String.valueOf(stats.get("overtimeAlertLevel")); %>
        <div class="overtime-alert <%=overtimeLevel%>" role="<%="danger".equals(overtimeLevel)?"alert":"status"%>">
          <span class="overtime-alert-icon" aria-hidden="true"><%="safe".equals(overtimeLevel)?"✓":"!"%></span>
          <div><strong>残業アラート</strong>
            <p>今月の残業は<strong><%=String.format("%.1f",overtimeHours)%>時間</strong>です（基準 <%=String.format("%.0f",overtimeThreshold)%>時間）。
              <%if("danger".equals(overtimeLevel)){%>基準に達しています。勤務状況を確認してください。<%}else if("warning".equals(overtimeLevel)){%>基準に近づいています。今後の勤務予定にご注意ください。<%}else{%>現在は基準内です。<%}%>
            </p>
          </div>
        </div>
        <div class="metric-grid">
          <div class="metric"><span class="label">今日の勤務者</span><strong><%=e(stats.get("todayWorkers"))%><small>名</small></strong></div>
          <div class="metric"><span class="label">未承認申請</span><strong><%=e(stats.get("pending"))%><small>件</small></strong></div>
          <div class="metric"><span class="label">有休残日数</span><strong><%=days(stats.get("leave"))%><small>日</small></strong></div>
          <div class="metric"><span class="label">人員不足</span><div class="staffing-shortage"><span>日勤<strong><%=e(stats.get("dayShortagePercent"))%>%</strong></span><span>夜勤<strong><%=e(stats.get("nightShortagePercent"))%>%</strong></span></div></div>
          <div class="metric"><span class="label">今月の実勤務</span><strong><%=String.format("%.1f",stats.get("monthHours"))%><small>時間</small></strong></div>
        </div>
        <div class="dashboard-grid">
          <section class="section"><div class="section-header"><h2>勤務時間・残業時間・有休取得数の推移</h2><span class="muted"><%=en?"Work / overtime / leave":"直近6か月"%></span></div>
            <% if (chart.isEmpty()) { %><div class="empty">集計できる勤怠データがありません。</div><% } else { %>
            <div class="chart"><% for (Map<String,Object> item : chart) {
              double hours = ((Number)item.get("total_hours")).doubleValue(); double overtime = ((Number)item.get("overtime_hours")).doubleValue(); double leave = ((Number)item.get("leave_days")).doubleValue(); %>
              <div class="chart-column"><span class="bar" style="height:<%=Math.min(100,hours/2)%>%" title="勤務 <%=hours%>時間"></span><span class="bar overtime" style="height:<%=Math.min(100,overtime*2)%>%" title="残業 <%=overtime%>時間"></span><span class="bar leave" style="height:<%=Math.min(100,leave*12)%>%" title="<%=en?"Leave":"有休"%> <%=leave%>日"></span><small><%=e(item.get("month_label"))%></small></div>
            <% } %></div><% } %>
          </section>
          <% { String rosterTitle="シフト"; String rosterLink=null; List<Map<String,Object>> rosterBranches=(List<Map<String,Object>>)request.getAttribute("dashboardBranches"); Number selectedRosterBranch=(Number)request.getAttribute("dashboardBranchId"); Long rosterBranchId=selectedRosterBranch==null?user.getBranchId():selectedRosterBranch.longValue(); if(rosterBranches==null)rosterBranches=Collections.emptyList(); String selectedRosterBranchQuery=rosterBranchId==null?"":"&amp;branchId="+rosterBranchId; %>
            <div class="toolbar no-print">
              <form method="get"><%if(rosterBranchId!=null){%><input type="hidden" name="branchId" value="<%=rosterBranchId%>"><%}%><label>対象月<input type="month" name="month" value="<%=month%>" data-auto-submit></label></form>
              <div class="actions"><a class="button" href="<%=ctx%>/app/shifts/print?month=<%=month%><%=selectedRosterBranchQuery%>&amp;printDialog=1">印刷</a></div>
            </div>
            <%@ include file="_shiftRoster.jspf" %>
          <% } %>
        </div>

      <% } else if (pageKey.startsWith("shifts/")) { Number selectedShiftBranch=(Number)request.getAttribute("shiftBranchId"); String selectedShiftBranchQuery=selectedShiftBranch==null?"":"&amp;branchId="+selectedShiftBranch.longValue(); boolean printDialogRequested=pageKey.equals("shifts/print")&&"1".equals(request.getParameter("printDialog")); %>
        <div class="toolbar no-print">
          <form method="get"><%if(selectedShiftBranch!=null){%><input type="hidden" name="branchId" value="<%=selectedShiftBranch%>"><%}%><label>対象月<input type="month" name="month" value="<%=month%>" <%=(pageKey.equals("shifts/mine") || pageKey.equals("shifts/request") || pageKey.equals("shifts/change"))?"data-auto-submit":""%>></label><%if(!pageKey.equals("shifts/mine") && !pageKey.equals("shifts/request") && !pageKey.equals("shifts/change")){%><button type="submit">表示</button><%}%></form>
          <div class="actions"><%if(pageKey.equals("shifts/print")){%><a class="button" href="<%=ctx%>/app/shifts/mine?month=<%=month%><%=selectedShiftBranchQuery%>">シフトへ戻る</a><%if(!printDialogRequested){%><a class="button primary" href="<%=ctx%>/app/shifts/print?month=<%=month%><%=selectedShiftBranchQuery%>&amp;printDialog=1">印刷する</a><%}%><%}else{%><a class="button" href="<%=ctx%>/app/shifts/print?month=<%=month%><%=selectedShiftBranchQuery%>&amp;printDialog=1">印刷</a><% if(manager){ %><a class="button primary" href="<%=ctx%>/app/shifts/manage?month=<%=month%>">調整する</a><% }} %></div>
        </div>
        <%if(printDialogRequested){%><div class="alert no-print" hidden data-print-on-load>印刷ダイアログを表示できませんでした。ブラウザの <strong>Ctrl+P</strong>（Macは <strong>⌘+P</strong>）を押してください。</div><%}%>
        <% if (pageKey.equals("shifts/request")) { Map<String,Object> submissionWindow=(Map<String,Object>)request.getAttribute("submissionWindow"); boolean submissionOpen=Boolean.TRUE.equals(submissionWindow.get("open")); Map<String,Object> preferenceSubmission=(Map<String,Object>)request.getAttribute("preferenceSubmission"); List<Map<String,Object>> preferenceRows=(List<Map<String,Object>>)request.getAttribute("preferenceRows"); Map<String,String> preferenceByDate=new HashMap<>(); Map<String,String> preferenceReasonByDate=new HashMap<>(); for(Map<String,Object> preference:preferenceRows){String preferenceDate=String.valueOf(preference.get("preference_date"));preferenceByDate.put(preferenceDate,String.valueOf(preference.get("request_type")));if(preference.get("note")!=null)preferenceReasonByDate.put(preferenceDate,String.valueOf(preference.get("note")));} %>
        <div class="<%=submissionOpen?"alert":"error-banner"%>">対象月: <strong><%=e(submissionWindow.get("target_month"))%></strong> / 提出期限: <strong><%=e(submissionWindow.get("deadline"))%></strong><%=submissionOpen?"":"（受付終了）"%> / 状態: <strong><%=submissionStatusLabel(preferenceSubmission.get("status"))%></strong></div>
        <section class="section preference-section"><div class="section-header"><div><h2>希望日をまとめて選択</h2><p class="muted">希望がある日だけ選択してください。未選択日は自動割当の対象になります。</p></div></div>
          <div class="alert info-banner" style="margin-bottom: 1.5rem; background: #f0f9ff; border-left: 4px solid #0284c7; padding: 1rem; border-radius: 4px;">
            <strong style="color: #0369a1;">【有給休暇の申請について】</strong><br>
            有給休暇（有休）の取得を希望される場合は、この画面からは申請できません。左側メニューの<strong>「有休申請」</strong>から個別に申請を行ってください。承認された有休は、自動割り当て時に最優先で反映されます。
          </div>
          <form method="post" data-preference-form>
            <input type="hidden" name="action" value="submitMonthlyPreferences"><input type="hidden" name="returnPage" value="shifts/request"><input type="hidden" name="returnMonth" value="<%=month%>"><input type="hidden" name="month" value="<%=month%>">
            <%String[] preferenceWeekdays={"月","火","水","木","金","土","日"};%>
            <div class="preference-matrix-wrap"><table class="preference-matrix" aria-label="<%=month%> 希望シフト"><thead><tr><th class="preference-person" rowspan="2"><%=month.getYear()%>年<%=month.getMonthValue()%>月</th>
              <%for(int day=1;day<=month.lengthOfMonth();day++){%><th class="preference-date"><%=day%></th><%}%><th class="preference-total-heading" colspan="3">合計数</th></tr><tr>
              <%for(int day=1;day<=month.lengthOfMonth();day++){LocalDate preferenceDate=month.atDay(day);int weekday=preferenceDate.getDayOfWeek().getValue();%><th class="preference-weekday <%=weekday==7?"sunday":weekday==6?"saturday":""%>"><%=preferenceWeekdays[weekday-1]%></th><%}%>
              <th class="preference-total">日勤</th><th class="preference-total">夜勤</th><th class="preference-total">休日</th></tr></thead><tbody><tr><th class="preference-person"><strong><%=e(user.getName())%></strong><small><%=e(user.getEmployeeNumber())%></small></th>
              <%for(int day=1;day<=month.lengthOfMonth();day++){LocalDate preferenceDate=month.atDay(day);String selectedPreference=preferenceByDate.getOrDefault(preferenceDate.toString(),"NONE");%><td class="preference-day <%=shiftClass(selectedPreference)%>" data-preference-day>
                <select name="preference_<%=preferenceDate%>" data-preference-select aria-label="<%=preferenceDate%>の希望">
                  <option value="NONE" data-label="希望なし" <%="NONE".equals(selectedPreference)?"selected":""%>>-</option><option value="DAY" data-label="日勤" <%="DAY".equals(selectedPreference)?"selected":""%>>日</option><option value="NIGHT" data-label="夜勤" <%="NIGHT".equals(selectedPreference)?"selected":""%>>夜</option><option value="OFF" data-label="休日希望" <%="OFF".equals(selectedPreference)?"selected":""%>>休</option>
                </select><input type="hidden" name="reason_<%=preferenceDate%>" value="<%=e(preferenceReasonByDate.get(preferenceDate.toString()))%>" data-preference-reason></td><%}%>
              <td class="preference-total" data-preference-total="DAY">0</td><td class="preference-total" data-preference-total="NIGHT">0</td><td class="preference-total" data-preference-total="OFF">0</td></tr></tbody></table></div>
            <dialog class="leave-reason-dialog" data-leave-reason-dialog><div class="leave-reason-card"><h3>有休希望の理由</h3><p><strong data-leave-reason-date></strong> の理由を入力できます（任意）。</p><label>理由<textarea maxlength="500" rows="4" data-leave-reason-text placeholder="例：家族行事のため"></textarea></label><div class="actions"><button type="button" data-leave-reason-save class="primary">設定する</button><button type="button" data-leave-reason-clear>理由なし</button><button type="button" data-leave-reason-cancel>閉じる</button></div></div></dialog>
            <div class="preference-review" aria-live="polite"><h3>提出する希望</h3><ul data-preference-summary></ul><p class="muted" data-preference-empty>選択済みの希望はありません。全日「希望なし」で提出できます。</p></div>
            <div class="preference-submit"><button class="primary" type="submit" <%=submissionOpen?"":"disabled"%>><%=preferenceSubmission.isEmpty()?"提出する":"更新して再提出する"%></button></div>
          </form>
        </section>
        <% } else if (pageKey.equals("shifts/manage") || pageKey.equals("shifts/change")) { %>
        <section class="section no-print"><div class="section-header"><h2><%=pageKey.equals("shifts/change")?"変更・休みを申請":"勤務区分を登録"%></h2></div>
          <form method="post" class="form-grid">
            <input type="hidden" name="action" value="<%=pageKey.equals("shifts/change")?"requestShiftChange":"saveShift"%>"><input type="hidden" name="returnPage" value="<%=pageKey%>">
            <% if(manager){ %><label>従業員<select name="userId" required><% for(Map<String,Object> person:people){ %><option value="<%=person.get("id")%>"><%=e(person.get("employee_number"))%> <%=e(person.get("name"))%></option><% } %></select></label><% } %>
            <label>日付<input type="date" name="date" required></label>
            <label>勤務区分<select name="workType" required><% for(Map<String,Object> wt:workTypes){ %><option value="<%=wt.get("code")%>"><%=e(en?wt.get("name_en"):wt.get("name_ja"))%></option><% } %></select></label>
            <% if(manager){ %><label>状態<select name="status"><option value="DRAFT">下書き</option><option value="SUBMITTED">提出済み</option><option value="CONFIRMED">確定</option></select></label><% } %>
            <label class="span-2">備考・理由<input type="text" name="<%=pageKey.equals("shifts/change")?"reason":"note"%>" maxlength="1000" <%=pageKey.equals("shifts/change")?"required":""%>></label>
            <div class="span-all"><button class="primary" type="submit"><%=pageKey.equals("shifts/change")?"申請する":"保存する"%></button></div>
          </form>
        </section><% } %>
        <% if(pageKey.equals("shifts/manage")){ List<Map<String,Object>> preferenceSubmissions=(List<Map<String,Object>>)request.getAttribute("preferenceSubmissions"); List<Map<String,Object>> preferenceDetails=(List<Map<String,Object>>)request.getAttribute("preferenceDetails"); %>
        <section class="section"><div class="section-header"><div><h2>希望シフト提出状況</h2><p class="muted">希望なしの日は自動割当の対象です。</p></div><form method="post"><input type="hidden" name="action" value="autoAssignShifts"><input type="hidden" name="returnPage" value="shifts/manage"><input type="hidden" name="returnMonth" value="<%=month%>"><input type="hidden" name="month" value="<%=month%>"><button class="primary" type="submit">希望を考慮して自動割当</button></form></div>
          <div class="table-wrap"><table><thead><tr><th>社員番号</th><th>氏名</th><th>支店</th><th>提出状態</th><th>希望日数</th><th>提出日時</th><th>確認</th></tr></thead><tbody><%for(Map<String,Object> summary:preferenceSubmissions){String submissionStatus=String.valueOf(summary.get("status"));String submissionLabel="APPROVED".equals(submissionStatus)?"承認済み":"RETURNED".equals(submissionStatus)?"差戻し":"SUBMITTED".equals(submissionStatus)?"提出済み":"未提出";%><tr><td><%=e(summary.get("employee_number"))%></td><td><%=e(summary.get("name"))%></td><td><%=e(summary.get("branch_name"))%></td><td><span class="status <%=List.of("SUBMITTED","APPROVED").contains(submissionStatus)?"approved":"pending"%>"><%=submissionLabel%></span></td><td><%=e(summary.get("preference_count"))%>日</td><td><%=e(summary.get("submitted_at"))%></td><td><%if("SUBMITTED".equals(submissionStatus)){%><form method="post" class="actions"><input type="hidden" name="action" value="reviewShiftPreferences"><input type="hidden" name="returnPage" value="shifts/manage"><input type="hidden" name="returnMonth" value="<%=month%>"><input type="hidden" name="id" value="<%=summary.get("submission_id")%>"><button class="primary" name="decision" value="approve">承認</button><button name="decision" value="return">差戻し</button></form><%}else{%>-<%}%></td></tr><%}%></tbody></table></div>
          <h3>提出された希望日</h3><div class="table-wrap"><table><thead><tr><th>日付</th><th>社員番号</th><th>氏名</th><th>希望</th><th>有休希望の理由</th></tr></thead><tbody><%for(Map<String,Object> detail:preferenceDetails){%><tr><td><%=e(detail.get("preference_date"))%></td><td><%=e(detail.get("employee_number"))%></td><td><%=e(detail.get("name"))%></td><td><span class="preference-label <%=shiftClass(detail.get("request_type"))%>"><%=preferenceLabel(detail.get("request_type"))%></span></td><td><%="LEAVE".equals(String.valueOf(detail.get("request_type")))?e(detail.get("note")):"-"%></td></tr><%}%><%if(preferenceDetails.isEmpty()){%><tr><td colspan="5" class="empty">提出された希望日はありません。</td></tr><%}%></tbody></table></div>
        </section><%}%>
        <% if (pageKey.equals("shifts/confirm") || pageKey.equals("shifts/manage")) { List<Map<String,Object>> warnings=(List<Map<String,Object>>)request.getAttribute("warnings"); %><section class="section no-print"><h2>確定前チェック</h2><%if(warnings==null||warnings.isEmpty()){%><p class="alert">警告はありません。</p><%}else{%><div class="table-wrap"><table><thead><tr><th>種類</th><th>日付</th><th>内容</th><th>必要</th><th>実績</th></tr></thead><tbody><%for(Map<String,Object>w:warnings){%><tr><td class="warning-text"><%=e(w.get("warning"))%></td><td><%=e(w.get("work_date"))%></td><td><%=e(w.get("detail"))%></td><td><%=e(w.get("required"))%></td><td><%=e(w.get("actual"))%></td></tr><%}%></tbody></table></div><%}%><%if(pageKey.equals("shifts/confirm")){%><form method="post" class="stack-form"><input type="hidden" name="action" value="confirmShifts"><input type="hidden" name="returnPage" value="shifts/confirm"><input type="hidden" name="month" value="<%=month%>"><%if(warnings!=null&&!warnings.isEmpty()){%><label>警告付きで確定する理由<textarea name="warningReason" required maxlength="500"></textarea></label><%}%><button class="primary" type="submit">警告を確認して確定</button></form><%}%></section><% } %>
        <% if(pageKey.equals("shifts/history") || pageKey.equals("shifts/change") || pageKey.equals("shifts/manage")){ List<Map<String,Object>> requests=(List<Map<String,Object>>)request.getAttribute("requests"); %><section class="section"><div class="section-header"><h2>変更・休み申請</h2><span class="muted"><%=requests.size()%>件</span></div><div class="table-wrap"><table><thead><tr><th>日付</th><th>申請者</th><th>変更前</th><th>変更後</th><th>理由</th><th>緊急</th><th>状態</th><%if(manager){%><th>操作</th><%}%></tr></thead><tbody><%for(Map<String,Object>r:requests){%><tr><td><%=e(r.get("work_date"))%></td><td><%=e(r.get("name"))%></td><td><%=e(r.get("current_type"))%></td><td><%=e(r.get("requested_name"))%></td><td><%=e(r.get("reason"))%></td><td><%=Boolean.TRUE.equals(r.get("urgent"))?"緊急":"-"%></td><td><span class="status <%=status(r.get("status"))%>"><%=statusLabel(r.get("status"))%></span></td><%if(manager){%><td><%if("PENDING".equals(r.get("status"))){%><form method="post"><input type="hidden" name="action" value="decideShiftChange"><input type="hidden" name="returnPage" value="<%=pageKey%>"><input type="hidden" name="id" value="<%=r.get("id")%>"><button class="primary" name="decision" value="approve">承認</button><button class="danger-button" name="decision" value="reject">却下</button></form><%}%></td><%}%></tr><%}%><%if(requests.isEmpty()){%><tr><td colspan="8" class="empty">申請はありません。</td></tr><%}%></tbody></table></div></section><%}%>
        <% if(pageKey.equals("shifts/mine") || pageKey.equals("shifts/print")){ %>
          <% { String rosterTitle="シフト"; String rosterLink=null; List<Map<String,Object>> rosterBranches=(List<Map<String,Object>>)request.getAttribute("shiftBranches"); Long rosterBranchId=selectedShiftBranch==null?user.getBranchId():selectedShiftBranch.longValue(); %>
            <%@ include file="_shiftRoster.jspf" %>
          <% } %>
        <% } else { %>
        <section class="section"><div class="section-header"><h2><%=month%> 月間シフト</h2><span class="muted"><%=rows.size()%>件</span></div>
          <div class="table-wrap"><table><thead><tr><th>日付</th><th>社員番号</th><th>氏名</th><th>勤務区分</th><th>状態</th><th>備考</th></tr></thead><tbody>
          <% for(Map<String,Object> row:rows){ %><tr><td><%=e(row.get("work_date"))%></td><td><%=e(row.get("employee_number"))%></td><td><%=e(row.get("name"))%></td><td><%=e(row.get("work_type"))%></td><td><span class="status <%=status(row.get("status"))%>"><%=shiftStatusLabel(row.get("status"))%></span></td><td><%=e(row.get("note"))%></td></tr><% } %>
          <% if(rows.isEmpty()){%><tr><td colspan="6" class="empty">対象月のシフトはありません。</td></tr><%}%></tbody></table></div>
        </section>
        <% } %>

      <% } else if (pageKey.equals("leave") || pageKey.startsWith("leave/")) {
        Map<String,Object> balance=(Map<String,Object>)request.getAttribute("balance");
        List<Map<String,Object>> leaveApprovers=(List<Map<String,Object>>)request.getAttribute("leaveApprovers");
        if(leaveApprovers==null) leaveApprovers=Collections.emptyList();
        String leaveTab=request.getParameter("tab");
        if(pageKey.equals("leave/request")) leaveTab="request";
        else if(pageKey.equals("leave/history")) leaveTab="history";
        else if(pageKey.equals("leave/balance")) leaveTab="balance";
        else if(leaveTab==null || leaveTab.isBlank()) leaveTab="balance";
        boolean leaveApprovals=pageKey.equals("leave/approvals");
      %>
        <div class="metric-grid"><div class="metric"><span class="label">有休残日数</span><strong><%=days(balance.get("days_remaining"))%><small>日</small></strong></div><div class="metric"><span class="label">時間有休残</span><strong><%=e(balance.get("hourly_remaining"))%><small>時間</small></strong></div></div>
        <% if(!leaveApprovals){ %><nav class="page-tabs leave-tabs" aria-label="有休メニュー"><a class="leave-tab-request <%="request".equals(leaveTab)?"active":""%>" href="<%=ctx%>/app/leave?tab=request">有給申請</a><a class="leave-tab-history <%="history".equals(leaveTab)?"active":""%>" href="<%=ctx%>/app/leave?tab=history">申請履歴</a><a class="leave-tab-balance <%="balance".equals(leaveTab)?"active":""%>" href="<%=ctx%>/app/leave?tab=balance">残数・履歴</a></nav><% } %>
        <% if("request".equals(leaveTab) && !leaveApprovals){ %><section class="section"><h2>有休を申請</h2><div class="approver-panel"><span class="label">承認者</span><%if(leaveApprovers.isEmpty()){%><strong>承認者が設定されていません</strong><small>有休承認権限を持つ上長または人事担当者の設定を確認してください。</small><%}else{%><div class="approver-list"><%for(Map<String,Object> approver:leaveApprovers){%><span><strong><%=e(approver.get("name"))%></strong><small><%=e(approver.get("approver_type"))%></small></span><%}%></div><%}%></div><form method="post" class="form-grid"><input type="hidden" name="action" value="requestLeave"><input type="hidden" name="returnPage" value="leave?tab=history"><label>取得日<input type="date" name="date" required></label><label>取得単位<select name="unit"><option value="FULL">1日</option><option value="AM">午前休</option><option value="PM">午後休</option><option value="HOURLY">時間単位</option></select></label><label>時間数<input type="number" name="hours" min="1" max="8" placeholder="時間単位の場合"></label><label class="span-all">理由（任意）<textarea name="reason" maxlength="1000"></textarea></label><div class="span-all"><button class="primary" type="submit">申請する</button></div></form></section><% } %>
        <% if("history".equals(leaveTab) || leaveApprovals){ %><section class="section"><div class="section-header"><h2><%=leaveApprovals?"有休承認":"申請履歴"%></h2><span class="muted"><%=rows.size()%>件</span></div><div class="table-wrap"><table><thead><tr><th>取得日</th><th>申請者</th><th>単位</th><th>時間</th><th>理由</th><th>状態</th><th>操作</th></tr></thead><tbody>
          <%for(Map<String,Object> row:rows){%><tr><td><%=e(row.get("leave_date"))%></td><td><%=e(row.get("name"))%></td><td><%=leaveUnitLabel(row.get("leave_unit"))%></td><td><%=e(row.get("hours"))%></td><td><%=e(row.get("reason"))%></td><td><span class="status <%=status(row.get("status"))%>"><%=statusLabel(row.get("status"))%></span></td><%if(leaveApprovals){%><td><%if("PENDING".equals(row.get("status")) && Boolean.TRUE.equals(row.get("can_decide_leave"))){%><div class="actions leave-decision-actions"><form method="post"><input type="hidden" name="action" value="decideLeave"><input type="hidden" name="returnPage" value="leave/approvals"><input type="hidden" name="id" value="<%=row.get("id")%>"><button class="primary" name="decision" value="approve">承認</button></form><button type="button" class="danger-button" data-leave-reject-open data-request-id="<%=row.get("id")%>" data-requester="<%=e(row.get("name"))%>" data-leave-date="<%=e(row.get("leave_date"))%>" data-reason="<%=e(row.get("reason"))%>" data-status="<%=statusLabel(row.get("status"))%>">却下</button></div><%}%></td><%}else{%><td><%if("PENDING".equals(row.get("status"))||"APPROVED".equals(row.get("status"))){%><form method="post"><input type="hidden" name="action" value="cancelLeave"><input type="hidden" name="returnPage" value="leave?tab=history"><input type="hidden" name="id" value="<%=row.get("id")%>"><button class="danger-button">取消</button></form><%}%></td><%}%></tr><%}%>
          <%if(rows.isEmpty()){%><tr><td colspan="7" class="empty">申請はありません。</td></tr><%}%></tbody></table></div></section>
          <%if(leaveApprovals){%><dialog class="leave-reject-dialog" data-leave-reject-dialog><form method="post" class="leave-reject-card" data-leave-reject-form novalidate><input type="hidden" name="action" value="decideLeave"><input type="hidden" name="returnPage" value="leave/approvals"><input type="hidden" name="id" data-leave-reject-id><input type="hidden" name="decision" value="reject"><div class="section-header"><h2>有休申請を却下</h2></div><dl class="decision-summary"><div><dt>申請者</dt><dd data-leave-reject-requester></dd></div><div><dt>取得日</dt><dd data-leave-reject-date></dd></div><div><dt>申請理由</dt><dd data-leave-reject-reason></dd></div><div><dt>現在の状態</dt><dd data-leave-reject-status></dd></div></dl><label>却下理由<textarea name="rejectionReason" required maxlength="500" rows="4" placeholder="却下理由を入力してください" data-leave-reject-reason-input></textarea></label><p class="form-error" data-leave-reject-error hidden>却下理由を入力してください</p><div class="actions dialog-actions"><button type="button" data-leave-reject-cancel>キャンセル</button><button class="danger-button" type="submit">却下して送信</button></div></form></dialog><%}%>
        <% } %>
        <%if("balance".equals(leaveTab) && !leaveApprovals){List<Map<String,Object>> ledger=(List<Map<String,Object>>)request.getAttribute("leaveLedger");%><section class="section"><div class="section-header"><h2>有休台帳</h2><span class="muted">付与・取得・取消・失効</span></div><div class="table-wrap"><table><thead><tr><th>日付</th><th>種類</th><th>日数</th><th>時間</th><th>備考</th></tr></thead><tbody><%for(Map<String,Object> event:ledger){%><tr><td><%=e(event.get("event_date"))%></td><td><%=leaveEventTypeLabel(event.get("event_type"))%></td><td><%=days(event.get("days"))%></td><td><%=e(event.get("hours"))%></td><td><%=leaveNoteLabel(event.get("note"))%></td></tr><%}%><%if(ledger.isEmpty()){%><tr><td colspan="5" class="empty">台帳履歴はありません。</td></tr><%}%></tbody></table></div></section><%}%>

      <% } else if(pageKey.startsWith("attendance/")){ %>
        <%
          Map<String,Object> clockSummary=(Map<String,Object>)request.getAttribute("clockSummary");
          if(clockSummary==null) clockSummary=Collections.emptyMap();
          int missingClockOut=0, finalizedCount=0, completeCount=0, pendingAdjustmentCount=0;
          for(Map<String,Object> row:rows){
            if(row.get("clock_in")!=null && row.get("clock_out")==null) missingClockOut++;
            if(Boolean.TRUE.equals(row.get("finalized"))) finalizedCount++;
            if(row.get("clock_in")!=null && row.get("clock_out")!=null) completeCount++;
          }
          List<Map<String,Object>> adjustmentMetrics=(List<Map<String,Object>>)request.getAttribute("adjustments");
          if(adjustmentMetrics!=null) for(Map<String,Object> a:adjustmentMetrics) if("PENDING".equals(a.get("status"))) pendingAdjustmentCount++;
        %>
        <%if(pageKey.equals("attendance/clock")||pageKey.equals("attendance/mine")||pageKey.equals("attendance/adjust")||pageKey.equals("attendance/history")){%>
          <nav class="attendance-tabs" aria-label="勤怠画面">
            <a class="<%=pageKey.equals("attendance/clock")?"active":""%>" href="<%=ctx%>/app/attendance/clock">出退勤打刻</a>
            <a class="<%=pageKey.equals("attendance/mine")?"active":""%>" href="<%=ctx%>/app/attendance/mine">自分の勤怠</a>
            <a class="<%=pageKey.equals("attendance/adjust")?"active":""%>" href="<%=ctx%>/app/attendance/adjust">打刻修正</a>
            <a class="<%=pageKey.equals("attendance/history")?"active":""%>" href="<%=ctx%>/app/attendance/history">修正履歴</a>
          </nav>
        <%}%>
        <%if(pageKey.equals("attendance/clock")){%>
          <section class="section clock-panel">
            <div class="clock-status-grid">
              <div class="clock-live">
                <p class="muted"><%=LocalDate.now()%></p>
                <div class="clock-time" data-clock>--:--:--</div>
                <p class="clock-guidance">打刻時に端末の位置情報を記録します。場所による打刻制限はありません。</p>
              </div>
              <div class="clock-summary">
                <div><span>本日の勤務</span><strong><%=e(clockSummary.getOrDefault("work_type", clockSummary.getOrDefault("work_type_code", "-")))%></strong></div>
                <div><span>出勤</span><strong><%=timestampLabel(clockSummary.get("clock_in"))%></strong></div>
                <div><span>退勤</span><strong><%=timestampLabel(clockSummary.get("clock_out"))%></strong></div>
                <%if(Boolean.TRUE.equals(clockSummary.get("has_open_clock"))){%><div class="span-all"><span>未退勤の勤怠</span><strong><%=timestampLabel(clockSummary.get("open_clock_in"))%></strong></div><%}%>
              </div>
            </div>
            <div class="clock-actions">
              <form method="post" data-clock-form>
                <input type="hidden" name="action" value="clock"><input type="hidden" name="returnPage" value="attendance/clock"><input type="hidden" name="direction" value="in"><input type="hidden" name="lat"><input type="hidden" name="lng"><input type="hidden" name="locationStatus">
                <button class="primary" type="submit" <%=Boolean.TRUE.equals(clockSummary.get("can_clock_in"))?"":"disabled"%>>出勤</button>
              </form>
              <form method="post" data-clock-form>
                <input type="hidden" name="action" value="clock"><input type="hidden" name="returnPage" value="attendance/clock"><input type="hidden" name="direction" value="out"><input type="hidden" name="lat"><input type="hidden" name="lng"><input type="hidden" name="locationStatus">
                <button type="submit" <%=Boolean.TRUE.equals(clockSummary.get("can_clock_out"))?"":"disabled"%>>退勤</button>
              </form>
            </div>
            <%if(Boolean.TRUE.equals(clockSummary.get("today_complete"))){%><p class="clock-note">本日の打刻は完了しています。時刻の変更は打刻修正から申請してください。</p><%}%>
          </section>
        <%}%>
        <%if(pageKey.equals("attendance/adjust")){%><section class="section"><h2>打刻修正を申請</h2><form method="post" class="form-grid"><input type="hidden" name="action" value="requestAttendanceAdjustment"><input type="hidden" name="returnPage" value="attendance/history"><label>対象勤怠<select name="attendanceId"><%for(Map<String,Object>r:rows){%><option value="<%=r.get("id")%>"><%=e(r.get("work_date"))%> <%=timestampLabel(r.get("clock_in"))%> - <%=timestampLabel(r.get("clock_out"))%></option><%}%></select></label><label>修正後の出勤<input type="datetime-local" name="requestedIn" required></label><label>修正後の退勤<input type="datetime-local" name="requestedOut" required></label><label class="span-all">理由<textarea name="reason" required></textarea></label><div class="span-all"><button class="primary">申請する</button></div></form></section><%}%>
        <%if(pageKey.equals("attendance/adjust")||pageKey.equals("attendance/history")||pageKey.equals("attendance/manage")){List<Map<String,Object>> adjustments=(List<Map<String,Object>>)request.getAttribute("adjustments");%><section class="section"><div class="section-header"><h2>打刻修正申請</h2><span class="muted"><%=adjustments.size()%>件</span></div><div class="table-wrap"><table><thead><tr><th>勤務日</th><th>申請者</th><th>現在の出勤</th><th>修正後の出勤</th><th>現在の退勤</th><th>修正後の退勤</th><th>理由</th><th>状態</th><%if(manager){%><th>操作</th><%}%></tr></thead><tbody><%for(Map<String,Object>a:adjustments){%><tr><td><%=e(a.get("work_date"))%></td><td><%=e(a.get("name"))%></td><td><%=timestampLabel(a.get("current_in"))%></td><td><%=timestampLabel(a.get("requested_in"))%></td><td><%=timestampLabel(a.get("current_out"))%></td><td><%=timestampLabel(a.get("requested_out"))%></td><td><%=e(a.get("reason"))%></td><td><span class="status <%=status(a.get("status"))%>"><%=e(a.get("status"))%></span></td><%if(manager){%><td><%if("PENDING".equals(a.get("status"))){%><form method="post"><input type="hidden" name="action" value="decideAttendanceAdjustment"><input type="hidden" name="returnPage" value="<%=pageKey%>"><input type="hidden" name="id" value="<%=a.get("id")%>"><button class="primary" name="decision" value="approve">承認</button><button class="danger-button" name="decision" value="reject">却下</button></form><%}%></td><%}%></tr><%}%><%if(adjustments.isEmpty()){%><tr><td colspan="9" class="empty">修正申請はありません。</td></tr><%}%></tbody></table></div></section><%}%>
        <%if(pageKey.equals("attendance/manage")){%><section class="attendance-summary" aria-label="勤怠サマリー"><div><span>対象件数</span><strong><%=rows.size()%></strong></div><div><span>打刻完了</span><strong><%=completeCount%></strong></div><div class="<%=missingClockOut>0?"attention":""%>"><span>退勤漏れ</span><strong><%=missingClockOut%></strong></div><div><span>確定済み</span><strong><%=finalizedCount%></strong></div><div class="<%=pendingAdjustmentCount>0?"attention":""%>"><span>未処理修正</span><strong><%=pendingAdjustmentCount%></strong></div></section><%}%>
        <div class="toolbar"><form method="get"><label>対象月<input type="month" name="month" value="<%=month%>"></label><button>表示</button></form><%if(pageKey.equals("attendance/manage")){List<Map<String,Object>> attendancePeople=(List<Map<String,Object>>)request.getAttribute("people");%><div class="actions"><form method="post"><input type="hidden" name="action" value="finalizeAttendanceEmployeeMonth"><input type="hidden" name="returnPage" value="attendance/manage"><input type="hidden" name="month" value="<%=month%>"><select name="userId"><%for(Map<String,Object> person:attendancePeople){%><option value="<%=person.get("id")%>"><%=e(person.get("employee_number"))%> <%=e(person.get("name"))%></option><%}%></select><button class="primary" name="finalized" value="true">従業員別確定</button><button name="finalized" value="false">従業員別解除</button></form><form method="post"><input type="hidden" name="action" value="finalizeAttendanceMonth"><input type="hidden" name="returnPage" value="attendance/manage"><input type="hidden" name="month" value="<%=month%>"><button class="primary" name="finalized" value="true">月次一括確定</button><button name="finalized" value="false">月次確定解除</button></form></div><%}%></div>
        <section class="section"><div class="section-header"><h2>勤怠実績</h2><span class="muted"><%=rows.size()%>件</span></div><div class="table-wrap"><table><thead><tr><th>日付</th><th>氏名</th><th>勤務</th><th>出勤</th><th>退勤</th><th>遅刻</th><th>早退</th><th>残業</th><th>位置情報</th><%if(pageKey.equals("attendance/manage")){%><th>確定</th><%}%></tr></thead><tbody>
          <%for(Map<String,Object> row:rows){boolean rowOpen=row.get("clock_in")!=null&&row.get("clock_out")==null; boolean rowFinalized=Boolean.TRUE.equals(row.get("finalized"));%><tr class="<%=rowOpen?"attendance-open":rowFinalized?"attendance-finalized":""%>"><td><%=e(row.get("work_date"))%></td><td><%=e(row.get("name"))%></td><td><%=workTypeLabel(row.get("work_type_code"), row.get("work_type"))%></td><td><%=timestampLabel(row.get("clock_in"))%></td><td><%=timestampLabel(row.get("clock_out"))%></td><td><%=Boolean.TRUE.equals(row.get("late"))?"遅刻":"-"%></td><td><%=Boolean.TRUE.equals(row.get("early"))?"早退":"-"%></td><td><%=e(row.get("overtime_minutes"))%>分</td><td><%=locationLabel(row.get("location_status"))%></td><%if(pageKey.equals("attendance/manage")){%><td><form method="post"><input type="hidden" name="action" value="finalizeAttendance"><input type="hidden" name="returnPage" value="attendance/manage"><input type="hidden" name="id" value="<%=row.get("id")%>"><input type="hidden" name="finalized" value="<%=!rowFinalized%>"><button><%=rowFinalized?"解除":"確定"%></button></form></td><%}%></tr><%}%>
          <%if(rows.isEmpty()){%><tr><td colspan="10" class="empty">勤怠データはありません。</td></tr><%}%></tbody></table></div></section>

      <% } else if(pageKey.equals("notifications")){ %>
        <div class="toolbar"><span></span><form method="post"><input type="hidden" name="action" value="markNotificationsRead"><input type="hidden" name="returnPage" value="notifications"><button>すべて既読にする</button></form></div><section class="section"><%for(Map<String,Object> row:rows){%><article class="notification <%=Boolean.TRUE.equals(row.get("is_read"))?"":"unread"%>"><div><h3><%=e(row.get("title"))%></h3><p><%=e(row.get("message"))%></p></div><div><small><%=e(row.get("created_at"))%></small><%if(row.get("target_url")!=null){%><p><a href="<%=ctx+e(row.get("target_url"))%>">詳細</a></p><%}%></div></article><%}%><%if(rows.isEmpty()){%><div class="empty">通知はありません。</div><%}%></section>
        <%if(user.isHr()){ List<Map<String,Object>> mailRows=(List<Map<String,Object>>)request.getAttribute("mailRows");%><section class="section"><div class="section-header"><h2>メール送信状況</h2><span class="muted">最新300件</span></div><div class="table-wrap"><table><thead><tr><th>作成日時</th><th>宛先</th><th>件名</th><th>状態</th><th>試行</th><th>送信日時</th><th>エラー</th><th>操作</th></tr></thead><tbody><%for(Map<String,Object> mail:mailRows){%><tr><td><%=e(mail.get("created_at"))%></td><td><%=e(mail.get("recipient"))%></td><td><%=e(mail.get("subject"))%></td><td><span class="status <%=status(mail.get("status"))%>"><%=e(mail.get("status"))%></span></td><td><%=e(mail.get("attempts"))%></td><td><%=e(mail.get("sent_at"))%></td><td><%=e(mail.get("last_error"))%></td><td><%if("FAILED".equals(mail.get("status"))){%><form method="post"><input type="hidden" name="action" value="retryMail"><input type="hidden" name="returnPage" value="notifications"><input type="hidden" name="id" value="<%=mail.get("id")%>"><button>再送</button></form><%}%></td></tr><%}%><%if(mailRows.isEmpty()){%><tr><td colspan="8" class="empty">送信待ちメールはありません。</td></tr><%}%></tbody></table></div></section><%}%>

      <% } else if(pageKey.equals("employees")||pageKey.equals("employees/edit")){ %>
        <%if(pageKey.equals("employees/edit")){ List<Map<String,Object>> branches=(List<Map<String,Object>>)request.getAttribute("branches"); List<Map<String,Object>> departments=(List<Map<String,Object>>)request.getAttribute("departments"); List<Map<String,Object>> employment=(List<Map<String,Object>>)request.getAttribute("employment"); Map<String,Object> selected=(Map<String,Object>)request.getAttribute("selectedEmployee"); boolean editing=selected!=null;%><section class="section"><h2><%=editing?"従業員を編集":"従業員を登録"%></h2><form method="post" class="form-grid"><input type="hidden" name="action" value="<%=editing?"updateEmployee":"addEmployee"%>"><input type="hidden" name="returnPage" value="employees"><%if(editing){%><input type="hidden" name="id" value="<%=selected.get("id")%>"><%}%><label>社員番号<input name="employeeNumber" required value="<%=editing?e(selected.get("employee_number")):""%>"></label><label>氏名<input name="name" required value="<%=editing?e(selected.get("name")):""%>"></label><label>メールアドレス<input name="email" type="email" required value="<%=editing?e(selected.get("email")):""%>"></label><label>入社日<input name="hireDate" type="date" required value="<%=editing?e(selected.get("hire_date")):""%>"></label><label>営業所<select name="branchId"><%for(Map<String,Object> r:branches){%><option value="<%=r.get("id")%>" <%=editing&&String.valueOf(r.get("id")).equals(String.valueOf(selected.get("branch_id")))?"selected":""%>><%=e(r.get("name"))%></option><%}%></select></label><label>部署<select name="departmentId"><%for(Map<String,Object> r:departments){%><option value="<%=r.get("id")%>" <%=editing&&String.valueOf(r.get("id")).equals(String.valueOf(selected.get("department_id")))?"selected":""%>><%=e(r.get("name"))%></option><%}%></select></label><label>雇用形態<select name="employmentId"><%for(Map<String,Object> r:employment){%><option value="<%=r.get("id")%>" <%=editing&&String.valueOf(r.get("id")).equals(String.valueOf(selected.get("employment_type_id")))?"selected":""%>><%=e(r.get("name"))%></option><%}%></select></label><label>役割<select name="role"><option value="EMPLOYEE" <%=editing&&"EMPLOYEE".equals(selected.get("role"))?"selected":""%>>従業員</option><option value="MANAGER" <%=editing&&"MANAGER".equals(selected.get("role"))?"selected":""%>>店長</option><option value="HR" <%=editing&&"HR".equals(selected.get("role"))?"selected":""%>>人事担当者</option></select></label><%if(editing){%><label>状態<select name="active"><option value="true" <%=Boolean.TRUE.equals(selected.get("active"))?"selected":""%>>有効</option><option value="false" <%=Boolean.FALSE.equals(selected.get("active"))?"selected":""%>>無効</option></select></label><%}%><div class="span-all"><button class="primary"><%=editing?"更新する":"登録して招待"%></button></div></form></section><%}%>
        <div class="toolbar"><span></span><a class="button primary" href="<%=ctx%>/app/employees/edit">従業員を登録</a></div><section class="section"><div class="table-wrap"><table><thead><tr><th>社員番号</th><th>氏名</th><th>メール</th><th>営業所</th><th>部署</th><th>雇用形態</th><th>役割</th><th>状態</th><th>操作</th></tr></thead><tbody><%for(Map<String,Object> row:rows){%><tr><td><%=e(row.get("employee_number"))%></td><td><%=e(row.get("name"))%></td><td><%=e(row.get("email"))%></td><td><%=e(row.get("branch"))%></td><td><%=e(row.get("department"))%></td><td><%=e(row.get("employment"))%></td><td><%=e(row.get("role"))%></td><td><%=Boolean.TRUE.equals(row.get("active"))?"有効":"無効"%></td><td><div class="actions"><a class="button" href="<%=ctx%>/app/employees/edit?id=<%=row.get("id")%>">編集</a><%if(Boolean.TRUE.equals(row.get("active"))){%><form method="post"><input type="hidden" name="action" value="reissueInvite"><input type="hidden" name="returnPage" value="employees"><input type="hidden" name="id" value="<%=row.get("id")%>"><button type="submit">招待再発行</button></form><%}%></div></td></tr><%}%></tbody></table></div></section>

      <% } else if(pageKey.equals("qualifications")){ %>
        <%List<Map<String,Object>> qualificationTypes=(List<Map<String,Object>>)request.getAttribute("qualificationTypes"); if(user.isHr()){%><section class="section"><h2>資格を登録</h2><form method="post" class="inline-form"><input type="hidden" name="action" value="addQualification"><input type="hidden" name="returnPage" value="qualifications"><label>従業員<select name="userId"><%for(Map<String,Object> p:people){if(Boolean.TRUE.equals(p.get("active"))){%><option value="<%=p.get("id")%>"><%=e(p.get("name"))%></option><%}}%></select></label><label>資格名<select name="name"><%for(Map<String,Object> q:qualificationTypes){if(Boolean.TRUE.equals(q.get("active"))){%><option value="<%=e(q.get("name"))%>"><%=e(q.get("name"))%></option><%}}%></select></label><label>有効期限<input type="date" name="expiresOn"></label><button class="primary">登録</button></form></section><%}%><section class="section"><div class="section-header"><h2>資格履歴</h2><span class="muted"><%=rows.size()%>件</span></div><div class="table-wrap"><table><thead><tr><th>社員番号</th><th>氏名</th><th>資格名</th><th>有効期限</th><th>状態</th><%if(user.isHr()){%><th>操作</th><%}%></tr></thead><tbody><%for(Map<String,Object> row:rows){%><tr><%if(user.isHr()){%><form method="post"><%}%><td><%=e(row.get("employee_number"))%><%if(user.isHr()){%><input type="hidden" name="action" value="updateQualification"><input type="hidden" name="returnPage" value="qualifications"><input type="hidden" name="id" value="<%=row.get("id")%>"><%}%></td><td><%=e(row.get("employee_name"))%></td><td><%if(user.isHr()){%><select name="name"><%for(Map<String,Object> q:qualificationTypes){if(Boolean.TRUE.equals(q.get("active"))||String.valueOf(q.get("name")).equals(String.valueOf(row.get("qualification_name")))){%><option value="<%=e(q.get("name"))%>" <%=String.valueOf(q.get("name")).equals(String.valueOf(row.get("qualification_name")))?"selected":""%>><%=e(q.get("name"))%></option><%}}%></select><%}else{%><%=e(row.get("qualification_name"))%><%}%></td><td><%if(user.isHr()){%><input type="date" name="expiresOn" value="<%=e(row.get("expires_on"))%>"><%}else{%><%=e(row.get("expires_on"))%><%}%></td><td><%if(user.isHr()){%><select name="active"><option value="true" <%=Boolean.TRUE.equals(row.get("active"))?"selected":""%>>有効</option><option value="false" <%=Boolean.FALSE.equals(row.get("active"))?"selected":""%>>無効</option></select><%}else{%><%=Boolean.TRUE.equals(row.get("active"))?"有効":"無効"%><%}%></td><%if(user.isHr()){%><td><button>更新</button></td></form><%}%></tr><%}%><%if(rows.isEmpty()){%><tr><td colspan="6" class="empty">資格履歴はありません。</td></tr><%}%></tbody></table></div></section>

      <% } else if(pageKey.equals("delegations")){ %>
        <section class="section"><h2>代理店長を設定</h2><form method="post" class="inline-form"><input type="hidden" name="action" value="addDelegation"><input type="hidden" name="returnPage" value="delegations"><%if(user.isHr()){%><label>店長<select name="managerId"><%for(Map<String,Object> p:people){if("MANAGER".equals(p.get("role"))&&Boolean.TRUE.equals(p.get("active"))){%><option value="<%=p.get("id")%>"><%=e(p.get("name"))%></option><%}}%></select></label><%}else{%><input type="hidden" name="managerId" value="<%=user.getId()%>"><%}%><label>代理者<select name="delegateId"><%for(Map<String,Object> p:people){ if(((Number)p.get("id")).longValue()!=user.getId()){%><option value="<%=p.get("id")%>"><%=e(p.get("name"))%></option><%}}%></select></label><label>開始日<input type="date" name="startsOn" required></label><label>終了日<input type="date" name="endsOn" required></label><button class="primary">設定</button></form></section><section class="section"><div class="table-wrap"><table><thead><tr><th>店長</th><th>代理者</th><th>開始日</th><th>終了日</th><th>状態</th><th>操作</th></tr></thead><tbody><%for(Map<String,Object> row:rows){%><tr><form method="post"><td><%=e(row.get("manager_name"))%><input type="hidden" name="action" value="updateDelegation"><input type="hidden" name="returnPage" value="delegations"><input type="hidden" name="id" value="<%=row.get("id")%>"></td><td><%=e(row.get("delegate_name"))%></td><td><input type="date" name="startsOn" required value="<%=e(row.get("starts_on"))%>"></td><td><input type="date" name="endsOn" required value="<%=e(row.get("ends_on"))%>"></td><td><select name="active"><option value="true" <%=Boolean.TRUE.equals(row.get("active"))?"selected":""%>>有効</option><option value="false" <%=Boolean.FALSE.equals(row.get("active"))?"selected":""%>>無効</option></select></td><td><button>更新</button></td></form></tr><%}%></tbody></table></div></section>

      <% } else if(pageKey.startsWith("masters/")){ String masterType=(String)request.getAttribute("masterType"); %>
        <div class="toolbar"><div class="actions"><a class="button" href="<%=ctx%>/app/masters/branches">営業所</a><a class="button" href="<%=ctx%>/app/masters/departments">部署</a><a class="button" href="<%=ctx%>/app/masters/work-types">勤務区分・休憩</a><a class="button" href="<%=ctx%>/app/masters/staffing">必要人数</a><a class="button" href="<%=ctx%>/app/masters/catalogs">雇用形態</a></div></div>
        <%if(!"work_types".equals(masterType)){%><section class="section"><h2>項目を追加</h2><form method="post" class="inline-form"><input type="hidden" name="action" value="addMaster"><input type="hidden" name="returnPage" value="<%=pageKey%>"><input type="hidden" name="type" value="<%=masterType%>"><label>名称<input name="name" required></label><button class="primary">追加</button></form></section><%}%>
        <section class="section"><div class="table-wrap"><table><thead><tr><%if("work_types".equals(masterType)){%><th>コード</th><th>日本語名</th><th>英語名</th><th>開始</th><th>終了</th><th>休憩</th><th>必要人数</th><th>状態</th><th>操作</th><%}else{%><th>ID</th><th>名称</th><th>状態</th><th>操作</th><%}%></tr></thead><tbody><%for(Map<String,Object> row:rows){%><tr><form method="post"><%if("work_types".equals(masterType)){%><td><%=e(row.get("code"))%><input type="hidden" name="code" value="<%=e(row.get("code"))%>"><input type="hidden" name="action" value="updateWorkType"><input type="hidden" name="returnPage" value="<%=pageKey%>"></td><td><input name="nameJa" value="<%=e(row.get("name_ja"))%>" required></td><td><input name="nameEn" value="<%=e(row.get("name_en"))%>" required></td><td><input type="time" name="start" value="<%=e(row.get("start_time"))%>"></td><td><input type="time" name="end" value="<%=e(row.get("end_time"))%>"></td><td><input type="number" name="breakMinutes" min="0" value="<%=e(row.get("break_minutes"))%>"></td><td><input type="number" name="requiredStaff" min="0" value="<%=e(row.get("required_staff"))%>"></td><td><select name="active"><option value="true" <%=Boolean.TRUE.equals(row.get("active"))?"selected":""%>>有効</option><option value="false" <%=Boolean.FALSE.equals(row.get("active"))?"selected":""%>>無効</option></select></td><td><button>更新</button></td><%}else{%><td><%=e(row.get("id"))%><input type="hidden" name="action" value="updateMaster"><input type="hidden" name="returnPage" value="<%=pageKey%>"><input type="hidden" name="type" value="<%=masterType%>"><input type="hidden" name="id" value="<%=row.get("id")%>"></td><td><input name="name" value="<%=e(row.get("name"))%>" required></td><td><select name="active"><option value="true" <%=Boolean.TRUE.equals(row.get("active"))?"selected":""%>>有効</option><option value="false" <%=Boolean.FALSE.equals(row.get("active"))?"selected":""%>>無効</option></select></td><td><button>更新</button></td><%}%></form></tr><%}%></tbody></table></div></section>
        <%if(pageKey.endsWith("catalogs")){ List<Map<String,Object>> qualificationTypes=(List<Map<String,Object>>)request.getAttribute("qualificationTypes"); List<Map<String,Object>> appSettings=(List<Map<String,Object>>)request.getAttribute("appSettings");%>
        <section class="section"><div class="section-header"><h2>資格名称</h2></div><form method="post" class="inline-form"><input type="hidden" name="action" value="addMaster"><input type="hidden" name="returnPage" value="masters/catalogs"><input type="hidden" name="type" value="qualifications"><label>名称<input name="name" required></label><button class="primary">追加</button></form><div class="table-wrap"><table><thead><tr><th>ID</th><th>名称</th><th>状態</th><th>操作</th></tr></thead><tbody><%for(Map<String,Object> row:qualificationTypes){%><tr><form method="post"><td><%=e(row.get("id"))%><input type="hidden" name="action" value="updateMaster"><input type="hidden" name="returnPage" value="masters/catalogs"><input type="hidden" name="type" value="qualifications"><input type="hidden" name="id" value="<%=row.get("id")%>"></td><td><input name="name" value="<%=e(row.get("name"))%>" required></td><td><select name="active"><option value="true" <%=Boolean.TRUE.equals(row.get("active"))?"selected":""%>>有効</option><option value="false" <%=Boolean.FALSE.equals(row.get("active"))?"selected":""%>>無効</option></select></td><td><button>更新</button></td></form></tr><%}%></tbody></table></div></section>
        <section class="section"><div class="section-header"><h2>法令・運用設定</h2></div><div class="table-wrap"><table><thead><tr><th>設定</th><th>値</th><th>説明</th><th>操作</th></tr></thead><tbody><%for(Map<String,Object> setting:appSettings){String key=String.valueOf(setting.get("setting_key")); boolean boolSetting=key.equals("ALLOW_CONFIRM_WITH_WARNINGS")||key.equals("LEAVE_ALLOW_PAST")||key.equals("LOCATION_REQUIRED");%><tr><form method="post"><td><code><%=e(key)%></code><input type="hidden" name="action" value="updateSetting"><input type="hidden" name="returnPage" value="masters/catalogs"><input type="hidden" name="key" value="<%=e(key)%>"></td><td><%if(boolSetting){%><select name="value"><option value="true" <%=Boolean.parseBoolean(String.valueOf(setting.get("setting_value")))?"selected":""%>>有効</option><option value="false" <%=!Boolean.parseBoolean(String.valueOf(setting.get("setting_value")))?"selected":""%>>無効</option></select><%}else{%><input type="number" name="value" min="<%=key.equals("LEAVE_MIN_NOTICE_DAYS")?"0":"1"%>" value="<%=e(setting.get("setting_value"))%>" required><%}%></td><td><%=e(setting.get("description"))%></td><td><button>更新</button></td></form></tr><%}%></tbody></table></div></section><%}%>

        <%if(pageKey.endsWith("catalogs")){List<Map<String,Object>> leaveRules=(List<Map<String,Object>>)request.getAttribute("leaveRules");%><section class="section"><div class="section-header"><h2>有休付与ルール</h2></div><form method="post" class="form-grid"><input type="hidden" name="action" value="addLeaveRule"><input type="hidden" name="returnPage" value="masters/catalogs"><label>施行日<input type="date" name="effectiveFrom" required></label><label>出勤率基準<input type="number" name="attendanceThreshold" min="0" max="1" step="0.001" value="0.800" required></label><label>時間単位上限（日）<input type="number" name="hourlyLimitDays" min="0" max="5" value="5" required></label><label>1日の時間数<input type="number" name="hoursPerDay" min="1" max="24" value="8" required></label><label>有効期間（月）<input type="number" name="expiryMonths" min="1" max="120" value="24" required></label><label>取得義務日数<input type="number" name="mandatoryDays" min="0" max="20" value="5" required></label><div class="span-all"><button class="primary">ルールを追加</button></div></form><div class="table-wrap"><table><thead><tr><th>施行日</th><th>出勤率</th><th>時間上限日数</th><th>時間/日</th><th>有効月数</th><th>義務日数</th><th>状態</th><th>操作</th></tr></thead><tbody><%for(Map<String,Object> rule:leaveRules){%><tr><form method="post"><td><input type="date" name="effectiveFrom" value="<%=e(rule.get("effective_from"))%>" required><input type="hidden" name="action" value="updateLeaveRule"><input type="hidden" name="returnPage" value="masters/catalogs"><input type="hidden" name="id" value="<%=rule.get("id")%>"></td><td><input type="number" name="attendanceThreshold" min="0" max="1" step="0.001" value="<%=e(rule.get("attendance_threshold"))%>" required></td><td><input type="number" name="hourlyLimitDays" min="0" max="5" value="<%=e(rule.get("hourly_limit_days"))%>" required></td><td><input type="number" name="hoursPerDay" min="1" max="24" value="<%=e(rule.get("hours_per_day"))%>" required></td><td><input type="number" name="expiryMonths" min="1" max="120" value="<%=e(rule.get("expiry_months"))%>" required></td><td><input type="number" name="mandatoryDays" min="0" max="20" value="<%=e(rule.get("mandatory_days"))%>" required></td><td><select name="active"><option value="true" <%=Boolean.TRUE.equals(rule.get("active"))?"selected":""%>>有効</option><option value="false" <%=Boolean.FALSE.equals(rule.get("active"))?"selected":""%>>無効</option></select></td><td><button>更新</button></td></form></tr><%}%></tbody></table></div></section><%}%>

      <% } else if(pageKey.equals("exports")){ %>
        <%List<Map<String,Object>> exportBranches=(List<Map<String,Object>>)request.getAttribute("branches"); List<Map<String,Object>> exportDepartments=(List<Map<String,Object>>)request.getAttribute("departments"); List<Map<String,Object>> exportPeople=(List<Map<String,Object>>)request.getAttribute("people");%><section class="section"><h2>データ出力</h2><form action="<%=ctx%>/export" method="get" class="form-grid"><label>対象データ<select name="type"><option value="shifts">シフト表</option><option value="attendance">勤怠実績</option><option value="leave">有休取得状況</option></select></label><label>開始日<input type="date" name="from" value="<%=month.atDay(1)%>" required></label><label>終了日<input type="date" name="to" value="<%=month.atEndOfMonth()%>" required></label><label>営業所<select name="branchId"><option value="">すべて</option><%for(Map<String,Object> branch:exportBranches){%><option value="<%=branch.get("id")%>"><%=e(branch.get("name"))%></option><%}%></select></label><label>部署<select name="departmentId"><option value="">すべて</option><%for(Map<String,Object> department:exportDepartments){%><option value="<%=department.get("id")%>"><%=e(department.get("name"))%></option><%}%></select></label><label>従業員<select name="userId"><option value="">すべて</option><%for(Map<String,Object> person:exportPeople){%><option value="<%=person.get("id")%>"><%=e(person.get("employee_number"))%> <%=e(person.get("name"))%></option><%}%></select></label><label>形式<select name="format"><option value="csv">CSV</option><option value="xls">Excel</option></select></label><div class="span-all"><button class="primary">出力する</button></div></form></section>

      <% } else if(pageKey.equals("audit")){ %>
        <%List<Map<String,Object>> auditPeople=(List<Map<String,Object>>)request.getAttribute("people"); List<Map<String,Object>> auditActions=(List<Map<String,Object>>)request.getAttribute("auditActions"); String selectedActor=request.getParameter("actorId"); String selectedTarget=request.getParameter("targetUserId"); String selectedOperation=request.getParameter("operation");%><section class="section no-print"><h2>検索条件</h2><form method="get" class="form-grid"><label>開始日<input type="date" name="from" value="<%=e(request.getParameter("from"))%>"></label><label>終了日<input type="date" name="to" value="<%=e(request.getParameter("to"))%>"></label><label>実行者<select name="actorId"><option value="">すべて</option><%for(Map<String,Object> person:auditPeople){%><option value="<%=person.get("id")%>" <%=String.valueOf(person.get("id")).equals(selectedActor)?"selected":""%>><%=e(person.get("employee_number"))%> <%=e(person.get("name"))%></option><%}%></select></label><label>操作種別<select name="operation"><option value="">すべて</option><%for(Map<String,Object> operation:auditActions){%><option value="<%=e(operation.get("action"))%>" <%=String.valueOf(operation.get("action")).equals(selectedOperation)?"selected":""%>><%=e(operation.get("action"))%></option><%}%></select></label><label>対象者<select name="targetUserId"><option value="">すべて</option><%for(Map<String,Object> person:auditPeople){%><option value="<%=person.get("id")%>" <%=String.valueOf(person.get("id")).equals(selectedTarget)?"selected":""%>><%=e(person.get("employee_number"))%> <%=e(person.get("name"))%></option><%}%></select></label><div class="span-all"><button class="primary">検索</button><a class="button" href="<%=ctx%>/app/audit">条件をクリア</a></div></form></section><section class="section"><div class="section-header"><h2>操作履歴</h2><span class="muted"><%=rows.size()%>件（最大300件）</span></div><div class="table-wrap"><table><thead><tr><th>日時</th><th>実行者</th><th>操作</th><th>対象者</th><th>対象</th><th>対象ID</th><th>変更前</th><th>変更後</th></tr></thead><tbody><%for(Map<String,Object> row:rows){%><tr><td><%=e(row.get("created_at"))%></td><td><%=e(row.get("actor_name"))%></td><td><%=e(row.get("action"))%></td><td><%=e(row.get("target_employee_number"))%> <%=e(row.get("target_user_name"))%></td><td><%=e(row.get("target_type"))%></td><td><%=e(row.get("target_id"))%></td><td><%=e(row.get("before_value"))%></td><td><%=e(row.get("after_value"))%></td></tr><%}%><%if(rows.isEmpty()){%><tr><td colspan="8" class="empty">条件に一致する操作履歴はありません。</td></tr><%}%></tbody></table></div></section>

      <% } else if(pageKey.equals("account")){ %>
        <section class="section"><h2>表示と言語</h2><form action="<%=ctx%>/account" method="post" class="form-grid"><label>表示言語<select name="locale"><option value="ja" <%=en?"":"selected"%>>日本語</option><option value="en" <%=en?"selected":""%>>English</option></select></label><label class="span-2">新しいパスワード<input type="password" name="password" minlength="10" placeholder="変更する場合のみ入力"></label><div class="span-all"><button class="primary">設定を保存</button></div></form><p class="note">社員番号: <%=e(user.getEmployeeNumber())%> / メール: <%=e(user.getEmail())%> / 所属: <%=e(user.getBranchName())%> <%=e(user.getDepartmentName())%></p></section>
      <% } %>
    </main>
    <footer class="app-footer"><a href="<%=ctx%>/privacy"><%=en?"Privacy and location data":"個人情報・位置情報の取扱い"%></a></footer>
  </div>
</div>
<script src="<%=ctx%>/assets/app.js?v=20260620-6"></script>
</body>
</html>
