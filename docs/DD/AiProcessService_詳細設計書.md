# AiProcessService 詳細設計書 v5

## 1. 対象クラス

`AiProcessService`

`ai_processes` の生成、処理種別、状態遷移、未確定AI提案の保存・クリア、判断順序、再整理処理数の管理、長時間残留 `PROCESSING` の判定・回復を担当するServiceとする。

AI処理開始時の「memoロック取得 → 所有者確認 → CURRENT確認 → 残留回復 → 開始条件確認 → PROCESSING作成」全体は、開始処理専用の短いトランザクション単位が制御する。`AiProcessService` はその中でAI処理状態に関する判定・更新を担当する。

開始処理専用トランザクション単位の最終クラス名は実装時に決定する。

---

## 2. 責務

- AI処理開始レコードの作成
- `process_type` の管理
- `PROCESSING / COMPLETED / FAILED / CANCELLED` の状態遷移管理
- `proposed_result` の保存・取得・クリア
- `proposed_at` の設定
- 現在の判断対象の特定・指定 `aiProcessId` との一致確認
- memoId / aiProcessId の紐付け検証
- 再整理処理数のカウント
- 長時間残留 `PROCESSING` の判定・回復
- `error_message` の保存ルール
- `updated_at` の更新
- 不正な状態遷移の防止

以下は責務に含めない。

- AI整理結果の意味解釈
- organization作成・変更
- CURRENTの登録・入替
- CURRENTの存在確認そのもの
- memoの所有者確認
- AI ServiceとのHTTP通信
- AI処理全体のオーケストレーション

---

## 3. 処理種別

`ai_processes.process_type`：

- `INITIAL`
- `REORGANIZE`

INITIALは初回整理、REORGANIZEは再整理とする。

---

## 4. 状態定義

```text
PROCESSING
├─ COMPLETED
├─ FAILED
└─ CANCELLED
```

`COMPLETED / FAILED / CANCELLED` は終端状態。

`CANCELLED` はAI処理自体の失敗ではなく、memo削除やユーザーによるCURRENT確定などにより、返却結果を反映する必要がなくなった状態を表す。

再実行は既存レコードを再利用せず、新規 `ai_processes` レコードを作成する。

---

## 5. proposed_result / proposed_at

| process_type | status | proposed_result | proposed_at | 意味 |
|---|---|---|---|---|
| INITIAL | PROCESSING | NULL | NULL | 初回AI処理中 |
| INITIAL | COMPLETED | NULL | NULL | 初回整理完了 |
| REORGANIZE | PROCESSING | NULL | NULL | 再整理AI処理中 |
| REORGANIZE | COMPLETED | 値あり | 値あり | 再整理提案が未判断 |
| REORGANIZE | COMPLETED | NULL | 設定済み可 | 再整理提案が判断済み |
| INITIAL / REORGANIZE | FAILED | NULL | NULL | AI処理失敗 |
| INITIAL / REORGANIZE | CANCELLED | NULL | NULL | 継続不要 |

MVPでは `PROPOSED`、`ACCEPTED` 等の追加statusは設けない。

---

## 6. 主要メソッド

### 6.1 `createProcessing(...)`

主な入力：

- `memoId`
- `processType`

作成時：

- `status = PROCESSING`
- `proposed_result = NULL`
- `proposed_at = NULL`
- `error_message = NULL`
- `created_at` / `updated_at` 設定

開始条件確認とPROCESSING作成は、開始処理専用トランザクション単位によってmemo悲観ロック下の同一トランザクションで行う。

---

### 6.2 `markCompleted(...)`

許可：

```text
PROCESSING → COMPLETED
```

主にINITIALで利用。

更新条件：

```text
id = 対象aiProcessId
AND status = PROCESSING
AND process_type = INITIAL
```

更新：

- `status = COMPLETED`
- `proposed_result = NULL`
- `error_message = NULL`
- `updated_at` 更新

更新件数は1件であることを確認する。0件の場合は既に状態が変化しているため上書きしない。残留回復後に遅れて到着したAI結果等による0件更新は想定内の状態競合として扱い、原則INFOログに記録する。

---

### 6.3 `saveProposedResult(...)`

REORGANIZE専用。

更新条件：

```text
id = 対象aiProcessId
AND process_type = REORGANIZE
AND status = PROCESSING
```

更新：

- `proposed_result` 保存
- `proposed_at = clock_timestamp()`（DB時刻。SQLを通じて採番）
- `status = COMPLETED`
- `error_message = NULL`
- `updated_at` 更新

