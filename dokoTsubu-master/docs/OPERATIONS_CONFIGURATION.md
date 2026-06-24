# シフト・有休管理 バックアップ・監視・アラート・ログ保管設定

## 運用目標

承認済み方針は、日次バックアップ、RPO 24時間、RTO 4時間、保守時間帯0:00〜5:00である。

| 項目 | 設定 |
|---|---|
| H2バックアップ | 毎日1:00、H2 `SCRIPT`、DBとは別のアクセス制限領域 |
| ヘルス監視 | 5分間隔、HTTP・DBファイル・ログ更新状態 |
| アラート | 失敗時に非機密JSONを専用領域へ作成し、タスク失敗として記録 |
| ログ | 30日間はTomcat領域、以後ZIP圧縮、365日経過後に削除 |
| 復元試験 | リリース前後および定期運用で空の別DBへ復元 |

監視JSONにはURL、発生日時、重大度、一般メッセージだけを記録する。ログ本文、位置情報、有休理由、パスワード、トークン、SMTP秘密情報は含めない。

## 設定前確認

1. 本番Tomcat、DB、バックアップ、アラート、ログアーカイブの絶対パスを確定する。
2. タスク実行アカウントへ必要最小限の読取・書込権限を付与する。
3. バックアップとアーカイブをDBと別の障害領域へ配置する。
4. ディスク容量監視、タスク失敗通知、アラートJSONの取込み先を運用監視製品側で設定する。
5. 秘密値をスクリプト引数、タスク名、説明、ログへ含めない。

## タスク定義の確認

最初は`-Apply`なしで実行する。システム状態は変更されず、登録予定の3タスクがJSON表示される。

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\register-operations-tasks.ps1 `
  -ProjectPath C:\ShiftFlow\app `
  -TomcatBase C:\ShiftFlow\production\tomcat `
  -BackupDir D:\ShiftFlowBackups\daily `
  -AlertDir D:\ShiftFlowAlerts `
  -Url https://shiftflow.example/shiftflow/
```

作業者と確認者がパス・時刻を照合後、承認済みの管理者セッションで`-Apply`を追加する。タスクスケジューラ上で実行アカウントと「ユーザーがログオンしているかどうかにかかわらず実行」を組織基準に従って設定する。

## 個別確認

### バックアップ

[H2バックアップ・復元検証](../ops/BACKUP.md)に従い、SCRIPTファイルが作成され、空の別DBへ復元できることを確認する。稼働DBファイルをコピーしない。

### 監視とアラート

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\monitor-health.ps1 `
  -Url https://shiftflow.example/shiftflow/ `
  -TomcatBase C:\ShiftFlow\production\tomcat `
  -AlertDir D:\ShiftFlowAlerts
```

正常時はヘルスJSONと終了コード0、異常時はアラートJSONと終了コード1になる。監視製品はタスク終了コードまたはアラートディレクトリを監視し、P1連絡先へ通知する。

### ログ保管

最初は`-Apply`なしで対象件数を確認する。

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\archive-logs.ps1 `
  -TomcatBase C:\ShiftFlow\production\tomcat `
  -ArchiveDir D:\ShiftFlowLogArchive
```

確認後に`-Apply`を付ける。30日超のTomcatログを1ファイルずつZIP化し、ZIPが作成されたことを確認してから元ファイルを削除する。365日超のZIPだけを削除する。監査ログはDBの5年保存処理で管理し、このスクリプトの対象外である。

## 運用確認表

| 頻度 | 確認内容 |
|---|---|
| 5分 | HTTP監視、アラート有無 |
| 毎日 | バックアップ作成・サイズ、タスク結果、ディスク空き容量 |
| 毎週 | FAILEDメール、Tomcat重大ログ、アラート対応漏れ |
| 毎月 | ログ圧縮、バックアップ世代、復元試験予定 |
| リリース前後 | SCRIPTバックアップと別DB復元、RTO計測 |

障害時は [運用ランブック](OPERATIONS_RUNBOOK.md)、切り戻しは [本番移行・切り戻し](MIGRATION_ROLLBACK.md) に従う。

