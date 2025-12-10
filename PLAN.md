# タイプ指定機能 実装計画

## 調査結果サマリ

### CMIS 1.1 標準の制限

| プロパティ | Updatability | 更新可否 |
|-----------|-------------|---------|
| `cmis:objectTypeId` | ONCREATE | ❌ 作成後は変更不可 |
| `cmis:secondaryObjectTypeIds` | READWRITE | ✅ いつでも更新可能 |

### バックエンド対応状況

| 機能 | 状況 | 実装場所 |
|-----|------|---------|
| アップロード時タイプ指定 | ✅ 対応済み | `createDocument()` の `cmis:objectTypeId` |
| セカンダリタイプ追加 | ✅ 対応済み | Browser Binding `addSecondaryTypeIds` |
| セカンダリタイプ削除 | ✅ 対応済み | Browser Binding `removeSecondaryTypeIds` |
| 主タイプ変更 | ❌ 未対応 | CMIS標準で禁止 |

### UI対応状況

| 機能 | 状況 |
|-----|------|
| アップロード時タイプ選択UI | ❌ なし |
| セカンダリタイプ選択UI | ❌ なし |
| 複数値プロパティ送信 | ⚠️ バグあり（文字列化される） |

---

## 実装計画

### Phase 1: アップロード時のタイプ指定UI

**目的**: ファイルアップロード時にカスタムタイプを選択可能にする

**実装内容**:
1. `DocumentList.tsx` のアップロードモーダルにタイプ選択ドロップダウンを追加
2. `cmis:document` を基底タイプとするタイプ一覧を取得・表示
3. 選択されたタイプを `cmis:objectTypeId` として送信
4. デフォルト値は `cmis:document`（既存動作と互換）

**変更ファイル**:
- `core/src/main/webapp/ui/src/components/DocumentList/DocumentList.tsx`
- `core/src/main/webapp/ui/src/services/cmis.ts`（タイプ取得用ユーティリティ追加）

**UI案**:
```
┌─────────────────────────────────────────┐
│ ファイルアップロード                      │
├─────────────────────────────────────────┤
│ ファイル選択: [ドラッグ&ドロップエリア]    │
│                                          │
│ ファイル名:   [________________]          │
│                                          │
│ タイプ:       [cmis:document ▼]          │  ← 新規追加
│               ├ cmis:document            │
│               ├ custom:invoice           │
│               └ custom:contract          │
│                                          │
│ [キャンセル]              [アップロード]   │
└─────────────────────────────────────────┘
```

---

### Phase 2: セカンダリタイプ選択UI

**目的**: 登録済みオブジェクトにセカンダリタイプを付与・削除可能にする

**実装内容**:
1. 新規コンポーネント `SecondaryTypeSelector.tsx` を作成
2. `cmis:secondary` を基底タイプとするタイプ一覧を取得
3. 複数選択可能なUI（Ant Design Select mode="multiple"）
4. 変更時に `addSecondaryTypeIds` / `removeSecondaryTypeIds` パラメータで更新
5. 既存プロパティ画面に統合

**変更ファイル**:
- `core/src/main/webapp/ui/src/components/SecondaryTypeSelector/SecondaryTypeSelector.tsx`（新規）
- `core/src/main/webapp/ui/src/components/PropertyEditor/PropertyEditor.tsx`
- `core/src/main/webapp/ui/src/services/cmis.ts`

**UI案**:
```
┌─────────────────────────────────────────┐
│ プロパティ                               │
├─────────────────────────────────────────┤
│ 基本情報                                 │
│   タイプ:     cmis:document (変更不可)   │
│   名前:       契約書A.pdf                │
│   作成日:     2025-12-11                 │
│                                          │
│ セカンダリタイプ                          │  ← 新規追加
│   [ custom:tagged       × ]              │
│   [ custom:auditable    × ]              │
│   [セカンダリタイプを追加... ▼]           │
│                                          │
│ プロパティ                               │
│   cmis:name:    [契約書A.pdf____]        │
│   custom:tag:   [重要, 契約______]       │  ← セカンダリタイプのプロパティ
│   custom:audit: [2025-12-01_____]        │
└─────────────────────────────────────────┘
```

---

### Phase 3: 複数値プロパティ送信バグ修正

**目的**: `cmis:secondaryObjectTypeIds` などの複数値プロパティを正しく送信

