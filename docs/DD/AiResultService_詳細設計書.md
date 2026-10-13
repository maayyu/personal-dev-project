# AiResultService 詳細設計書 v5

## 1. 対象クラス

`AiResultService`

AI Serviceから返却された整理結果、およびユーザーによるAI提案への判断を、アプリケーション内の確定状態へ反映するServiceとする。

`AiResultService` 自身は `organizations`、`memo_organizations`、`ai_processes` を直接Repository操作せず、各責務を持つServiceへ処理を依頼する。

主な連携先：

- `AiProcessService`
- `OrganizationService`
- `MemoOrganizationService`
- memo取得・悲観ロックを担当するService / Repository
- AI処理開始専用の短いトランザクション単位（最終クラス名は実装時に決定）
- 上位ユースケース層（AI処理全体のオーケストレーション）
- `AiServiceClient`（上位ユースケース層から呼び出し、DBトランザクション外で実行）

---

## 2. 責務

`AiResultService` の責務は以下とする。

- 初回AI整理結果の確定
- 再整理結果の未確定提案としての保存
- AI提案の採用
- AI提案の却下
- AI提案をユーザーが編集した内容での確定（「変更して使う」）
- 上記DB反映処理におけるトランザクション境界の管理
- memo単位の排他制御
- AI結果・ユーザー編集結果の妥当性確認
- `OrganizationService`、`MemoOrganizationService`、`AiProcessService` の処理順序の制御

以下は責務に含めない。

- AI ServiceとのHTTP通信そのもの
- AI処理開始から通信・結果反映・失敗処理までの全体オーケストレーション
- AI処理開始条件の正本管理
- `ai_processes` の状態遷移・FIFO・判断対象判定ルールの管理
- organization単体の作成・名称変更・移動・削除ロジック
- ユーザーによる通常の整理先変更（CURRENT置換）。これは `MemoOrganizationService` がmemo悲観ロック取得からCURRENT置換までを単一トランザクションで担当する

CURRENTの来歴は次の規則で保持する。

- INITIAL由来CURRENT：`ai_process_id = INITIAL aiProcessId`
- REORGANIZE提案をそのまま採用したCURRENT：`ai_process_id = REORGANIZE aiProcessId`
- 通常の手動変更／「変更して使う」：`ai_process_id = NULL`（CURRENT行の `created_at` を確定時刻として扱う）
- memoとorganizationの関連付けを直接Repository操作する処理
- APIレスポンスDTOの生成

---

## 3. 上位ユースケース層・開始処理との関係

AI処理全体は、トランザクションを保持しない上位ユースケース層が制御する。

AI処理開始は別の短いトランザクション単位で行う。

```text
上位ユースケース層（@Transactionalなし）
↓
開始処理専用トランザクション
・memo悲観ロック
・memo存在／所有者確認
・CURRENT存在確認
・残留PROCESSING回復
・開始条件／件数確認
・ai_processes PROCESSING作成
↓
commit / ロック解放
↓
AI Service通信（DBトランザクション外）
↓
成功
→ AiResultServiceで結果反映

失敗
→ 本体処理のROLLBACK完了後
→ AiProcessService.markFailed(...)
```

重要事項：

- 上位ユースケース層本体には `@Transactional` を付与しない
- AI通信中にDBトランザクションを保持しない
- 開始処理のトランザクションと結果反映トランザクションを分離する
- `AiResultService` の結果反映メソッドで例外が発生した場合、本体トランザクションのROLLBACK完了後に上位ユースケース層から `markFailed(...)` を呼ぶ
- AI処理開始後の想定外例外についても、対象processがまだ `PROCESSING` なら上位ユースケース層から条件付き `markFailed(...)` を試行する
- `markFailed(...)` を結果反映トランザクション内部から実行しない
- 開始処理専用単位・上位ユースケース層の具体的クラス名は実装時に決定する

---

## 4. 基本方針

### 4.1 AI通信とDBトランザクションを分離する

AI ServiceへのHTTP通信はDB結果反映トランザクション開始前に完了させる。

```text
AI Service呼び出し
↓
AI応答受信
↓
DBトランザクション開始
↓
memo悲観ロック
↓
結果反映
↓
commit
```

