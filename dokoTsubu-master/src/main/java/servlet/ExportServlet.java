package servlet;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import model.User;

@WebServlet("/export")
public class ExportServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private final service.ExportService exports = new service.ExportService();

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    User user = (User) req.getSession().getAttribute("loginUser");
    if (user == null || !user.isHr()) { res.sendError(403); return; }
    String type = req.getParameter("type");
    String format = "xls".equals(req.getParameter("format")) ? "xls" : "csv";
    YearMonth month = YearMonth.now(java.time.ZoneId.of("Asia/Tokyo"));
    LocalDate from = date(req.getParameter("from"), month.atDay(1));
    LocalDate to = date(req.getParameter("to"), month.atEndOfMonth());

    res.setCharacterEncoding("UTF-8");
    res.setContentType("xls".equals(format) ? "application/vnd.ms-excel;charset=UTF-8" : "text/csv;charset=UTF-8");
    res.setHeader("Content-Disposition", "attachment; filename=\"" + (type == null ? "shifts" : type) + "-" + from + "-" + to + "." + format + "\"");
    res.setHeader("Cache-Control", "no-store");
    res.setBufferSize(32 * 1024);

    try {
      exports.export(user, type, from, to, number(req.getParameter("branchId")), number(req.getParameter("departmentId")), number(req.getParameter("userId")), format, res.getWriter());
    } catch (IllegalArgumentException e) {
      res.sendError(400, e.getMessage());
    }
  }
  private LocalDate date(String value, LocalDate fallback) { try { return LocalDate.parse(value); } catch (Exception e) { return fallback; } }
  private Long number(String value) { try { return value == null || value.isBlank() ? null : Long.valueOf(value); } catch (Exception e) { return null; } }
}
