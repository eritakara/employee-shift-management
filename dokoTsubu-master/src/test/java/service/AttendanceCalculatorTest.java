package service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class AttendanceCalculatorTest {
  public static void main(String[] args) {
    AttendanceCalculator calculator = new AttendanceCalculator();
    AttendanceCalculator.Schedule day = new AttendanceCalculator.Schedule(LocalTime.of(8, 0), LocalTime.of(17, 0), false, 60);
    AttendanceCalculator.Schedule night = new AttendanceCalculator.Schedule(LocalTime.of(17, 0), LocalTime.of(8, 0), true, 120);
    LocalDate normalDay = LocalDate.of(2026, 6, 18);

    AttendanceCalculator.Result exactDay = calculator.calculate(normalDay, normalDay.atTime(8, 0), normalDay.atTime(17, 0), day);
    check(exactDay.workedMinutes() == 480 && exactDay.overtimeMinutes() == 0 && !exactDay.late() && !exactDay.early(), "day break deduction");
    AttendanceCalculator.Result extendedDay = calculator.calculate(normalDay, normalDay.atTime(8, 5), normalDay.atTime(18, 0), day);
    check(extendedDay.workedMinutes() == 535 && extendedDay.overtimeMinutes() == 55 && extendedDay.late() && !extendedDay.early(), "late and overtime");
    AttendanceCalculator.Result earlyDay = calculator.calculate(normalDay, normalDay.atTime(8, 0), normalDay.atTime(16, 30), day);
    check(earlyDay.workedMinutes() == 450 && earlyDay.early(), "early departure");

    assertNight(calculator, night, LocalDate.of(2026, 1, 31), "month boundary");
    assertNight(calculator, night, LocalDate.of(2026, 12, 31), "year boundary");
    assertNight(calculator, night, LocalDate.of(2028, 2, 29), "leap-day boundary");
    expectFailure(() -> calculator.calculate(normalDay, normalDay.atTime(17, 0), normalDay.atTime(8, 0), day), "invalid clock order");
    System.out.println("AttendanceCalculatorTest: all checks passed");
  }

  private static void assertNight(AttendanceCalculator calculator, AttendanceCalculator.Schedule night, LocalDate start, String label) {
    LocalDateTime clockIn = start.atTime(17, 0);
    LocalDateTime clockOut = start.plusDays(1).atTime(8, 0);
    AttendanceCalculator.Result result = calculator.calculate(start, clockIn, clockOut, night);
    check(result.workedMinutes() == 780 && result.overtimeMinutes() == 0 && !result.late() && !result.early(), label);
  }

  private static void expectFailure(Runnable action, String label) {
    try { action.run(); } catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
