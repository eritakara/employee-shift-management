package util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

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
    return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
  }

  public static LocalTime toTime(Object value) {
    if (value == null) return null;
    if (value instanceof LocalTime time) return time;
    if (value instanceof java.sql.Time time) return time.toLocalTime();
    return LocalTime.parse(String.valueOf(value));
  }
}
