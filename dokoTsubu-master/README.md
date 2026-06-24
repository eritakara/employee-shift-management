# ShiftFlow

シフト、有休、勤怠を管理するJava Webアプリケーションです。Jakarta Servlet、JSP、H2、Tomcat 10で動作します。

## デモ環境

本アプリケーションは、ポートフォリオ・面接提出用に Render + Supabase (PostgreSQL) を使用したデモ環境を公開しています。

* **デモURL**: `https://employee-shift-management-demo.onrender.com/`
  ※このデモ環境は Render Free Plan を利用しているため、一定時間アクセスがない場合はサービスがスリープします。初回アクセス時は起動まで30〜60秒程度かかる場合があります。
* **デモログイン情報**:
  * **ログイン情報 (ID・パスワード)**: 面接・提出時に別途共有 (※ セキュリティ保護のためGitHub上には公開していません。本番環境起動時に環境変数 `INITIAL_HR_EMAIL` および `INITIAL_HR_PASSWORD` に設定した管理者アカウント情報を別途共有してください)

> [!NOTE]
> * セキュリティおよび個人情報保護のため、READMEやGit管理対象のコード内には本番用のパスワードや個人用メールアドレス、SupabaseのDB接続情報等は一切含めておりません。
> * ローカル開発環境用のデモアカウントは、本番環境 (`APP_ENV=production`) では自動生成されず、ご自身で設定した初期管理者アカウントのみが使用可能となります。

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

## デモログイン情報

ローカル開発環境および本番環境（デモ環境）で動作させる場合のログイン情報は、GitHub上には公開していません。面接・提出時に別途共有いたします。

※初回起動時に、営業所、部署、勤務区分、およびデモ用のアカウントが自動登録されます（DBはTomcat開発環境の `.tomcat\data` に保存されます）。
※本番環境 (`APP_ENV=production`) では、セキュリティの観点から一般従業員・店長等のデモアカウントは作成されず、初期設定した管理者のみが作成されます。

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
docker run --rm -p 10000:10000 shiftflow
```

起動後に `http://localhost:10000/` を開きます。
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

#### 既存のWeb Serviceでブランチを変更してテストデプロイする場合の手順
Branchを変更してテストデプロイを行う際は、環境変数の不足によるビルド/起動エラーを防ぐため、**必ず環境変数を先に登録してからデプロイ**を実行してください。

1. Renderのダッシュボードで対象のWeb Serviceの設定画面を開きます。
2. **Settings** タブで、**Branch** を `feature/render-supabase-root-war` に変更し、保存します。
3. **Environment Variables** タブに移動し、以下の環境変数を設定して保存します。
4. 設定保存後、画面右上にある **Manual Deploy** ボタンをクリックし、**Clear Cache & Deploy** を選択して実行します。

#### 推奨環境変数設定 (初心者向け 6つの設定項目)

本番環境のデータベースが Supabase PostgreSQL の場合、Renderのダッシュボードで以下の **6つの環境変数** を基本構成として登録してください。Render等の無料コンテナ環境では、Direct Connection (IPv6) を利用すると `Network is unreachable` などの到達性エラーが発生する可能性が高いため、接続用エンドポイントには IPv4 を提供する **Session Pooler** を前提に設定します。

* `APP_ENV` = `production` （本番環境指定。H2への意図しないフォールバックを防止します）
* `JDBC_URL` = `jdbc:postgresql://<Supabase Session pooler Host>:5432/postgres?sslmode=require` （SupabaseのSession Poolerへの接続用JDBC URL。ホスト名はご自身のプーラーのホスト名、ポートは `5432` または `6543` などの実際の接続先ポートに置き換えてください。※ユーザー名とパスワードは含めません）
* `DB_USER` = `postgres.<project-ref>` （接続用のユーザー名。Supabaseのプーラー画面に表示される **Connection PoolerのUser**、例: `postgres.<project-ref>` をそのまま指定してください。※通常のpostgresとは異なり、サフィックスが付きます）
* `DB_PASSWORD` = `<SupabaseのDatabase Password>` （接続用のパスワード。SupabaseのDatabase Passwordを指定してください）
* `INITIAL_HR_EMAIL` = `<初期管理者メール>` （本番でログインする最初の管理者メールアドレス）
* `INITIAL_HR_PASSWORD` = `<強力な初期管理者パスワード>` （最初の管理者のパスワード。初期値のまま起動しようとすると安全のため起動エラーになります）
* `DEMO_ACCOUNTS_ENABLED` = `true` （ポートフォリオデモ環境用の全拠点デモアカウントを自動生成する場合に `true` を指定）
* `DEMO_ACCOUNTS_PASSWORD` = `<強力なデモアカウント共通パスワード>` （自動生成されるデモアカウントの共通パスワード。本番環境で `DEMO_ACCOUNTS_ENABLED=true` の場合は必須）


