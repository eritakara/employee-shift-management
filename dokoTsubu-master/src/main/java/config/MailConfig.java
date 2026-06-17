package config;

public record MailConfig(String host, int port, String security, String username,
    String password, String fromAddress, String fromName, int maxAttempts) {

  public static MailConfig load() {
    String security = setting("SHIFTFLOW_SMTP_SECURITY", "starttls").toLowerCase();
    int defaultPort = "smtps".equals(security) ? 465 : 587;
    return new MailConfig(
        setting("SHIFTFLOW_SMTP_HOST", ""),
        integer("SHIFTFLOW_SMTP_PORT", defaultPort),
        security,
        setting("SHIFTFLOW_SMTP_USER", ""),
        setting("SHIFTFLOW_SMTP_PASSWORD", ""),
        setting("SHIFTFLOW_MAIL_FROM", ""),
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

  private static int integer(String name, int fallback) {
    try { return Integer.parseInt(setting(name, String.valueOf(fallback))); }
    catch (NumberFormatException e) { return fallback; }
  }
}
