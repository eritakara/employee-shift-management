package util;

import java.util.Map;

public final class AuditActionLabel {
  private static final Map<String, String> LABELS = Map.ofEntries(
      Map.entry("APPROVE_LEAVE", "有休申請を承認"),
      Map.entry("REJECT_LEAVE", "有休申請を却下"),
      Map.entry("REQUEST_LEAVE", "有休を申請"),
      Map.entry("CANCEL_LEAVE", "有休申請を取消"),
      Map.entry("APPROVE_SHIFT_CHANGE", "シフト変更を承認"),
      Map.entry("REJECT_SHIFT_CHANGE", "シフト変更を却下"),
      Map.entry("REQUEST_SHIFT_CHANGE", "シフト変更を申請"),
      Map.entry("AUTO_ASSIGN_SHIFTS", "シフトを自動割当"),
      Map.entry("SAVE_SHIFT", "シフトを保存"),
      Map.entry("CONFIRM_SHIFTS", "シフトを確定"),
      Map.entry("SUBMIT_SHIFT_PREFERENCES", "希望シフトを提出"),
      Map.entry("REVIEW_SHIFT_PREFERENCES", "希望シフトを確認"),
      Map.entry("CLOCK_IN", "出勤打刻"),
      Map.entry("CLOCK_OUT", "退勤打刻"),
      Map.entry("REQUEST_ATTENDANCE_ADJUSTMENT", "打刻修正を申請"),
      Map.entry("APPROVE_ATTENDANCE_ADJUSTMENT", "打刻修正を承認"),
      Map.entry("REJECT_ATTENDANCE_ADJUSTMENT", "打刻修正を却下"),
      Map.entry("FINALIZE_ATTENDANCE", "勤怠を確定"),
      Map.entry("REOPEN_ATTENDANCE", "勤怠確定を解除"),
      Map.entry("FINALIZE_ATTENDANCE_EMPLOYEE_MONTH", "従業員の月次勤怠を確定"),
      Map.entry("REOPEN_ATTENDANCE_EMPLOYEE_MONTH", "従業員の月次勤怠確定を解除"),
      Map.entry("FINALIZE_ATTENDANCE_MONTH", "月次勤怠を一括確定"),
      Map.entry("REOPEN_ATTENDANCE_MONTH", "月次勤怠確定を解除"),
      Map.entry("CREATE_USER", "ユーザーを作成"),
      Map.entry("UPDATE_USER", "ユーザーを更新"),
      Map.entry("UPDATE_ACCOUNT", "アカウントを更新"),
      Map.entry("ISSUE_INVITE_TOKEN", "招待トークンを発行"),
      Map.entry("CONSUME_INVITE_TOKEN", "招待トークンを使用"),
      Map.entry("ISSUE_RESET_TOKEN", "再設定トークンを発行"),
      Map.entry("CONSUME_RESET_TOKEN", "再設定トークンを使用"),
      Map.entry("REISSUE_INVITE", "招待を再発行"),
      Map.entry("ADD_DELEGATION", "代理店長を設定"),
      Map.entry("UPDATE_DELEGATION", "代理店長設定を更新"),
      Map.entry("ADD_QUALIFICATION", "資格を登録"),
      Map.entry("UPDATE_QUALIFICATION", "資格を更新"),
      Map.entry("CREATE_MASTER", "マスタ項目を作成"),
      Map.entry("UPDATE_MASTER", "マスタ項目を更新"),
      Map.entry("TOGGLE_MASTER", "マスタ項目の状態を変更"),
      Map.entry("UPDATE_WORK_TYPE", "勤務区分を更新"),
      Map.entry("CREATE_LEAVE_RULE", "有休付与ルールを作成"),
      Map.entry("UPDATE_LEAVE_RULE", "有休付与ルールを更新"),
      Map.entry("UPDATE_SETTING", "システム設定を更新"),
      Map.entry("RETRY_MAIL", "メール送信を再試行"),
      Map.entry("RUN_DATA_RETENTION", "保存期限処理を実行"),
      Map.entry("LOGIN", "ログイン"),
      Map.entry("LOGOUT", "ログアウト")
  );

  private AuditActionLabel() { }

  public static String labelOf(Object action) {
    if (action == null) return "";
    String code = String.valueOf(action);
    return LABELS.getOrDefault(code, code);
  }
}
