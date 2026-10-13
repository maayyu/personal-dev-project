# Phase 6 Organization機能 詳細設計書 v2

## 1. Organization機能の責務

Organization機能は、ユーザーごとの階層型整理構造を管理する。

| 責務 | 担当 |
|---|---|
| organizationツリーの取得 | `OrganizationService` |
| organizationの作成・名前変更・移動・削除 | `OrganizationService` |
| 親子関係、所有者境界、循環参照の検証 | `OrganizationService` |
| memoとorganizationの確定済み関連（`AI_SUGGESTED` / `CURRENT`）の更新 | `MemoOrganizationService` |
| organization削除可否確認用のmemo参照照会 | `MemoOrganizationQueryService` |

`OrganizationService`は`organizations`を所有するが、`memo_organizations`を直接操作しない。
削除可否判定でメモ関連を確認する場合は、Memo機能が公開するServiceメソッドを呼び出す。

### 1.1 対象操作

- organizationツリー取得
- organization作成
- organization名前変更
- organization移動
- organization削除可否確認
- organization削除

### 1.2 対象外

- メモの`CURRENT`、`AI_SUGGESTED`の更新、および `ai_processes.proposed_result` の管理
- AIによるorganization候補生成
- メモ一覧・検索処理
- ユーザー向けメッセージ文言の決定

---

## 2. パッケージ構成

```text
com.github.maayyu.personaldevproject.organization
│
├─ controller/
│  └─ OrganizationController
│
├─ service/
│  ├─ OrganizationService
│  └─ OrganizationQueryService
│
├─ repository/
│  └─ OrganizationRepository
│
├─ entity/
│  └─ Organization
│
├─ model/
│  ├─ OrganizationDeletionCheckResult
│  └─ OrganizationDeletionReason
│
└─ dto/
   ├─ OrganizationCreateRequest
   ├─ OrganizationRenameRequest
   ├─ OrganizationMoveRequest
   ├─ OrganizationResponse
   ├─ OrganizationTreeResponse
   ├─ OrganizationDeletionCheckResponse
   └─ OrganizationDeletionReasonResponse
```

### 補足

- 名前変更と移動は検証内容が異なるため、Request DTOとServiceメソッドを分ける。
- `OrganizationDeletionCheckResult`はService内部の業務結果、`OrganizationDeletionCheckResponse`は外部APIの表現とする。
- 削除不可理由はJavaの`enum`で管理し、画面表示文言とは分離する。
- 更新処理は`OrganizationService`へまとめ、他機能向けの所有者検証だけを`OrganizationQueryService`へ分離する。
- Organization側の所有者検証は `OrganizationQueryService`、memo関連の読み取り照会は `MemoOrganizationQueryService` へ分離する。
- 通常の整理先変更では `MemoOrganizationService → OrganizationService` の依存を許可する一方、`OrganizationService` は `MemoOrganizationService` ではなく `MemoOrganizationQueryService` を参照する。これによりSpring Beanの循環依存を作らない。
- ControllerへEntityを直接返さない。

---

## 3. クラス責務

## 3.1 `OrganizationController`

### 責務

- Organization関連HTTPリクエストの受付
- Request DTOの形式・Bean Validation検証
- ログインユーザーIDのServiceへの受け渡し
- Service呼び出し
- Response DTOとHTTPステータスの返却

### Controllerに置かない処理

- organizationの所有者判定
- 親organizationの所有者判定
- 自分自身・子孫への移動判定
- 削除不可理由の収集
- Repositoryの直接呼び出し
- トランザクション制御

### 対応API

| API | 呼び出し先 |
|---|---|
| `GET /organizations/tree` | `OrganizationService#getOrganizationTree` |
| `POST /organizations` | `OrganizationService#createOrganization` |
| `PATCH /organizations/{id}/name` | `OrganizationService#renameOrganization` |
| `PATCH /organizations/{id}/parent` | `OrganizationService#moveOrganization` |
| `GET /organizations/{id}/deletion-check` | `OrganizationService#checkDeletionAllowed` |
| `DELETE /organizations/{id}` | `OrganizationService#deleteOrganization` |

---

## 3.2 `OrganizationService`

### 責務

- ログインユーザーが所有するorganizationの取得
- ツリー構造への変換
- organizationの作成・名前変更・移動・削除
- `normalized_name` の生成と同一階層重複の業務検証
- 親子関係とユーザー境界の検証
- 循環参照の防止
- 削除可否と削除不可理由の判定
- EntityとResponse DTOの変換

### 公開メソッド

