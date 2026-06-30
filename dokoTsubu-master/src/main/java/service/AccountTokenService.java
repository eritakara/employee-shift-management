package service;

import dao.Sql;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import util.PasswordUtil;

public class AccountTokenService {
  public record IssueResult(String token, long mailId) { }

  public String issue(String email, String type, String baseUrl) {
    IssueResult result = issueWithMail(email, type, baseUrl);
    return result == null ? null : result.token();
  }

  public IssueResult issueWithMail(String email, String type, String baseUrl) {
    Map<String, Object> user = Sql.one("SELECT id,email,name FROM users WHERE LOWER(email)=LOWER(?) AND active=TRUE", email == null ? "" : email.trim());
    if (user.isEmpty()) return null;
    String token = UUID.randomUUID().toString().replace("-", "");
    Sql.update("UPDATE account_tokens SET used_at=CURRENT_TIMESTAMP WHERE user_id=? AND token_type=? AND used_at IS NULL", user.get("id"), type);
    Sql.insert("INSERT INTO account_tokens(user_id,token,token_type,expires_at) VALUES(?,?,?,?)",
        user.get("id"), token, type, LocalDateTime.now().plusHours(24));
    String path = "INVITE".equals(type) ? "/invite?token=" : "/reset?token=";
    String subject = "INVITE".equals(type) ? "ShiftFlowへのご招待" : "ShiftFlow パスワード再設定";
    String body = user.get("name") + " 様\n\n次のリンクから手続きを行ってください。\n" + baseUrl + path + token + "\n\nこのリンクは24時間有効です。";
    long mailId = Sql.insert("INSERT INTO mail_outbox(recipient,subject,body) VALUES(?,?,?)", user.get("email"), subject, body);
    AuditService.record(null, "ISSUE_" + type + "_TOKEN", "USER", String.valueOf(user.get("id")), null, "expires in 24h");
    return new IssueResult(token, mailId);
  }

  public boolean isValid(String token, String type) {
    return !Sql.query("SELECT id FROM account_tokens WHERE token=? AND token_type=? AND used_at IS NULL AND expires_at>CURRENT_TIMESTAMP", token, type).isEmpty();
  }

  public boolean consume(String token, String type, String password) {
    if (password == null || password.length() < 10) throw new IllegalArgumentException("パスワードは10文字以上で入力してください。");
    Map<String, Object> row = Sql.one("SELECT id,user_id FROM account_tokens WHERE token=? AND token_type=? AND used_at IS NULL AND expires_at>CURRENT_TIMESTAMP", token, type);
    if (row.isEmpty()) return false;
    Sql.update("UPDATE users SET password_hash=? WHERE id=?", PasswordUtil.hash(password), row.get("user_id"));
    Sql.update("UPDATE account_tokens SET used_at=CURRENT_TIMESTAMP WHERE id=?", row.get("id"));
    AuditService.record(((Number) row.get("user_id")).longValue(), "CONSUME_" + type + "_TOKEN", "USER", String.valueOf(row.get("user_id")), null, null);
    return true;
  }
}
