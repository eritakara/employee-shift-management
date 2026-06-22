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

成果物は `target\shiftflow.war` です。

## ローカル起動

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```

起動後に `http://localhost:8080/shiftflow/` を開きます。停止時は次を実行します。

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

このプロジェクトは Spring Boot、Quarkus、Micronaut ではなく、Jakarta Servlet / JSP を Tomcat 10.1 に配備する Java Web アプリです。Dockerfile は Tomcat 10.1 + Java 21 上で WAR をビルドし、`/shiftflow` コンテキストに配備します。

### ローカルでDocker起動

```powershell
docker build -t shiftflow .
docker run --rm -p 8080:8080 -e PORT=8080 shiftflow
```

起動後に `http://localhost:8080/` を開きます。ルートURLから `http://localhost:8080/shiftflow/` へ自動転送されます。

H2データベースはコンテナ内の `/opt/shiftflow/data` に保存されます。ローカルでデータを残したい場合は、次のようにボリュームを割り当てます。

```powershell
docker run --rm -p 8080:8080 -e PORT=8080 -v shiftflow-data:/opt/shiftflow/data shiftflow
```

### Renderへのデプロイ手順

1. GitHubにこのリポジトリを push します。
2. Render の Dashboard で **New +** → **Web Service** を選択します。
3. 対象リポジトリを接続します。
4. Runtime は **Docker** を選択します。
5. Root Directory は未設定のままでデプロイできます。`dokoTsubu-master` を Root Directory に指定する場合も、同じ内容の `Dockerfile` があるためデプロイできます。
6. 環境変数 `PORT` は Render が自動設定するため、手動追加は不要です。
7. データベースとして Supabase (PostgreSQL) を利用する場合は、以下の環境変数を Render の **Environment** 設定で追加します。
   * `DB_URL` (または `JDBC_URL`, `DB_JDBC_URL`): `jdbc:postgresql://<Supabaseの接続プーラーホスト名>:5432/postgres` (Supabase管理画面の Session pooler 設定から取得する接続URL)
     * アプリは `postgres://` や `postgresql://` で始まる PostgreSQL 接続 URI にも対応しています。
       例: `postgresql://postgres.<PROJECT_REF>:<PASSWORD>@<Supabaseの接続プーラーホスト名>:5432/postgres` 形式で指定した場合、接続情報（ユーザー名とパスワード）が自動的にパースされ、内部的に適切な JDBC 形式へ変換されて使用されます。
   * `DB_USER` (または `JDBC_USER`): `postgres.<PROJECT_REF>`
   * `DB_PASSWORD` (または `JDBC_PASSWORD`): `<Supabaseのデータベース接続パスワード>`
   * (※ セキュリティの観点から、パスワードなどの秘密情報は `DB_URL` に直接埋め込まず、 `DB_USER` と `DB_PASSWORD` に分割して設定することを強く推奨します。)
   * (※ 環境変数が指定されない場合は、自動的にコンテナ内のローカル H2 データベースが使用されます。H2を使用かつデータを永続化する場合は、Render Disk を追加し、Mount Path を `/opt/shiftflow/data` に指定します。)
8. Deploy 後、`https://<service-name>.onrender.com/` にアクセスします。ルートURLから `/shiftflow/` へ自動転送されます。

Dockerfile は起動時に Render の `PORT` 環境変数を Tomcat の HTTP Connector に反映します。
