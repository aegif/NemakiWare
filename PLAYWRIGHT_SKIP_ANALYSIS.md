# Playwright Skip項目の包括的分析とUI実装計画

**作成日**: 2025-11-01
**目的**: Playwrightテストのskip項目を整理し、UI未実装機能の実装計画を立案

---

## 📊 Skip項目サマリー

**全テストファイル**: 18ファイルにskip処理が含まれる

**Skip種別**:
1. **UI未実装**: 4ファイル（test.describe.skip使用）
2. **条件付きスキップ**: 14ファイル（test.skip()を動的に使用）
3. **データ依存スキップ**: PDF/document前提条件の欠如

---

## 🔴 カテゴリ1: 完全スキップ（test.describe.skip）- UI未実装

### 1. custom-type-attributes.spec.ts ❌ **最優先実装が必要**

**ファイル**: `tests/admin/custom-type-attributes.spec.ts`
**スキップ理由**: Manual Form UI not implemented (2025-10-26調査結果)

**現状**:
- **期待UI**: "新規タイプ"ボタン → 手動フォーム入力
- **実装済みUI**: "ファイルからインポート"ボタン → JSON編集アプローチ

**影響範囲**:
- カスタムタイプ作成（プロパティ定義付き）: 3テスト
- カスタム属性の表示と編集検証
- エンドツーエンドのタイプ管理ワークフロー

**実装優先度**: ⭐⭐⭐⭐ **HIGH**
**理由**: CMISカスタムタイプ管理は企業向けECM機能の中核

**実装タスク**:
- [ ] TypeManagement.tsx に手動フォーム作成UI追加
- [ ] プロパティ定義フォームカード実装
- [ ] ベースタイプ選択コンポーネント実装
- [ ] プロパティタイプ/カーディナリティセレクタ実装
- [ ] 更新可能フラグトグル実装
- [ ] カスタムタイプ作成APIエンドポイント統合
- [ ] test.describe.skip → test.describe 変更してテスト有効化

**推定工数**: 5-8日
**委譲先推奨**: Devin（UI実装 + E2Eテスト統合が得意）

---

## 🟡 カテゴリ2: 条件付きスキップ（test.skip() 動的使用）- セルフヒーリング型

以下のテストは**UI要素の存在チェック**を行い、見つからない場合のみスキップする設計。UIが実装されれば**自動的にテスト実行される**。

### 2.1 タイプ管理関連（優先度: HIGH）

#### type-management.spec.ts ⚠️

**スキップパターン**:
```typescript
// Line 237: nemaki:parentChildRelationship type not found
test.skip('nemaki:parentChildRelationship type not found - may need to verify API response');

// Line 347: Custom type editing
test.skip('should allow editing nemaki: custom type description', async ({ page, browserName }) => {

// Line 412, 416: Description field / type not found
test.skip('Description field not available in edit modal');
test.skip('nemaki:parentChildRelationship type not found in table');
```

**実装ギャップ**:
- カスタムタイプ説明フィールドの編集機能
- タイプテーブルのデータロード問題（nemaki:parentChildRelationship不可視）

**実装優先度**: ⭐⭐⭐ MEDIUM-HIGH
**実装タスク**:
- [ ] タイプ編集モーダルに説明フィールド追加
- [ ] TypeManagement.tsxのテーブルデータロード修正
- [ ] nemaki:parentChildRelationshipタイプの可視性確認

**推定工数**: 2-3日

---

#### type-definition-upload.spec.ts ⚠️

**スキップパターン**:
```typescript
// Lines 368, 475, 600, 724, 762: Import button not found
test.skip('Import button not found - upload feature not implemented');

// Lines 531, 684: Type not found in table
test.skip(`Type ${testTypeId} not found in table`);
```

**実装ギャップ**:
- タイプ定義ファイルインポートボタン
- アップロード後のタイプテーブル更新

