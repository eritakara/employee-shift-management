# ShiftFlow

シフト、有休、勤怠を管理するJava Webアプリケーションです。Jakarta Servlet、JSP、H2、Tomcat 10で動作します。

## 必要環境

- Java 21以上
- Apache Tomcat 10.1
- PowerShell

既定ではTomcatを `C:\tomcat\10` から使用します。

## ビルドとテスト

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
powershell -ExecutionPolicy Bypass -File .\test.ps1
```

成果物は `target\ROOT.war` です。

## ローカル起動

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```

起動後に `http://localhost:8080/` を開きます。停止時は次を実行します。

```powershell
$env:CATALINA_HOME='C:\tomcat\10'
$env:CATALINA_BASE=(Resolve-Path '.tomcat').Path
& 'C:\tomcat\10\bin\shutdown.bat'
```

## デモアカウント

| 役割 | メールアドレス | パスワード |
|---|---|---|
| 人事担当者 | `hr@example.com` | `Password1!` |
| 店長 | `manager@example.com` | `Password1!` |
| 従業員 | `employee@example.com` | `Password1!` |

初回起動時に、営業所、部署、勤務区分とデモアカウントが自動登録されます。DBはTomcat開発環境の `.tomcat\data` に保存されます。
※本番環境 (`APP_ENV=production`) では、セキュリティの観点から人事担当者以外のデモアカウントは作成されず、初期設定した管理者のみが作成されます。

## 主な構成

- `src/main/java/config`: DB初期化、起動処理
- `src/main/java/dao`: DBアクセス
- `src/main/java/filter`: セッション認証
- `src/main/java/model`: ユーザーモデル
- `src/main/java/service`: シフト、有休、勤怠、通知などの業務処理
- `src/main/java/servlet`: 認証、画面、出力のコントローラー
- `src/main/webapp`: JSP、CSS、JavaScript、H2ドライバー
- `src/test/java`: 外部テストライブラリを使わないスモークテスト

## 仕様・進捗資料

- [要件定義書](docs/REQUIREMENTS.md)
- [タスクリスト](docs/TASK_LIST.md)
- [受入テストケース](docs/UAT_TEST_CASES.md)

`run-dev.ps1` で起動した開発環境では、`src/main/resources/data` のCSVから2026年6月のデモ従業員・シフトを投入します。デモシフトは那覇支店・北部支店・中部支店の17名分です。

## メール

招待、パスワード再設定、申請通知メールは `mail_outbox` に送信待ちとして登録されます。実運用前に利用するメールサービスを決め、送信処理へ接続してください。ローカル環境では再設定画面への確認用リンクを画面に表示します。

## Docker / Render デプロイ

このプロジェクトは、Jakarta Servlet / JSP を Tomcat 10.1 に配備する Java Web アプリです。Dockerfile は Tomcat 10.1 + Java 21 上で WAR（`ROOT.war`）をビルドし、ルートコンテキスト（`/`）に配備します。

### ローカルでDocker起動

```powershell
docker build -t shiftflow .
docker run --rm -p 8080:8080 -e PORT=8080 shiftflow
```

起動後に `http://localhost:8080/` を開きます。
※なお、古いコンテキストパス `/shiftflow/` にアクセスした場合も、自動的にルート `/` へ転送（リダイレクト）されるように設計されています。

### データベースと接続用ドライバについて

本番環境（Renderなど）で PostgreSQL (Supabase など) に接続するため、Dockerfile のビルド時に以下の JDBC ドライバーが自動ダウンロードされ、WAR パッケージに同梱されます。

* **使用する PostgreSQL JDBC ドライバー**: `postgresql-42.7.3.jar`

データベース構造および初期データの詳細については、[データベース構造定義書](docs/database.md) を参照してください。本番データベースが空の状態でアプリを起動すると、テーブル構造および初期シードデータが自動的に構築されます。

### Renderへのデプロイ手順

1. GitHubにこのリポジトリを push します。
2. Render の Dashboard で **New +** → **Web Service** を選択します。
3. 対象リポジトリを接続します。
4. Runtime は **Docker** を選択します。
5. **Environment Variables** (環境変数) に以下を設定します。

#### 推奨環境変数設定 (初心者向け 6つの設定項目)

本番環境のデータベースが Supabase PostgreSQL の場合、Renderのダッシュボードで以下の **6つの環境変数** を登録してください。

* `APP_ENV` = `production` （本番環境指定。H2への意図しないフォールバックを防止します）
* `JDBC_URL` = `jdbc:postgresql://<host>:5432/<dbname>?sslmode=require` （接続用URL。ホスト名等はご自身のSupabaseのものに置き換えてください）
* `DB_USER` = `postgres` （接続用のユーザー名。接続URLに埋め込まれていない場合は必須）
* `DB_PASSWORD` = `あなたのデータベースパスワード` （接続用のパスワード。接続URLに埋め込まれていない場合は必須）
* `INITIAL_HR_EMAIL` = `your-admin@example.com` （本番でログインする最初の管理者メールアドレス）
* `INITIAL_HR_PASSWORD` = `あなたの強力なパスワード` （最初の管理者のパスワード。初期値のまま起動しようとすると安全のため起動エラーになります）

