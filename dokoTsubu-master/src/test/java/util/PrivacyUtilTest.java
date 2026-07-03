package util;

public class PrivacyUtilTest {
  public static void main(String[] args) {
    // 1. maskEmail tests
    assertEquals("t***@example.com", PrivacyUtil.maskEmail("taro.okinawa@example.com"));
    assertEquals("a***@example.com", PrivacyUtil.maskEmail("a@example.com"));
    assertEquals("", PrivacyUtil.maskEmail(null));
    assertEquals("", PrivacyUtil.maskEmail(""));
    assertEquals("-", PrivacyUtil.maskEmail("invalid-address"));
    assertEquals("-", PrivacyUtil.maskEmail("abc@invalid"));
    assertEquals("-", PrivacyUtil.maskEmail("@example.com"));
    assertEquals("-", PrivacyUtil.maskEmail("abc@"));

    // 2. maskEmailsInText tests
    assertEquals("Failed to send to t***@example.com", 
        PrivacyUtil.maskEmailsInText("Failed to send to taro.okinawa@example.com"));
    assertEquals("Error: a***@example.com and b***@example.com", 
        PrivacyUtil.maskEmailsInText("Error: a@example.com and b@example.com"));
    assertEquals("", PrivacyUtil.maskEmailsInText(null));
    assertEquals("", PrivacyUtil.maskEmailsInText(""));
    assertEquals("Some random text with no emails.", 
        PrivacyUtil.maskEmailsInText("Some random text with no emails."));

    assertEquals("10", LeaveDayFormat.format(new java.math.BigDecimal("10.000")));
    assertEquals("10.5", LeaveDayFormat.format(new java.math.BigDecimal("10.500")));
    assertEquals("10.01", LeaveDayFormat.format(new java.math.BigDecimal("10.010")));

    System.out.println("PrivacyUtilTest: all checks passed");
  }

  private static void assertEquals(String expected, String actual) {
    if (expected == null && actual == null) return;
    if (expected != null && expected.equals(actual)) return;
    throw new AssertionError("Expected: [" + expected + "], but got: [" + actual + "]");
  }
}
