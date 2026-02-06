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

---

## 3. 実装方針

### 3.1 変更箇所

- `ContentServiceImpl.delete()`: Document/Folder/Item 削除時、source の parentChild リレーションの target（子）を先に再帰削除。
- 再帰時に `Set<String> visited` を渡し、ループ・重複を防止。
- Relationship オブジェクト削除時はカスケードしない（現状どおり）。

### 3.2 parentChild 判定

- `nemaki:parentChildRelationship` またはそのサブタイプかどうかは、TypeManager で型階層を辿って判定。
- ContentServiceImpl に `isParentChildRelationshipType(repositoryId, typeId)` を追加。

### 3.3 呼び出しフロー

```
deleteObject(親) 
  → ObjectServiceInternalImpl.deleteObjectInternal 
    → ContentServiceImpl.delete(親)  [Document/Folder/Item]
      → source の parentChild を取得
      → 各子について visited になければ delete(子, visited) を再帰
      → 全リレーション削除
      → 親削除

deleteObject(リレーション)
  → ContentServiceImpl.delete(リレーション)
    → リレーションの source/target は Document/Folder ID
    → getRelationshipsBySource(リレーションID) は空（リレーションが source になることはない）
    → 通常のリレーション削除のみ実行（カスケードなし）
```

※ 補足: Relationship の id を getRelationshipsBySource に渡すと、sourceId=relId のリレーションを探す。通常 parentChild の source は Document/Folder なので、Relationship 削除時は空で正しい。

---

## 4. テスト戦略

1. **単体テスト**: `ContentServiceImpl` のカスケードロジック（モック DAO）
2. **統合テスト**: CMIS API 経由でオブジェクト・リレーション作成 → deleteObject → 結果検証
3. **E2E**: Playwright で UI 経由の削除を確認（既存 cascade-delete.spec を活用）
