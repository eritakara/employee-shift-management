package service;

import java.time.LocalDate;
import java.time.YearMonth;

public class ShiftSubmissionPolicyTest {
  public static void main(String[] args) {
    ShiftSubmissionPolicy policy = new ShiftSubmissionPolicy();
    LocalDate before = LocalDate.of(2026, 6, 14);
    LocalDate deadline = LocalDate.of(2026, 6, 15);
    LocalDate after = LocalDate.of(2026, 6, 16);
    LocalDate july = LocalDate.of(2026, 7, 1);

    check(policy.targetMonth(before).equals(YearMonth.of(2026, 7)), "target month");
    check(policy.deadline(before, 15).equals(deadline), "configured deadline");
    policy.validate(before, july, 15);
    policy.validate(deadline, july, 15);
    expectFailure(() -> policy.validate(after, july, 15), "after deadline");
    expectFailure(() -> policy.validate(before, LocalDate.of(2026, 8, 1), 15), "non-target month");
    check(policy.deadline(LocalDate.of(2026, 2, 1), 31).equals(LocalDate.of(2026, 2, 28)), "short month clamp");
    check(policy.deadline(LocalDate.of(2028, 2, 1), 31).equals(LocalDate.of(2028, 2, 29)), "leap year clamp");
    System.out.println("ShiftSubmissionPolicyTest: all checks passed");
  }

  private static void expectFailure(Runnable action, String label) {
    try { action.run(); }
    catch (IllegalArgumentException expected) { return; }
    throw new AssertionError("Failed: " + label);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
  }
}
