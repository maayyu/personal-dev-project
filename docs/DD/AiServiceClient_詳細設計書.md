# Phase 6 AiServiceClient 詳細設計書 v2

## 1. `AiServiceClient`の責務

`AiServiceClient`は、Spring Bootから外部AI Serviceを呼び出すためのHTTP通信窓口を担当する。

| 責務 | 担当 |
|---|---|
| AI ServiceへのHTTPリクエスト送信 | `AiServiceClient` |
| AI ServiceからのHTTPレスポンス受信 | `AiServiceClient` |
| 通信エラーの検知 | `AiServiceClient` |
| 通信エラーのAI Service通信用例外への変換 | `AiServiceClient` |
| AI結果の業務的な検証・解釈 | `AiResultService` |
| organizationの作成・検証 | Organization機能のService |
| `AI_SUGGESTED` / `CURRENT`の保存 | `MemoOrganizationService` |
| 未判断再整理結果 `proposed_result` の保存 | `AiProcessService` |
| `ai_processes`の状態更新 | `AiProcessService`（上位ユースケース層から依頼） |

`AiServiceClient`は外部AI Serviceとの通信境界に責務を限定し、AI結果の意味解釈やDB更新などの業務処理を持たない。

### 1.1 対象操作

- 初回AI整理APIの呼び出し
- AI再整理APIの呼び出し
- 接続・レスポンス待ちのタイムアウト検知
- 4xx / 5xxレスポンスの検知
- レスポンス変換失敗の検知
- 通信エラーの専用例外への変換

### 1.2 対象外

- AI結果の業務的な妥当性確認
- AI結果に含まれるorganizationの存在・所有者確認
- organizationの作成
- `AI_SUGGESTED` / `CURRENT` / `ai_processes.proposed_result`の登録・更新
- `ai_processes`の`FAILED`更新
- ユーザー向けエラー文言の決定
- 自動リトライ
- エラーの最終ログ出力

---

## 2. パッケージ構成

現時点では、`AiServiceClient`に直接関係する構成を次のようにする。

```text
com.github.maayyu.personaldevproject.ai
│
├─ client/
│  └─ AiServiceClient
│
├─ service/
│  └─ AiResultService
│
├─ dto/
│  ├─ 初回整理Request DTO
│  ├─ 初回整理Response DTO
│  ├─ 再整理Request DTO
│  └─ 再整理Response DTO
│
└─ exception/
   ├─ AiServiceException
   ├─ AiServiceConnectionException
   ├─ AiServiceTimeoutException
   ├─ AiServiceClientException
   ├─ AiServiceServerException
   └─ AiServiceResponseException
```

### 補足

- Request / Response DTOの具体的なクラス名・項目はAI Service側API詳細と合わせて後続設計で確定する。
- タイムアウト値を保持する設定クラスの有無・名称は、Spring BootでのHTTPクライアント実装方式決定時に確定する。
- `AiServiceClient`と`AiResultService`は責務を分離し、通信処理と業務処理を混在させない。
- `AiServiceClient`はMVPでは具象クラスとして実装し、interface化しない。複数のAI Service実装や差し替え要件が生じた場合にinterface化を検討する。

---

## 3. `AiServiceClient`

### 3.1 責務

- AI Serviceの対象APIを選択して呼び出す。
- Request DTOをHTTPリクエストとして送信する。
- HTTPレスポンスをResponse DTOへ変換して返す。
- 内部APIキー方式によるSpring Boot ⇔ AI Service間の認証情報を通信時に付与する。
- 通信上の失敗を検知し、原因種別に応じた専用例外へ変換する。
- 接続タイムアウトとレスポンス待ちタイムアウトを適用する。

### 3.2 `AiServiceClient`に置かない処理

- AI結果の内容が業務要件を満たすかの検証
- AI結果から必要なorganizationを判断する処理
- organizationの新規作成
- `memo_organizations`の登録・更新
- `ai_processes`の状態遷移
- ユーザー向け表示内容の決定
- 失敗時の自動リトライ
- `ERROR`レベルでの最終的な障害ログ記録