```java
List<OrganizationTreeResponse> getOrganizationTree(Long userId);

OrganizationResponse createOrganization(
    Long userId,
    OrganizationCreateRequest request
);

OrganizationResponse renameOrganization(
    Long userId,
    Long organizationId,
    OrganizationRenameRequest request
);

OrganizationResponse moveOrganization(
    Long userId,
    Long organizationId,
    OrganizationMoveRequest request
);

OrganizationDeletionCheckResponse checkDeletionAllowed(
    Long userId,
    Long organizationId
);

void deleteOrganization(Long userId, Long organizationId);
```

### private補助メソッド候補

```java
Organization getOwnedOrganization(Long userId, Long organizationId);

Organization getOwnedParentOrNull(Long userId, Long parentId);

void validateMove(
    Long userId,
    Organization target,
    Organization newParent
);

boolean wouldCreateCycle(
    Long userId,
    Long targetId,
    Organization newParent
);

OrganizationDeletionCheckResult collectDeletionBlockers(
    Long userId,
    Long organizationId
);

List<OrganizationTreeResponse> buildTree(
    List<Organization> organizations
);

String normalizeName(String name);

void validateNoDuplicateName(
    Long userId,
    Long parentId,
    String normalizedName,
    Long excludeOrganizationId
);
```

---

## 3.3 `OrganizationQueryService`

### 責務

Memo機能およびAI Integration機能に対し、organizationの存在・所有者検証を提供する。
更新処理と削除可否判定は持たない。

```java
void validateOwnedOrganizations(
    Long userId,
    Collection<Long> organizationIds
);

Optional<Long> findOrganizationIdByNormalizedName(
    Long userId,
    Long parentId,
    String normalizedName
);
```

`findOrganizationIdByNormalizedName(...)` はAI結果の名称・親子関係を既存organization IDへ解決する内部用途で使用する。`parentId = null` の場合はルート階層を対象とする。

### ルール

- 入力IDの重複を除去して検証する。
- 指定された全IDがログインユーザー所有である場合のみ正常終了する。
- 1件でも存在しない、または他ユーザー所有のIDが含まれる場合は処理を拒否する。
- 呼び出し元が`OrganizationRepository`を直接参照する代わりに使用する。
- `OrganizationService`へ依存しない。

これにより依存関係は次の形となり、Beanの循環依存を発生させない。

```text
通常CURRENT変更:
MemoOrganizationService → OrganizationService → MemoOrganizationQueryService → MemoOrganizationRepository

Organization所有者検証:
MemoOrganizationService / AiResultService → OrganizationQueryService → OrganizationRepository
```

---

## 3.4 `OrganizationRepository`

### 責務

`organizations`テーブルへの永続化・参照を担当する。

Spring Data JPA Repositoryとして実装する。

### 必要なRepository操作

```java
Optional<Organization> findByIdAndUserId(Long id, Long userId);

List<Organization> findAllByUserIdOrderByNameAscIdAsc(Long userId);

boolean existsByParentIdAndUserId(Long parentId, Long userId);

List<Organization> findAllByParentIdAndUserIdOrderByNameAscIdAsc(
    Long parentId,
    Long userId
);

Optional<Organization> findByUserIdAndParentIdAndNormalizedName(
    Long userId,
    Long parentId,
    String normalizedName
);

Optional<Organization> findByUserIdAndParentIdIsNullAndNormalizedName(
    Long userId,
    String normalizedName
);
```

標準の`save`、`delete`を併用する。

### 取得方針

- 所有者確認を別クエリに分離せず、原則として`id + userId`で取得する。
- ツリー取得はユーザー配下を一括取得し、Serviceで親子構造を構築する。
- organizationごとに子要素を逐次取得するN+1方式は使用しない。

---

## 3.5 `OrganizationDeletionCheckResult`

Service内部で削除可否と阻害情報を保持する。

```java
public record OrganizationDeletionCheckResult(
    boolean deletable,
    Map<OrganizationDeletionReason, List<Long>> blockingTargetIds
) {}
```

`blockingTargetIds`は削除不可理由と、原因となった子organization IDまたはメモIDの対応を保持する。
Memo機能から受け取った参照情報は、`OrganizationService`内でこの形式へ変換する。

Memo機能が返す内部連携用モデルは次を区別できるものとする。

- `CURRENT`参照中のメモ
- `AI_SUGGESTED`参照中のメモ

未判断再整理提案は `memo_organizations` ではなく `ai_processes.proposed_result` に保持するため、organization削除可否の参照理由には含めない。既存organizationが提案保存後に削除された場合は、提案採用時に `409 / PROPOSAL_STALE` として扱う。