**実装優先度**: ⭐⭐⭐ MEDIUM-HIGH
**実装タスク**:
- [ ] "ファイルからインポート"ボタンUI実装
- [ ] JSON/XMLファイルアップロード機能
- [ ] インポート成功後のテーブル再ロード

**推定工数**: 3-4日

---

#### custom-type-creation.spec.ts ✅ **既に実装済み（2025-10-25セレクタ修正）**

**状態**: セレクタ修正済み、test.describe.skip使用なし、条件付きskipのみ

**スキップパターン**:
```typescript
// Line 287: Create button not found (UI存在チェック)
test.skip('Create type button not found - UI may not be implemented');
// Line 313: Edit button missing
test.skip('Edit button not found');
// Line 385: Property tab not available
test.skip('Property tab not available');
```

**実装状況**: TypeManagement.tsxに全UI実装済み
- ✅ "新規タイプ"ボタン（Line 391）
- ✅ タイプ作成モーダル（Lines 403-428）
- ✅ プロパティ追加UI（Lines 176-287）

**アクション不要**: セルフヒーリング設計により、UIが検出されれば自動実行

---

### 2.2 ドキュメント管理関連（優先度: MEDIUM）

#### document-properties-edit.spec.ts ⚠️

**スキップパターン**:
```typescript
// Line 212: Upload functionality not available
test.skip('Upload functionality not available');

// Line 282: Editable properties not found
test.skip('Editable properties not found');

// Lines 285, 350: Test document not found
test.skip('Test document not found');
test.skip('Test document not found after reload');
```

**実装ギャップ**:
- ドキュメントアップロード機能（ボタン未検出）
- プロパティ編集フィールド（編集可能フィールド未検出）

**実装優先度**: ⭐⭐ MEDIUM
**実装タスク**:
- [ ] ファイルアップロードボタン可視性確認
- [ ] DocumentPropertyEditorでの編集可能プロパティ表示
- [ ] プロパティ保存API統合

**推定工数**: 2-3日

---

#### pdf-preview.spec.ts ⚠️

**スキップパターン**:
```typescript
// Lines 233, 237: Technical Documents folder / PDF not found
test.skip(true, 'CMIS specification PDF not found in Technical Documents folder');
test.skip(true, 'Technical Documents folder not found');

// Lines 322, 325, 400, 406, 497, 500: PDF document not available
test.skip('CMIS specification PDF not found in Technical Documents folder');
test.skip('PDF document not found in repository - file needs to be uploaded');
```

**実装ギャップ**:
- PDF preview functionality自体は実装済み
- **データ前提条件**: Technical Documentsフォルダ/PDF文書が不在

**実装優先度**: ⭐ LOW（データ準備のみ）
**実装タスク**:
- [ ] Technical Documentsフォルダ初期化スクリプト作成
- [ ] CMIS-v1.1-Specification-Sample.pdfサンプルファイル配置
- [ ] 初期コンテンツセットアップにPDFアップロード追加

**推定工数**: 0.5-1日（データ準備のみ）

---

### 2.3 アクセス制御・権限管理関連（優先度: LOW-MEDIUM）

#### permission-management-ui.spec.ts ⚠️

**状態**: 条件付きskip使用（詳細調査未完了）

**実装優先度**: ⭐⭐ MEDIUM
**推定工数**: 調査後決定

---

#### acl-management.spec.ts, access-control.spec.ts ⚠️

**状態**: 条件付きskip使用（詳細調査未完了）

**実装優先度**: ⭐⭐ MEDIUM
**推定工数**: 調査後決定

---

### 2.4 ユーザー/グループ管理関連（優先度: LOW）

#### user-management.spec.ts, user-management-crud.spec.ts ⚠️

**状態**: 条件付きskip使用（主にナビゲーション/タイムアウト問題）

**実装優先度**: ⭐ LOW（機能は実装済み、テスト安定性の問題）

---