**現状の問題**:
```typescript
// cmis.ts appendPropertiesToFormData (line 402)
formData.append(`propertyValue[${propertyIndex}]`, String(value));
// → 配列が "[value1,value2]" という文字列になってしまう
```

**修正後**:
```typescript
// 複数値の場合は個別に追加
if (Array.isArray(value)) {
  value.forEach((v, i) => {
    formData.append(`propertyValue[${propertyIndex}][${i}]`, String(v));
  });
} else {
  formData.append(`propertyValue[${propertyIndex}]`, String(value));
}
```

**変更ファイル**:
- `core/src/main/webapp/ui/src/services/cmis.ts`

---

### Phase 4: セカンダリタイプのプロパティ表示

**目的**: セカンダリタイプが指定されている場合、そのプロパティ定義も編集可能にする

**実装内容**:
1. オブジェクトの `cmis:secondaryObjectTypeIds` を確認
2. 各セカンダリタイプの `PropertyDefinition` を取得
3. PropertyEditor に追加のプロパティフィールドを表示
4. 値の編集・保存を可能にする

**変更ファイル**:
- `core/src/main/webapp/ui/src/components/PropertyEditor/PropertyEditor.tsx`
- `core/src/main/webapp/ui/src/services/cmis.ts`

---

### Phase 5（要確認）: 主タイプの変更

**CMIS標準の制限**: `cmis:objectTypeId` は作成後変更不可

**実装オプション**:

#### オプション A: 機能を提供しない（推奨）
- CMIS標準に準拠
- データ整合性を保証
- 「タイプ変更が必要な場合は再アップロード」を案内

#### オプション B: 独自REST APIで実装
- 非標準の拡張機能として提供
- 必要な実装:
  1. `PUT /api/v1/repo/{repositoryId}/objects/{objectId}/type` REST API
  2. CouchDBのドキュメント直接更新
  3. 旧タイプのプロパティ削除/新タイプのプロパティ追加
  4. Solrインデックスの再構築
  5. バージョン履歴の整合性確認
- リスク:
  - CMIS標準との非互換
  - データ欠損のリスク
  - バージョン管理の複雑化

---

## 実装優先順位

| 優先度 | Phase | 機能 | 工数見積 |
|-------|-------|------|---------|
| 🔴 高 | 1 | アップロード時タイプ指定 | 小 |
| 🔴 高 | 2 | セカンダリタイプ選択UI | 中 |
| 🔴 高 | 3 | 複数値プロパティバグ修正 | 小 |
| 🟡 中 | 4 | セカンダリタイプのプロパティ表示 | 中 |
| 🟢 低 | 5 | 主タイプ変更 | 大（要判断） |

---

## 確認事項

1. **Phase 5（主タイプ変更）について**
   - オプションA（提供しない）で進めてよろしいでしょうか？
   - それともオプションB（独自API実装）が必要でしょうか？

2. **フォルダ作成時のタイプ指定**
   - ドキュメントと同様に、フォルダ作成時もタイプ選択UIが必要でしょうか？
   - 対象: `cmis:folder` を基底タイプとするカスタムタイプ

3. **データ欠損の警告表示**
   - セカンダリタイプを外す際に、関連プロパティが削除される警告を表示しますか？
   - 例: 「custom:tagged を外すと、custom:tag プロパティの値が失われます」

---

## 技術詳細

### タイプ取得API

```typescript
// cmis:document の子タイプ一覧を取得
const documentTypes = await cmisService.getTypes(repositoryId, 'cmis:document', true);

// cmis:secondary の子タイプ一覧を取得
const secondaryTypes = await cmisService.getTypes(repositoryId, 'cmis:secondary', true);
```

### セカンダリタイプ更新API（Browser Binding）

```bash
# セカンダリタイプを追加
curl -X POST "http://localhost:8080/core/browser/bedroom" \
  -d "cmisaction=update" \
  -d "objectId=xxx" \
  -d "addSecondaryTypeIds=custom:tagged,custom:auditable"

# セカンダリタイプを削除
curl -X POST "http://localhost:8080/core/browser/bedroom" \
  -d "cmisaction=update" \
  -d "objectId=xxx" \
  -d "removeSecondaryTypeIds=custom:tagged"
```

### 複数値プロパティのFormData形式

```
propertyId[0]=cmis:secondaryObjectTypeIds
propertyValue[0][0]=custom:tagged
propertyValue[0][1]=custom:auditable
```