### 4.2 AI Serviceタイムアウト

MVP初期値：

- 接続タイムアウト：5秒
- レスポンス待ちタイムアウト：60秒

具体値は設定値として外出しし、実測結果に応じて変更可能とする。

### 4.3 memo単位の排他制御

以下の処理では対象memoの悲観ロックを取得する。

- AI結果確定
- AI提案の採用
- AI提案の却下
- 「変更して使う」
- ユーザーによる通常の整理先変更（CURRENT置換。ロック取得主体は `MemoOrganizationService`）
- memo本文・属性の更新
- memo削除
- AI処理開始時の開始条件確認・PROCESSING作成

ロック取得待ち上限は3秒とする。

ユーザー操作で3秒以内に取得できない場合：

- 処理中止
- `409 Conflict`
- APIエラーコード `LOCK_TIMEOUT`

トランザクション分離レベルはMVPでは `READ_COMMITTED` を前提とする。

AI結果反映時に3秒以内に取得できない場合：

- AI結果を反映しない
- 当該AI処理を `FAILED`
- MVPでは自動リトライしない

### 4.4 organizationの同時作成・同名制約

同一ユーザー・同一親organization配下では、大文字小文字を無視した同名organizationを禁止する。

名称重複判定には `normalized_name` を使用する。

MVPの正規化：

- 前後空白を除去
- 小文字化（Java実装では `toLowerCase(Locale.ROOT)` を使用）
- 全角半角等の追加正規化はMVP対象外

例：

```text
"Java"
"java"
"JAVA"
" Java "
→ normalized_name = "java"
```

同一親配下では重複不可。別親配下では同名を許可する。

ルートorganizationは `parent_id = NULL` のままとし、ルート専用のDB一意制約によって同一ユーザー内の `normalized_name` 重複を禁止する。

子organizationは、同一ユーザー・同一 `parent_id`・同一 `normalized_name` の重複をDB一意制約で禁止する。

- アプリケーション側でも事前重複チェック
- 同時作成時の最終的な整合性保証はDB制約
- DB競合時は反映処理全体をROLLBACK
- INITIAL等のAI結果反映経路では内部リトライしない
- AI結果反映経路の一意制約違反・FK違反・デッドロック・DBロックタイムアウトは、ROLLBACK後に当該AI処理を `FAILED`
- ユーザーによる採用・変更・organization編集での競合は `409 Conflict`

### 4.5 organization作成順序

複数organizationを新規作成する場合、DB競合・デッドロックの発生可能性を下げるため、作成順を決定的にする。

```text
1. 親 → 子
2. 同一階層では normalized_name ASC
```

既存organizationは新規作成せず再利用する。

### 4.6 トランザクション

各確定ユースケース全体を1トランザクションとする。

代表例：

- organization作成
- CURRENT入替
- `proposed_result` クリア
- `ai_processes.status` 正常完了更新

途中で失敗した場合は全体をROLLBACKする。

ユーザー確定処理（採用・却下・変更して使う）の失敗では、AI処理自体は既に正常完了しているため `status = FAILED` に変更しない。

---

## 5. AI処理種別と開始条件の参照

`ai_processes.process_type`：

- `INITIAL`
- `REORGANIZE`

INITIAL / REORGANIZEの開始条件、残留PROCESSING回復、REORGANIZEの同時PROCESSING上限・アクティブ総数上限は `AiProcessService` 詳細設計書 §7 を正とする。

`AiResultService` では開始条件を重複定義しない。

---

## 6. 主要メソッド

### 6.1 `applyInitialResult(...)`

#### 目的

初回AI整理結果を初期CURRENTとして確定する。

#### 主な入力

- `memoId`
- `aiProcessId`
- AI整理結果

#### 処理フロー

