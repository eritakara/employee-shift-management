package config;

public record MailConfig(String host, int port, String security, String username,
    String password, String fromAddress, String fromName, int maxAttempts) {

  public static MailConfig load() {
    String configuredSecurity = setting("SMTP_SECURITY", "SHIFTFLOW_SMTP_SECURITY", "").toLowerCase();
    int defaultPort = "smtps".equals(configuredSecurity) ? 465 : 587;
    int port = integer("SMTP_PORT", "SHIFTFLOW_SMTP_PORT", defaultPort);
    String security = configuredSecurity.isBlank() ? (port == 465 || port == 2465 ? "smtps" : "starttls") : configuredSecurity;
    return new MailConfig(
        setting("SMTP_HOST", "SHIFTFLOW_SMTP_HOST", ""),
        port,
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
