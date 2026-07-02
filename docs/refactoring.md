# リファクタリング報告書

このドキュメントでは、シフト・勤怠管理アプリの開発中に行ったリファクタリング内容をまとめています。

今回の対応では、不要なファイルの削除、重複した処理の整理、役割ごとのクラス分割などを行い、今後の機能追加や保守がしやすい状態を目指しました。

---

## 第1段階：使っていないコードの整理

まず、現在のシフト・勤怠管理アプリでは使っていない、旧つぶやきアプリ（dokoTsubu）関連のファイルや不要なコードを整理しました。

不要なファイルを残したままにしておくと、どのコードが実際に使われているのか分かりにくくなります。そのため、現在のアプリに関係のないファイルを削除し、プロジェクト全体を見通しやすくしました。

### 1. 未使用ファイルの削除

以下のファイルをプロジェクトから削除しました。

- **DAO**: `dao/MuttersDAO.java`
- **モデル（処理やデータを扱うクラス）**:
  - `model/GetMutterListLogic.java`
  - `model/LoginLogic.java`
  - `model/Mutter.java`
  - `model/PostMutterLogic.java`
- **コントローラー（画面からの操作を受け取るクラス）**:
  - `servlet/Login.java`
  - `servlet/Main.java`
  - `servlet/Logout.java`
- **ビュー（画面表示用のJSPファイル）**:
  - `WEB-INF/jsp/main.jsp`
  - `WEB-INF/jsp/loginResult.jsp`
  - `WEB-INF/jsp/logout.jsp`

### 2. [User.java](../dokoTsubu-master/src/main/java/model/User.java) の不要コード削除

削除した旧ログイン処理からしか使われていなかった、以下のコードを削除しました。

- `pass` フィールド
- `User(String, String)` コンストラクタ
- `getPass()` メソッド

これにより、現在のログイン処理に必要な情報だけを `User.java` に残す形に整理しました。

---

## 第2段階：重複していた処理の整理

次に、複数の場所に同じような処理が書かれていた部分を整理し、共通で使えるクラスにまとめました。

同じ処理がいろいろな場所にあると、修正が必要になったときに変更漏れが起きやすくなります。共通化することで、修正箇所を減らし、保守しやすい構成にしました。

### 1. 共通ユーティリティクラスの追加

- **[DateUtil.java](../dokoTsubu-master/src/main/java/util/DateUtil.java)**:
  - 日付・日時・時刻の変換処理をまとめました。
  - 複数のサービスで同じように書かれていた日付処理を、1か所から使えるようにしました。
- **[ServletUtil.java](../dokoTsubu-master/src/main/java/util/ServletUtil.java)**:
  - 画面操作を受け取るサーブレットで共通して使う処理をまとめました。
  - `baseUrl` や `isLocal` など、複数のクラスで重複していた処理を整理しました。

### 2. 重複処理の削除と共通クラスの利用

- **`PortalService.java`（削除済みの製品コード）**:
  - 独自に持っていた日付変換メソッドを削除し、`util.DateUtil` を使う形に変更しました。
- **[LeavePolicyService.java](../dokoTsubu-master/src/main/java/service/LeavePolicyService.java)**:
  - 重複していた `toDate` メソッドを削除し、`util.DateUtil.toDate` を使う形に変更しました。
  - この処理は、実装済みの有休機能を保守・拡張しやすくするためのものです。
- **[PortalServlet.java](../dokoTsubu-master/src/main/java/servlet/PortalServlet.java)**:
  - 重複していた `baseUrl` メソッドを削除し、`util.ServletUtil.baseUrl` を使う形に変更しました。
- **[AuthServlet.java](../dokoTsubu-master/src/main/java/servlet/AuthServlet.java)**:
  - 重複していた `baseUrl` と `isLocal` メソッドを削除し、`util.ServletUtil` を使う形に変更しました。
- **[ExportService.java](../dokoTsubu-master/src/main/java/service/ExportService.java)**:
  - 独自に持っていたHTMLエスケープ処理を削除し、共通クラス `util.HtmlEscaper.escape` を使う形に統一しました。

---

## 第3段階：名前の分かりやすさの改善

