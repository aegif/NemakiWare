# サーバー側 ParentChild カスケード削除 設計

**ブランチ**: feature/server-cascade-delete  
**目的**: オブジェクト削除時に parentChild リレーションの子孫を 1 リクエスト内で連鎖削除する。

---

## 1. 機序

1. **オブジェクト削除**時は常にリレーションをチェックし、関連リレーションを削除する（現行どおり）。
2. **リレーション削除時**に、そのリレーションが parentChild かどうかを判定する。
3. **parentChild かつ「オブジェクト削除の副次効果」**（親オブジェクト削除により削除される）の場合のみ、子オブジェクト削除を再帰呼び出しする。
4. **リレーションシップの直接削除**（リンクだけ外す）の場合は、子は削除しない。

---

## 2. エッジケースとテストケース

### 2.1 基本ケース

| ID | シナリオ | 期待結果 |
|----|----------|----------|
| B1 | 親 A → 子 B (parentChild)、deleteObject(A) | A と B とリレーションが削除される |
| B2 | 親 A → 子 B → 孫 C (parentChild 鎖)、deleteObject(A) | A, B, C とリレーションが削除される |
| B3 | deleteObject(リレーション)（リンクのみ削除） | 親・子とも残り、リレーションのみ削除 |

### 2.2 ループ・ダイヤモンド

| ID | シナリオ | 期待結果 |
|----|----------|----------|
| L1 | A→B→C→A のループ、deleteObject(A) | visited により無限ループせず、全ノード削除 |
| L2 | A→B→C, A→D→C（ダイヤモンド）、deleteObject(A) | C は 1 回だけ削除候補、重複なし |

### 2.3 混在

| ID | シナリオ | 期待結果 |
|----|----------|----------|
| M1 | A が parentChild の子と bidir の子を持つ、deleteObject(A) | parentChild の子のみカスケード、bidir の子は残る |
| M2 | 親が Folder、子が Document、deleteObject(親Folder) | フォルダ包含と parentChild の両方が扱われる |

### 2.4 境界

| ID | シナリオ | 期待結果 |
|----|----------|----------|
| E1 | parentChild の子がいない、deleteObject(A) | A とリレーションのみ削除（既存挙動） |
| E2 | 子が既に削除済み、deleteObject(親) | エラーにならず親削除完了 |
| E3 | nemaki:parentChildRelationship のサブタイプ、deleteObject(親) | サブタイプもカスケード対象 |
| E4 | 親は削除可・子は削除不可（ACL）、deleteObject(親) | permissionDenied で失敗し、親・子とも残る |

---

## 3. 実装方針

### 3.1 変更箇所（P1/P2 対応後）

- **ObjectServiceInternalImpl.deleteObjectInternal**: Document/Folder/Item 削除前に、`ContentService.getParentChildChildIds` で parentChild の子 ID を取得し、**各子に対して deleteObjectInternal を再帰呼び出し**する。これにより各子で以下が保証される:
  - **権限チェック**: `permissionDenied(CAN_DELETE_OBJECT)` を通過するため、子に削除権限が無い場合はカスケードせず例外。
  - **ロック**: `ThreadLockService.getWriteLock` で子ごとにロック取得。
  - **キャッシュ無効化**: `nemakiCachePool.removeCmisCache` が子削除後に実行される。
- **ループ検出**: スレッドローカルな `Set<String> CASCADE_VISITED` で再訪を防ぐ。
- **ContentServiceImpl**: parentChild の子削除ロジックは削除済み。カスケードは ObjectServiceInternalImpl に一本化。

### 3.2 権限境界（設計確定）

- **「親を削除できるなら子も削除できる」は採用しない。** 各子は `deleteObjectInternal` 経由のため、**子に CAN_DELETE_OBJECT が無い場合は削除できず、親削除も permissionDenied で失敗する**（子の削除に失敗するため）。
- 親だけ削除可能で子は削除不可のケースでは、親の deleteObject は **子の削除試行で permissionDenied となり失敗**する。その挙動をテストで明示することを推奨。

### 3.3 parentChild 判定

- `nemaki:parentChildRelationship` またはそのサブタイプかどうかは、ContentServiceImpl の `isParentChildRelationshipType(repositoryId, typeId)` で型階層を辿って判定。
- `ContentService.getParentChildChildIds(repositoryId, parentObjectId)` が parentChild の target（子）ID リストを返す。

### 3.4 呼び出しフロー

```
deleteObject(親)
  → ObjectServiceInternalImpl.deleteObjectInternal(親)
    → 権限・制約チェック、ロック取得
    → getParentChildChildIds(親) で子 ID リスト取得
    → 各子について deleteObjectInternal(子, deleteWithParent=true) を再帰
      （各子で権限チェック・ロック・キャッシュ無効化が行われる）
    → ContentServiceImpl.delete(親)  // リレーション削除＋本体削除
    → removeCmisCache(親)

deleteObject(リレーション)
  → ObjectServiceInternalImpl.deleteObjectInternal(リレーション)
  → ContentServiceImpl.delete(リレーション)  // カスケードなし、リンクのみ削除
```

---

## 4. テスト戦略

1. **単体テスト**: `ContentServiceImpl` のカスケードロジック（モック DAO）
2. **統合テスト**: CMIS API 経由でオブジェクト・リレーション作成 → deleteObject → 結果検証
3. **E2E**: Playwright で UI 経由の削除を確認（既存 cascade-delete.spec を活用）