#### 環境変数一覧

| 環境変数名 | 推奨値 / 例 | 説明 |
| :--- | :--- | :--- |
| `APP_ENV` | `production` | **本番環境指定**: `RENDER=true` とともに、万が一接続 URL が不足している場合に安全に起動を止めるための設定。 |
| `JDBC_URL` | `jdbc:postgresql://<Supabase Session pooler Host>:5432/postgres?sslmode=require` | **優先順位 1**: PostgreSQL 接続用 JDBC URL (Session Poolerポート経由推奨)。<br>※`postgres://` または `postgresql://` 形式の接続文字列は、自動的に `jdbc:postgresql://` 形式へ置換されますが、ユーザー名・パスワードは含めません。 |
| `DB_USER` | `postgres.<project-ref>` | **接続ユーザー**: Supabaseのプーラー設定画面に表示されるユーザー名（例: `postgres.<project-ref>`）を指定してください。 |
| `DB_PASSWORD` | `<SupabaseのDatabase Password>` | **接続パスワード**: SupabaseのDatabase Passwordを指定してください。 |
| `INITIAL_HR_EMAIL` | `<初期管理者メール>` | **初回管理者（HRロール）のメールアドレス**: 本番環境で初期登録される管理者のメールアドレスです。初期ダミーアドレスのまま本番稼働させないために設定します。 |
| `INITIAL_HR_PASSWORD` | `<強力な初期管理者パスワード>` | **初回管理者の初期パスワード**: 本番の安全性を高めるため、半角英数字記号を含む強力なパスワードを指定してください。初期ダミーパスワードのままで本番起動しようとすると、安全のため起動時にエラー（例外）が発生しアプリが停止します。 |
| `DEMO_ACCOUNTS_ENABLED` | `true` | **デモアカウント自動生成フラグ**: ポートフォリオデモ環境用に各拠点（本社＋6支店）に「店長1名・従業員4名」のデモアカウントを自動生成する場合は `true` に設定します。 |
| `DEMO_ACCOUNTS_PASSWORD` | `<強力なデモアカウント共通パスワード>` | **デモアカウント共通パスワード**: 自動生成されるデモアカウントに設定する共通パスワードです。本番環境で `DEMO_ACCOUNTS_ENABLED=true` に設定する場合は、セキュリティ保護のためデフォルト値（`Password1!`）以外の強力なパスワードを指定する必要があります（初期値のままだと起動例外が発生します）。 |

※ `APP_ENV=production` または `RENDER=true` または `DB_REQUIRED=true` が設定されている場合、接続 URL が不足していると、データ保存 of 失敗を防ぐためH2データベースへフォールバックせずに、起動エラーとして強制終了します。
※ 本番環境 (`APP_ENV=production`) では、セキュリティの観点から一般社員・店長などの一般デモユーザーの自動作成（シードデータ投入）はスキップされます。初期HRユーザーのみが作成されます。

#### Supabase 接続方式について

本アプリケーションは、通常の Tomcat Webコンテナの常時接続プールを使用するため、ネットワーク疎通性および接続数制限回避の観点から **Session Pooler** の使用を強く推奨します。

* **Session Pooler (推奨・最優先)**:
  * RenderなどのIPv6に非対応の環境から接続する場合、Direct Connectionはエラーになるため、IPv4を提供している **Session Pooler** を使用してください。
  * Supabase 側でプーラーモードを必ず **Session** に指定してください（Transactionモードは JDBC の Prepared Statement と競合して動作しないため利用不可です）。
  * 接続文字列の例: `jdbc:postgresql://<Supabase Session pooler Host>:5432/postgres?sslmode=require` (ポートは実際のSession Poolerで指定されているポート番号を指定してください)
  * ※ `JDBC_URL` にはユーザー名・パスワードを含めず、`DB_USER` と `DB_PASSWORD` を個別に設定してください。
* **Direct Connection (非推奨/動作不可の場合あり)**:
  * 接続元（Render等）が IPv6 に完全対応している環境、または有料オプションである **IPv4 Add-on** を適用している場合のみ動作します。ホスト名例（`db.<project-ref>.supabase.co`）等の Direct Connection は通常のテストデプロイ環境では動作しないため非推奨です。

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