更新件数は1件であることを確認する。0件の場合は、残留回復・削除競合等により既に終端状態になっている可能性があるため上書きしない。遅延AI結果による0件更新は想定内の状態競合として原則INFOログに記録する。

---

### 6.4 `markCancelled(...)`

基本遷移：

```text
PROCESSING → CANCELLED
```

更新条件：

```text
id = 対象aiProcessId
AND status = PROCESSING
```

更新：

- `status = CANCELLED`
- `proposed_result = NULL`
- `error_message = NULL`
- `updated_at` 更新

memoロック下・memo削除後のロックなし経路のどちらでも条件付きUPDATEを用い、更新件数は1件であることを確認する。

更新件数0件なら、すでにPROCESSINGではないため終端状態を上書きしない。

---

### 6.5 `markFailed(...)`

基本遷移：

```text
PROCESSING → FAILED
```

更新条件：

```text
id = 対象aiProcessId
AND status = PROCESSING
```

更新：

- `status = FAILED`
- `proposed_result = NULL`
- サニタイズ済み `error_message`
- `updated_at` 更新

独立した新規トランザクションで実行し、上位ユースケース層が本体ROLLBACK完了後に呼ぶ。

更新件数0件なら終端状態等を上書きせずWARNログ。

---

### 6.6 `validateMemoProcessRelation(...)`

```text
ai_process.memo_id == memoId
→ 正常

ai_process.memo_id != memoId
→ Conflict

ai_process.memo_id == NULL
→ memo削除済み
```

MVPでは `ai_processes.user_id` は追加しない。

---

### 6.7 `validateCurrentDecisionTarget(memoId, aiProcessId)`

現在の判断対象は、必ず対象memoに絞り込んだうえで判定する。

```text
memo_id = memoId
AND process_type = REORGANIZE
AND status = COMPLETED
AND proposed_result IS NOT NULL
ORDER BY proposed_at ASC, id ASC
```

先頭レコードの `id` と指定 `aiProcessId` が一致しない場合はConflict。

`id ASC` は `proposed_at` が同値の場合の決定的なタイブレークにのみ使用し、業務的な時系列意味は持たせない。

FIFO判定ロジックは本Serviceに集約する。

---

### 6.8 `clearProposedResult(...)`

実行条件：

- `process_type = REORGANIZE`
- `status = COMPLETED`
- `proposed_result IS NOT NULL`

更新：

- `proposed_result = SQL NULL`
- `proposed_at` は履歴として変更しない
- `updated_at` 更新
- `status` は `COMPLETED` のまま

判断対象検証後の採用・却下・変更して使う処理から呼び出す。

---

### 6.9 `countActiveReorganizationProcesses(...)`

REORGANIZEのアクティブ上限判定に使用する。

対象：

```text
process_type = REORGANIZE
AND (
  status = PROCESSING
  OR
  (status = COMPLETED AND proposed_result IS NOT NULL)
)
```

アクティブ総数は最大2件。

ただし、開始処理では必ず先に `recoverStaleProcessing(...)` を実行するため、残留判定時間を超えた `PROCESSING` は上限判定に含まれない。

追加条件：

- 同一memoの `REORGANIZE + PROCESSING` は最大1件
- `COMPLETED + proposed_resultあり` 1件 + `PROCESSING` 1件は許可
- `PROCESSING` 2件は許可しない

INITIALは再整理アクティブ件数に含めない。

---

### 6.10 `recoverStaleProcessing(...)`

INITIAL / REORGANIZEのどちらの開始処理でも、memo悲観ロック取得後、開始条件・件数判定より前に実行する。

初期設定：

- 残留判定時間：2分
- 設定値として外出し
- 実測により90秒・3分等へ調整可能

対象：

```text
status = PROCESSING
AND process_type IN (INITIAL, REORGANIZE)
AND created_at < 現在時刻 - 残留判定時間
```

対象を `FAILED` とし、`error_message = "Processing timed out"` 等を保存する。

回復更新も `status = PROCESSING` の条件付きUPDATEとし、更新件数を確認する。

遅れて到着したAI結果は、対象statusが `PROCESSING` でないため反映しない。

定期スケジューラはMVPでは導入しない。

---

### 6.11 状態参照時のタイムアウト判定

状態取得APIでは、DBを更新せずに残留判定時間を超えた `PROCESSING` をタイムアウト扱いとして判定できるようにする。

