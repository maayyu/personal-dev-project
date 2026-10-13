# Phase 6 MemoService 詳細設計書 v5

## 1. 対象クラス

`MemoService`

メモ本体の保存・取得・更新・削除、およびこれらの操作に必要な所有者確認・入力検証・memo単位の排他制御を担当するServiceとする。

AI処理状態そのものの管理は `AiProcessService`、AI結果の反映は `AiResultService`、memoとorganizationの関連管理は `MemoOrganizationService` に分離する。

---

## 2. 責務

`MemoService` の責務は以下とする。

- memoの新規保存
- memoの詳細取得
- memoのタイトル・原文更新
- memo削除
- memoの所有者確認
- memo更新・削除時の悲観ロック取得
- memo入力値の業務検証
- memo削除時に必要な関連処理の実行順序制御
- ユーザー操作時のmemoロックタイムアウトの業務エラー変換

以下は責務に含めない。

- AI ServiceとのHTTP通信
- AI処理開始条件・状態遷移の管理
- `ai_processes` のFIFO・未判断proposal管理
- AI結果の業務的な検証・解釈
- organizationの作成・変更・削除ロジック
- メモ検索ロジック（Search系の責務へ分離）
- APIレスポンスDTOの最終生成

---

## 3. 基本方針

### 3.1 memo保存とAI整理を分離する

memo保存成功とAI整理成功は別の結果として扱う。

```text
memo保存トランザクション
↓
commit
↓
AI処理開始
```

memo保存後のAI整理に失敗しても、保存済みmemoはROLLBACKしない。

AI処理開始・AI Service通信・AI結果反映は、MemoServiceの保存トランザクションに含めない。

### 3.2 memo単位の悲観ロック

以下のMemoService操作では対象memoの悲観ロックを取得する。

- タイトル更新
- 原文更新
- memo削除

また、MemoService外であってもCURRENTを書き換える通常の整理先変更、INITIAL結果反映、提案採用、「変更して使う」は同じmemo悲観ロック規則に従う。

ロック待ち上限は3秒とする。トランザクション分離レベルはMVPでは `READ_COMMITTED` を前提とする。

ユーザー操作で3秒以内にmemoロックを取得できない場合は処理を中止し、`409 Conflict` / `LOCK_TIMEOUT` とする。

### 3.3 AI処理中のmemo編集

MVPでは、AI処理中であってもタイトル・原文の編集を許可する。

- AI処理中かどうかを理由に原文編集を拒否しない。
- 更新時は通常どおりmemo悲観ロックを取得する。
- AI Serviceへ送信した本文と結果反映時点の本文が異なっていても、AI結果を自動無効化しない。
- memo編集時に `CURRENT`、未判断proposal、AI処理履歴を自動削除しない。
- memoバージョンによるAI結果の自動破棄はMVP対象外。
- 必要に応じて画面側で整理結果が古い可能性を示す（outdated表示）。

---

## 4. 主要メソッド

最終的なJavaシグネチャは実装時に確定するが、責務単位では以下を想定する。

### 4.1 `createMemo(...)`

#### 目的

ユーザー入力のmemoをAI処理より先に確実に保存する。

#### 主な入力

- `userId`
- `title`（任意）
- `content`

#### 処理フロー

```text
入力検証
↓
ユーザー情報確認
↓
memo作成
↓
`content_updated_at = 作成時刻` を設定
↓
保存
↓
commit
```

#### 入力ルール

- `content` は必須。
- 空文字・空白・改行のみは不可。
- `title` の未指定時の補完方法はAPI／画面仕様に従う。
- memo保存時点ではorganization指定を必須としない。
- 作成時は `content_updated_at` を作成時刻で初期化する。

AI整理開始は本メソッドのDBトランザクション外で、上位ユースケース層から行う。

---

### 4.2 `getMemo(...)`

#### 目的

ログインユーザーが所有するmemo本体を取得する。

#### 処理フロー

```text
memo取得
↓
所有者確認
↓
返却
```

通常参照では悲観ロックを取得しない。

存在しない、または他ユーザー所有の場合は `404 Not Found` とする。

---

### 4.3 `updateMemo(...)`

#### 目的

memoのタイトル・原文を更新する。

#### 主な入力

- `memoId`
- `userId`
- 更新対象（タイトル／原文）