---

## 4. 公開メソッド

初回整理と再整理では呼び出すAI APIおよび用途が異なるため、公開メソッドを用途別に分ける。

```java
InitialOrganizationResponse requestInitialOrganization(
    InitialOrganizationRequest request
);

ReorganizationResponse requestReorganization(
    ReorganizationRequest request
);
```

※上記Request / Response DTO名は設計上の仮称。AI Service側API詳細と合わせて最終確定する。

### 4.1 用途別メソッドとする理由

- 呼び出し側から処理目的を判別しやすくする。
- 初回整理と再整理でRequest / Response形式が異なる場合に型で区別できる。
- 将来的にAI APIが増えた場合でも、1つの汎用`request()`内に処理種別分岐が集中することを防ぐ。
- 初回整理結果は`AiResultService#applyInitialResult`、再整理結果は`AiResultService#saveReorganizationProposal`へつながるため、通信入口から後続処理まで用途を対応させやすい。

### 4.2 内部共通化

HTTP送信、認証ヘッダー付与、レスポンス変換、例外変換など、両メソッドで共通する処理は必要に応じてprivateメソッドへ共通化する。

具体的なprivateメソッド構成はHTTPクライアント実装方式を確定した後に決定する。

---

## 5. 正常時の処理フロー

### 5.1 初回整理

```text
上位ユースケース層
（開始トランザクションでmemo悲観ロック下に取得したcontent snapshotを使用し、RequestでCURRENT／organization構造等の整合性が必要な場合も開始トランザクション内で取得した値から構築）
↓
AiServiceClient#requestInitialOrganization
↓
内部APIキー等の通信情報を設定
↓
AI Service 初回整理APIへHTTPリクエスト
↓
HTTPレスポンス受信
↓
Response DTOへ変換
↓
上位ユースケース層へ返却
↓
AiResultService#applyInitialResult
```

`AiServiceClient`はResponse DTOを返却した時点で責務を終了する。

### 5.2 再整理

```text
上位ユースケース層
（開始トランザクションでmemo悲観ロック下に取得したcontent snapshotを使用し、RequestでCURRENT／organization構造等の整合性が必要な場合も開始トランザクション内で取得した値から構築）
↓
AiServiceClient#requestReorganization
↓
内部APIキー等の通信情報を設定
↓
AI Service 再整理APIへHTTPリクエスト
↓
HTTPレスポンス受信
↓
Response DTOへ変換
↓
上位ユースケース層へ返却
↓
AiResultService#saveReorganizationProposal
```

---

## 6. エラー処理方針

AI Serviceとの通信に失敗した場合、`AiServiceClient`は失敗原因を検知し、AI Service通信用の専用例外へ変換して上位Serviceへ通知する。

`AiServiceClient`内では、`ai_processes`の更新やユーザー向け処理などの業務処理を行わない。

### 6.1 例外の継承方針

AI Service通信用例外の共通親クラスとして`AiServiceException`を設ける。

```java
AiServiceException extends RuntimeException
├─ AiServiceConnectionException
├─ AiServiceTimeoutException
├─ AiServiceClientException
├─ AiServiceServerException
└─ AiServiceResponseException
```

- `AiServiceException`は`RuntimeException`を継承する非チェック例外とする。
- 5種類の通信例外はすべて`AiServiceException`を継承する。
- 上位ユースケース層では、MVPの共通失敗処理について`AiServiceException`を一括catchすることを基本とする。
- `AiServiceException` へ変換しない想定外のRuntimeException等も上位ユースケース層へ伝播させ、対象processがまだ `PROCESSING` なら本体処理のROLLBACK後に条件付き `markFailed(...)` を試行する。
- 将来、例外種別ごとに処理を変更する必要が生じた場合のみ、個別例外型でのcatchを検討する。