```text
status = PROCESSING
AND created_at < 現在時刻 - 残留判定時間
→ timedOut = true
```

状態参照時には `FAILED` へ更新しない。

タイムアウト扱いの `PROCESSING` は、画面へ返す有効な処理中件数には含めない。

実際の `PROCESSING → FAILED` 回復は、次回INITIAL / REORGANIZE開始時にmemo悲観ロック下で行う。

---

## 7. INITIAL / REORGANIZE 開始条件

開始条件は本節を正とする。

AI処理開始は、開始処理専用の短いトランザクション単位で次の順序に統一する。

```text
memo悲観ロック取得
↓
memo存在・所有者確認
↓
CURRENT存在確認
↓
recoverStaleProcessing(...)
↓
process_typeごとの開始条件確認
↓
REORGANIZEの場合はPROCESSING件数・アクティブ件数確認
↓
createProcessing(...)
↓
AI Serviceへ渡すmemo原文を同じmemoロック下で読み取り、content snapshotとして保持
↓
REORGANIZE等でAI Requestに現在のCURRENT／organization構造が必要な場合も、整合性が必要な入力は同じ開始トランザクション内で読み取る
↓
commit / memoロック解放
```

開始トランザクションは `ai_processes.created_at` とAI入力本文の対応を崩さないため、AI Serviceへ渡す原文をmemo悲観ロック下で読み取る。commit後にmemoを再取得して本文を作り直さず、このcontent snapshotを上位ユースケース層から `AiServiceClient` へ渡す。

これにより、AI処理開始後に原文が変更されたかは `content_updated_at > ai_processes.created_at` で判定できる。`content_updated_at` と `ai_processes.created_at` は同一の時刻源・精度で比較できることを実装条件とし、具体的な時刻生成方式はPhase 7で確定する。

### INITIAL

memo悲観ロック下で：

- CURRENTが存在しない
- 残留回復後に `process_type = INITIAL AND status = PROCESSING` が存在しない

場合のみ開始。

### REORGANIZE

memo悲観ロック下で：

- CURRENTが存在する
- 残留回復後に `process_type = INITIAL AND status = PROCESSING` が存在しない
- `process_type = REORGANIZE AND status = PROCESSING` が存在しない
- REORGANIZEのアクティブ処理数が2件未満

場合のみ開始。

REORGANIZEの `PROCESSING` が既に1件存在する場合は、新規再整理開始をConflictとする。

---

## 8. 状態遷移チェック

`AiProcessService` 自身がstatusとprocess_typeを確認し、許可された遷移のみ実行する。

PROCESSINGからの終端状態更新は、原則として `status = PROCESSING` を条件に含む条件付きUPDATEとし、更新件数1件を確認する。

不正遷移時：

- 既存状態を上書きしない
- Conflict
- `aiProcessId`、現在status、process_type、要求処理をログ

---

## 9. 排他制御

排他制御の基本単位はmemo悲観ロック。

CURRENTを書き換えるすべての経路（通常の整理先変更、INITIAL結果反映、提案採用、「変更して使う」を含む）は同じmemo悲観ロックを使用する。

通常の整理先変更では `MemoOrganizationService` がロック取得とCURRENT置換のトランザクション境界を担当する。

トランザクション分離レベルはMVPでは `READ_COMMITTED` を前提とする。

`ai_processes` 自体には原則として追加の悲観ロックを設けない。

memoロックを利用できない経路を含め、PROCESSINGから終端状態への更新は `status = PROCESSING` 条件付きUPDATEで保護する。

organizationの同名一意性はmemoロックでは保証せず、Organization側のDB一意制約を最終防衛線とする。

---

## 10. トランザクション

通常更新は呼び出し元トランザクションへ参加。

AI処理開始時は、開始処理専用の短いトランザクション単位でmemoロック取得から `createProcessing(...)` までを完結させる。

AI ServiceとのHTTP通信は、この開始トランザクションのcommit後にトランザクション外で実行する。

上位ユースケース層本体には `@Transactional` を付与しない。開始処理・AI通信・結果反映を1つの長いトランザクションに含めない。

`markFailed(...)` は独立した新規トランザクションとし、本体ROLLBACK完了後に実行する。上位ユースケース層は `AiServiceException` に限らず、AI処理開始後に発生した想定外例外についても、対象processがまだ `PROCESSING` の場合は条件付き `markFailed(...)` を試行し、残留 `PROCESSING` を極力残さない。