Organization側は`memo_organizations`のRepositoryやEntityへ直接依存しない。

---

## 3.6 `OrganizationDeletionReason`

```java
public enum OrganizationDeletionReason {
    HAS_CHILD_ORGANIZATION,
    HAS_CURRENT_MEMO,
    HAS_AI_SUGGESTED_REFERENCE
}
```

### 判定条件

| 理由コード | 条件 |
|---|---|
| `HAS_CHILD_ORGANIZATION` | 対象を親とするorganizationが1件以上存在する |
| `HAS_CURRENT_MEMO` | 対象を`CURRENT`で参照するメモが1件以上存在する |
| `HAS_AI_SUGGESTED_REFERENCE` | 対象を`AI_SUGGESTED`で参照するメモが1件以上存在する |

複数条件に該当する場合、該当する理由をすべて返す。

---

## 4. Entity詳細

## 4.1 `Organization`

### 対応テーブル

`organizations`

### フィールド

| Java項目 | DBカラム | 必須 | 概要 |
|---|---|---:|---|
| `id` | `id` | Yes | organization ID |
| `userId`または`user` | `user_id` | Yes | 所有ユーザー |
| `parentId`または`parent` | `parent_id` | No | 親organization。ルート直下はNULL |
| `name` | `name` | Yes | 表示名 |
| `normalizedName` | `normalized_name` | Yes | 同一階層の重複判定用。前後空白除去＋ `toLowerCase(Locale.ROOT)` |
| `createdAt` | `created_at` | Yes | 作成日時 |
| `updatedAt` | `updated_at` | Yes | 更新日時 |

### Entityの責務

- DBレコードとのマッピング
- organization自身の状態保持
- `normalizedName` を同名判定用の正規化値として保持
- 名前と親の変更

### JPA関連付け方針

- MVPでは`parentId`のみを保持する方針を第一候補とする。
- 親・子の双方向自己参照マッピングは必須としない。
- ツリー構築、親確認、循環確認はRepositoryとServiceで明示的に行う。
- `memo_organizations`との双方向関連は持たない。

この方針により、遅延ロードと再帰シリアライズによる複雑化を避ける。

---

## 5. DTO詳細

## 5.1 `OrganizationCreateRequest`

### 用途

`POST /organizations`

```java
public record OrganizationCreateRequest(
    String name,
    Long parentId
) {}
```

### ルール

- `name`は必須。
- `name`はtrim後1文字以上とする。
- `parentId == null`はルート直下への作成を表す。
- `parentId`指定時はログインユーザー所有のorganizationに限る。
- 保存値は前後空白を除去した値とする。

---

## 5.2 `OrganizationRenameRequest`

### 用途

`PATCH /organizations/{id}/name`

```java
public record OrganizationRenameRequest(
    String name
) {}
```

### ルール

- `name`は必須。
- `name`はtrim後1文字以上とする。
- 保存値は前後空白を除去した値とする。
- 現在と同じ名称の場合は成功扱いとし、状態を変更しない。

---

## 5.3 `OrganizationMoveRequest`

### 用途

`PATCH /organizations/{id}/parent`

```java
public record OrganizationMoveRequest(
    Long newParentId
) {}
```

### ルール

- `newParentId == null`はルート直下への移動を表す。
- 自分自身は指定できない。
- 自分の子孫は指定できない。
- 他ユーザーのorganizationは指定できない。
- 現在と同じ親を指定した場合は成功扱いとし、状態を変更しない。

JSONで`newParentId`を省略した場合もルート移動と解釈するため、明示的な`null`との区別は不要とする。

---

## 5.4 `OrganizationResponse`

### 用途

作成・名前変更・移動結果、および他機能で使用するorganization概要。

```java
public record OrganizationResponse(
    Long id,
    String name,
    Long parentId,
    Instant createdAt,
    Instant updatedAt
) {}
```

ユーザーIDは認証済み利用者自身の情報であり、Responseには含めない。

Memo機能の`OrganizationSummary`は本DTOをそのまま共有せず、必要項目だけを持つMemo側専用DTOとする。
機能間で外部API DTOを共有すると変更影響が広がるためである。

---

## 5.5 `OrganizationTreeResponse`

### 用途

`GET /organizations/tree`

```java
public record OrganizationTreeResponse(
    Long id,
    String name,
    Long parentId,
    List<OrganizationTreeResponse> children
) {}
```

### ルール

- ルートorganizationの配列を返す。
- 子要素は`children`へ再帰的に格納する。
- 子がない場合も`children`は`null`ではなく空配列とする。
- 各階層は`name`昇順、同名時は`id`昇順とする。

