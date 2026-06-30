package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrivacyUtil {
  private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9._%+-]+)(@[a-zA-Z0-9.-]+\\.[a-zA-Z]+)");

  private PrivacyUtil() {}

  public static String maskEmail(Object value) {
    if (value == null) return "";
    String email = String.valueOf(value).trim();
    if (email.isEmpty()) return "";

    int atIdx = email.indexOf('@');
    if (atIdx <= 0 || atIdx == email.length() - 1 || email.indexOf('.', atIdx) == -1) {
      return "-";
    }

    String localPart = email.substring(0, atIdx);
    String domain = email.substring(atIdx);

    char firstChar = localPart.charAt(0);
    return firstChar + "***" + domain;
  }

  public static String maskEmailsInText(Object value) {
    if (value == null) return "";
    String text = String.valueOf(value);
    if (text.isEmpty()) return "";

    Matcher matcher = EMAIL_PATTERN.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String localPart = matcher.group(1);
      String domain = matcher.group(2);
      if (!localPart.isEmpty()) {
        char firstChar = localPart.charAt(0);
        matcher.appendReplacement(sb, Matcher.quoteReplacement(firstChar + "***" + domain));
      } else {
        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
      }
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