memo削除後の `markCancelled(...)` はロックなしの条件付きUPDATEで実行可能とする。

---

## 11. error_message

内部向け概要のみ保存。

例：

- AI Service connection failed
- AI Service timeout
- Invalid AI response
- Failed to apply AI result
- Failed to acquire memo lock while applying AI result
- Processing timed out

保存しない：

- stack trace
- APIキー・秘密情報
- HTTPレスポンス全文
- memo本文
- 不要な内部URL

最大500文字。保存前にサニタイズ。

UIではそのまま表示しない。

---

## 12. updated_at / proposed_at

`updated_at` は永続データ更新時に更新。

`proposed_at` はREORGANIZEのAI提案が `proposed_result` として保存された時点で設定し、FIFOに利用する。採用・却下・変更して使う、またはメモ削除に伴う提案クリア後も履歴として保持する。未判断判定は `proposed_result IS NOT NULL` を必須条件とする。

FAILEDはPROCESSINGからのみ遷移し、PROCESSING時点では `proposed_at = NULL` のため、FAILEDの `proposed_at` は常にNULL。

`created_at` はFIFOには使わない。残留PROCESSING判定に加え、AI入力本文の基準時刻としてoutdated判定にも使用する。AIへ送る本文は同じ開始トランザクション内でmemoロック下に取得する。

---

## 13. memo削除との連携

memo削除処理はmemo悲観ロック下、同一トランザクションで以下の順序。

```text
1. COMPLETED + proposed_resultあり を全件クリア
2. `ON DELETE CASCADE` により対象memoの `memo_organizations` を削除
3. memo削除
4. ON DELETE SET NULL により ai_processes.memo_id = NULL
```

未判断 `proposed_result` を先にクリアする理由は、memo削除後にmemo由来のAI提案内容を残さないため。

`ai_processes` の履歴レコード自体はMVPでは削除せず保持する。

memo削除後にAI応答が返ったPROCESSINGレコードは、`status = PROCESSING` 条件付きUPDATEにより `CANCELLED` へ遷移。

---

## 14. AI処理中のmemo編集

memo編集は許可する。

memo更新処理もmemo悲観ロックを取得する。

AI Serviceへ送信した本文と結果反映時点の本文が異なっていても、MVPではAI結果を無効化しない。

memoバージョン管理・結果の自動破棄はMVP対象外。

---

## 15. 例外・競合

HTTP 409を使用する主なケースでは、APIレスポンスに機械判定可能なエラーコードを付与する。

最低限以下を区別する。

| エラーコード | 主なケース |
|---|---|
| `DECISION_TARGET_MISMATCH` | 指定 `aiProcessId` が現在の判断対象ではない |
| `NO_PENDING_PROPOSAL` | 判断可能な未確定提案が存在しない |
| `LOCK_TIMEOUT` | ユーザー操作でmemoロック取得がタイムアウト |
| `ACTIVE_LIMIT_EXCEEDED` | 再整理のアクティブ上限超過、またはREORGANIZE PROCESSING重複 |
| `PROPOSAL_STALE` | 未判断提案が参照する既存organizationが削除済み等でそのまま採用できない |
| `ORGANIZATION_CONFLICT` | organization同名一意制約・参照整合性等の競合 |
| `PROCESS_STATE_CONFLICT` | CURRENT有無などAI処理開始条件・状態条件を満たさない |

その他：

- memo / organizationが存在しない、または他ユーザー所有：Not Found
- `memoId` と `aiProcessId` 不一致：Conflict
- 状態遷移条件不一致：Conflict
- organization同時作成等のDB一意制約競合：ユーザー操作ではConflict
- AI結果反映中の一意制約違反・FK違反・デッドロック・DBロックタイムアウト：反映全体をROLLBACKし、AI処理をFAILED
- AI結果反映時のmemoロックタイムアウト：AI処理をFAILED
- 予期しないDB障害：Internal Error

内部エラーコードとユーザー向け表示文言は分離する。具体的な表示文言は画面設計で決定する。

---

## 16. 不変条件