この構成により、`AiServiceClient`内では原因把握のために例外を分類しつつ、上位Serviceでは「AI Service通信系エラー」として共通処理できる。

### 6.2 例外分類

| 例外 | 対象 |
|---|---|
| `AiServiceConnectionException` | AI Serviceへ接続できない |
| `AiServiceTimeoutException` | 接続またはレスポンス待ちがタイムアウトした |
| `AiServiceClientException` | AI Serviceが4xxレスポンスを返した |
| `AiServiceServerException` | AI Serviceが5xxレスポンスを返した |
| `AiServiceResponseException` | JSON変換失敗等、受信したレスポンスを正常に処理できない |

### 6.3 分類方針

- HTTPステータスごとの個別例外までは作成しない。
- 4xxと5xxは障害原因の切り分けと将来拡張のため分離する。
- 4xxを「必ずSpring Boot側の不具合」とは断定しない。
- 例外分類の主目的は、開発・保守時の原因把握とログ調査を容易にすることである。
- MVPでは例外種別ごとにユーザー向け業務処理を変更しない。
- Request DTOのシリアライズ失敗など、HTTP通信開始前にSpring Boot内部で発生する予期しないプログラム例外は、5種類のAI Service通信例外へ無理に変換しない。通信エラーとは別のプログラム上の不具合として、原則そのまま上位へ伝播させる。

---

## 7. 例外に保持する情報

専用例外には、原因調査に必要な情報のみを保持する。

### 7.1 保持対象

必要に応じて次を保持する。

- HTTPステータス
- エラー種別
- AI Service側のエラーコード（定型enum・定型コード等、業務データや自由記述本文を含まない安全なコードに限る）
- 元例外（cause）
- タイムアウト種別（接続 / レスポンス待ち）

### 7.2 原則として保持しない情報

- メモ本文
- AI Serviceへのリクエスト全文
- AI Serviceからのレスポンス全文
- その他、ユーザーの業務データを含む可能性がある情報

原因調査に必要な情報を確保しつつ、不要な業務データが例外やログへ残ることを防ぐ。

---

## 8. タイムアウト設計

接続とAI処理結果待ちでは性質が異なるため、タイムアウトを分ける。

| 設定 | 意味 |
|---|---|
| 接続タイムアウト | AI Serviceとの接続確立を待つ上限 |
| レスポンス待ちタイムアウト | 接続後、AI Serviceからの応答を待つ上限 |

### 8.1 設定管理

タイムアウト値はJavaコードへ直接埋め込まず、外部設定で管理する。

設定イメージ：

```yaml
ai:
  service:
    connect-timeout: ...
    read-timeout: ...
```

### 8.2 MVP設定値

- 接続タイムアウト：5秒
- レスポンス待ちタイムアウト：60秒

具体値は外部設定として保持し、実測結果に応じて変更可能とする。HTTPクライアント実装方式が変わっても、この2種類を分離する方針は維持する。

---

## 9. 自動リトライ

MVPではAI処理の自動リトライを実装しない既存方針に従い、`AiServiceClient`でも自動リトライを行わない。

```text
AI Serviceへ1回リクエスト
↓
成功
├─ Yes → Response DTOを返却
└─ No  → 対応する専用例外へ変換してthrow
```

将来自動リトライを導入する場合は、現在の例外分類を利用して、接続失敗・タイムアウト・5xxなど対象を限定することを検討する。

MVPでは実装しない。

---

## 10. ログ出力方針

### 10.1 エラーログ

`AiServiceClient`では原則としてエラーの最終ログ出力を行わない。

```text
AiServiceClient
↓
通信失敗を検知
↓
専用例外へ変換
↓
上位Serviceへthrow
↓
上位Service
├─ ai_processesをFAILEDへ更新
└─ 業務上の文脈を含めてエラーログ出力
```

この方針により、同じ障害を複数レイヤーで重複して`ERROR`ログへ出力することを防ぐ。

