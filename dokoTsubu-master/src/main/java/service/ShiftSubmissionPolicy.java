package service;

import java.time.LocalDate;
import java.time.YearMonth;

public class ShiftSubmissionPolicy {
  public YearMonth targetMonth(LocalDate today) {
    return YearMonth.from(today.plusMonths(1));
  }

  public LocalDate deadline(LocalDate today, int deadlineDay) {
    YearMonth current = YearMonth.from(today);
    return current.atDay(Math.min(Math.max(deadlineDay, 1), current.lengthOfMonth()));
  }

  public void validate(LocalDate today, LocalDate workDate, int deadlineDay) {
    YearMonth targetMonth = YearMonth.from(workDate);
    YearMonth deadlineMonth = targetMonth.minusMonths(1);
    LocalDate deadline = deadlineMonth.atDay(Math.min(Math.max(deadlineDay, 1), deadlineMonth.lengthOfMonth()));

    if (targetMonth.isBefore(YearMonth.from(today))) {
      throw new IllegalArgumentException("過去の月の希望シフトは提出できません。");
    }
    if (today.isAfter(deadline)) {
      throw new IllegalArgumentException("希望シフトの提出期限（" + deadline + "）を過ぎています。");
    }
  }
}
