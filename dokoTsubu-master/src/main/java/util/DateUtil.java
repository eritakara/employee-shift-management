package util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class DateUtil {
  private DateUtil() { }

  public static LocalDate toDate(Object value) {
    if (value instanceof LocalDate date) return date;
    if (value instanceof java.sql.Date date) return date.toLocalDate();
    return LocalDate.parse(String.valueOf(value));
  }

  public static LocalDateTime toDateTime(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime dateTime) return dateTime;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
    if (value instanceof java.time.OffsetDateTime offsetDateTime) return offsetDateTime.toLocalDateTime();
    if (value instanceof java.time.ZonedDateTime zonedDateTime) return zonedDateTime.toLocalDateTime();
    return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
  }

  public static String formatUtcToJst(Object value) {
    if (value == null) return "";
    ZonedDateTime jst = null;
    if (value instanceof java.time.OffsetDateTime odt) {
      jst = odt.atZoneSameInstant(ZoneId.of("Asia/Tokyo"));
    } else if (value instanceof java.time.ZonedDateTime zdt) {
      jst = zdt.withZoneSameInstant(ZoneId.of("Asia/Tokyo"));
    } else {
      LocalDateTime ldt = toDateTime(value);
      if (ldt == null) return "";
      ZonedDateTime utc = ldt.atZone(ZoneId.of("UTC"));
      jst = utc.withZoneSameInstant(ZoneId.of("Asia/Tokyo"));
    }
    return jst.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
  }

  public static LocalTime toTime(Object value) {
    if (value == null) return null;
    if (value instanceof LocalTime time) return time;
    if (value instanceof java.sql.Time time) return time.toLocalTime();
    return LocalTime.parse(String.valueOf(value));
  }
}
