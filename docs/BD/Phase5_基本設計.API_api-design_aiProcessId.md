# Phase 5 API基本設計

## 1. 文書の目的

本書は、「放り込むだけAI整理ツール」のMVPで画面からSpring Bootへ要求する外部APIと、Spring BootからAI Serviceへ要求する内部APIの基本仕様を定義する。

Request／Responseのクラス名、厳密なJSON Schema、タイムアウト値などはPhase 6で具体化する。

## 2. 共通方針

- 外部APIはJSONを基本とする。
- 利用者認証はSession＋Cookieを使用する。
- ログインユーザーが所有するメモ・organizationだけを操作できる。
- 入力不正、未認証、権限外、競合、対象なし、サーバー障害を区別する。
- AI処理の成否をメモ保存APIの成否と分離する。
- AI ServiceはDBを直接更新せず、整理候補だけをSpring Bootへ返す。
- Spring BootとAI Service間は内部APIキーで認証する。
- MVPの検索はDB検索による部分一致を基本とし、意味検索・ベクトル検索・RAGは行わない。

## 3. HTTPステータスの基本ルール

| ステータス | 用途 |
|---:|---|
| `200 OK` | 取得・更新・検索等の成功 |
| `201 Created` | リソース作成成功 |
| `204 No Content` | 削除成功など、本文を返さない成功 |
| `400 Bad Request` | 形式不正・入力値不正 |
| `401 Unauthorized` | 未認証 |
| `403 Forbidden` | 認証済みだが操作権限なし |
| `404 Not Found` | 対象リソースが存在しない |
| `409 Conflict` | 判断対象不一致、多重実行、ロックタイムアウト、同名競合などの競合 |
| `500 Internal Server Error` | Spring Boot内の予期しない障害 |
| `502 Bad Gateway` | AI Service等の連携先障害を外部へ表現する場合 |
| `504 Gateway Timeout` | AI Serviceのタイムアウトを外部へ表現する場合 |

実際に外部へ返すエラー形式と、内部障害をどこまで公開するかはPhase 6で統一する。

## 4. API一覧

### 4.1 メモAPI

| メソッド | パス | 概要 |
|---|---|---|
| `POST` | `/memos` | メモを保存し、初回AI整理を非同期で依頼する |
| `GET` | `/memos/{id}` | メモ詳細を取得する |
| `PATCH` | `/memos/{id}` | タイトルまたは原文を変更する |
| `DELETE` | `/memos/{id}` | メモを削除する |
| `POST` | `/memos/{id}/reorganize` | AI再整理を開始する |
| `POST` | `/memos/{id}/organize` | `CURRENT`なしで有効なINITIAL `PROCESSING`がなければ、初回整理を開始／再試行する（`process_type = INITIAL`。FAILED履歴は不要） |
| `GET` | `/memos/search?q={keyword}` | タイトル・原文・`CURRENT`のorganization名を部分一致で検索する |

### 4.2 メモ整理先API

| メソッド | パス | 概要 |
|---|---|---|
| `GET` | `/memos/{id}/organizations` | `CURRENT`、`AI_SUGGESTED`、未判断再整理提案配列、判断対象、AI処理状態を取得する |
| `PUT` | `/memos/{id}/organizations` | ユーザーが選択した整理先で`CURRENT`を置換する。提案を変更して使う場合は表示済み`aiProcessId`を指定する |
| `POST` | `/memos/{id}/organizations/accept` | 表示済み`aiProcessId`の未判断提案を、その時点の判断対象と照合して採用する |
| `DELETE` | Phase 6で確定 | 表示済み`aiProcessId`の未判断提案を破棄する（「使わない」専用API） |

初回整理再試行APIは `/memos/{id}/organize` とする。「使わない」専用APIの具体的URIは後続API詳細設計で確定するが、Requestでは判断対象照合用の`aiProcessId`を受け取る。

### 4.3 organization管理API

整理構造編集画面から次の操作を提供する。

- ツリー取得
- organization作成
- 名前変更
- 親organization変更（移動）
- organization削除

各操作の最終URIとRequest／Response形式は、元のPhase 5-2最終版との照合後に確定する。Phase 6で新規に仕様を追加するのではなく、画面設計に必要な責務を維持する。

## 5. メモAPI詳細

### 5.1 `POST /memos`

#### 目的

ユーザーの原文を確実に保存した後、初回AI整理を非同期で開始する。

