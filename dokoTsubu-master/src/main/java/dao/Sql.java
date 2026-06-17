package dao;

import config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Sql {
  private Sql() { }

  public static List<Map<String, Object>> query(String sql, Object... params) {
    try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, params);
      try (ResultSet rs = p.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i).toLowerCase(), rs.getObject(i));
          }
          rows.add(row);
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Query failed: " + sql, e);
    }
  }

  public static Map<String, Object> one(String sql, Object... params) {
    List<Map<String, Object>> rows = query(sql, params);
    return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
  }

  public static int update(String sql, Object... params) {
    try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, params);
      return p.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Update failed: " + sql, e);
    }
  }

  public static long insert(String sql, Object... params) {
    try (Connection c = Database.getConnection();
         PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
      bind(p, params);
      p.executeUpdate();
      try (ResultSet rs = p.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : 0; }
    } catch (SQLException e) {
      throw new IllegalStateException("Insert failed: " + sql, e);
    }
  }

  private static void bind(PreparedStatement p, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) p.setObject(i + 1, params[i]);
  }
}