#### group-management.spec.ts, group-management-crud.spec.ts ⚠️

**状態**: 条件付きskip使用（主にナビゲーション/タイムアウト問題）

**実装優先度**: ⭐ LOW（機能は実装済み、テスト安定性の問題）

---

### 2.5 その他

#### document-management.spec.ts ⚠️

**スキップパターン**: 削除機能のUI更新タイミング問題（Mobile Chromeのみ）

**実装優先度**: ⭐ LOW（Mobile特有のタイミング問題）

---

#### versioning/document-versioning.spec.ts ⚠️

**スキップパターン**: バージョニング機能関連（詳細調査未完了）

**実装優先度**: ⭐⭐⭐ MEDIUM-HIGH（CMIS core機能）
**推定工数**: 調査後決定

---

#### large-file-upload.spec.ts ⚠️

**実装優先度**: ⭐ LOW（パフォーマンステスト）

---

#### advanced-search.spec.ts, verify-404-redirect.spec.ts ⚠️

**実装優先度**: ⭐ LOW（周辺機能）

---

## 📋 実装優先度マトリックス

### フェーズ1: 最優先実装（1-2週間）

| 機能 | ファイル | 優先度 | 工数 | 委譲先推奨 |
|------|---------|--------|------|-----------|
| カスタムタイプ作成（手動フォーム） | custom-type-attributes.spec.ts | ⭐⭐⭐⭐ | 5-8日 | Devin |
| タイプ編集機能 | type-management.spec.ts | ⭐⭐⭐ | 2-3日 | Devin |
| タイプ定義アップロード | type-definition-upload.spec.ts | ⭐⭐⭐ | 3-4日 | Devin |

**フェーズ1合計**: 10-15日

---

### フェーズ2: 中優先度実装（2-3週間）

| 機能 | ファイル | 優先度 | 工数 | 委譲先推奨 |
|------|---------|--------|------|-----------|
| ドキュメントバージョニングUI | document-versioning.spec.ts | ⭐⭐⭐ | 調査後決定 | Devin |
| ドキュメントプロパティ編集 | document-properties-edit.spec.ts | ⭐⭐ | 2-3日 | Devin |
| 権限管理UI改善 | permission-management-ui.spec.ts | ⭐⭐ | 調査後決定 | Devin |
| ACL管理UI | acl-management.spec.ts | ⭐⭐ | 調査後決定 | Claude Code |

**フェーズ2合計**: 調査後精緻化

---

### フェーズ3: 低優先度改善（必要に応じて）

| 機能 | ファイル | 優先度 | 工数 | 委譲先推奨 |
|------|---------|--------|------|-----------|
| PDF preview データ準備 | pdf-preview.spec.ts | ⭐ | 0.5-1日 | 任意 |
| ユーザー管理テスト安定化 | user-management-crud.spec.ts | ⭐ | 1-2日 | Devin |
| グループ管理テスト安定化 | group-management-crud.spec.ts | ⭐ | 1-2日 | Devin |
| モバイル削除UI改善 | document-management.spec.ts | ⭐ | 1日 | Devin |

---

## 🎯 実装推奨アプローチ

### Claude Code → Devinへの委譲フロー

**ステップ1: Claude Codeが実施**（準備）
```bash
# 1. 環境の健全性確認
docker ps
./qa-test.sh  # 56/56 PASS確認

# 2. ベースラインPlaywrightテスト実行
cd core/src/main/webapp/ui
npx playwright test tests/admin/custom-type-attributes.spec.ts
# 期待: 3 tests skipped（UI未実装のため）

# 3. HANDOFF.mdに委譲内容記載
```