```text
対象memoをID指定で悲観ロック付き取得
↓
memoが存在する？
├─ No
│  → 404にしない
│  → AiProcessService.markCancelled(aiProcessId)
│     （status = PROCESSING の条件付きUPDATE）
│  → AI結果を反映せず終了
│
└─ Yes
   ↓
   操作権限確認
   ↓
   AiProcessServiceへ
   ・aiProcessIdの存在確認
   ・process_type = INITIAL確認
   ・status = PROCESSING確認
   ・ai_process.memo_id == memoId確認
   を依頼
   ↓
   CURRENTが存在しないことを再確認
   ↓
   CURRENTあり
   → ユーザーが先に確定した状態を優先
   → AI結果を反映しない
   → PROCESSING → CANCELLED
   → 終了
   ↓
   AI整理結果の妥当性確認
   ↓
   AI結果ツリーを親→子の順に走査し、既存organizationを解決
   ・名称をMVPルールで `normalized_name` 化（`Locale.ROOT`）
   ・同一ユーザーかつ同一親配下の `normalized_name` 一致で既存を特定
   ・ルートは `parent_id = NULL` の同一ユーザー配下で照合
   ・既存があればそのIDを再利用し、なければ新規候補とする
   （実際の検索・作成はOrganizationServiceへ依頼）
   ↓
   必要なorganization作成／既存organization利用をOrganizationServiceへ依頼
   ↓
   MemoOrganizationServiceへAI_SUGGESTED登録を依頼
   ・relation_type = AI_SUGGESTED
   ・ai_process_id = 今回のINITIAL aiProcessId
   ↓
   同じ整理先を初期CURRENTとして登録するようMemoOrganizationServiceへ依頼
   ・relation_type = CURRENT
   ・ai_process_id = 今回のINITIAL aiProcessId（AI由来CURRENTの来歴として保持）
   ↓
   AiProcessServiceへCOMPLETED更新を依頼
   ↓
   commit
```

#### 既存organizationの解決規則

INITIALでは `proposed_result` を保存しないが、AI Serviceから返る名称・親子関係をそのまま新規作成扱いにはしない。

- `AiResultService` が解決処理を制御し、実際の検索・作成は `OrganizationService` に依頼する。
- 親→子の順で、同一ユーザー・同一親配下の `normalized_name` 一致を既存organizationとして再利用する。
- ルートorganizationは同一ユーザー・`parent_id = NULL`・`normalized_name` 一致で照合する。
- 親が新規の場合、その配下ノードは親の作成後に同じ規則で照合／作成する。

#### memo本文がAI処理中に編集された場合

MVPでは結果を無効化しない。

- memo編集は許可
- AI処理開始時点よりmemo本文が変更されていても返却結果は通常通り反映
- memoバージョンによる無効化はMVPでは行わない
- 必要であれば後から再整理可能

#### 異常時

結果反映処理中にエラーが発生した場合：

1. 本体トランザクションをROLLBACK
2. 上位ユースケース層へ例外を返す
3. ROLLBACK完了後に上位ユースケース層から `AiProcessService.markFailed(...)` を呼ぶ

以下も同様に `FAILED` とする。

- AI結果反映時のmemoロック取得タイムアウト
- organization新規作成時の一意制約違反
- FK違反
- デッドロック
- DBロックタイムアウト

MVPでは競合発生後に既存organizationを再取得して同一処理内で再試行しない。ユーザーによる再試行で、新たなAI処理レコードを作成して再実行する。

---

### 6.2 `saveReorganizationProposal(...)`

#### 目的

再整理AI結果を未確定提案として `ai_processes.proposed_result` に保存する。

#### 処理フロー

```text
対象memoをID指定で悲観ロック付き取得
↓
memoが存在する？
├─ No
│  → 404にしない
│  → AiProcessService.markCancelled(aiProcessId)
│     （status = PROCESSING の条件付きUPDATE）
│  → proposed_resultを保存せず終了
│
└─ Yes
   ↓
   操作権限確認
   ↓
   AiProcessServiceへ
   ・aiProcessIdの存在確認
   ・process_type = REORGANIZE確認
   ・status = PROCESSING確認
   ・ai_process.memo_id == memoId確認
   を依頼
   ↓
   AI結果の妥当性確認
   ↓
   AI結果ツリーを親→子の順に走査し、既存organization参照をIDへ解決
   ・名称をMVPルールで `normalized_name` 化（`Locale.ROOT`）
   ・同一ユーザーかつ同一親配下の `normalized_name` 一致を既存と判定
   ・既存は `organizationId` を設定
   ・一致しない新規候補は `organizationId = null`
   ・親が新規候補の場合、その配下も新規候補として保持
   （検索はOrganizationServiceへ依頼し、この時点ではorganizationを作成しない）
   ↓
   AiProcessServiceへproposed_result保存を依頼
   ↓
   status = COMPLETED
   proposed_at = 現在時刻
   ↓
   commit
```

