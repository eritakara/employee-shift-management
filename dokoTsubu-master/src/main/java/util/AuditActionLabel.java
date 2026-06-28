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

  public static java.util.Set<String> actions() {
    return LABELS.keySet();
  }

  public static String labelOf(Object action) {
    if (action == null) return "";
    String code = String.valueOf(action);
    return LABELS.getOrDefault(code, code);
  }

  private static final Map<String, String> TARGET_LABELS = Map.ofEntries(
      Map.entry("USER", "ユーザー"),
      Map.entry("ATTENDANCE", "勤怠実績"),
      Map.entry("ATTENDANCE_MONTH", "月次勤怠"),
      Map.entry("ATTENDANCE_ADJUSTMENT", "打刻修正申請"),
      Map.entry("QUALIFICATION", "資格"),
      Map.entry("DELEGATION", "代理店長"),
      Map.entry("LEAVE_RULE", "有休付与ルール"),
      Map.entry("LEAVE_REQUEST", "有休申請"),
      Map.entry("WORK_TYPE", "勤務区分"),
      Map.entry("MAIL_OUTBOX", "送信メール"),
      Map.entry("APP_SETTING", "システム設定"),
      Map.entry("SHIFT", "シフト"),
      Map.entry("SHIFT_PREFERENCE", "希望シフト"),
      Map.entry("SHIFT_PREFERENCE_SUBMISSION", "希望シフト提出"),
      Map.entry("SHIFT_MONTH", "月間シフト"),
      Map.entry("SHIFT_CHANGE", "シフト変更申請"),
      Map.entry("BRANCH", "営業所"),
      Map.entry("DEPARTMENT", "部署"),
      Map.entry("EMPLOYMENT_TYPE", "雇用形態")
  );

  public static String targetLabelOf(Object targetType) {
    if (targetType == null) return "";
    String code = String.valueOf(targetType);
    return TARGET_LABELS.getOrDefault(code, code);
  }

  public static String decodeValue(Object val) {
    if (val == null) return "-";
    String text = String.valueOf(val);
    if (text.isBlank() || "null".equals(text)) return "-";

    text = text.replace("request_status=", "申請状態=")
               .replace("clock_in=", "出勤=")
               .replace("clock_out=", "退勤=")
               .replace("rejection_reason=", "却下理由=")
               .replace("status=", "状態=")
               .replace("employee_number=", "社員番号=")
               .replace("hire_date=", "入社日=")
               .replace("branch_id=", "営業所ID=")
               .replace("department_id=", "部署ID=")
               .replace("employment_type_id=", "雇用形態ID=")
               .replace("role=", "役割=")
               .replace("active=", "有効=")
               .replace("name=", "氏名=")
               .replace("email=", "メールアドレス=")
               .replace("delegateId=", "代理店長ID=")
               .replace("start=", "開始日=")
               .replace("end=", "終了日=")
               .replace("expires=", "有効期限=")
               .replace("expires in 24h", "24時間で失効")
               .replace("CONFIRMED reason=", "確定 理由=")
               .replace("PENDING", "申請中")
               .replace("APPROVED", "承認済み")
               .replace("REJECTED", "却下")
               .replace("CANCELLED", "取消済み")
               .replace("COMPLETE", "完了")
               .replace("OPEN", "未完了")
               .replace("DRAFT", "下書き")
               .replace("QUEUED", "送信待ち")
               .replace("FAILED", "送信失敗")
               .replace("EMPLOYEE", "従業員")
               .replace("MANAGER", "店長")
               .replace("HR", "人事")
               .replace("ADMIN", "管理者")
               .replace("ACQUIRED", "取得済み")
               .replace("DENIED", "拒否")
               .replace("UNAVAILABLE", "利用不可")
               .replace("UNKNOWN", "不明")
               .replace("FULL:", "1日:")
               .replace("AM:", "午前休:")
               .replace("PM:", "午後休:")
               .replace("HOURLY:", "時間有休:")
               .replace("true", "有効")
               .replace("false", "無効");

    return text;
  }
}
