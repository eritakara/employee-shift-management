<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>個人情報・位置情報の取扱い | ShiftFlow</title>
  <link rel="stylesheet" href="<%=request.getContextPath()%>/assets/app.css">
</head>
<body>
<a class="skip-link" href="#main-content">本文へ移動 / Skip to main content</a>
<main class="legal-page" id="main-content" tabindex="-1">
  <header>
    <p class="eyebrow">PRIVACY NOTICE</p>
    <h1>個人情報・位置情報の取扱い</h1>
    <p class="muted">Privacy and location data notice</p>
  </header>

  <section lang="ja">
    <h2>取得する情報と利用目的</h2>
    <ul>
      <li>氏名、社員番号、メールアドレス、所属、雇用形態、資格情報は、本人確認、権限管理、従業員情報管理、業務連絡に使用します。</li>
      <li>シフト、有休、勤怠、申請理由、承認履歴は、勤務計画、申請承認、勤怠・有休管理、問い合わせ対応に使用します。</li>
      <li>打刻時刻、緯度、経度、位置情報の取得状態は、打刻記録の確認に使用します。位置情報は不正判定や打刻制限には使用しません。</li>
      <li>ログイン、設定変更、承認、出力などの操作履歴は、セキュリティ、監査、障害調査に使用します。</li>
    </ul>
  </section>

  <section lang="ja">
    <h2>閲覧範囲</h2>
    <p>従業員は本人の情報、店長と有効期間中の代理者は担当営業所・部署の業務情報、人事担当者は業務上必要な全社情報を閲覧します。位置情報を含むデータは、業務上必要な権限を持つ担当者だけが確認します。</p>
  </section>

  <section lang="ja">
    <h2>保存期間</h2>
    <p>シフト、勤怠、位置情報、打刻修正、有休、通知、メール送信記録、操作履歴は5年間保存し、保存期間を過ぎたデータは定期処理で削除します。無効化された退職者の識別情報は、5年経過後に匿名化します。法令または承認済み社内規程で異なる期間が確定した場合は、その規程を適用して本通知を更新します。</p>
  </section>

  <section lang="ja">
    <h2>位置情報を取得できない場合</h2>
    <p>端末で位置情報を拒否した場合や取得に失敗した場合は、その取得状態を記録します。打刻を許可する最終ルールは会社の承認済み運用規程に従います。端末の位置情報設定を変更する場合は、利用者自身が内容を確認してください。</p>
  </section>

  <section lang="en">
    <h2>English summary</h2>
    <p>ShiftFlow uses employee identity and organization data for authentication, authorization, workforce administration, and business communications. Shift, leave, attendance, request, approval, and audit data are used to operate and review workforce processes.</p>
    <p>Clock-in/out time, latitude, longitude, and acquisition status are recorded only to review time-clock records. Location data is not used to determine misconduct or automatically block clocking.</p>
    <p>Shift, attendance, location, correction, leave, notification, mail-delivery, and audit records are retained for five years and then deleted by scheduled retention processing. Identifying data for deactivated former employees is anonymized after five years.</p>
    <p>Employees can view their own data; managers and active delegates can view the business data in their assigned scope; HR users can view company-wide data when required for their duties.</p>
  </section>

  <p><a href="<%=request.getContextPath()%>/">ShiftFlowへ戻る / Back to ShiftFlow</a></p>
</main>
</body>
</html>