---

## 5.6 `OrganizationDeletionCheckResponse`

### 用途

`GET /organizations/{id}/deletion-check`および削除不可時の詳細表現。

```java
public record OrganizationDeletionCheckResponse(
    Long organizationId,
    boolean deletable,
    List<OrganizationDeletionReasonResponse> reasons
) {}
```

削除可能な場合、`reasons`は空配列とする。

---

## 5.7 `OrganizationDeletionReasonResponse`

```java
public record OrganizationDeletionReasonResponse(
    OrganizationDeletionReason code,
    long count,
    List<Long> targetIds
) {}
```

### `targetIds`の意味

| 理由コード | `targetIds` |
|---|---|
| `HAS_CHILD_ORGANIZATION` | 直接の子organization ID |
| `HAS_CURRENT_MEMO` | 該当メモID |
| `HAS_AI_SUGGESTED_REFERENCE` | 該当メモID |

### 返却方針

- `count`は重複を除いた対象件数とする。
- `targetIds`も重複を除く。
- 画面は理由コードをユーザー向け文言へ変換する。
- organizationの子要素はツリー内で強調できる。
- メモIDは既存のメモ詳細へ進む導線に利用できる。
- 技術用語である理由コードをそのまま画面表示しない。

---

## 6. 他機能との依存境界

## 6.1 Memo機能

削除可否確認は次の依存方向とする。

```text
OrganizationController
    ↓
OrganizationService
    ↓
MemoOrganizationQueryService
    ↓
MemoOrganizationRepository
```

### Memo側に追加する読み取り専用Service

`MemoOrganizationQueryService` は、organization削除可否確認等で必要なmemo参照照会のみを担当する。

```java
MemoOrganizationReferenceSummary findReferencesByOrganizationId(
    Long userId,
    Long organizationId
);
```

`MemoOrganizationReferenceSummary`は、現行の `memo_organizations` 関係種別ごとのメモID一覧を返す。

```java
public record MemoOrganizationReferenceSummary(
    List<Long> currentMemoIds,
    List<Long> aiSuggestedMemoIds
) {}
```

### 境界ルール

- Organization側は`MemoOrganizationRepository`を直接参照せず、読み取りは `MemoOrganizationQueryService` を経由する。
- `MemoOrganizationService` は通常の整理先変更時に `OrganizationService` へ新規organization作成を依頼できる。
- `MemoOrganizationQueryService` は `OrganizationService` へ依存しない読み取り専用Serviceとする。
- Memo側から既存Organizationを検証する場合は`OrganizationQueryService#validateOwnedOrganizations`を使用する。
- Memo側はorganizationの削除・移動・名前変更を行わない。
- 削除可否照会は読み取り専用とする。

依存方向は次のようにし、Bean循環依存を作らない。

```text
MemoOrganizationService
    ↓
OrganizationService
    ↓
MemoOrganizationQueryService
    ↓
MemoOrganizationRepository
```

---

## 6.2 AI Integration機能

AI整理結果とOrganization機能の境界は次のとおりとする。

- AI結果に既存 `organizationId` が含まれる内部処理では、`OrganizationQueryService#validateOwnedOrganizations` で存在・所有者を検証する。
- AI Serviceが返す名称・親子関係から既存organizationを解決する場合は、`OrganizationQueryService#findOrganizationIdByNormalizedName` を使用し、同一ユーザー・同一親配下の `normalized_name` 一致で判定する。
- ルートorganizationは `parentId = null` として同一ユーザー内のルート `normalized_name` 一致で解決する。
- REORGANIZEの提案保存時は既存organizationのID解決だけを行い、この時点では新規organizationを作成しない。

Organization機能は次を行わない。

- AI ServiceとのHTTP通信
- AI提案の採用判断
- `AI_SUGGESTED`、`CURRENT`、`ai_processes.proposed_result`の登録・更新

---

## 7. トランザクション設計

## 7.1 `createOrganization`

1トランザクションで実施する。APIから直接呼ばれる場合は `OrganizationService#createOrganization` がトランザクション境界となり、`AiResultService` や `MemoOrganizationService` の既存トランザクションから呼ばれる場合はそのトランザクションへ参加する。

```text
BEGIN / 呼び出し元トランザクションへ参加
  親organizationの存在・所有者確認
  normalized_name生成
  同一ユーザー・同一親配下（ルートはparent_id = NULL）の重複確認
  organization保存
  DB一意制約競合 → 409 / ORGANIZATION_CONFLICT
COMMIT（自ら開始した場合）
```

