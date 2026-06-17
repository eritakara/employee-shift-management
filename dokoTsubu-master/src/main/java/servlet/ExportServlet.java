package servlet;

import dao.Sql;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import model.User;

@WebServlet("/export")
public class ExportServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    User user = (User) req.getSession().getAttribute("loginUser");
    if (!user.isHr()) { res.sendError(403); return; }
    String type = req.getParameter("type");
    String format = "xls".equals(req.getParameter("format")) ? "xls" : "csv";
    YearMonth month;
    try { month = YearMonth.parse(req.getParameter("month")); } catch (Exception e) { month = YearMonth.now(); }
    List<Map<String, Object>> rows;
    if ("attendance".equals(type)) {
      rows = Sql.query("SELECT a.work_date,u.employee_number,u.name,a.clock_in,a.clock_out,a.status,a.finalized FROM attendance a JOIN users u ON u.id=a.user_id WHERE a.work_date BETWEEN ? AND ? ORDER BY a.work_date,u.employee_number", month.atDay(1), month.atEndOfMonth());
    } else if ("leave".equals(type)) {
      rows = Sql.query("SELECT l.leave_date,u.employee_number,u.name,l.leave_unit,l.hours,l.status,l.created_at FROM leave_requests l JOIN users u ON u.id=l.user_id WHERE l.leave_date BETWEEN ? AND ? ORDER BY l.leave_date,u.employee_number", month.atDay(1), month.atEndOfMonth());
    } else {
      rows = Sql.query("SELECT s.work_date,u.employee_number,u.name,wt.name_ja work_type,s.status FROM shifts s JOIN users u ON u.id=s.user_id JOIN work_types wt ON wt.code=s.work_type_code WHERE s.work_date BETWEEN ? AND ? ORDER BY s.work_date,u.employee_number", month.atDay(1), month.atEndOfMonth());
    }
    res.setCharacterEncoding("UTF-8");
    res.setContentType("xls".equals(format) ? "application/vnd.ms-excel" : "text/csv");
    res.setHeader("Content-Disposition", "attachment; filename=\"" + (type == null ? "shifts" : type) + "-" + month + "." + format + "\"");
    try (PrintWriter out = res.getWriter()) {
      if ("xls".equals(format)) writeExcel(out, rows); else writeCsv(out, rows);
    }
  }

  private void writeCsv(PrintWriter out, List<Map<String, Object>> rows) {
    out.write('\ufeff');
    if (rows.isEmpty()) return;
    out.println(String.join(",", rows.get(0).keySet()));
    for (Map<String, Object> row : rows) {
      out.println(row.values().stream().map(this::csv).collect(java.util.stream.Collectors.joining(",")));
    }
  }

  private void writeExcel(PrintWriter out, List<Map<String, Object>> rows) {
    out.println("<html><head><meta charset=\"UTF-8\"></head><body><table border=\"1\">");
    if (!rows.isEmpty()) {
      out.print("<tr>"); for (String key : rows.get(0).keySet()) out.print("<th>" + html(key) + "</th>"); out.println("</tr>");
      for (Map<String, Object> row : rows) {
        out.print("<tr>"); for (Object value : row.values()) out.print("<td>" + html(String.valueOf(value == null ? "" : value)) + "</td>"); out.println("</tr>");
      }
    }
    out.println("</table></body></html>");
  }

  private String csv(Object value) { return "\"" + String.valueOf(value == null ? "" : value).replace("\"", "\"\"") + "\""; }
  private String html(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
}