コードを読んだときに、変数やメソッドの役割がすぐ分かるように、あいまいな名前をより具体的な名前に変更しました。

名前が分かりやすいと、後からコードを読む人が処理内容を理解しやすくなり、修正時のミスも減らせます。

### 1. あいまいなメソッド名の変更

- **`PortalService.java`（削除済みの製品コード）**:
  - `users(User)` メソッドを、従業員を取得する処理だと分かる **`findEmployees(User)`** に変更しました。
  - `master(String)` メソッドを、マスタデータを取得する処理だと分かる **`getMasterData(String)`** に変更しました。
- **[PortalServlet.java](../dokoTsubu-master/src/main/java/servlet/PortalServlet.java)**:
  - 上記の名前変更に合わせて、呼び出し側の処理も修正しました。
- **テストコード ([SmokeTest.java](../dokoTsubu-master/src/test/java/SmokeTest.java), [PerformanceTest.java](../dokoTsubu-master/src/test/java/service/PerformanceTest.java))**:
  - テスト内で使っていた古いメソッド名も、新しい名前に合わせて修正しました。

### 2. 初期データ登録用メソッド名の改善

- **[Database.java](../dokoTsubu-master/src/main/java/config/Database.java)**:
  - `setting` を **`insertSetting`** に変更しました。
  - `workType` を **`insertWorkType`** に変更しました。

これにより、「設定情報を登録する処理」「勤務区分を登録する処理」であることが、名前から分かりやすくなりました。

---

## 第4段階：PortalService.java の役割分担

以前の `PortalService.java` には、多くの機能の処理が1つのクラスに集まっていました。ファイルサイズも約74KBあり、シフト、勤怠、従業員、通知など、さまざまな処理が混在している状態でした。

このままだと、修正したい処理を探しにくく、機能追加時の影響範囲も分かりにくくなります。そこで、機能ごとにサービスクラスを分け、役割を整理しました。

なお、既存のサーブレットやテストコードへの影響を抑えるため、最初の段階では `PortalService` を中継役として残し、新しく作ったサービスへ処理を渡す形にしました。

### 1. 機能ごとのサービスクラスを作成

以下の新しいサービスクラスを `service` パッケージに作成し、`PortalService` に集まっていた処理を移しました。

- **[ShiftService.java](../dokoTsubu-master/src/main/java/service/ShiftService.java)**:
  - シフト作成、希望シフト提出、自動割り当て、シフト変更申請、シフト警告に関する処理
- **[LeaveService.java](../dokoTsubu-master/src/main/java/service/LeaveService.java)**:
  - 実装済みの有休申請・承認・残数管理などに関する処理
- **[AttendanceService.java](../dokoTsubu-master/src/main/java/service/AttendanceService.java)**:
  - 出退勤打刻、勤怠実績、月次確定、打刻修正申請に関する処理
- **[EmployeeService.java](../dokoTsubu-master/src/main/java/service/EmployeeService.java)**:
  - 従業員一覧、追加、更新、招待、ログイン情報、代理設定に関する処理
- **[MasterDataService.java](../dokoTsubu-master/src/main/java/service/MasterDataService.java)**:
  - 店舗、部署、勤務区分などのマスタデータの追加・更新処理
- **[NotificationService.java](../dokoTsubu-master/src/main/java/service/NotificationService.java)**:
  - システム内通知、メール送信キュー登録、エラーメール再送に関する処理
- **[DashboardService.java](../dokoTsubu-master/src/main/java/service/DashboardService.java)**:
  - ダッシュボード表示用の集計や、残業時間グラフ用データの取得処理
- **[AuditLogService.java](../dokoTsubu-master/src/main/java/service/AuditLogService.java)**:
  - 操作履歴の検索や、操作種別一覧の取得処理

### 2. PortalService.java を中継役として整理

`PortalService` に直接書かれていた処理を削除し、新しく作成したサービスクラスへ処理を渡す形に変更しました。

その結果、`PortalService.java` は元の1026行（約74KB）から280行まで短くなりました。

また、テストコードから参照されていた一部のメソッドについても、新しいサービスへ適切に処理を渡せるように調整しました。

---

## 第5段階：自動割り当て・申請・希望シフト提出処理の整理

