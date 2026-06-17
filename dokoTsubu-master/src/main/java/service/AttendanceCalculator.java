package service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class AttendanceCalculator {
  public record Schedule(LocalTime start, LocalTime end, boolean crossesMidnight, int breakMinutes) { }
  public record Result(long workedMinutes, long overtimeMinutes, boolean late, boolean early) { }

  public Result calculate(LocalDate workDate, LocalDateTime clockIn, LocalDateTime clockOut, Schedule schedule) {
    if (clockIn == null || clockOut == null || schedule == null || schedule.start() == null || schedule.end() == null) {
      return new Result(0, 0, false, false);
    }
    if (clockOut.isBefore(clockIn)) throw new IllegalArgumentException("退勤時刻は出勤時刻以降にしてください。");
    LocalDateTime plannedIn = workDate.atTime(schedule.start());
    LocalDateTime plannedOut = workDate.plusDays(schedule.crossesMidnight() ? 1 : 0).atTime(schedule.end());
    long actualGross = Duration.between(clockIn, clockOut).toMinutes();
    long plannedGross = Math.max(0, Duration.between(plannedIn, plannedOut).toMinutes());
    long worked = Math.max(0, actualGross - schedule.breakMinutes());
    long plannedWorked = Math.max(0, plannedGross - schedule.breakMinutes());
    return new Result(worked, Math.max(0, worked - plannedWorked), clockIn.isAfter(plannedIn), clockOut.isBefore(plannedOut));
  }
}