親指定なしの場合は親確認を行わない。業務検証後にもDB一意制約を最終防衛線とする。

---

## 7.2 `renameOrganization`

1トランザクションで実施する。

```text
BEGIN
  対象organizationの存在・所有者確認
  nameからnormalized_nameを再生成
  同一ユーザー・同一親配下の名称重複を自分自身を除外して確認
  name・normalized_name更新
  DB一意制約違反 → 409 / ORGANIZATION_CONFLICT
COMMIT
```

名前変更は子organizationおよびメモ関連へ影響しない。

---

## 7.3 `moveOrganization`

1トランザクションで実施する。

```text
BEGIN
  対象organizationの存在・所有者確認
  新しい親の存在・所有者確認
  自分自身指定を確認
  子孫指定による循環発生を確認
  移動先の同一ユーザー・同一親配下でnormalized_name重複を自分自身を除外して確認
  parent_id更新
  DB一意制約違反 → 409 / ORGANIZATION_CONFLICT
COMMIT
```

### 循環確認方式

新しい親候補から`parent_id`を上方向へたどり、対象organization自身へ到達するか確認する。

```text
newParent
  ↓ parent
ancestor
  ↓ parent
...
```

- 対象organizationへ到達した場合は循環するため拒否する。
- `parent_id == null`へ到達した場合は移動可能とする。
- 同じIDを再訪した場合は既存データ不整合として処理を中止し、500系の内部エラーとして記録する。

---

## 7.4 `checkDeletionAllowed`

読み取り専用トランザクションで実施する。

```text
対象organizationの存在・所有者確認
↓
OrganizationRepositoryで直接の子を確認
↓
MemoOrganizationQueryServiceでCURRENT／AI_SUGGESTEDの2種のメモ参照を確認
↓
該当する理由をすべて収集
↓
OrganizationDeletionCheckResponseを返却
```

削除可能条件は次のすべてを満たすことである。

```text
子organizationなし
AND CURRENT参照なし
AND AI_SUGGESTED参照なし
```

末端organizationであっても、`CURRENT` または `AI_SUGGESTED` のメモ関連が存在する場合は削除できない。

未判断 `proposed_result` が当該organization IDを参照していることだけを理由には削除をブロックしない。削除後にその提案を採用しようとした場合は、採用側で `409 / PROPOSAL_STALE` として提案を保持する。

---

## 7.5 `deleteOrganization`

1トランザクションで実施する。

```text
BEGIN
  対象organizationの存在・所有者確認
  削除阻害条件を再確認
  ├─ あり → OrganizationDeletionNotAllowedException
  └─ なし → organization削除
COMMIT
```

### 重要

- 事前に削除可否APIを実行済みでも、DELETE処理内で必ず再判定する。
- 事前確認後に子やメモ関連が追加された場合は削除しない。
- DB外部キーは`RESTRICT`を基本とし、競合によって業務判定後に参照が追加された場合もDBで削除を防ぐ。
- 外部キー制約違反が発生した場合は削除不可として扱い、可能な範囲で理由を再取得して409へ変換する。
- 子organizationや`memo_organizations`を連鎖削除しない。

---

## 8. API別処理詳細

## 8.1 `GET /organizations/tree`

### 処理

```text
ログインユーザーID取得
↓
ユーザー配下のorganizationを一括取得
↓
parentId単位でグループ化
↓
ルートから再帰的にResponseを構築
↓
200 OK
```

### Response例

```json
[
  {
    "id": 1,
    "name": "学習",
    "parentId": null,
    "children": [
      {
        "id": 2,
        "name": "Java",
        "parentId": 1,
        "children": []
      }
    ]
  }
]
```

organizationが0件の場合は`200 OK`と空配列を返す。

---

## 8.2 `POST /organizations`

### Request例

```json
{
  "name": "Spring Boot",
  "parentId": 2
}
```

ルート直下へ作成する場合：

```json
{
  "name": "仕事",
  "parentId": null
}
```

### 処理

```text
入力検証
↓
親指定あり？
├─ Yes → 親の存在・所有者確認
└─ No
↓
保存
↓
201 Created
```

### エラー

- 名称がnull／空白のみ：400
- 親organizationが存在しない、または他ユーザー所有：404
- 未認証：401
- 同一階層の名称重複：409 / `ORGANIZATION_CONFLICT`
- その他のDB保存失敗：500

---

## 8.3 `PATCH /organizations/{id}/name`

### Request例

```json
{
  "name": "Java Silver"
}
```

### 処理

```text
入力検証
↓
対象の存在・所有者確認
↓
名称更新
↓
200 OK
```

