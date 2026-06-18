# H2バックアップ・復元検証

稼働中DBの `.mv.db` ファイルは直接コピーしません。H2の `SCRIPT` コマンドで論理バックアップを取得します。

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\backup-h2.ps1
```

バックアップは既定で `backups\shiftapp-yyyyMMdd-HHmmss.sql` に作成されます。途中で失敗した `.partial.sql` ファイルは削除され、完成したファイルだけが公開されます。

復元検証では、空の専用ディレクトリを指定します。本番DBと同じ場所、既存DB、空でないディレクトリは拒否されます。

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\restore-h2.ps1 `
  -BackupFile .\backups\shiftapp-20260618-120000.sql `
  -RestoreDataDir .\restore-check\20260618
```

復元には `RUNSCRIPT` を使用し、完了後にテーブル数と利用者数を読み取って検証します。確認が終わった復元先は、本番DBと取り違えないようディレクトリ単位で管理してください。