正常終了：

- `status = COMPLETED`
- `proposed_result = AI再整理結果`
- 既存organizationは `organizationId` 付き、新規候補は `organizationId = null` の正規化済み提案として保存
- `proposed_at` 設定
- `error_message = NULL`
- `updated_at` 更新

AI処理中にmemo本文が編集されていても、MVPでは結果を無効化せず候補として保存する。

---

### 6.3 `acceptProposal(...)`

```text
memo悲観ロック取得
↓
対象memoの存在・操作権限確認
↓
AiProcessServiceへ
「memoIdにおける指定aiProcessIdが現在の判断対象か」の検証を依頼
↓
proposed_result妥当性確認
↓
提案内で参照する既存organizationを `organizationId` で確認
・存在すること
・ログインユーザー所有であること
・新規ノードの親として使用する場合はその親が存在すること
※名前変更・親変更だけでは提案を無効化しない
↓
検証NG
→ 409 Conflict
→ proposed_resultはクリアしない
→ CURRENTも変更しない
→ 終了
↓
必要なorganizationを決定的な順序で作成／既存利用
↓
旧CURRENT削除
↓
新CURRENT登録
・提案をそのまま採用するため `ai_process_id = 今回のREORGANIZE aiProcessId`
↓
AiProcessServiceへproposed_resultクリアを依頼
↓
commit
```

organization照合：

- proposed_resultで既存organizationは `organizationId` を保持し、新規organizationは `organizationId = null` として区別する
- 既存organizationが削除済み等で `organizationId` を解決できない場合は `409 / PROPOSAL_STALE` とし、提案を保持する
- 同一親配下に `normalized_name` が同じorganizationが存在する場合は既存を再利用
- 存在しない場合は新規作成
- 別親配下の同名organizationは許可
- 新規作成は親→子、同一階層は `normalized_name ASC`
- DB一意制約・FK・デッドロック・DBロック競合は409、全体ROLLBACK
- ユーザー確定処理の失敗なので `ai_processes.status` はFAILEDへ変更しない

`aiProcessId` が現在の判断対象でない場合は409。

提案作成後に参照先organizationが削除される等、既存 `organizationId` を解決できずそのまま採用できない場合は `409 / PROPOSAL_STALE` とする。名前変更・親変更だけでは提案を無効化しない。提案は消費せず保持する。

具体的なユーザー向け表示文言は画面設計で決定する。

---

### 6.4 `rejectProposal(...)`

```text
memo悲観ロック取得
↓
対象memoの存在・操作権限確認
↓
AiProcessServiceへ
「memoIdにおける指定aiProcessIdが現在の判断対象か」の検証を依頼
↓
AiProcessServiceへproposed_resultクリアを依頼
↓
commit
```

CURRENT・organizations・memo_organizationsは変更しない。`status` は `COMPLETED` のままとする。

却下ではproposed_result内のorganization妥当性検証を要求しない。提案内容が古くても却下可能とする。

---

### 6.5 `acceptEditedProposal(...)`

```text
memo悲観ロック取得
↓
対象memoの存在・操作権限確認
↓
AiProcessServiceへ
「memoIdにおける指定aiProcessIdが現在の判断対象か」の検証を依頼
↓
編集後データ検証
↓
既存organization検証
↓
必要な新規organizationを
親→子、同一階層はnormalized_name ASCで作成
↓
旧CURRENT削除
↓
編集後内容を新CURRENTとして登録
・ユーザー編集後の手動確定として `ai_process_id = NULL`
・CURRENT行の `created_at` を確定時刻としてoutdated判定に使用
↓
AiProcessServiceへproposed_resultクリアを依頼
↓
commit
```

ユーザー編集後の内容で `proposed_result` を上書きしない。

既存organizationの検証失敗・DB競合時は409とし、`proposed_result` は消費しない。

---

## 7. 「変更して使う」の入力検証