### エラー

- 名称がnull／空白のみ：400
- 対象が存在しない、または他ユーザー所有：404
- 未認証：401
- 同一階層の名称重複：409 / `ORGANIZATION_CONFLICT`
- その他のDB更新失敗：500

---

## 8.4 `PATCH /organizations/{id}/parent`

### Request例

```json
{
  "newParentId": 10
}
```

ルート直下へ移動する場合：

```json
{
  "newParentId": null
}
```

### 処理

```text
対象の存在・所有者確認
↓
新しい親の存在・所有者確認
↓
自分自身・子孫でないことを確認
├─ NG → 409 Conflict
└─ OK
    ↓
    parent_id更新
    ↓
    200 OK
```

### エラー

- 対象または新しい親が存在しない、他ユーザー所有：404
- 自分自身を指定：409
- 子孫を指定して循環が発生：409
- 未認証：401
- 同一階層の名称重複：409 / `ORGANIZATION_CONFLICT`
- その他のDB更新失敗：500

---

## 8.5 `GET /organizations/{id}/deletion-check`

### 削除可能Response例

```json
{
  "organizationId": 2,
  "deletable": true,
  "reasons": []
}
```

### 削除不可Response例

```json
{
  "organizationId": 2,
  "deletable": false,
  "reasons": [
    {
      "code": "HAS_CHILD_ORGANIZATION",
      "count": 1,
      "targetIds": [3]
    },
    {
      "code": "HAS_CURRENT_MEMO",
      "count": 2,
      "targetIds": [101, 105]
    }
  ]
}
```

削除不可は正常な照会結果であるため、本APIは`200 OK`を返す。

### エラー

- 対象が存在しない、または他ユーザー所有：404
- 未認証：401
- DB参照失敗：500

---

## 8.6 `DELETE /organizations/{id}`

### 成功

削除完了時は`204 No Content`を返す。

### 削除不可

削除阻害条件がある場合は`409 Conflict`とし、共通エラー形式の`details`に削除可否Responseと同じ理由情報を含める。

```json
{
  "code": "ORGANIZATION_DELETE_NOT_ALLOWED",
  "message": "organizationを削除できません。",
  "details": {
    "organizationId": 2,
    "deletable": false,
    "reasons": [
      {
        "code": "HAS_AI_SUGGESTED_REFERENCE",
        "count": 1,
        "targetIds": [101]
      }
    ]
  }
}
```

### エラー

- 対象が存在しない、または他ユーザー所有：404
- 削除阻害条件あり：409
- 未認証：401
- DB削除失敗：500

---

## 9. 入力検証・業務検証

| 対象 | 条件 | 結果 |
|---|---|---|
| 作成`name` | null | 400 |
| 作成`name` | trim後空 | 400 |
| 名前変更`name` | null | 400 |
| 名前変更`name` | trim後空 | 400 |
| `parentId`／`newParentId` | 存在しない | 404 |
| `parentId`／`newParentId` | 他ユーザー所有 | 404 |
| 移動先 | 自分自身 | 409 |
| 移動先 | 自分の子孫 | 409 |
| 削除 | 子organizationあり | 409＋`HAS_CHILD_ORGANIZATION` |
| 削除 | `CURRENT`参照あり | 409＋`HAS_CURRENT_MEMO` |
| 削除 | `AI_SUGGESTED`参照あり | 409＋`HAS_AI_SUGGESTED_REFERENCE` |
| 作成・名前変更・移動 | 同一ユーザー・同一親配下に同じ `normalized_name` が存在 | 409＋`ORGANIZATION_CONFLICT` |
| ルート作成・名前変更・移動 | 同一ユーザーのルートに同じ `normalized_name` が存在 | 409＋`ORGANIZATION_CONFLICT` |

### 所有者情報の非公開

他ユーザー所有のIDを指定した場合も、対象の存在を推測させないため404として扱う。
この方針は共通認可詳細設計で変更された場合、全機能で統一する。

---

## 10. 例外・エラーコード

| 例外／コード | HTTP | 用途 |
|---|---:|---|
| `OrganizationNotFoundException` / `ORGANIZATION_NOT_FOUND` | 404 | 対象または親が存在しない、もしくはユーザー所有外 |
| `InvalidOrganizationNameException` / `INVALID_ORGANIZATION_NAME` | 400 | 名称が入力条件を満たさない |
| `OrganizationCycleException` / `ORGANIZATION_CYCLE` | 409 | 自分自身・子孫への移動 |
| `OrganizationDeletionNotAllowedException` / `ORGANIZATION_DELETE_NOT_ALLOWED` | 409 | 削除阻害条件が存在する |
| `OrganizationConflictException` / `ORGANIZATION_CONFLICT` | 409 | 同一階層の `normalized_name` 一意制約、参照整合性等の競合 |
| `AuthenticationRequiredException` / `AUTHENTICATION_REQUIRED` | 401 | 未認証 |

