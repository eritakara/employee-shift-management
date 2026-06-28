package service;

import dao.Sql;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    StringWriter out = new StringWriter();
    try { writeCsv(rows, out); }
    catch (IOException impossible) { throw new IllegalStateException(impossible); }
    return out.toString();
  }

  public String excelHtml(List<Map<String, Object>> rows) {
    StringWriter out = new StringWriter();
    try { writeExcelHtml(rows, out); }
    catch (IOException impossible) { throw new IllegalStateException(impossible); }
    return out.toString();
  }

  public void writeCsv(List<Map<String, Object>> rows, Writer out) throws IOException {
    out.write("\ufeff");
    if (rows.isEmpty()) return;
    out.write(String.join(",", rows.get(0).keySet()));
    out.write('\n');
    for (Map<String, Object> row : rows) {
      boolean first = true;
      for (Object value : row.values()) {
        if (!first) out.write(',');
        out.write(csvCell(value));
        first = false;
      }
      out.write('\n');
    }
  }

  public void writeExcelHtml(List<Map<String, Object>> rows, Writer out) throws IOException {
    out.write("<html><head><meta charset=\"UTF-8\"></head><body><table border=\"1\">");
    if (!rows.isEmpty()) {
      StringBuilder line = new StringBuilder("<tr>");
      for (String key : rows.get(0).keySet()) line.append("<th>").append(util.HtmlEscaper.escape(key)).append("</th>");
      out.write(line.append("</tr>").toString());
      for (Map<String, Object> row : rows) {
        line.setLength(0);
        line.append("<tr>");
        for (Object value : row.values()) line.append("<td>").append(util.HtmlEscaper.escape(value)).append("</td>");
        out.write(line.append("</tr>").toString());
      }
    }
    out.write("</table></body></html>");
  }

  private String csvCell(Object value) {
    String text = value == null ? "" : String.valueOf(value);
    String stripped = text.stripLeading();
    if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  public void export(User user, String type, LocalDate from, LocalDate to,
      Long branchId, Long departmentId, Long userId, String format, java.io.Writer out) throws IOException {
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

    try (Connection c = config.Database.getConnection();
         PreparedStatement p = c.prepareStatement(query.toString())) {
      for (int i = 0; i < args.size(); i++) {
        p.setObject(i + 1, args.get(i));
      }
      try (ResultSet rs = p.executeQuery()) {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        
        List<String> headers = new ArrayList<>();
        for (int i = 1; i <= cols; i++) {
          headers.add(meta.getColumnLabel(i));
        }

        if ("xls".equals(format)) {
          out.write("<html><head><meta charset=\"UTF-8\"></head><body><table border=\"1\">");
          StringBuilder headerLine = new StringBuilder("<tr>");
          for (String h : headers) {
            headerLine.append("<th>").append(util.HtmlEscaper.escape(h)).append("</th>");
          }
          out.write(headerLine.append("</tr>").toString());

          while (rs.next()) {
            StringBuilder line = new StringBuilder("<tr>");
            for (int i = 1; i <= cols; i++) {
              Object val = rs.getObject(i);
              line.append("<td>").append(util.HtmlEscaper.escape(val)).append("</td>");
            }
            out.write(line.append("</tr>").toString());
          }
          out.write("</table></body></html>");
        } else {
          out.write("\ufeff");
          out.write(String.join(",", headers));
          out.write('\n');

          while (rs.next()) {
            boolean first = true;
            for (int i = 1; i <= cols; i++) {
              if (!first) out.write(',');
              out.write(csvCell(rs.getObject(i)));
              first = false;
            }
            out.write('\n');
          }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Database error during export", e);
    }
  }

}
