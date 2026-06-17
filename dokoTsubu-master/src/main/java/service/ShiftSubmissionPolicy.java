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
    if (!YearMonth.from(workDate).equals(targetMonth(today))) {
      throw new IllegalArgumentException("希望シフトは翌月分だけ提出できます。");
    }
    if (today.isAfter(deadline(today, deadlineDay))) {
      throw new IllegalArgumentException("今月の希望シフト提出期限を過ぎています。");
    }
  }
}