#### Request概要

| 項目 | 必須 | ルール |
|---|---:|---|
| `content` | Yes | 1文字以上。スペース・改行だけは不可 |
| `title` | No | 未指定時は仮タイトルまたはAI生成タイトルを利用する方針 |

#### 処理概要

1. 入力を検証する。
2. ログインユーザーのメモとして保存する。
3. DBコミット後、初回AI整理を依頼する。
4. AI整理の完了を待たず、保存済みメモを返す。

#### 重要事項

- AI Service障害によってメモ保存を失敗扱いにしない。
- 非同期依頼の作成に失敗した場合も、保存済みメモを失わない。

### 5.2 `GET /memos/{id}`

メモ詳細画面に必要な次の情報を取得する。

- ID
- タイトル
- 原文
- 作成・更新日時
- 現在のAI処理状態
- 有効なAI処理中件数（残留判定時間を超えた`PROCESSING`は除外）
- 残留判定時間を超えた`PROCESSING`がある場合のタイムアウト扱い情報
- AI整理回数（成功・失敗・CANCELLEDを含む全`ai_processes`件数）
- 原文変更後に整理結果が古くなっているかを示す情報。CURRENTの `ai_process_id` が非NULLなら対応する `ai_processes.created_at`、NULLならCURRENT行の `created_at` を基準として `content_updated_at` と比較する。未判断REORGANIZE提案は対応する `ai_processes.created_at` を基準とする。`proposed_at` はFIFO専用でoutdated判定には使用しない

状態取得では、残留判定時間（初期値2分）を超えた`PROCESSING`をタイムアウト扱いとして返すが、GET処理ではDBを更新しない。

整理先・未判断提案の詳細は`GET /memos/{id}/organizations`から取得する。

### 5.3 `PATCH /memos/{id}`

#### 更新ルール

| 更新対象 | AI処理中 | 挙動 |
|---|---:|---|
| タイトル | 可 | memo悲観ロック下で通常更新する |
| 原文 | 可 | memo悲観ロック下で更新する。AI結果はMVPでは自動無効化しない |

memoロック待ちが設定値（初期値3秒）を超えた場合は`409 Conflict`（`LOCK_TIMEOUT`）とする。

原文更新後も既存の整理結果は削除しない。画面がoutdated状態を識別できる情報を返す。

### 5.4 `DELETE /memos/{id}`

- AI処理中でも削除できる。
- memo悲観ロック下で、未判断`proposed_result`を全件クリアしてからメモを削除する。
- 関連するメモ整理先を削除する。
- `ai_processes`履歴はMVPでは残し、`ON DELETE SET NULL`により対象メモIDをNULLにする。
- 削除後にAI結果が返ってきた場合は404にせず、結果を反映せず対象AI処理を`CANCELLED`とする。

### 5.5 `POST /memos/{id}/reorganize`

#### 実行可否

対象メモに`CURRENT`が存在する場合のみ実行できる。

開始時はmemo悲観ロック取得後、INITIAL / REORGANIZEの長時間残留`PROCESSING`を先に回復してから判定する。

実行条件：

- `CURRENT`が存在する
- 回復後にINITIALの`PROCESSING`が存在しない
- 回復後にREORGANIZEの`PROCESSING`が存在しない（同時PROCESSING最大1件）
- REORGANIZEのアクティブ総数が2件未満
  - 非タイムアウト`PROCESSING`
  - `COMPLETED AND proposed_result IS NOT NULL`
- タイムアウト扱いの`PROCESSING`はアクティブ件数に含めない

条件違反は`409 Conflict`とする。残留回復後もINITIAL `PROCESSING`が存在する場合は `PROCESS_STATE_CONFLICT`、REORGANIZE `PROCESSING`重複またはアクティブ上限超過は `ACTIVE_LIMIT_EXCEEDED` を使用する。

#### 処理概要

1. memo悲観ロックを取得し、対象メモ・所有者・CURRENTを検証する。
2. 残留`PROCESSING`を回復する。
3. 上記開始条件を確認する。
4. `process_type = REORGANIZE / status = PROCESSING` の新しいAI処理を登録する。
5. commit・ロック解放後、AI Serviceへ依頼する。
6. AI処理成功時に対象`ai_processes.proposed_result`を保存し、`proposed_at`を設定して`COMPLETED`とする。
7. 既存の未判断提案は消さず、最大2件まで保持する。
8. `CURRENT`は変更しない。
9. ユーザー確定前は`organizations`および`memo_organizations`を変更しない。

