# ShiftFlow 本番移行・切り戻し手順

## 1. 適用範囲と原則

- ステージングで承認した同一WARだけを本番へ昇格する。本番で再ビルドしない。
- 成果物はバージョン、Gitコミット、SHA-256を記録し、後から置換しない。
- 稼働中H2の `.mv.db` は直接コピーしない。バックアップはH2 `SCRIPT` を使用する。
- 復元先は必ず空の別DBとし、本番DBを上書きしない。
- DB、WAR、設定、SMTP、ログを環境間で共有しない。
- 本番では `-Dshiftapp.seedDemo=false` を必須とする。

## 2. 役割

| 役割 | 責任 |
|---|---|
| 作業者 | コマンド実行、時刻・結果・ハッシュの記録 |
| 確認者 | 手順の相互確認、バックアップ・設定・動作確認の照合 |
| システム責任者 | 開始、切り戻し、サービス再開の判断 |
| 業務責任者 | 業務停止・再開、代表データの確認 |

作業者と確認者は分離する。秘密値は作業記録へ転記せず、承認済みの秘密管理先から設定する。

## 3. リリース成果物の作成

クリーンなGit作業ツリーで実行する。

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\create-release.ps1 -Version 1.0.0
```

スクリプトは静的解析、ビルド、全自動テストを実行し、次を生成する。

- `shiftflow.war`
- `release-manifest.json`
- `shiftflow.war.sha256`

ステージングと本番で `Get-FileHash -Algorithm SHA256 shiftflow.war` を実行し、manifestの値と一致することを二者確認する。`-AllowDirty` はローカルでのスクリプト検証専用であり、ステージング・本番成果物には使用しない。

## 4. 移行前チェック

以下を作業票へ記録する。

1. リリース番号、Gitコミット、WARのSHA-256
2. 対象環境、作業日時、作業者、確認者、判断者
3. 承認済み停止時間、利用者告知、問い合わせ先
4. 現行WARのバージョン・SHA-256と保管場所
5. `shiftapp.dataDir`、`catalina.base`、Java・Tomcat版（秘密値は除く）
6. DB・WAR・ログ領域の空き容量
7. SMTP、DNS、TLS証明書の有効性
8. 未処理申請、送信待ちメール、実行中の移行処理がないこと
9. ステージングUATと復元検証が合格していること
10. 切り戻し判断期限と、切り戻し先WAR・DBパス

## 5. ステージング移行リハーサル

1. 本番と同じJava、Tomcat、起動プロパティ構成を用意する。
2. 本番データを使わず、承認済みの匿名化・移行リハーサルデータを使用する。
3. 現行ステージングDBから `backup-h2.ps1` でバックアップを取得する。
4. Tomcatを停止し、新WARを配備する。
5. `shiftapp.seedDemo=false` と環境専用DBパスを確認して起動する。
6. `check-health.ps1`、全役割ログイン、主要E2E、メール、日次処理、出力を確認する。
7. バックアップを空の別DBへ復元し、件数と代表データを照合する。
8. 所要時間、問題、手順修正、切り戻し所要時間を記録する。

## 6. 本番移行

### 6.1 停止前

1. 開始承認を得て、利用者へメンテナンス開始を通知する。
2. ログイン中利用者へ作業開始を周知し、新規更新操作を止める。
3. ヘルスチェック結果と現行DBファイルの容量を記録する。
4. 現行WAR、設定ファイル、起動オプションを読取専用の退避領域へ保管する。DBファイルはコピーしない。

### 6.2 バックアップと配備

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\backup-h2.ps1 `
  -DataDir C:\ShiftFlow\production\data `
  -BackupDir D:\ShiftFlowBackups\pre-release
```

1. SCRIPTファイルが0バイトでないことと作成日時を二者確認する。
2. Tomcatを停止し、プロセス終了とHTTP停止を確認する。
3. 承認済みWARを配備し、SHA-256を再確認する。
4. 本番の `shiftapp.dataDir` と `shiftapp.seedDemo=false` を確認する。
5. Tomcatを起動し、起動ログに重大エラーがないことをサーバー内で確認する。

### 6.3 技術確認

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\check-health.ps1 `
  -Url https://shiftflow.example/shiftflow/ `
  -TomcatBase C:\ShiftFlow\production\tomcat
```

次に、従業員・店長・人事担当者の代表アカウントで、ログイン、ダッシュボード、シフト、有休、勤怠、通知、出力を確認する。テストデータを本番へ登録した場合は、画面の正規手順で取消・無効化し監査履歴を残す。

### 6.4 業務確認と再開

業務責任者が代表従業員の確定シフト、有休残数、当月勤怠を移行前記録と照合する。メール送信待ち件数とエラーを確認し、システム責任者・業務責任者の双方が承認後にサービス再開を通知する。

## 7. 切り戻し判断

次のいずれかに該当し、作業時間内に安全な修正が確認できない場合は切り戻す。

- アプリが起動しない、またはヘルスチェックが失敗する
- ログイン、打刻、申請、承認の主要機能が利用できない
- DBエラー、データ欠落・重複・文字化けがある
- 権限外データの表示、秘密情報・個人情報漏えいの疑いがある
- 業務責任者が代表データ不一致を確認した

漏えい・破損疑いはP1障害として扱い、調査前に変更や再デプロイを繰り返さない。

## 8. 切り戻し手順

### 8.1 WARだけを戻せる場合

DB更新がない、または旧WARとの後方互換性をステージングで確認済みの場合に限る。

1. 利用者更新を停止し、Tomcatを停止する。
2. 失敗したWARとログ時刻を記録する。
3. 退避した旧WARを配備し、SHA-256を確認する。
4. 元の起動設定でTomcatを起動する。
5. ヘルスチェックと代表業務を確認し、再開承認を得る。

### 8.2 データをリリース前へ戻す場合

本番DBを上書きしない。新しい空ディレクトリへ復元し、検証後に接続先を切り替える。

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\restore-h2.ps1 `
  -BackupFile D:\ShiftFlowBackups\pre-release\shiftapp-YYYYMMDD-HHMMSS.sql `
  -RestoreDataDir C:\ShiftFlow\restore\rollback-YYYYMMDDHHMM `
  -ProductionDataDir C:\ShiftFlow\production\data
```

1. 復元検証DBの件数と代表業務データを照合する。
2. 業務責任者へ、バックアップ取得後の更新が失われる範囲を提示し承認を得る。
3. Tomcat停止中に `shiftapp.dataDir` を検証済み復元DBのディレクトリへ変更する。
4. 対応する旧WARを配備し、SHA-256と `shiftapp.seedDemo=false` を確認する。
5. 起動後にヘルスチェック、権限別確認、データ照合を行う。
6. 元DBは削除・上書きせず、アクセスを制限して原因調査完了まで保全する。

## 9. 完了記録

- 開始・停止・起動・再開の各時刻
- WARバージョン、Gitコミット、SHA-256
- SCRIPTバックアップと復元検証先
- 実施した確認、結果、証跡
- 発生した問題、不具合番号、暫定対応
- 移行または切り戻しの承認者
- 利用者通知の時刻と内容