#### 処理フロー

```text
memo悲観ロック取得
↓
存在・所有者確認
↓
更新項目の入力検証
↓
タイトル／原文更新
↓
原文が実際に変更された場合のみ `content_updated_at` 更新
（タイトルのみの更新、または原文が同値の更新では変更しない）
※更新時刻はmemo悲観ロック取得後に採番し、`ai_processes.created_at` と同一の時刻源・精度で比較できるようにする
↓
updated_at更新
↓
commit
```

#### AI処理中の扱い

AI処理状態による編集禁止判定は行わない。

```text
AI PROCESSING中
↓
updateMemo要求
↓
memoロック取得
↓
取得成功 → 通常更新
取得タイムアウト → 409 / LOCK_TIMEOUT
```

原文更新後も以下は維持する。

- `CURRENT`
- `AI_SUGGESTED`
- 未判断 `proposed_result`
- `ai_processes` 履歴

AI結果の反映時には、そのAI処理開始後にmemo本文が変更されていてもMVPでは結果を通常どおり扱う。

---

### 4.4 `deleteMemo(...)`

#### 目的

ユーザー所有memoを削除する。

AI処理中であっても削除を許可する。

#### 処理フロー

```text
memo悲観ロック取得
↓
存在・所有者確認
↓
対象memoの未判断proposed_resultを全件クリア
↓
`memo_organizations` は `memos` へのFK `ON DELETE CASCADE` により削除される（実装方式として明示削除を併用する場合も、残存させない）
↓
memo削除
↓
ON DELETE SET NULL により ai_processes.memo_id = NULL
↓
commit
```

未判断proposalを先にクリアする理由は、memo削除後にmemo由来の未確定内容を残さないため。

`ai_processes` の履歴レコード自体はMVPでは削除しない。

PROCESSING中にmemoが削除され、後からAI結果が返った場合は、AI結果反映側でmemo不存在を検知し、404とはせず条件付きUPDATEにより当該処理を `PROCESSING → CANCELLED` とする。

---

## 5. トランザクション境界

### 5.1 create

memo保存のみを1トランザクションとする。

AI処理開始やAI Service通信は含めない。

### 5.2 update

以下を1トランザクションとする。

```text
memoロック
↓
所有者確認
↓
入力検証
↓
更新
```

### 5.3 delete

以下を1トランザクションとする。

```text
memoロック
↓
所有者確認
↓
未判断proposalクリア
↓
関連削除
↓
memo削除
```

途中で失敗した場合は全体をROLLBACKする。

AI Service通信中にMemoServiceのトランザクションを保持しない。

---

## 6. 排他制御

memoの更新・削除に加え、CURRENTを書き換える通常変更も、AI結果反映・AI提案判断・AI処理開始と同じmemo悲観ロック規則を使用する。

これにより、同一memoに対する確定更新を直列化する。

例：

```text
updateMemo がmemoロック取得
↓
同じmemoへのAI結果反映は待機
↓
updateMemo commit / lock解放
↓
AI結果反映がロック取得して続行
```

AI結果反映側が先にロックを取得した場合は逆順となる。

ロック待ち時間を無制限にはせず、ユーザー操作では3秒を上限とする。

---

## 7. 入力検証

### title

- API基本設計で定義した文字数・必須条件に従う。
- 文字列の正規化・最大長の具体値は共通入力仕様に従う。

### content

- 空文字不可。
- 空白・改行のみ不可。
- 最大長はAPI／DB物理設計で確定した値に従う。

入力不正は `400 Bad Request` とする。

---

## 8. 例外・競合

| ケース | 扱い |
|---|---|
| memo不存在 | `404 Not Found` |
| 他ユーザー所有memo | `404 Not Found` |
| 入力不正 | `400 Bad Request` |
| ユーザー操作時のmemoロックタイムアウト | `409 Conflict` / `LOCK_TIMEOUT` |
| 予期しないDB障害 | `500 Internal Server Error` |

AI処理中であること自体はmemo編集の409理由にしない。

---

## 9. 他Serviceとの連携

### `AiProcessService`

- memo削除時の未判断proposalクリアを依頼する。
- AI処理履歴の削除は依頼しない。

### `MemoOrganizationService`