MVPではAI処理内部の自動リトライを行わない。

### 5.6 `POST /memos/{id}/organize`

#### 目的

`CURRENT`が存在しないメモに対して、初回AI整理を `INITIAL` として開始または再試行する。過去のFAILED履歴の有無は開始条件にしない。

#### 実行可否

開始時はmemo悲観ロック取得後、INITIAL / REORGANIZEの残留`PROCESSING`を先に回復する。

| 条件 | 実行 |
|---|---:|
| `CURRENT`なし、残留回復後INITIAL PROCESSINGなし | 可 |
| `CURRENT`あり | 不可（409 / `PROCESS_STATE_CONFLICT`） |
| 回復後もINITIAL PROCESSINGあり | 不可（409 / `PROCESS_STATE_CONFLICT`） |

#### 処理概要

1. 対象memo・所有者を確認する。
2. 残留`PROCESSING`を回復する。
3. `CURRENT`が存在せず、残留回復後に有効なINITIAL PROCESSINGが存在しないことを確認する。
4. `process_type = INITIAL / status = PROCESSING` の新しい`ai_processes`を作成する。
5. commit・ロック解放後にAI Serviceへ依頼する。
6. 結果反映時にCURRENT不存在を再確認する。
7. CURRENTがなければ`AI_SUGGESTED`と`CURRENT`を作成し`COMPLETED`とする。
8. AI処理中にユーザーがCURRENTを設定済みなら結果を反映せず`CANCELLED`とする。
9. memo削除済みなら404にせず結果を反映せず`CANCELLED`とする。

`REORGANIZE`として扱わず、`proposed_result`は使用しない。

### 5.7 `GET /memos/search?q={keyword}`

#### 目的

ログインユーザーが保存したメモを、MVP範囲の簡易キーワード検索で探せるようにする。

#### 検索対象

次のいずれかに検索キーワードが部分一致するメモを対象とする。

- `memos.title`
- `memos.content`
- メモに紐づく`CURRENT`のorganization名

AI整理結果のうち検索対象とするのは、現在正式に採用されている`CURRENT`だけとする。

次は検索対象外とする。

- `AI_SUGGESTED`
- 未確定の再整理提案（`ai_processes.proposed_result`）

#### 検索ルール

- 部分一致検索とする。
- 英字の大文字・小文字は区別しない。
- タイトル、原文、`CURRENT`のorganization名の複数箇所で一致しても、同一メモは検索結果に1件だけ返す。
- 他ユーザーのメモは検索対象に含めない。
- 検索結果が0件の場合も正常終了とし、`200 OK`と空配列を返す。
- Organizationによる追加絞り込み、日付絞り込みはMVPでは追加しない。
- 意味検索、ベクトル検索、RAG、検索専用エンジンはMVPでは使用しない。

#### 空入力

画面では、空文字またはスペースのみの場合は検索APIを呼び出さず、通常のHome表示を維持する。

APIへ直接空文字または空白のみの`q`が送信された場合の厳密なバリデーション・エラー形式はPhase 6で共通入力検証と合わせて確定する。

## 6. メモ整理先API詳細

### 6.1 `GET /memos/{id}/organizations`

Responseでは、少なくとも次の情報を区別して返す。

| 項目 | 内容 |
|---|---|
| `current` | 現在正式に採用している整理先。`memo_organizations`の`CURRENT`から取得 |
| `aiSuggested` | 初回AI整理の固定結果。`memo_organizations`の`AI_SUGGESTED`から取得 |
| `pendingProposals` | 未判断再整理提案の配列。`proposed_at ASC, id ASC`のFIFO順 |
| `decisionTargetAiProcessId` | 現在ユーザーが判断できる先頭提案のAI処理ID |
| `activeProcessingCount` | タイムアウト扱いを除外した有効な処理中件数 |
| `timedOutProcessing` | 残留判定時間を超えた`PROCESSING`が存在するか等、再試行表示に必要な情報 |

`pendingProposals`の各要素には `aiProcessId` を含める。先頭提案が現在の判断対象であることを画面側が識別できる情報を返す。

状態取得時のタイムアウト判定ではDB更新を行わない。

### 6.2 `PUT /memos/{id}/organizations`

#### 用途

- ユーザーによる通常の整理先変更
- 未判断AI提案を「変更して使う」