- organization所属は最低1件必須
- 同一memo・同一organizationへの重複所属は禁止
- 新規organization名は空文字・空白のみ不可
- 新規organization名は前後空白を除去
- `normalized_name` は前後空白除去後の小文字化で生成し、Java実装では `Locale.ROOT` を使用する
- 同一親配下では `normalized_name` が同じorganizationを禁止
- 別親配下での同名organizationは許可
- ルートorganizationも同一ユーザー内で `normalized_name` の重複を禁止
- 既存organizationは `organizationId` で指定
- 新規organizationは `organizationId = null`
- 既存organizationは存在し、ログインユーザーが利用可能
- 新規organizationの親として既存organizationを指定する場合、その親もログインユーザー所有
- 既存organizationの名前・親子関係は変更しない
- 別階層に同名organizationが必要な場合は新規organizationとして作成
- 新規organizationが複数階層の場合は親から順に作成
- 同一階層に複数作成する場合は `normalized_name ASC`
- organization構造に循環等の不正がないこと

他ユーザー所有または参照不可のmemo / organizationは `404 Not Found`。

---

## 8. AI提案の判断順序

未判断AI提案は最大2件まで保持可能とする。

FIFOはAI処理開始順ではなく、**提案が `proposed_result` として保存された順**とする。

`ai_processes.proposed_at` を使用し、同順位時は `id ASC` をタイブレークとする。

現在の判断対象：

```text
memo_id = 対象memoId
AND process_type = REORGANIZE
AND status = COMPLETED
AND proposed_result IS NOT NULL
ORDER BY proposed_at ASC, id ASC
```

判断対象判定は `AiProcessService` に集約する。

一度現在の判断対象になった提案は、それ自身が消費されるまで後から完了した提案によって判断対象を奪われない。

---

## 9. 再整理処理数の上限と残留回復

開始条件・残留回復の正本は `AiProcessService` 詳細設計書 §7 とする。

概要：

- REORGANIZEのアクティブ総数は最大2件
- REORGANIZEの `PROCESSING` は同一memoにつき最大1件
- `COMPLETED + proposed_resultあり` 1件 + `PROCESSING` 1件は許可
- INITIAL / REORGANIZEの長時間残留 `PROCESSING` はどちらも回復対象
- 残留判定時間の初期値は2分
- INITIAL / REORGANIZE開始時、memoロック取得後・開始条件判定前に残留回復する
- 定期スケジューラはMVPでは導入しない

状態取得APIでは残留判定時間を超えた `PROCESSING` をDB更新せずタイムアウト扱いとして返し、有効な処理中件数から除外できるようにする。

---

## 10. memo削除時の扱い

memo削除処理も対象memoの悲観ロックを取得する。

同一トランザクションで以下の順序とする。

```text
memo悲観ロック
↓
対象memoに紐づく未判断proposed_resultを全件クリア
↓
`ON DELETE CASCADE` により対象memoの `memo_organizations` を削除
↓
memo削除
↓
ON DELETE SET NULL により ai_processes.memo_id = NULL
↓
commit
```

未判断proposalを先にクリアする理由は、memo削除後にmemo由来のAI提案内容を残さないため。

`ai_processes` の履歴レコード自体はMVPでは削除しない。

PROCESSING中にmemoが削除され、その後AI応答が返った場合：

```text
結果反映時にmemoをID指定でロック付き取得
↓
memo不存在
↓
404にはしない
↓
AI結果を反映しない
↓
status = PROCESSING の条件付きUPDATEでCANCELLED
↓
終了
```

memo不存在時はmemoロックを取得できないため、CANCELLED更新はロックなしの条件付きUPDATEとする。

---

## 11. memoId / aiProcessId の整合性

AI処理結果を反映する際、対象 `ai_process.memo_id` がリクエストされた `memoId` と一致することを必ず確認する。

- 一致：続行
- 不一致：`409 Conflict`
- `memo_id = NULL`：memo削除済みとしてAI結果反映ではCANCELLED経路

判断対象検証では、先に `memoId` で対象提案を絞り込んだうえで指定 `aiProcessId` と照合する。

MVPでは `ai_processes.user_id` は追加しない。

---

## 12. エラー分類

409系では、HTTPステータスに加えてフロントエンドが処理を分岐できる機械判定用エラーコードを返す。

