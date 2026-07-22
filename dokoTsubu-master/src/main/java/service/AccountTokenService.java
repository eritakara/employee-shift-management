package service;

import config.Database;
import dao.Sql;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import util.PasswordUtil;

public class AccountTokenService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int TOKEN_BYTES = 32;
  public record IssueResult(String token, long mailId) { }

  public String issue(String email, String type, String baseUrl) {
    IssueResult result = issueWithMail(email, type, baseUrl);
    return result == null ? null : result.token();
  }

  public IssueResult issueWithMail(String email, String type, String baseUrl) {
    Map<String, Object> user = Sql.one("SELECT id,email,name FROM users WHERE LOWER(email)=LOWER(?) AND active=TRUE", email == null ? "" : email.trim());
    if (user.isEmpty()) return null;
    String token = newToken();
    Sql.update("UPDATE account_tokens SET used_at=CURRENT_TIMESTAMP WHERE user_id=? AND token_type=? AND used_at IS NULL", user.get("id"), type);
    Sql.insert("INSERT INTO account_tokens(user_id,token,token_type,expires_at) VALUES(?,?,?,?)",
        user.get("id"), tokenHash(token), type, LocalDateTime.now().plusHours(1));
    String path = "INVITE".equals(type) ? "/invite?token=" : "/reset?token=";
    String subject = "INVITE".equals(type) ? "ShiftFlowへのご招待" : "ShiftFlow パスワード再設定";
    String body = user.get("name") + " 様\n\n次のリンクから手続きを行ってください。\n" + baseUrl + path + token + "\n\nこのリンクは24時間有効です。";
    long mailId = Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES(?,?,?)", user.get("email"), subject, body);
    AuditService.record(null, "ISSUE_" + type + "_TOKEN", "USER", String.valueOf(user.get("id")), null, "expires in 1h");
    return new IssueResult(token, mailId);
  }

  public boolean isValid(String token, String type) {
    if (token == null || token.isBlank()) return false;
    return !Sql.query("SELECT id FROM account_tokens WHERE token=? AND token_type=? AND used_at IS NULL AND expires_at>CURRENT_TIMESTAMP",
        tokenHash(token), type).isEmpty();
  }

  public boolean consume(String token, String type, String password) {
    if (password == null || password.length() < 10) throw new IllegalArgumentException("パスワードは10文字以上で入力してください。");
    if (token == null || token.isBlank()) return false;
    String passwordHash = PasswordUtil.hash(password);
    long userId;
    try (Connection connection = Database.getConnection()) {
      boolean originalAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement find = connection.prepareStatement(
            "SELECT id,user_id FROM account_tokens WHERE token=? AND token_type=? "
                + "AND used_at IS NULL AND expires_at>CURRENT_TIMESTAMP FOR UPDATE")) {
          find.setString(1, tokenHash(token));
          find.setString(2, type);
          try (ResultSet result = find.executeQuery()) {
            if (!result.next()) {
              connection.rollback();
              return false;
            }
            long tokenId = result.getLong("id");
            userId = result.getLong("user_id");
            try (PreparedStatement updatePassword = connection.prepareStatement(
                     "UPDATE users SET password_hash=? WHERE id=?");
                 PreparedStatement consumeToken = connection.prepareStatement(
                     "UPDATE account_tokens SET used_at=CURRENT_TIMESTAMP WHERE id=? AND used_at IS NULL")) {
              updatePassword.setString(1, passwordHash);
              updatePassword.setLong(2, userId);
              if (updatePassword.executeUpdate() != 1) throw new SQLException("Token user was not found");
              consumeToken.setLong(1, tokenId);
              if (consumeToken.executeUpdate() != 1) throw new SQLException("Token was already consumed");
            }
          }
        }
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(originalAutoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Could not consume account token", e);
    }
    AuditService.record(userId, "CONSUME_" + type + "_TOKEN", "USER", String.valueOf(userId), null, null);
    return true;
  }

  static String tokenHash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