#### Request概要

| 項目 | 必須 | 説明 |
|---|---:|---|
| 整理先内容 | Yes | 新しい`CURRENT`として採用するorganization情報。新規organizationを含められる |
| 操作種別 | Yes | 通常変更か、AI提案を変更して使用する操作かを識別する |
| `aiProcessId` | 条件付き | 「変更して使う」の場合必須。画面に表示した判断対象ID |

#### 更新ルール

- 通常変更・「変更して使う」のどちらも、CURRENTを書き換えるため対象memoの悲観ロックを取得する。ロック待ち上限は3秒で、取得できない場合は `409 / LOCK_TIMEOUT`。
- 通常変更では、既存`CURRENT`を新しい選択内容へ置換する。未判断`proposed_result`は変更しない。
- 「変更して使う」では、`memoId`で絞り込んだ現在の判断対象とRequestの`aiProcessId`を照合する。
- 判断対象不一致は`409 Conflict`（`DECISION_TARGET_MISMATCH`）。
- 未判断提案が存在しない場合は`409 Conflict`（`NO_PENDING_PROPOSAL`）。
- 必要な新規organizationはユーザー確定時に初めて作成する。
- organization作成は親→子、同一階層は正規化名称昇順とする。
- `CURRENT`置換と対象`proposed_result`のNULL化を1トランザクションで行う。
- 検証・DB競合で409となった場合、提案は消費しない。

### 6.3 `POST /memos/{id}/organizations/accept`

#### Request概要

- `aiProcessId`：必須。画面に表示した現在の判断対象ID。

#### 更新ルール

1. `memoId`単位で現在の判断対象を取得し、Requestの`aiProcessId`と一致することを確認する。
2. 不一致は`409 Conflict`（`DECISION_TARGET_MISMATCH`）。
3. `proposed_result`内の既存organizationは `organizationId` で検証し、「存在」「ログインユーザー所有」「新規ノードの親として利用する場合は親が存在」を確認する。名前変更・親変更だけでは提案を無効化しない。
4. 参照先organizationが削除済み等で既存IDを解決できず、そのまま反映できない場合は `409 / PROPOSAL_STALE` とし、`proposed_result`は消費しない。
5. 必要なorganizationを既存再利用しつつ、親→子・同一階層は正規化名称昇順で作成する。
6. 既存`CURRENT`を削除する。
7. 提案内の整理先を`CURRENT`として登録する。
8. 対象`proposed_result`をNULLにする。

5〜8は同一トランザクションで実行する。ユーザー確定処理で競合した場合、AI処理自体を`FAILED`へ変更しない。

### 6.4 AI提案を使用しない専用API（URIは後続API詳細設計で確定）

#### Request概要

- `aiProcessId`：必須。画面に表示した現在の判断対象ID。

処理：

1. `memoId`単位で現在の判断対象と`aiProcessId`を照合する。
2. 対象`proposed_result`をNULLにする。
3. `CURRENT`は変更しない。
4. `AI_SUGGESTED`は変更しない。

却下処理では、提案内organizationが現在も有効かの妥当性検証を要求しない。

判断対象不一致は `DECISION_TARGET_MISMATCH`、未判断提案なしは `NO_PENDING_PROPOSAL` として409を返す。

## 7. organization管理APIの業務ルール

### 作成・名前変更

- ログインユーザーの整理構造だけを操作できる。
- 空白だけの名称は認めない。
- 名称は前後空白を除去し、小文字化した`normalized_name`を重複判定に使用する。Java実装では `Locale.ROOT` を使用する。
- `Java` / `java` / `JAVA` 等は同一親配下では同名として扱う。
- ルート階層でも同一ユーザー内の同名を禁止する。
- 親を指定する場合は同一ユーザーのorganizationでなければならない。
- 同名一意制約違反等のorganization競合は `409 / ORGANIZATION_CONFLICT` へ変換する。

### 移動

- 自分自身を親に指定できない。
- 自分の子孫を親に指定できない。
- 循環が生じる更新は拒否する。

### 削除

- 削除できない場合は、単なるエラー文ではなく理由を識別できるResponseを返す。
- 画面が「次に必要な操作」へ誘導できる情報を含める。
- 子organizationや関連メモ等、具体的な削除阻害条件は元の承認済み設計と照合する。

## 8. Spring Boot－AI Service内部API

### 基本方針

