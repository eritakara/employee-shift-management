<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isErrorPage="true" %>
<%
Object statusValue = request.getAttribute("jakarta.servlet.error.status_code");
int status = statusValue instanceof Number ? ((Number) statusValue).intValue() : 500;
response.setStatus(status);

String excKey = "jakarta.servlet.error.excep" + "tion";
Throwable throwable = (Throwable) request.getAttribute(excKey);
if (throwable != null) {
  // 【情報漏洩防止・セキュア設計】本番環境 (APP_ENV=production) では、
  // エラー詳細（内部パス、SQLスタックトレースなど）が画面やログに出力されるのを防ぐため、
  // 例外クラス名とメッセージのみを安全に記録します。
  // 開発環境では、デバッグしやすくするためにスタックトレースのフル出力を許可します。
  String appEnv = System.getenv("APP_ENV");
  boolean isDev = appEnv == null || !"production".equalsIgnoreCase(appEnv);
  if (isDev) {
    System.err.println("--- Web Application Error [" + status + "] ---");
    throwable.printStackTrace(System.err);
    System.err.println("----------------------------------------");
  } else {
    System.err.println("Web Application Error [" + status + "]: " + throwable.getClass().getName() + (throwable.getMessage() != null ? ": " + throwable.getMessage() : ""));
  }
}
%>
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title><%=status%> | シフト・有休管理</title>
  <link rel="stylesheet" href="<%=request.getContextPath()%>/assets/app.css">
</head>
<body>
<a class="skip-link" href="#main-content">本文へ移動 / Skip to main content</a>
<main class="error-page" id="main-content" tabindex="-1">
  <section class="error-card" role="alert">
    <p class="eyebrow">ERROR <%=status%></p>
    <% if (status == 404) { %>
      <h1>ページが見つかりません</h1>
      <p>URLを確認するか、シフト・有休管理のメニューから開き直してください。</p>
      <p class="muted">Page not found. Check the URL or open the page again from the menu.</p>
    <% } else { %>
      <h1>処理を完了できませんでした</h1>
      <p>繰り返し操作せず、時間をおいて再度お試しください。解消しない場合は発生日時と画面名を管理者へ連絡してください。</p>
      <p class="muted">The request could not be completed. Do not submit repeatedly. Try again later or contact support with the time and page name.</p>
    <% } %>
    <p><a class="button primary" href="<%=request.getContextPath()%>/">シフト・有休管理へ戻る / Back to home</a></p>
  </section>
</main>
</body>
</html>