**HANDOFF.md記載例**:
```markdown
## Devinへの委譲タスク（フェーズ1）

**タスク**: カスタムタイプ作成UI実装（手動フォーム）

**対象ファイル**:
- UI実装: `core/src/main/webapp/ui/src/components/TypeManagement.tsx`
- テスト: `core/src/main/webapp/ui/tests/admin/custom-type-attributes.spec.ts`

**前提条件**:
- QAテスト 56/56 PASS
- Dockerコンテナ全起動
- Gitブランチ: vk/368c-tck

**期待成果**:
1. "新規タイプ"ボタンクリック → 手動フォーム表示
2. プロパティ定義タブでプロパティ追加可能
3. test.describe.skip → test.describe 変更後、3テスト全PASS

**API エンドポイント**:
- POST /core/rest/bedroom/type/create
- PUT /core/rest/bedroom/type/update

**実装要件**:
- ベースタイプ選択（cmis:document, cmis:folder）
- プロパティID/表示名/データ型/カーディナリティ/更新可能フラグ
- Ant Designコンポーネント使用（Modal, Form, Select, Switch）
```

**ステップ2: Devinが実施**（実装）
```bash
# 1. TypeManagement.tsx に手動フォーム追加
# 2. Property定義カードコンポーネント実装
# 3. API統合
# 4. test.describe.skip削除してテスト有効化
# 5. Playwrightテスト実行

npx playwright test tests/admin/custom-type-attributes.spec.ts --project=chromium

# 期待: 3/3 PASS
```

**ステップ3: Devinが報告**（成果物）
```bash
# 1. 変更コミット
git add core/src/main/webapp/ui/src/components/TypeManagement.tsx
git add core/src/main/webapp/ui/tests/admin/custom-type-attributes.spec.ts
git commit -m "feat: Implement custom type creation manual form UI

- Add type creation modal with property definition tab
- Implement property card with type/cardinality selectors
- Enable custom-type-attributes.spec.ts tests (3/3 PASS)"

# 2. HANDOFF.md更新
# - 実装完了したUI機能
# - テスト結果（3/3 PASS）
# - 発見したバグ（あれば）
# - 次のステップ提案

# 3. プッシュ
git push origin vk/368c-tck
```

---

## 📊 実装完了後の期待テスト成果

### フェーズ1完了後

**Before**:
- custom-type-attributes.spec.ts: 0/3 (全スキップ)
- type-management.spec.ts: 部分スキップ
- type-definition-upload.spec.ts: 部分スキップ

**After**:
- custom-type-attributes.spec.ts: 3/3 PASS ✅
- type-management.spec.ts: 大部分PASS（説明編集含む）✅
- type-definition-upload.spec.ts: インポート機能PASS ✅

**テストカバレッジ向上**: +15-20 tests (estimated)

---

### フェーズ2完了後

**追加PASS予想**:
- document-versioning.spec.ts: バージョニングUI実装で+5-10 tests
- document-properties-edit.spec.ts: プロパティ編集で+4 tests
- permission/acl tests: 権限管理UI改善で+8-12 tests

**テストカバレッジ向上**: +30-40 tests累計（フェーズ1+2）

---

## 🔍 次のステップ

### 即座に実行可能

1. ✅ **この分析をHANDOFF.mdに追記**
2. ✅ **Devinへフェーズ1タスク委譲準備**
3. ⏳ **custom-type-attributes.spec.ts詳細要件をGitHub Issueに記載**

### 調査が必要

1. ⚠️ **document-versioning.spec.ts**: スキップ理由の詳細調査
2. ⚠️ **permission-management-ui.spec.ts**: スキップ理由の詳細調査
3. ⚠️ **acl-management.spec.ts**: スキップ理由の詳細調査

### 長期計画

1. 📌 **フェーズ1実装完了**（1-2週間後）
2. 📌 **フェーズ2調査完了**（フェーズ1完了後）
3. 📌 **フェーズ2実装開始**（調査完了次第）

---

**最終更新**: 2025-11-01
**次回更新**: フェーズ1実装開始時
**関連ドキュメント**: AGENTS.md, HANDOFF.md, BUILD_DEPLOY_GUIDE.md