シフトの自動割り当て、各種申請、希望シフト提出まわりの処理は、条件チェックが多く、複雑になりやすい部分でした。

そこで、1つの処理の中にまとまっていた条件判定を、役割ごとに小さなメソッドへ分けました。これにより、処理の流れが読みやすくなり、今後の修正や機能追加もしやすくなりました。

### 1. 自動割り当て判定ロジックの整理

- **[ShiftService.java](../dokoTsubu-master/src/main/java/service/ShiftService.java)**:
  - `canAutoAssign` メソッドに書かれていた複数の条件判定を、以下の分かりやすい名前のメソッドに分けました。
    - `isShiftAlreadyAssigned`
    - `wasOnNightShiftYesterday`
    - `hasPreferredOffOrLeave`
    - `exceedsMaxConsecutiveWorkDays`

これにより、自動割り当ての判定で何を確認しているのかが分かりやすくなりました。

### 2. 申請関連のチェック処理の整理

- **[LeaveService.java](../dokoTsubu-master/src/main/java/service/LeaveService.java)**:
  - 実装済みの有休申請機能を保守しやすくするため、`requestLeave` メソッド内に直接書かれていたチェック処理を整理しました。
  - 過去日チェック、事前申請期限、有休残日数、時間単位の上限などの確認を、以下のメソッドに分けました。
    - `validateLeaveRequestDate`
    - `validateLeaveBalance`

これにより、今後有休機能を拡張するときにも、どこを修正すればよいか分かりやすくなりました。

### 3. 希望シフト提出時のチェック処理の整理

- **[ShiftService.java](../dokoTsubu-master/src/main/java/service/ShiftService.java)**:
  - `submitMonthlyPreferences` の中で、ループ処理と一緒に書かれていた対象月チェックや文字数制限などの確認処理を、独立したメソッドに分けました。
    - `validatePreferenceEntry`

これにより、データを更新する処理と、入力内容が正しいかを確認する処理を分けて管理できるようになりました。

---

## 第6段階：PortalService.java の削除

最後に、サーブレットから `PortalService` への依存をなくし、機能ごとに分けたサービスクラスを直接呼び出す構成に変更しました。

これにより、製品コードとしての `PortalService.java` は不要になったため削除しました。1つのクラスに多くの役割が集中していた状態を解消し、より自然な設計に近づけています。

### 1. サーブレット側の修正

- **[PortalServlet.java](../dokoTsubu-master/src/main/java/servlet/PortalServlet.java)**:
  - `PortalService` を使う形をやめ、`ShiftService` や `AttendanceService` など、機能ごとのサービスを直接使う形に変更しました。
  - `doGet` や `doPost` の各処理についても、内容に応じたサービスへ直接処理を渡すように修正しました。

### 2. テストコードへの影響を抑える対応

- **[PortalService.java（テスト用）](../dokoTsubu-master/src/test/java/service/PortalService.java)**:
  - 既存のテストコードには、`PortalService` を直接使っているものが多数ありました。
  - それらを一度に大きく書き換えると、テスト側の修正ミスが起きる可能性があります。
  - そのため、テスト用の `PortalService` を用意し、既存テストへの影響を最小限に抑えました。

これにより、製品コードからは `PortalService` を削除しつつ、既存のテストも引き続き確認できる形にしています。

### 3. 製品コードからの PortalService.java 削除

製品コード側の `src/main/java/service/PortalService.java` をプロジェクトから削除しました。

これにより、多くの役割を1つのクラスに集めていた状態を解消し、機能ごとに役割が分かれた構成になりました。

---

## 検証結果

### 自動テスト

各リファクタリングを行った後、既存の自動テストを実行し、コンパイルエラーや動作不具合が起きていないことを確認しました。

- 実行コマンド:

  ```powershell
  cd dokoTsubu-master
  powershell -ExecutionPolicy Bypass -File .\test.ps1
  ```

- **結果**: ビルドとすべてのテストが正常に完了しました。

第1段階から第6段階までのリファクタリングを行った後も、アプリの動作に問題がないことを確認しています。サーブレットから機能ごとのサービスを直接呼び出す構成へ変更し、製品コードから `PortalService.java` を削除した状態でも、既存のテストはすべて成功しました。
