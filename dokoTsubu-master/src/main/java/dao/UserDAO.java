package dao;

import config.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import model.User;
import util.PasswordUtil;

public class UserDAO {
  public User authenticate(String email, String password) {
    String sql = "SELECT u.*,b.name branch_name,d.name department_name FROM users u "
        + "JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id "
        + "WHERE LOWER(u.email)=LOWER(?) AND u.active=TRUE";
    try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, email == null ? "" : email.trim());
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next() && PasswordUtil.verify(password, rs.getString("password_hash"))) {
          return map(rs);
        }
      }
    } catch (SQLException e) {
      System.err.println("SQL Error during authentication: " + e.getMessage());
      e.printStackTrace(System.err);
      throw new IllegalStateException("Login failed", e);
    }
    return null;
  }

  public User findById(long id) {
    return findById(id, false);
  }

  public User findActiveById(long id) {
    return findById(id, true);
  }

  private User findById(long id, boolean activeOnly) {
    String sql = "SELECT u.*,b.name branch_name,d.name department_name FROM users u "
        + "JOIN branches b ON b.id=u.branch_id JOIN departments d ON d.id=u.department_id WHERE u.id=?"
        + (activeOnly ? " AND u.active=TRUE" : "");
    try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setLong(1, id);
      try (ResultSet rs = p.executeQuery()) { return rs.next() ? map(rs) : null; }
    } catch (SQLException e) {
      System.err.println("SQL Error during findById: " + e.getMessage());
      e.printStackTrace(System.err);
      throw new IllegalStateException("Could not load user", e);
    }
  }

  public void updateLocale(long id, String locale) {
    if (!"en".equals(locale)) locale = "ja";
    try (Connection c = Database.getConnection();
         PreparedStatement p = c.prepareStatement("UPDATE users SET locale=? WHERE id=?")) {
      p.setString(1, locale); p.setLong(2, id); p.executeUpdate();
    } catch (SQLException e) {
      System.err.println("SQL Error during updateLocale: " + e.getMessage());
      e.printStackTrace(System.err);
      throw new IllegalStateException("Could not update locale", e);
    }
  }

  public void updatePassword(long id, String password) {
    try (Connection c = Database.getConnection();
         PreparedStatement p = c.prepareStatement("UPDATE users SET password_hash=? WHERE id=?")) {
      p.setString(1, PasswordUtil.hash(password)); p.setLong(2, id); p.executeUpdate();
    } catch (SQLException e) {
      System.err.println("SQL Error during updatePassword: " + e.getMessage());
      e.printStackTrace(System.err);
      throw new IllegalStateException("Could not update password", e);
    }
  }

  private User map(ResultSet rs) throws SQLException {
    return new User(rs.getLong("id"), rs.getString("employee_number"), rs.getString("name"),
        rs.getString("email"), rs.getString("role"), rs.getLong("branch_id"),
        rs.getLong("department_id"), rs.getString("branch_name"),
        rs.getString("department_name"), rs.getString("locale"));
  }
}