- `INITIAL` / `REORGANIZE` を `process_type` で明示
- `PROCESSING` からのみ `COMPLETED / FAILED / CANCELLED`
- PROCESSINGからの終端状態更新は条件付きUPDATE＋更新件数確認
- 終端状態から別statusへ遷移しない
- 再試行は新規レコード
- `FAILED / CANCELLED` は `proposed_result` を保持しない
- FAILEDの `proposed_at` はNULL
- 未判断提案は `REORGANIZE + COMPLETED + proposed_resultあり`
- FIFOは `proposed_at ASC, id ASC`
- 現在の判断対象判定はmemo単位で本Serviceへ集約
- REORGANIZEのアクティブ処理は同一memoにつき最大2件
- REORGANIZEの `PROCESSING` は同一memoにつき最大1件
- INITIALは再整理上限に含めない
- 残留回復はINITIAL / REORGANIZEの両方を対象とする
- 状態取得時は残留PROCESSINGを判定のみ行い、DB更新しない
- 上位ユースケース層本体には `@Transactional` を付与しない
- AI提案内容の履歴保持はMVP対象外
- memo削除後も `ai_processes` 履歴はMVPでは保持する
- CURRENTを書き換えるすべての経路は同じmemo悲観ロック規則に従う
- トランザクション分離レベルはMVPでは `READ_COMMITTED` を前提とする

---

### 16.1 Phase 8 Wave 0-A：DB制約と時刻・ロック実装方針

- `ai_processes.proposed_result` は `JSONB`。SQL NULLとJSON `null`を区別し、DB CHECKにより非NULL値をJSONオブジェクトに限定する。Hibernate JSONマッピングはWave 1冒頭に保存・取得・SQL NULL化を検証する。
- PostgreSQL 17で `(memo_id) WHERE process_type='REORGANIZE' AND status='PROCESSING'` の部分一意インデックス `uq_ai_processes_reorganize_processing` を採用。REORGANIZEの二重PROCESSING登録をDBでも防ぐ。アクティブ総数は引き続きmemo悲観ロック下でServiceが制御する。INITIAL専用の部分一意インデックスはMVPで見送る。
- memo悲観ロック待機上限3秒は維持する。JPAのロックタイムアウトヒントをPostgreSQLで2接続により実測し、満たせない場合は同じトランザクション内の `SET LOCAL lock_timeout = '3s'` 等を検討する。SQLSTATE／例外の変換は実測後に確定し、ユーザー操作の409 `LOCK_TIMEOUT`、AI反映時のROLLBACK後 `FAILED` を区別する。
- `memos.content_updated_at`、`ai_processes.created_at`、手動CURRENTの `memo_organizations.created_at`、`ai_processes.proposed_at`、各対象行の `updated_at` は `TIMESTAMPTZ(6)`・Java `Instant`・DB `clock_timestamp()`で時刻源を統一する。`ai_processes.created_at` は開始トランザクション中のmemoロック取得後、原文snapshotと対応付けて採番する。`now()`やJVM時計を混在させない。JPA経由の具体的採番経路と精度はWave 1〜2に実測する。
- 未判断提案のFIFO用部分インデックス `idx_ai_processes_proposal` と残留回復用 `idx_ai_processes_processing_recovery` はPhase 5 DB設計§10.1に定義したDDL案を使用する。

## 17. 実装時に固定しない事項

- 各Javaメソッドの最終シグネチャ
- Entity / DTO / Optional等の具体的戻り値型
- RepositoryのSQL / JPQL
  - ただしPROCESSINGから終端状態への更新は `status = PROCESSING` 条件付き更新
- `updated_at` / `proposed_at` のJPA実装方式（日時はDB `clock_timestamp()` を基本とし、実DB動作確認が必要）
- organizationルート一意制約等のDB製品依存DDL
- ログメッセージの具体的文面
- 開始処理専用トランザクション単位の最終クラス名
- 上位ユースケース層の最終クラス名
### Wave 0-A 最終レビュー補足：時刻の更新経路

- `PROCESSING → COMPLETED / FAILED / CANCELLED` 等の条件付きUPDATEでは、`updated_at = clock_timestamp()` をSQLのSET句に明示する。REORGANIZE提案保存時は `proposed_at = clock_timestamp()` も明示する。
- `content_updated_at` はメモ原文の変更時だけ更新し、DB時刻を同一トランザクションで取得する。具体的なRepository SQL／JPA経路はWave 1〜2の実DB検証で決定する。
- JPAのEntity自動更新だけに依存せず、バルクUPDATE後も監査日時が更新されることを確認する。
- `SET LOCAL lock_timeout` を採用する場合は適用範囲がトランザクション全体である点に留意し、memoロック取得失敗とその他のロック競合を、操作箇所・例外の原因で区別する。
