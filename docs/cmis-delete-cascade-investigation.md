# CMIS delete と parentChildRelationship カスケードの調査結果

**調査日**: 2026-01-24  
**目的**: CMIS 1.1 仕様に parentChild 連鎖削除の概念があるか、NemakiWare の現状と REST API 追加の妥当性を確認する。

---

## 1. 結論

- **CMIS 1.1 には「リレーションシップに基づく連鎖削除」の規定はない。**
- **deleteObject は「指定した 1 オブジェクトだけを削除」する。**
- **nemaki:parentChildRelationship で親を CMIS deleteObject すると、親オブジェクトだけが削除され、子（target）は残る。**
- そのため、**「ParentChild を考慮した削除」を REST API として持つのは妥当**である。

---

## 2. CMIS 1.1 仕様の要点

### 2.1 deleteObject

- Object Services の `deleteObject` は「指定したオブジェクトを削除する」のみ規定。
- リレーションシップ型や、親子関係に基づく連鎖削除の記述はない。

### 2.2 deleteTree

- **フォルダ専用**。
- フォルダ内の子オブジェクト（`getChildren` で取得）を再帰的に削除する。
- これは **フォルダ包含（implicit parent-child）** の階層であり、
  **explicit relationship（リレーションシップオブジェクト）** とは別。

### 2.3 Relationship Object（2.1.6）

- 仕様: "A relationship object represents an instance of a **directional relationship between two objects**."
- 削除に関する記述: リレーションシップオブジェクト自体の delete のみ。
- **source/target の削除時に、相手側や子孫を連鎖削除する規定はない。**

### 2.4 フォルダ包含 vs リレーションシップ

仕様 2.1.5 より:

> "This **system-maintained implicit relationship** is distinct from an **explicit relationship** which is instantiated by an application-maintained relationship object."

- **Implicit**: フォルダ包含（親フォルダ ↔ 子オブジェクト）。`getChildren` / `deleteTree` で扱う。
- **Explicit**: リレーションシップオブジェクト（source ↔ target）。`createRelationship` 等で作成。

`nemaki:parentChildRelationship` は **explicit** なリレーションシップであり、CMIS の deleteObject/deleteTree の対象外。

### 2.5 片側削除時のリレーションシップオブジェクトの扱い（ご質問への回答）

**結論: CMIS 1.1 規格では、リレーションシップの source または target のいずれかが削除された場合の、リレーションシップオブジェクトの扱いは規定されていません。リポジトリ実装に委ねられています。**

- **Relationship Object（2.1.6）**: source/target を持つ「2 オブジェクト間の方向的関係」として定義されているが、**「片側が削除されたときにリレーションシップオブジェクトをどうするか」という規定はない**。
- **Id プロパティ（2.1.2.1.1）**:  
  "Unless explicitly specified, **id properties NEED NOT maintain a referential integrity constraint**. Therefore, storing the id of one object in another object **NEED NOT constrain the behavior** of either object. **A repository MAY, however, support referential constraint** underneath CMIS if the effect on CMIS services remains consistent with an allowable behavior of the CMIS model."  
  → 参照整合性は必須ではなく、リポジトリが「CMIS の許容される振る舞い」の範囲で独自に制約を持ってもよい、というレベル。
- したがって、実装としては例えば次のいずれも許容されます:
  - 片側削除時に**リレーションシップオブジェクトを自動削除**する（オーファンにしない）
  - リレーションシップオブジェクトは**残す**が、getObject 等で参照時にエラーまたは「存在しない」扱いにする
  - 片側削除を**制約違反として拒否**する

---

## 3. NemakiWare の実装確認

### 3.1 サーバー側 deleteObject

```java
// ObjectServiceImpl.deleteObject()
objectServiceInternal.deleteObjectInternal(..., deleteWithParent = false);

// ObjectServiceInternalImpl.deleteObjectInternal()
// - Document: contentService.deleteDocument(...)
// - Folder: getChildren() で子がいる場合、deleteWithParent=false なら制約違反
// - Relationship: contentService.delete() でリレーションシップオブジェクトのみ削除
//   → source/target のキャッシュ無効化はするが、それらの削除はしない
```