- Spring BootからAI Serviceへの通信は内部APIキーで認証する。
- AI ServiceはDBへ直接アクセス・更新しない。
- Spring BootがAI処理状態と正式なDB更新の責任を持つ。
- タイムアウト値、リトライ方針、ヘッダー名等はPhase 6で確定する。

### Requestに必要な情報

- AI処理ID
- 対象メモの原文
- 現在利用可能なorganization構造
- 初回整理／再整理の区別
- 必要に応じて現在の`CURRENT`

### Responseに必要な情報

- 提案するorganizationツリー（名称と親子関係）
- 今回のメモを所属させるorganizationパスの一覧（複数所属可）
- タイトル候補（初回整理で使用する場合）
- 処理結果を検証するための識別情報

## 9. エラーResponse方針

画面が利用者向けメッセージと次の操作を判断できるよう、最低限次の情報を共通化する。

- エラーコード
- ユーザー向けメッセージ
- 入力項目ごとのエラー情報（必要な場合）
- 競合理由または削除不可理由（必要な場合）
- トレース用ID（内部調査用）

スタックトレース、内部APIキー、外部AIの生レスポンス等は返さない。

409系では最低限、以下の機械判定用コードを区別する。

| コード | 主なケース | 画面側の基本対応 |
|---|---|---|
| `DECISION_TARGET_MISMATCH` | 表示済み`aiProcessId`が現在の判断対象ではない | 状態を再取得して表示更新 |
| `NO_PENDING_PROPOSAL` | 判断可能な未判断提案が存在しない | 状態を再取得 |
| `LOCK_TIMEOUT` | ユーザー操作時にmemoロック取得がタイムアウト | 処理中通知・再操作案内 |
| `ACTIVE_LIMIT_EXCEEDED` | REORGANIZE PROCESSING重複またはアクティブ上限超過 | 現在のAI処理／判断待ち状態を案内 |
| `PROPOSAL_STALE` | 提案が参照する既存organizationが削除済み等で採用不能 | 提案を保持し、却下・変更して使う等の選択肢を案内 |
| `ORGANIZATION_CONFLICT` | organization同名一意制約・参照整合性等の競合 | 整理構造を再取得し、競合内容を案内 |
| `PROCESS_STATE_CONFLICT` | CURRENT有無等の開始条件・状態条件違反 | 最新状態を再取得して実行可能な操作を案内 |

次も`409 Conflict`とし、上記の専用コードを使用する。

- `CURRENT`が存在するメモへの初回整理開始／再試行：`PROCESS_STATE_CONFLICT`
- `CURRENT`が存在しないメモへの再整理：`PROCESS_STATE_CONFLICT`
- 再整理開始時、残留回復後もINITIAL `PROCESSING`が存在する場合：`PROCESS_STATE_CONFLICT`
- organization同名一意制約違反：`ORGANIZATION_CONFLICT`
- 提案採用時に参照organizationが削除・変更され、提案をそのまま反映できない場合

内部エラーコードとユーザー向け表示文言は分離し、具体的文言は画面詳細設計で決定する。

## 10. Phase 6への申し送り

- organization管理APIの最終URI、HTTPメソッドおよびJSON形式
- AI提案「使わない」専用APIの最終URI
- 全APIの厳密なRequest／Response例
- ページング・並び順
- 検索APIの厳密なResponse形式、空`q`の入力検証
- 検索実装におけるRepository参照方法と重複排除方法
- CSRF対策を含むSession＋Cookieの具体設定
- 内部APIキーヘッダー名とローテーション方法
- 非同期実行方式
- outdated判定に必要な具体フィールド
- タイムアウト扱い`PROCESSING`のResponseフィールド名
- 409系ユーザー向け具体文言

## 11. 最終レビュー確認事項

元のPhase 5-2最終版と照合し、特にorganization管理API、HTTPステータス、Responseフィールドに差異がある場合は、承認済み設計を優先して本書を修正する。

### Phase 6開始後の補足

Phase 6のバックエンド構成詳細設計中に、Phase 2〜4でMVP対象として定義されていた検索機能がPhase 5 API基本設計で具体化されていないことを確認した。

PO判断により、`GET /memos/search?q={keyword}`、タイトル・原文・`CURRENT`のorganization名を対象とした部分一致検索、`AI_SUGGESTED`／未確定再整理提案を検索対象外とする方針をPhase 5補足として追加した。