#### 環境変数一覧

| 環境変数名 | 推奨値 / 例 | 説明 |
| :--- | :--- | :--- |
| `APP_ENV` | `production` | **本番環境指定**: `RENDER=true` とともに、万が一接続 URL が不足している場合に安全に起動を止めるための設定。 |
| `JDBC_URL` | `jdbc:postgresql://<host>:5432/postgres?sslmode=require` | **優先順位 1**: PostgreSQL 接続用 JDBC URL。<br>※`postgres://` または `postgresql://` 形式の接続文字列も、アプリが自動的に `jdbc:postgresql://` 形式へ置換します。 |
| `DB_URL` | (同上) | **優先順位 2**: JDBC_URL 未指定時に参照される変数。 |
| `DB_JDBC_URL` | (同上) | **優先順位 3**: DB_URL 未指定時に参照される変数。 |
| `DATABASE_URL` | (同上) | **優先順位 4**: DB_JDBC_URL 未指定時に参照される変数（RenderやSupabase自動生成の接続文字列を指定可能）。 |
| `DB_USER` | `postgres` (占有/個別ユーザー名) | **接続ユーザー**: URL内に資格情報が埋め込まれている場合は空（未指定）で動作します。 |
| `DB_PASSWORD` | (接続パスワード) | **接続パスワード**: URL内に資格情報が埋め込まれている場合は空（未指定）で動作します。 |
| `INITIAL_HR_EMAIL` | `hr-admin@yourcompany.com` | **初回管理者（HRロール）のメールアドレス**: 本番環境で初期登録される管理者のメールアドレスです。初期値（`hr@example.com`）のまま本番稼働させないために設定します。 |
| `INITIAL_HR_PASSWORD` | `強力な任意のパスワード` | **初回管理者の初期パスワード**: 本番の安全性を高めるため、半角英数字記号を含む強力なパスワードを指定してください。初期値（`Password1!`）のままで本番起動しようとすると、安全のため起動時にエラー（例外）が発生しアプリが停止します。 |

※ `APP_ENV=production` または `RENDER=true` または `DB_REQUIRED=true` が設定されている場合、接続 URL が不足していると、データ保存の失敗を防ぐためH2データベースへフォールバックせずに、起動エラーとして強制終了します。
※ 本番環境 (`APP_ENV=production`) では、セキュリティの観点から一般社員・店長などのデモユーザー（`manager@example.com` など）の自動作成（シードデータ投入）はスキップされます。初期HRユーザーのみが作成されます。

#### Supabase 接続方式について
* **Direct Connection (推奨)**:
  * 通常の Tomcat Servlet 構成では常時接続のプール管理が優れているため、Supabase の **Direct Connection (ポート `5432`)** を推奨します。
  * 接続文字列の例: `jdbc:postgresql://db.xxxxxx.supabase.co:5432/postgres?sslmode=require`
* **Session Pooler (セッションプーラー)**:
  * 接続数制限を回避するために Pooler を使用する場合は、ポート **`6543`** を指定し、Supabase 側でプーラーモードを **Session** に指定してください（Transactionモードは JDBC の Prepared Statement と競合するため非推奨です）。
  * 接続文字列の例: `jdbc:postgresql://db.xxxxxx.supabase.co:6543/postgres?sslmode=require`

6. Deploy 後、`https://<service-name>.onrender.com/` にアクセスします。アプリがルートURL上で稼働します。

### Supabase の起動確認用 SQL

デプロイ完了後、Supabase の SQL Editor にて正常にスキーマ生成および初期シードデータが投入されたかを確認するには、以下のクエリを実行してください。

```sql
-- 1. 作成されたテーブル一覧の確認 (publicスキーマ)
SELECT tablename FROM pg_tables WHERE schemaname = 'public';

-- 2. 初期HR管理者が登録されているかの確認
-- (設定した INITIAL_HR_EMAIL のアドレスが表示されることを確認します)
SELECT id, employee_number, name, email, role, active FROM users;

-- 3. マスタデータ (拠点・部署・雇用形態) が作成されているかの確認
SELECT 'branches' AS table_name, COUNT(*) FROM branches
UNION ALL
SELECT 'departments', COUNT(*) FROM departments
UNION ALL
SELECT 'employment_types', COUNT(*) FROM employment_types;

-- 4. 勤務タイプ (work_types) の確認
SELECT code, name_ja, start_time, end_time FROM work_types;
```