最低限：

| エラーコード | 主なケース |
|---|---|
| `DECISION_TARGET_MISMATCH` | 指定した提案が現在の判断対象ではない |
| `NO_PENDING_PROPOSAL` | 未判断proposalが存在しない |
| `LOCK_TIMEOUT` | ユーザー操作でmemoロック取得がタイムアウト |
| `ACTIVE_LIMIT_EXCEEDED` | 再整理のアクティブ上限超過、またはPROCESSING重複 |
| `PROPOSAL_STALE` | 既存organization参照が削除済み等で未判断提案をそのまま採用できない |
| `ORGANIZATION_CONFLICT` | organization同名一意制約・FK等のユーザー操作競合 |
| `PROCESS_STATE_CONFLICT` | CURRENT有無等の処理開始条件・状態条件を満たさない |

その他の分類：

| 分類 | 主なケース | 扱い |
|---|---|---|
| Not Found | memo / organizationが存在しない、または他ユーザー所有 | 404 |
| Validation | organization 0件、名称空、構造不正等 | 400 |
| Conflict | 判断対象不一致、memo/aiProcess不一致、同名競合、上限超過、状態競合、ユーザー操作時ロックタイムアウト、古い提案の採用 | 409 |
| AI Result Apply Failure | AI結果反映時ロックタイムアウト、一意制約違反、FK違反、デッドロック、DBロックタイムアウト、その他反映失敗 | ai_processをFAILED |
| Unexpected | 予期しない内部障害 | 500 |

内部エラーコードをそのままユーザーへ表示しない。具体的なユーザー向け文言は画面設計で決定する。

---

## 13. 不変条件

- 上位ユースケース層本体には `@Transactional` を付与しない
- AI ServiceへのHTTP通信中はDBトランザクション・悲観ロックを保持しない
- AI処理開始は短い専用トランザクション単位で完結する
- CURRENT入替と `proposed_result` クリアは同一トランザクション
- 初回整理結果は、反映時点でもCURRENTが存在しない場合のみCURRENTへ確定
- INITIAL反映時にCURRENTが既に存在する場合はAI結果を反映せずCANCELLED
- 再整理結果はユーザー判断前にCURRENTへ反映しない
- FIFOは `proposed_at ASC, id ASC`
- 現在の判断対象判定はmemo単位で `AiProcessService` に集約
- ユーザー確定処理の失敗では `status = FAILED` にしない
- 古くなった提案の採用失敗では `proposed_result` を消費しない
- `proposed_result` はAI未確定提案のみ
- 既存organizationの構造変更は「変更して使う」では行わない
- organization所属は最低1件必要
- organization同名禁止はアプリ検証とDB制約の両方で保証
- organization名のMVP正規化は前後空白除去＋小文字化（Javaでは `Locale.ROOT`）
- AI結果内の既存organizationは、同一ユーザー・同一親配下の `normalized_name` 一致でIDへ解決する。REORGANIZEの新規候補は `organizationId = null` とする
- organization作成順は親→子、同一階層は `normalized_name ASC`
- AI結果反映中のDB競合では内部リトライせず、ROLLBACK後FAILED
- memo更新・削除・通常のCURRENT変更も同一のmemo悲観ロック規則に従う
- AI処理中のmemo編集はMVPでは許可し、結果を無効化しない
- `INITIAL` / `REORGANIZE` を `process_type` で区別する
- REORGANIZEの `PROCESSING` は同一memoにつき最大1件
- memo削除後も `ai_processes` 履歴はMVPでは保持する

---

## 14. 実装時に固定しない事項

- 各Javaメソッドの最終的な引数型・戻り値型
- privateメソッドの分割単位
- Repositoryの具体的なSQL / JPQL
- JPAによる悲観ロック・タイムアウト指定方法
- organization一意制約・部分一意インデックスの具体的SQL表現
- DTOクラスの最終名称
- AI処理開始専用トランザクション単位の最終クラス名
- 上位ユースケース層の最終クラス名
- `proposed_result` DTO / JSONの具体的フィールド名（ただし既存organizationはID、新規はIDなしの区別を維持）
- 409系のユーザー向け具体的表示文言