例外からHTTPレスポンスへの変換は共通の`@RestControllerAdvice`で行う。
Controller内で個別に`try-catch`しない。

---

## 11. ツリー構築ルール

### 11.1 構築方法

1. ログインユーザーのorganizationを一括取得する。
2. `parentId`をキーに子要素をグループ化する。
3. `parentId == null`の要素をルートとして扱う。
4. 各ルートから子要素を再帰的にResponseへ変換する。

### 11.2 防御的検証

DB制約だけでは循環を完全には表現できないため、ツリー構築時にも訪問中IDを管理する。

- 同一経路で同じIDを再訪した場合は循環データとして検出する。
- 親IDが存在しない孤立データを検出した場合はログへ記録する。
- 不整合データを別ユーザーのルートへ補完しない。
- 利用者へDB内部情報を返さない。

不整合時の画面表示と運用復旧方法は共通障害設計で確定する。

---

## 12. 画面連携

## 12.1 作成・名前変更

- 成功後は返却されたorganizationをツリーへ反映する。
- 400の場合は入力欄付近に理由を表示し、入力内容を保持する。

## 12.2 移動

- 移動先候補から対象自身と子孫を画面側でも除外する。
- Service側でも同じ業務検証を行う。
- 成功後はツリーを更新し、移動後の位置を表示する。
- 409の場合は最新ツリーを再取得できる導線を出す。

## 12.3 削除

1. 削除可否APIを呼び出す。
2. 削除可能なら確認モーダルを表示する。
3. 削除不可なら全理由を表示する。
4. `targetIds`を使い、子organizationの強調または該当メモ詳細への導線を表示する。
5. 削除確定後にDELETEを呼び出す。
6. DELETEが409の場合は、返された最新理由で表示を更新する。

理由コードとユーザー向け表示例：

| 理由コード | 表示例 | 次の操作 |
|---|---|---|
| `HAS_CHILD_ORGANIZATION` | 子の整理項目が残っています | 対象の子をツリー上で強調する |
| `HAS_CURRENT_MEMO` | 現在この整理先を使用しているメモがあります | 対象メモ詳細で整理先を変更する |
| `HAS_AI_SUGGESTED_REFERENCE` | 初回AI整理結果から参照されています | 対象メモ詳細で初回整理結果を確認する |

---

## 13. セキュリティ

- 全APIでSession＋Cookie認証を必須とする。
- ServiceはControllerから渡されたユーザーIDを使用し、organization IDだけで更新・削除しない。
- 親organizationと移動先も同一ユーザー所有であることを確認する。
- 他ユーザーのorganization名、階層、参照メモIDをResponseへ含めない。
- RequestのIDを信頼してRepositoryの標準`findById`だけで処理しない。
- CSRF対策は共通認証・セキュリティ詳細設計に従う。

---

## 14. 性能方針

- ツリーはユーザー単位で一括取得し、アプリケーション上で構築する。
- 一覧取得時に子要素ごとの追加SQLを発行しない。
- `organizations.user_id`、`organizations.parent_id`にはインデックスを設定する候補とする。
- `memo_organizations.organization_id`は削除可否確認で使用するためインデックス対象とする。
- 大規模化して一括取得が問題になった場合のみ、遅延取得または部分ツリーAPIを検討する。MVPでは追加しない。

---

## 15. 単体テスト観点

## 15.1 `OrganizationService`

- organizationが0件の場合、空配列を返す。
- 複数階層を正しい親子構造と順序で返す。
- 自分の親配下・ルート直下へ作成できる。
- 他ユーザーの親配下へ作成できない。
- 空白だけの名称で作成・名前変更できない。
- `Java` / `java` / `JAVA` を同一親配下では重複として拒否する。
- 同一ユーザーのルート階層でも `normalized_name` 重複を拒否する。
- 別の親配下では同じ `normalized_name` を許可する。
- 同じ名称・同じ親への更新が成功扱いになる。
- ルート直下へ移動できる。
- 自分自身または子孫へ移動できない。
- 他ユーザー配下へ移動できない。
- 削除不可理由を複数同時に返す。
- 子もメモ参照もない場合だけ削除できる。
- 末端でもメモ参照があれば削除できない。
- 削除可否確認後に阻害条件が増えた場合、DELETE時に拒否する。