### 10.2 DEBUGログ

必要に応じて、次のような通信状況を`DEBUG`レベルで記録する余地を残す。

- AI Service呼び出し開始
- AI Serviceからのレスポンス受信
- 呼び出したAPI種別
- 必要に応じたAPIパス

APIキー、メモ本文、Request / Response全文、URL全体や環境依存の接続情報を無条件にログ出力しない。

---

## 11. 上位Serviceとの連携

### 11.1 正常時

```text
AiServiceClient
↓ Response DTO
上位Service
↓
AiResultService
↓
AI結果の検証・解釈
↓
各機能Serviceへ処理依頼
```

### 11.2 異常時

MVPでは例外種別にかかわらず、上位Serviceでの業務結果を共通化する。

```text
AiServiceConnectionException
AiServiceTimeoutException
AiServiceClientException
AiServiceServerException
AiServiceResponseException
        ↓
上位Service
        ↓
ai_processes.status = FAILED
        ↓
共通のユーザー向け失敗状態
```

### 11.3 方針

- `AiServiceClient`では原因把握のために例外を分類する。
- MVPではユーザーが取る行動に大きな差がないため、ユーザー向け表示は共通とする。
- 将来、例外種別ごとに自動リトライやユーザー表示を変更する要件が生じた場合、現在の分類を利用して拡張する。

---

## 12. AI Service認証

Phase 4で確定したSpring Boot ⇔ AI Service間の内部APIキー方式を使用する。

### 12.1 `AiServiceClient`の責務

- AI Serviceへの通信時に必要な内部APIキーを付与する。
- APIキーの取得元・設定方法は外部設定として扱い、通信時に必要な認証情報だけを付与する。

### 12.2 後続設計への申し送り

次はAI Service側API・共通セキュリティ詳細設計と合わせて確定する。

- APIキーの設定名
- HTTPヘッダー名
- 環境変数等からの受け渡し方法
- APIキー未設定時の起動・通信時エラー方針
- APIキーをログへ出力しないための具体策

---

## 13. セキュリティ

- 内部APIキーをソースコードへハードコードしない。
- 内部APIキーをログへ出力しない。
- メモ本文やAI Request / Response全文をエラーログへ無条件に出力しない。
- AI Serviceから返却されたエラー本文をそのままユーザーへ返さない。
- 上位Serviceへ渡す例外には原因調査に必要な情報のみを保持する。
- AI Service側が返す内容を業務的に信頼せず、正常レスポンスの内容検証は`AiResultService`側で行う。
- AI Serviceが返すorganization名称・親子関係をDB IDとして信頼せず、既存organization IDへの解決はSpring Boot側で行う。

---

## 14. 単体テスト観点

### 14.1 正常系

- 初回整理APIへ正しいRequestを送信し、Response DTOを返せる。
- 再整理APIへ正しいRequestを送信し、Response DTOを返せる。
- 初回整理と再整理で対応するAI APIが正しく呼び分けられる。
- 必要なAI Service認証情報が通信時に付与される。
- 開始トランザクションで取得したcontent snapshotがRequestに使用され、通信直前にmemo本文を再取得しない。

### 14.2 通信エラー

- AI Serviceへ接続できない場合、`AiServiceConnectionException`へ変換する。
- 接続タイムアウト時、`AiServiceTimeoutException`へ変換する。
- レスポンス待ちタイムアウト時、`AiServiceTimeoutException`へ変換する。
- 外部設定から接続5秒・レスポンス待ち60秒を適用できる。
- 4xx時、`AiServiceClientException`へ変換する。
- 5xx時、`AiServiceServerException`へ変換する。
- レスポンス変換失敗時、`AiServiceResponseException`へ変換する。

### 14.3 例外情報

