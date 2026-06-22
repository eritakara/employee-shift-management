# Render demo data setup

## デモデータの保存場所

デモデータは SQL や JSON ではなく、WAR に同梱される CSV と Java の初期化処理で管理する。

| 種別 | 場所 | 内容 |
|---|---|---|
| CSV | `src/main/resources/data/demo-users.csv` | デモ従業員、管理者、所属情報 |
| CSV | `src/main/resources/data/demo-shifts.csv` | 2026-06 のシフトデータ |
| CSV | `src/main/resources/data/demo-attendance.csv` | 出退勤デモデータ |
| Java | `src/main/java/config/Database.java` | スキーマ作成、基本マスター、CSV の upsert |

Docker build では `src/main/resources` を `WEB-INF/classes` にコピーするため、Render 上でも `/data/*.csv` としてクラスパスから読み込める。

## Render での推奨構成

現在の配布物に含まれる JDBC ドライバは H2 のみ。Render でそのまま動かす場合は H2 ファイル DB を永続ディスク上に置く。

1. Render の Web Service を Docker で作成する。
2. Persistent Disk を追加し、mount path を `/opt/shiftflow/data` にする。
3. Environment Variables を設定する。
4. 初回デプロイ時だけ `DEMO_SEED=true` にする。
5. デモデータ投入後、再投入を避けたい場合は `DEMO_SEED=false` に戻して再デプロイする。

CSV 投入は `INSERT ... WHERE NOT EXISTS` と `MERGE` を使うため、既存データを削除しない。ただし同じ社員番号、同じ日付のデモシフト・出退勤は CSV の内容で更新される。

## Render Environment Variables

| Key | 値の例 | 必須 | 説明 |
|---|---|---|---|
| `SHIFTFLOW_DATA_DIR` | `/opt/shiftflow/data` | 推奨 | H2 ファイル DB の保存先。Persistent Disk の mount path と合わせる。 |
| `DEMO_SEED` | `true` | 初回のみ | `true` のときだけ CSV デモデータを投入する。 |
| `BASE_SEED` | `true` | 任意 | 基本マスターと初期ユーザーを投入する。未設定時は `true`。 |
| `JDBC_URL` | `jdbc:h2:file:/opt/shiftflow/data/shiftapp;AUTO_SERVER=TRUE` | 任意 | 明示的に JDBC 接続先を指定する場合に使う。未設定時は `SHIFTFLOW_DATA_DIR` から自動生成。 |
| `DB_USER` | `sa` | 任意 | JDBC ユーザー。未設定時は `sa`。 |
| `DB_PASSWORD` |  | 任意 | JDBC パスワード。未設定時は空文字。 |
| `DB_DRIVER` | `org.h2.Driver` | 任意 | H2 以外の JDBC ドライバを使う場合に指定する。 |

Render の Web Service は `PORT` 環境変数で指定されたポートに bind する必要がある。Dockerfile は `PORT` を読んで Tomcat の Connector port を変更する。

## 本番 DB にデモデータを投入する手順

H2 + Persistent Disk の場合、別途 SQL を手で流す必要はない。アプリ起動時に以下の条件で投入する。

1. `SHIFTFLOW_DATA_DIR=/opt/shiftflow/data` を設定する。
2. Persistent Disk の mount path を `/opt/shiftflow/data` にする。
3. `DEMO_SEED=true` を設定してデプロイする。
4. ログイン後、従業員、シフト、出退勤データが表示されることを確認する。
5. 継続運用でデモCSVの再同期が不要なら `DEMO_SEED=false` に変更して再デプロイする。

Render Managed Postgres など H2 以外の DB を使う場合は、先に対象 DB の JDBC ドライバを `WEB-INF/lib` に追加し、スキーマ SQL の互換性確認が必要。現在の `CREATE TABLE` と `MERGE` は H2 前提のため、そのまま Postgres 用 SQL としては扱わない。
