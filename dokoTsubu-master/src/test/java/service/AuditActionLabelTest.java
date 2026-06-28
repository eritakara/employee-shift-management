package service;

import util.AuditActionLabel;

public class AuditActionLabelTest {
  public static void main(String[] args) {
    check("有休申請を承認", AuditActionLabel.labelOf("APPROVE_LEAVE"), "leave label");
    check("シフトを自動割当", AuditActionLabel.labelOf("AUTO_ASSIGN_SHIFTS"), "shift label");
    check("出勤打刻", AuditActionLabel.labelOf("CLOCK_IN"), "attendance label");
    check("ログイン", AuditActionLabel.labelOf("LOGIN"), "login label");
    check("FUTURE_ACTION", AuditActionLabel.labelOf("FUTURE_ACTION"), "unknown fallback");
    check("", AuditActionLabel.labelOf(null), "null fallback");
    System.out.println("AuditActionLabelTest: all checks passed");
  }

  private static void check(String expected, String actual, String message) {
    if (!expected.equals(actual)) throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
  }
}
