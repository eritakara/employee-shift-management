package service;

import dao.Sql;
import java.util.List;
import java.util.Map;
import model.User;

public class SettingsService {
  public String value(String key, String fallback) {
    Object value = Sql.one("SELECT setting_value FROM app_settings WHERE setting_key=?", key).get("setting_value");
    return value == null ? fallback : String.valueOf(value);
  }

  public int integer(String key, int fallback) {
    try { return Integer.parseInt(value(key, String.valueOf(fallback))); }
    catch (NumberFormatException e) { return fallback; }
  }

  public boolean bool(String key, boolean fallback) {
    return Boolean.parseBoolean(value(key, String.valueOf(fallback)));
  }

  public List<Map<String, Object>> all(User user) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ利用できます。");
    return Sql.query("SELECT * FROM app_settings ORDER BY setting_key");
  }

  public void update(User user, String key, String value) {
    if (!user.isHr()) throw new SecurityException("人事担当者のみ操作できます。");
    Map<String, Object> before = Sql.one("SELECT setting_value FROM app_settings WHERE setting_key=?", key);
    if (before.isEmpty()) throw new IllegalArgumentException("設定項目が見つかりません。");
    String normalized = validate(key, value);
    Sql.update("UPDATE app_settings SET setting_value=?,updated_at=CURRENT_TIMESTAMP WHERE setting_key=?", normalized, key);
    AuditService.record(user.getId(), "UPDATE_SETTING", "APP_SETTING", key, String.valueOf(before.get("setting_value")), normalized);
  }

  private String validate(String key, String value) {
    String normalized = value == null ? "" : value.trim();
    if (key.equals("ALLOW_CONFIRM_WITH_WARNINGS") || key.equals("LEAVE_ALLOW_PAST") || key.equals("LOCATION_REQUIRED")) {
      if (!normalized.equals("true") && !normalized.equals("false")) throw new IllegalArgumentException("設定値が不正です。");
      return normalized;
    }
    try {
      int number = Integer.parseInt(normalized);
      int min = key.equals("LEAVE_MIN_NOTICE_DAYS") ? 0 : 1;
      int max = switch (key) {
        case "SHIFT_SUBMISSION_DAY", "MONTHLY_CLOSE_DAY" -> 31;
        case "RETENTION_YEARS" -> 100;
        case "MAX_CONCURRENT_USERS" -> 100000;
        default -> throw new IllegalArgumentException("設定項目が不正です。");
      };
      if (number < min || number > max) throw new IllegalArgumentException("設定値は" + min + "から" + max + "の範囲で入力してください。");
      return String.valueOf(number);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("設定値は整数で入力してください。");
    }
  }
}