## 15.2 `OrganizationController`

- 各成功時のHTTPステータスとResponse形式が正しい。
- 入力不正を400へ変換する。
- 未認証を401へ変換する。
- 所有外IDを404へ変換する。
- 循環移動と削除不可を409へ変換する。
- DELETE成功時にResponse Bodyを返さない。

## 15.3 `OrganizationRepository`

- `id + userId`検索で他ユーザーのデータを取得しない。
- user単位一覧を決定的な順序で取得する。
- 直接の子organization有無を正しく判定する。
- 同一親配下の `normalized_name` 一致を取得できる。
- ルート階層の `normalized_name` 一致を取得できる。

---

## 16. 確定事項と後続設計への申し送り

### 16.1 本書で確定する事項

- Organizationは階層構造そのものを管理する。
- `memo_organizations`の参照確認は読み取り専用の`MemoOrganizationQueryService`へ問い合わせる。
- 他機能向け所有者検証を`OrganizationQueryService`へ分け、Memo機能との循環依存を防ぐ。
- 作成、名前変更、移動はそれぞれ専用RequestとServiceメソッドを使用する。
- ツリーはユーザー配下を一括取得してServiceで構築する。
- 自分自身・子孫・他ユーザー配下への移動を拒否する。
- 削除可否確認用APIを設け、DELETEでも再判定する。
- 削除不可理由は次の3種類とする。
  - `HAS_CHILD_ORGANIZATION`
  - `HAS_CURRENT_MEMO`
  - `HAS_AI_SUGGESTED_REFERENCE`
- 未判断 `proposed_result` の参照だけではorganization削除をブロックせず、削除後の採用時は `PROPOSAL_STALE` とする。
- 末端かつ、`CURRENT` / `AI_SUGGESTED` のメモ関連から参照されていないorganizationだけ削除できる。
- `normalized_name` は前後空白除去＋小文字化（Javaでは `Locale.ROOT`）とする。
- 同一ユーザー・同一親配下の `normalized_name` 重複を禁止し、ルートでも同一ユーザー内の重複を禁止する。
- 同名判定はアプリ事前検証とDB一意制約の双方で保証する。
- DBの`RESTRICT`を最終防衛線とし、子・関連データの連鎖削除は行わない。
- Memo側の`OrganizationSummary`はMemo専用DTOとし、Organizationの外部API DTOを共有しない。

### 16.2 DB物理設計への申し送り

- `name`の最大長とDBカラム長
- `normalized_name` のカラム長
- 主キー生成方式
- `created_at`、`updated_at`の物理型と更新方式
- `user_id`、`parent_id`、`memo_organizations.organization_id`のインデックス
- PostgreSQL 17ではルート・子階層を一括で扱う `uq_organizations_sibling_name`（`UNIQUE NULLS NOT DISTINCT (user_id, parent_id, normalized_name)`）を採用する。ルート用／子階層用の独立した2制約は設けない
- 自己参照外部キーおよび`memo_organizations`外部キーの`ON DELETE RESTRICT`

### 16.2.1 Phase 8 Wave 0-A：PostgreSQL同名制約の具体化

```sql
ALTER TABLE organizations
ADD CONSTRAINT uq_organizations_sibling_name
UNIQUE NULLS NOT DISTINCT (user_id, parent_id, normalized_name);
```

- `normalized_name` はMVP既定どおり前後空白除去＋Java `toLowerCase(Locale.ROOT)` で生成する。ルート `parent_id=NULL` の重複も同じDB制約で拒否する。
- Serviceでの重複事前チェックを維持し、制約違反はユーザー操作時に `409 / ORGANIZATION_CONFLICT` として扱う。AI結果反映中のDB競合は従来どおりROLLBACK後にAI処理をFAILEDとする。
- `organizations.parent_id`と`memo_organizations.organization_id`など参照側FKの索引は実際の検索・削除パターンに基づきFlyway V1で検討する。親子のユーザー所有者一致は既存Service検証を維持し、複合FKの要否は実DBの設計時に別途評価する。

### 16.3 共通API・画面詳細設計への申し送り

- 共通エラーレスポンスの最終JSON形式
- 404／403の全機能統一
- 削除不可理由ごとの最終表示文言
- `targetIds`が多い場合の上限・ページング
- ツリー上の選択・展開状態とAPI再取得時の維持方法
- `name`の最大文字数

`name`最大長は後続で確定する。同名可否は確定済みで、同一ユーザー・同一親配下および同一ユーザーのルート階層で `normalized_name` 重複を禁止する。