- 必要なHTTPステータスや原因情報を保持できる。
- 元例外をcauseとして追跡できる。
- メモ本文等の不要な業務データを例外へ保持しない。
- 接続タイムアウト時にタイムアウト種別が`CONNECT`として設定される。
- レスポンス待ちタイムアウト時にタイムアウト種別が`READ`として設定される。

### 14.4 リトライ・ログ

- 通信失敗時に`AiServiceClient`自身が自動リトライしない。
- 通信失敗時に同一障害の最終`ERROR`ログを`AiServiceClient`で重複出力しない。
- DEBUGログを出力する場合も、APIキーやメモ本文を含めない。

---

## 15. 確定事項と後続設計への申し送り

### 15.1 本書で確定する事項

- `AiServiceClient`は外部AI ServiceとのHTTP通信窓口とする。
- `AiServiceClient`はMVPでは具象クラスとして実装し、interface化しない。複数実装・差し替え要件が生じた場合にinterface化を検討する。
- AI Service通信用例外の共通親として`AiServiceException`を設け、`RuntimeException`を継承する。
- 5種類の通信例外はすべて`AiServiceException`を継承し、上位Serviceの共通失敗処理では`AiServiceException`を一括catchすることを基本とする。
- AI結果の業務的検証・解釈は`AiResultService`等の上位処理へ分離する。
- 初回整理と再整理は用途別の公開メソッドに分ける。
- 通信エラーは次の5種類に分類する。
  - `AiServiceConnectionException`
  - `AiServiceTimeoutException`
  - `AiServiceClientException`
  - `AiServiceServerException`
  - `AiServiceResponseException`
- 4xxと5xxを分離するが、HTTPステータス単位までは例外を細分化しない。
- Request DTOのシリアライズ失敗等、HTTP通信開始前の予期しないプログラム例外はAI Service通信例外へ無理に変換せず、原則そのまま上位へ伝播させる。
- 例外には障害調査に必要な情報のみを保持する。
- メモ本文やRequest / Response全文を例外へ無条件に保持しない。
- 接続タイムアウトとレスポンス待ちタイムアウトを分ける。
- MVP初期値は接続5秒、レスポンス待ち60秒とする。
- タイムアウト値は外部設定で管理する。
- AI Serviceへ送るmemo本文は、AI処理開始トランザクションでmemo悲観ロック下に取得したcontent snapshotを使用し、commit後にmemoを再読しない。
- MVPでは自動リトライを行わない。
- `AiServiceClient`では原則としてエラーの最終ログ出力を行わない。
- MVPでは例外種別にかかわらず、上位Service側でAI処理を`FAILED`として扱い、ユーザー向け失敗表示も共通とする。
- Spring Boot ⇔ AI Service間は既存方針どおり内部APIキー方式を使用する。

### 15.2 AI Service API詳細設計への申し送り

- 初回整理APIのパス・HTTPメソッド
- 再整理APIのパス・HTTPメソッド
- Request / Response DTOの具体的なクラス名・項目
- AI Service側エラーResponseの形式
- AI Service側エラーコードの有無・仕様
- APIキーのHTTPヘッダー名

### 15.3 Spring Boot実装方式への申し送り

- `AiServiceException`以外の想定外プログラム例外も上位ユースケース層で捕捉対象とし、本体ROLLBACK後、対象processがまだ `PROCESSING` の場合に条件付き `markFailed(...)` を試行する。具体的なcatch構造はPhase 7で実装タスクとして確定する。
- AI Requestに含めるcontent以外のsnapshot項目（CURRENT／organization構造等）の具体フィールドはDTO設計と合わせてPhase 7で確定する。整合性が必要な入力は開始トランザクション内で読み取る。
- 使用するHTTPクライアント
- HTTPクライアント設定クラスの構成
- 接続5秒／レスポンス待ち60秒を含む外部設定値のバインド方法
- 共通HTTP処理をprivateメソッドへ切り出す具体構成

これらは本書で勝手に新規決定せず、AI Service側API詳細およびSpring Boot実装方式と合わせて後続で確定する。