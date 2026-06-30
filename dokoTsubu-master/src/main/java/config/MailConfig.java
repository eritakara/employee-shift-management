package config;

public record MailConfig(String host, int port, String security, String username,
    String password, String fromAddress, String fromName, int maxAttempts) {

  public static MailConfig load() {
    String security = setting("SHIFTFLOW_SMTP_SECURITY", "starttls").toLowerCase();
    int defaultPort = "smtps".equals(security) ? 465 : 587;
    return new MailConfig(
        setting("SMTP_HOST", "SHIFTFLOW_SMTP_HOST", ""),
        integer("SMTP_PORT", "SHIFTFLOW_SMTP_PORT", defaultPort),
        security,
        setting("SMTP_USER", "SHIFTFLOW_SMTP_USER", ""),
        setting("SMTP_PASSWORD", "SHIFTFLOW_SMTP_PASSWORD", ""),
        setting("SMTP_FROM", "SHIFTFLOW_MAIL_FROM", ""),
        setting("SHIFTFLOW_MAIL_FROM_NAME", "ShiftFlow"),
        integer("SHIFTFLOW_MAIL_MAX_ATTEMPTS", 3));
  }

  public boolean enabled() {
    return host != null && !host.isBlank() && fromAddress != null && !fromAddress.isBlank();
  }

  private static String setting(String name, String fallback) {
    String property = System.getProperty(name);
    if (property != null) return property;
    String environment = System.getenv(name);
    return environment == null ? fallback : environment;
  }

  private static String setting(String name, String legacyName, String fallback) {
    String value = setting(name, "");
    return value.isBlank() ? setting(legacyName, fallback) : value;
  }

  private static int integer(String name, int fallback) {
    try { return Integer.parseInt(setting(name, String.valueOf(fallback))); }
    catch (NumberFormatException e) { return fallback; }
  }


  private static int integer(String name, String legacyName, int fallback) {
    String value = setting(name, "");
    if (value.isBlank()) return integer(legacyName, fallback);
    try { return Integer.parseInt(value); }
    catch (NumberFormatException e) { return fallback; }
  }
}