- `deleteObject` では `deleteWithParent = false` 固定。
- リレーションシップ削除時は、リレーションシップオブジェクトのみ削除し、source/target は削除しない。

### 3.2 片側削除時のリレーションシップの扱い（NemakiWare）

**Document/Folder 等を deleteObject した場合**、NemakiWare は**そのオブジェクトを source または target とするリレーションシップオブジェクトを先に一括削除**してから、本体オブジェクトを削除する。

- `ContentServiceImpl.delete()` 内で:
  - `getRelationshipsBySource(repositoryId, objectId)` と `getRelationshipsByTarget(repositoryId, objectId)` で関連リレーションシップを取得
  - それらの ID を `deleteRelationshipsBatch()` で一括削除
  - その後 `contentDaoService.delete()` でオブジェクト本体を削除
- そのため、**オーファンなリレーションシップ（存在しない source/target を参照するリレーション）は残らない**。規格で必須ではないが、整合性を保つ実装として採用している。

### 3.3 deleteTree

- フォルダのみ対象。
- `contentService.getChildren()`（フォルダ包含）で子を取得し、再帰的に削除。
- **parentChildRelationship は参照していない。**

### 3.4 UI の deleteObjectWithCascade（cmis.ts）

- `collectParentChildDescendants()` で `getRelationships` を呼び、`nemaki:parentChildRelationship` の子孫を探索。
- 子孫を葉から根の順で `deleteObject` を繰り返し呼び、最後にルートを削除。
- これは **クライアント側で複数回 CMIS delete を組み合わせて実現**している。

---

## 4. ユーザー指摘の整理

### 4.1 カスケードフラグについて

> 子孫要素のどこかにリレーションシップを貼った瞬間に上位全てにフラグを立てる操作が必要になり、一度ついたフラグを取り下げる処理は判定が高コストになる。

- その理解でよい。
- オブジェクトに「カスケード削除対象か」フラグを持たせる方式は、更新・整合性のコストが大きく、**実装としてあまり良くない**と判断して問題ない。

### 4.2 CMIS に連鎖削除がないこと

> parentChild のような連鎖削除は CMIS 標準の API セットには存在せず、それを複数の API の組合せで実現している。

- その認識で正しい。  
  - CMIS には「relationship に基づく連鎖削除」の規定はない。  
  - NemakiWare UI は、`getRelationships` + 複数回 `deleteObject` の組み合わせで実現している。

### 4.3 UI 経由でない場合の挙動

> NemakiWare 上で parentChild なリレーションシップをはってあっても、CMIS の delete を UI 経由せずに直接実行すると、リレーションシップを無視して親だけを消すことができてしまう。

- その理解で正しい。  
  - クライアントが CMIS `deleteObject` を直接呼ぶと、そのオブジェクトのみ削除される。  
  - 子（parentChildRelationship の target）は残り、親（source）のみ削除される。

---

## 5. ParentChild-aware な REST API を用意することの妥当性

- CMIS には **relationship 連鎖削除** の標準がなく、NemakiWare 固有のセマンティクスとなる。
- そのため、NemakiWare 拡張として **ParentChild を考慮した削除 REST API** を定義するのは妥当である。
- 例: `DELETE /rest/repo/{repoId}/cascade-delete/{objectId}`  
  - サーバー側で parentChildRelationship の子孫を探索し、  
  - 葉から根の順で一括削除する。

これにより:

- ネットワーク往復が減る（1 リクエストで完結）
- クライアント実装を単純化できる
- パフォーマンス改善が期待できる

---

## 6. 参考文献

- CMIS 1.1 Errata 01:  
  http://docs.oasis-open.org/cmis/CMIS/v1.1/errata01/os/CMIS-v1.1-errata01-os-complete.html
- NemakiWare 実装:  
  - `core/src/main/java/jp/aegif/nemaki/cmis/service/impl/ObjectServiceInternalImpl.java`  
  - `core/src/main/java/jp/aegif/nemaki/cmis/servlet/NemakiBrowserBindingServlet.java`（handleDeleteOperation）  
  - `core/src/main/webapp/ui/src/services/cmis.ts`（deleteObjectWithCascade, collectParentChildDescendants）