- memo削除時に `memo_organizations` を残さない。DBでは `memos` へのFK `ON DELETE CASCADE` を基本とする。
- memo更新ではCURRENTを自動変更しない。
- ユーザーによる通常の整理先変更（CURRENT置換）は `MemoOrganizationService` が担当する。
- 通常変更では、対象memoの悲観ロック取得、必要に応じた `OrganizationService` への新規organization作成依頼（親→子順）、旧CURRENT削除、新CURRENT登録までを単一トランザクションで行う。
- 通常の手動変更で作成するCURRENTは `ai_process_id = NULL` とし、CURRENT行の `created_at` を手動確定時刻として扱う。
- ユーザー操作のmemoロック待ち上限は3秒とし、取得できない場合は `409 / LOCK_TIMEOUT` とする。

### `MemoOrganizationQueryService`

- organization削除可否確認で使用するmemo参照照会を担当する読み取り専用Serviceとする。
- `CURRENT` / `AI_SUGGESTED` の参照memo IDを返す。
- `OrganizationService` から参照されるが、`OrganizationService` へは依存しない。これにより `MemoOrganizationService → OrganizationService → MemoOrganizationQueryService` の非循環な依存関係を保つ。

### `AiResultService`

- AI結果確定、提案採用・却下、「変更して使う」で同じmemoロック規則を共有する。
- 通常の整理先変更は `AiResultService` ではなく `MemoOrganizationService` の責務とする。
- memo本文更新後であっても、MVPではAI結果を自動無効化しない。

### AI処理開始用トランザクション単位

- memoと同一の悲観ロックを利用する。
- MemoService更新とAI処理開始が競合した場合、ロック取得順に直列化する。

---

## 10. 不変条件

- memoはAI整理より先に保存する。
- AI整理失敗によって保存済みmemoを失わない。
- memoは必ず所有ユーザー境界で操作する。
- memo更新・削除はmemo悲観ロック下で行う。
- ユーザー操作時のmemoロック待ち上限は3秒。
- AI処理中でもタイトル・原文を編集できる。
- `content_updated_at` は作成時に初期化し、原文が実際に変更された場合のみ更新する。タイトルのみの更新では変更しない。
- `content_updated_at` とAI処理の `created_at` は同一の時刻源・精度で比較可能にし、原文更新時の時刻はmemo悲観ロック取得後に採番する。
- memo編集によってAI結果・CURRENT・未判断proposalを自動削除しない。
- AI処理中でもmemo削除を許可する。
- memo削除時は未判断proposalを先にクリアする。
- memo削除後も `ai_processes` 履歴は保持し、`memo_id = NULL` とする。
- 削除済みmemoへ遅延AI結果を復活させない。
- AI通信中はDBトランザクション・memoロックを保持しない。

---

## 11. 単体テスト観点

### create

- 有効な原文でmemoを保存できる。
- 作成時に `content_updated_at` が初期化される。
- 空文字／空白・改行のみを拒否する。
- AI処理開始失敗によって保存済みmemoが失われない設計境界になっている。

### get

- 所有memoを取得できる。
- 不存在／他ユーザーmemoは404になる。

### update

- タイトルを更新できる。
- 原文を更新できる。
- AI PROCESSING中でも原文を更新できる。
- AI PROCESSING中でもタイトルを更新できる。
- 更新時にmemo悲観ロックを取得する。
- ロック待ち3秒超で409 / `LOCK_TIMEOUT` になる。
- 原文が実際に変更された場合だけ `content_updated_at` が更新される。
- タイトルのみの更新、または原文が同値の更新では `content_updated_at` が更新されない。
- 原文更新によってCURRENT・未判断proposalが削除されない。

### delete

- AI処理中でもmemoを削除できる。
- 未判断proposalクリア後にmemoを削除する。
- 削除後も `ai_processes` 履歴が残る。
- `ai_processes.memo_id` がNULLになる。
- 遅延AI結果がmemoを復活させない。

---

## 12. 実装時に固定しない事項

- 各Javaメソッドの最終シグネチャ
- DTO / Entityの具体的な型
- RepositoryのSQL / JPQL
- JPA悲観ロックの具体的API
- ロックタイムアウト設定の具体的なSpring/JPA指定方法
- title/contentの最終最大長（既存API・DB物理設計に合わせる）
- ユーザー向けエラー文言