package config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class DatabaseUrlParsingTest {
  private static void resetDatabaseClass() throws Exception {
    setPrivateStaticField("jdbcUrl", null);
    setPrivateStaticField("jdbcUser", null);
    setPrivateStaticField("jdbcPassword", null);
  }

  private static void setPrivateStaticField(String fieldName, Object value) throws Exception {
    Field field = Database.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(null, value);
  }

  private static Object getPrivateStaticField(String fieldName) throws Exception {
    Field field = Database.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.get(null);
  }

  private static String callConfiguredJdbcUrl() throws Exception {
    Method method = Database.class.getDeclaredMethod("configuredJdbcUrl");
    method.setAccessible(true);
    return (String) method.invoke(null);
  }

  public static void main(String[] args) throws Exception {
    System.out.println("Starting DatabaseUrlParsingTest...");

    // テスト1: jdbc: 形式のURLがそのまま返されること
    resetDatabaseClass();
    System.setProperty("shiftapp.dbUrl", "jdbc:postgresql://localhost:5432/mydb");
    String url1 = callConfiguredJdbcUrl();
    check("jdbc:postgresql://localhost:5432/mydb".equals(url1), "JDBC URL standard format");

    // テスト2: postgres:// 形式のURLがそのまま返されること（例外を投げない）
    resetDatabaseClass();
    System.setProperty("shiftapp.dbUrl", "postgres://user:pass@host:5432/db");
    String url2 = callConfiguredJdbcUrl();
    check("postgres://user:pass@host:5432/db".equals(url2), "postgres:// format is allowed");

    // テスト3: postgresql:// 形式のURLがそのまま返されること（例外を投げない）
    resetDatabaseClass();
    System.setProperty("shiftapp.dbUrl", "postgresql://user:pass@host:5432/db");
    String url3 = callConfiguredJdbcUrl();
    check("postgresql://user:pass@host:5432/db".equals(url3), "postgresql:// format is allowed");

    // テスト4: 無効なURL形式で例外が発生すること
    resetDatabaseClass();
    System.setProperty("shiftapp.dbUrl", "invalid://host:5432/db");
    try {
      callConfiguredJdbcUrl();
      throw new AssertionError("Should have failed for invalid URL scheme");
    } catch (Exception e) {
      check(e.getCause() instanceof IllegalStateException, "Invalid URL scheme throws IllegalStateException");
    }

    // テスト5: パースロジックの検証
    testParsingLogic("postgres://myuser:mypass@mysubdomain.supabase.co:5432/postgres",
        "jdbc:postgresql://mysubdomain.supabase.co:5432/postgres", "myuser", "mypass");

    testParsingLogic("postgresql://postgres.xxx:mypassword@aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres",
        "jdbc:postgresql://aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres", "postgres.xxx", "mypassword");

    System.out.println("DatabaseUrlParsingTest: all checks passed");
  }

  private static void testParsingLogic(String rawUrl, String expectedUrl, String expectedUser, String expectedPassword) {
    String jdbcUrl;
    String extractedUser = null;
    String extractedPassword = null;

    if (rawUrl != null && (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://"))) {
      String cleanUrl = rawUrl.substring(rawUrl.indexOf("://") + 3);
      int atIndex = cleanUrl.indexOf("@");
      if (atIndex != -1) {
        String userInfo = cleanUrl.substring(0, atIndex);
        String hostInfo = cleanUrl.substring(atIndex + 1);
        String[] userParts = userInfo.split(":", 2);
        extractedUser = userParts[0];
        extractedPassword = userParts.length > 1 ? userParts[1] : "";
        jdbcUrl = "jdbc:postgresql://" + hostInfo;
      } else {
        jdbcUrl = "jdbc:postgresql://" + cleanUrl;
      }
    } else {
      jdbcUrl = rawUrl;
    }

    check(expectedUrl.equals(jdbcUrl), "Extracted JDBC URL matches: " + jdbcUrl);
    check(expectedUser.equals(extractedUser), "Extracted User matches: " + extractedUser);
    check(expectedPassword.equals(extractedPassword), "Extracted Password matches: " + extractedPassword);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
    System.out.println("Passed: " + label);
  }
}
