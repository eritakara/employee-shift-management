<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<% if (session.getAttribute("loginUser") != null) { response.sendRedirect("app/dashboard"); return; } %>
<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ログイン | シフト・有給管理</title>
<link rel="stylesheet" href="assets/app.css">
</head>
<body class="auth-page">
<a class="skip-link" href="#main-content">本文へ移動</a>
<main class="auth-shell" id="main-content" tabindex="-1">
  <section class="auth-brand">
    <span class="brand-mark">SF</span>
    <div><strong>シフト・有給管理</strong></div>
  </section>
  <section class="auth-panel">
    <p class="eyebrow">WORKFORCE PORTAL</p>
    <h1>おかえりなさい</h1>
    <p class="muted">勤務予定と申請状況を、ひとつの場所で。</p>
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
    <div class="demo-accounts">
      <p>※このアプリはポートフォリオ用のデモ環境です。</p>
      <p>ログイン情報は面接・提出時に別途共有します。</p>
    </div>
  </section>
</main>
</body>
</html>
