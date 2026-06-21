package service;

import dao.Sql;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import model.User;

public class ExportService {
  public List<Map<String, Object>> rows(User user, String type, LocalDate from, LocalDate to,
      Long branchId, Long departmentId, Long userId) {
    if (user == null || !user.isHr()) throw new SecurityException("人事担当者のみ出力できます。");
    if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("出力期間が不正です。");
    String sql = switch (type) {
      case "attendance" -> "SELECT a.work_date,u.employee_number,u.name,b.name branch,d.name department,a.clock_in,a.clock_out,a.status,a.finalized FROM attendance a JOIN users u ON u.id=a.user_id JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id WHERE a.work_date BETWEEN ? AND ?";
      case "leave" -> "SELECT l.leave_date,u.employee_number,u.name,b.name branch,d.name department,l.leave_unit,l.hours,l.status,l.created_at FROM leave_requests l JOIN users u ON u.id=l.user_id JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id WHERE l.leave_date BETWEEN ? AND ?";
      case "shifts" -> "SELECT s.work_date,u.employee_number,u.name,b.name branch,d.name department,wt.name_ja work_type,s.status FROM shifts s JOIN users u ON u.id=s.user_id JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id JOIN work_types wt ON wt.code=s.work_type_code WHERE s.work_date BETWEEN ? AND ?";
      default -> throw new IllegalArgumentException("出力対象が不正です。");
    };
    List<Object> args = new ArrayList<>();
    args.add(from); args.add(to);
    StringBuilder query = new StringBuilder(sql);
    if (branchId != null) { query.append(" AND u.branch_id=?"); args.add(branchId); }
    if (departmentId != null) { query.append(" AND u.department_id=?"); args.add(departmentId); }
    if (userId != null) { query.append(" AND u.id=?"); args.add(userId); }
    query.append(type.equals("leave") ? " ORDER BY l.leave_date,u.employee_number" : type.equals("attendance")
        ? " ORDER BY a.work_date,u.employee_number" : " ORDER BY s.work_date,u.employee_number");
    return Sql.query(query.toString(), args.toArray());
  }

  public String csv(List<Map<String, Object>> rows) {
    StringBuilder out = new StringBuilder("\ufeff");
    if (rows.isEmpty()) return out.toString();
    out.append(String.join(",", rows.get(0).keySet())).append('\n');
    for (Map<String, Object> row : rows) {
      out.append(row.values().stream().map(this::csvCell).collect(Collectors.joining(","))).append('\n');
    }
    return out.toString();
  }

  public String excelHtml(List<Map<String, Object>> rows) {
    StringBuilder out = new StringBuilder("<html><head><meta charset=\"UTF-8\"></head><body><table border=\"1\">");
    if (!rows.isEmpty()) {
      out.append("<tr>");
      for (String key : rows.get(0).keySet()) out.append("<th>").append(util.HtmlEscaper.escape(key)).append("</th>");
      out.append("</tr>");
      for (Map<String, Object> row : rows) {
        out.append("<tr>");
        for (Object value : row.values()) out.append("<td>").append(util.HtmlEscaper.escape(value)).append("</td>");
        out.append("</tr>");
      }
    }
    return out.append("</table></body></html>").toString();
  }

  private String csvCell(Object value) {
    String text = value == null ? "" : String.valueOf(value);
    String stripped = text.stripLeading();
    if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }


}
