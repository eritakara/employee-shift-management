<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<% if (session.getAttribute("loginUser") != null) { response.sendRedirect("app/dashboard"); return; } %>
<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ログイン | シフト・有休管理</title>
<link rel="stylesheet" href="assets/app.css?v=20260626-3">
</head>
<body class="auth-page">
<a class="skip-link" href="#main-content">本文へ移動</a>
<main class="auth-shell" id="main-content" tabindex="-1">
  <section class="auth-brand">
    <span class="brand-mark" aria-hidden="true">
      <svg class="brand-icon" viewBox="0 0 24 24" focusable="false">
        <rect x="4" y="5" width="16" height="15" rx="2"></rect>
        <path d="M8 3v4M16 3v4M4 10h16M8 15l2.4 2.4L16 12"></path>
      </svg>
    </span>
    <div><strong>シフト・有休管理</strong></div>
  </section>
  <section class="auth-panel">
    <p class="eyebrow">WORKFORCE PORTAL</p>
    <h1>お疲れ様です</h1>
    <% if (request.getAttribute("error") != null) { %>
      <div class="alert danger"><%= request.getAttribute("error") %></div>
    <% } %>
    <form action="login" method="post" class="stack-form">
      <label>メールアドレス<input type="email" name="email" autocomplete="username" required value=""></label>
      <label>パスワード<input type="password" name="password" autocomplete="current-password" required value=""></label>
      <button class="primary wide" type="submit">ログイン</button>
    </form>
    <p><a href="forgot">パスワードをお忘れですか？</a></p>
    <p><a href="privacy">個人情報・位置情報の取扱い / Privacy notice</a></p>
  </section>
</main>
</body>
</html>
