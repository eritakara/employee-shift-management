package util;

import java.math.BigDecimal;

public final class LeaveDayFormat {
  private LeaveDayFormat() { }

  public static String format(Object value) {
    if (value == null) return "0";
    try {
      return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
    } catch (NumberFormatException e) {
      return String.valueOf(value);
    }
  }
}
