# Playwright テストリファクタリング計画

## 概要

レビュー指摘に基づき、テストコードの品質・メンテナンス性を向上させるリファクタリング計画。

## 進捗状況（2026-01-27 更新）

| Phase | 状態 | 完了日 |
|-------|------|--------|
| Phase 2: テストID統一 | ✅ 完了 | 2026-01-27 |
| Phase 3: モバイル処理共通化 | ✅ 完了 | 2026-01-27 |
| Phase 4: ApiHelper採用拡大 | ✅ 完了 | 2026-01-27 |

## 実施結果（2026-01-27）

| 項目 | 実施前 | 実施後 |
|-----|------|------|
| generateTestId()使用 | 5件 | 53件 |
| Date.now() ID生成 | 38件 | 0件 |
| randomUUID使用 | 30件 | 0件 |
| モバイル処理メソッド | なし | isMobile(), closeMobileSidebar() 追加 |
| 重複import修正 | - | 20件修正 |
| closeMobileSidebar()採用 | 0件 | 32件 |
| testHelper.isMobile()採用 | 0件 | 53件 |
| 重複コード削減 | - | -784行 |
| ApiHelper新メソッド | - | deleteGroup(), deleteType(), cleanupTestGroups() 追加 |
| UI経由→API経由変換 | - | 2ファイル (group-hierarchy-members, custom-type-attributes) |

---

## コードレビュー対応（2026-01-27）

レビュー指摘に基づき、以下の品質改善を実施。

| # | 指摘事項 | 修正内容 |
|---|---------|---------|
| 1 | route解除がテスト失敗時に実行されない | `try/finally`パターンで確実に`unroute()`実行 |
| 2 | uploadDocument()のリロード後待機が不十分 | `waitForAntdLoad()`を使用してUI準備完了を検知 |
| 3 | セレクターが日本語のみ | 英語パターンを追加（Upload, Search等） |
| 4 | closeAllOverlays()がDOM削除を優先 | UI操作を先に実行し、JS削除は最終手段に変更 |

### 修正詳細

#### 1. route解除のtry/finally化（error-recovery.spec.ts）
```typescript
// Before (テスト失敗時にunrouteが実行されない)
await page.route('**/core/browser/bedroom', handler);
// ... assertions ...
await page.unroute('**/core/browser/bedroom');

// After (確実にunrouteが実行される)
await page.route('**/core/browser/bedroom', handler);
try {
  // ... assertions ...
} finally {
  await page.unroute('**/core/browser/bedroom');
}
```

#### 2. uploadDocument()のUI待機改善（test-helper.ts）
```typescript
// Before
await this.page.reload({ waitUntil: 'networkidle' });
await this.page.waitForTimeout(3000);

// After
await this.page.reload({ waitUntil: 'networkidle' });
await this.waitForAntdLoad();
await this.page.waitForTimeout(1000);
```

#### 3. i18nセレクター追加（test-helper.ts）
```typescript
const uploadButtonSelectors = [
  'button:has-text("ファイルアップロード")',
  'button:has-text("アップロード")',
  'button:has-text("Upload")',           // 追加
  'button:has-text("File Upload")',      // 追加
  'button:has([data-icon="upload"])',
];
```

#### 4. closeAllOverlays()の順序修正（test-helper.ts）
```typescript
// Before: JavaScript DOM削除を最初に実行
// → Reactの状態とDOMが乖離するリスク

// After: UI操作優先
// Step 1: Escape キー押下
// Step 2: モーダル閉じるボタンクリック
// Step 3: キャンセルボタンクリック
// Step 4: Drawer閉じる
// Step 5: (最終手段) JavaScript DOM削除
```

---

## Phase 2: テストID生成の統一

### 目的
テストID生成パターンを`generateTestId()`に統一し、メンテナンス性を向上。

### 現状パターン
```typescript
// パターンA: Date.now() - 38ファイルで使用
const testFileName = `test-${Date.now()}.txt`;

// パターンB: randomUUID - 30ファイルで使用
const uuid = randomUUID().substring(0, 8);
const filename = `test-${uuid}.txt`;

// パターンC: generateTestId() - 5ファイルで使用（標準）
const testId = generateTestId();
const filename = `test-${testId}.txt`;
```

### 統一後パターン
```typescript
import { generateTestId } from '../utils/test-helper';

const testId = generateTestId();
const filename = `test-${testId}.txt`;
```

### 作業手順
1. `generateTestId()`のimportを全テストファイルに追加
2. `Date.now()`パターンを置換
3. `randomUUID().substring()`パターンを置換
4. 動作確認テスト実行

### 影響ファイル数
約68ファイル（Date.now + randomUUID使用ファイル、重複除く）

### 推定作業時間
2-3時間（sed/スクリプトによる一括置換 + 手動確認）

---

## Phase 3: モバイル処理の共通化

### 目的
重複するモバイルブラウザ判定処理を共通ユーティリティに統合。

### 現状パターン（37ファイルで重複）
```typescript
test.beforeEach(async ({ page, browserName }) => {
  const viewportSize = page.viewportSize();
  const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

  if (isMobileChrome) {
    const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
    if (await menuToggle.count() > 0) {
      await menuToggle.first().click({ timeout: 3000 });
      await page.waitForTimeout(500);
    }
  }
});
```

### 統一後パターン
```typescript
// tests/utils/test-helper.ts に追加
export class TestHelper {
  async closeMobileSidebar(browserName: string): Promise<void> {
    const viewportSize = this.page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = this.page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await this.page.waitForTimeout(500);
      }
    }
  }

  isMobile(browserName: string): boolean {
    const viewportSize = this.page.viewportSize();
    return browserName === 'chromium' && viewportSize && viewportSize.width <= 414;
  }
}
```

### 使用例
```typescript
test.describe('Example Test', () => {
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    testHelper = new TestHelper(page);
    await testHelper.closeMobileSidebar(browserName);
  });

  test('example test', async ({ page, browserName }) => {
    // テスト内でのモバイル判定
    const isMobile = testHelper.isMobile(browserName);
    await button.click(isMobile ? { force: true } : {});
  });
});
```

### コーディングルール

#### 初期化位置の統一
- **必須**: `describe`ブロック内で`let testHelper: TestHelper;`を宣言
- **必須**: `beforeEach`内で`testHelper = new TestHelper(page);`を初期化
- **禁止**: 各テスト内での`const testHelper = new TestHelper(page);`

#### importパスの統一
| ファイル位置 | importパス |
|-------------|-----------|
| `tests/` 直下 | `./utils/test-helper` |
| `tests/<subdir>/` | `../utils/test-helper` |

例:
```typescript
// tests/example.spec.ts
import { TestHelper } from './utils/test-helper';

// tests/admin/example.spec.ts
import { TestHelper } from '../utils/test-helper';
```

### 作業手順
1. TestHelperに`closeMobileSidebar()`と`isMobile()`メソッド追加
2. 各テストファイルの重複コードを共通メソッド呼び出しに置換
3. 動作確認テスト実行

### 影響ファイル数
37ファイル

### 推定作業時間
2-3時間

---

## Phase 4: ApiHelper採用拡大

### 目的
クリーンアップ処理をAPI経由に統一し、テストの信頼性向上。

### 現状
- ApiHelper使用: 5ファイル
- UI経由クリーンアップ: 多数のファイル

### ApiHelperの機能
```typescript
export class ApiHelper {
  // ドキュメント操作
  async createDocument(folderId: string, name: string, content: string): Promise<string>
  async deleteDocument(objectId: string): Promise<void>

  // フォルダ操作
  async createFolder(parentId: string, name: string): Promise<string>
  async deleteFolder(objectId: string): Promise<void>

  // クエリ
  async queryDocuments(query: string): Promise<any[]>

  // クリーンアップ
  async cleanupTestDocuments(prefix: string): Promise<void>
  async cleanupTestFolders(prefix: string): Promise<void>
}
```

### 改善対象パターン
```typescript
// 改善前: UI経由クリーンアップ（不安定）
test.afterEach(async ({ page }) => {
  const deleteButton = page.locator('[data-icon="delete"]');
  await deleteButton.click();
  await page.locator('.ant-popconfirm-ok').click();
});

// 改善後: API経由クリーンアップ（安定）
test.afterEach(async ({ page }) => {
  const apiHelper = new ApiHelper(page);
  await apiHelper.cleanupTestDocuments('test-');
});
```

### 作業手順
1. 各テストファイルのafterEach/afterAll処理を確認
2. UI経由クリーンアップをAPI経由に置換
3. 動作確認テスト実行

### 影響ファイル数
要調査（クリーンアップ処理があるファイル）

### 推定作業時間
3-4時間

---

## 実行優先順位

| 優先度 | Phase | 理由 |
|--------|-------|------|
| 1 | Phase 2 | 一括置換可能、影響範囲が明確 |
| 2 | Phase 3 | 重複削減でメンテナンス性向上 |
| 3 | Phase 4 | テスト信頼性向上、工数大 |

---

## 実行スケジュール案

### オプションA: 一括実行（1日）
- 午前: Phase 2（テストID統一）
- 午後: Phase 3（モバイル処理共通化）
- 翌日: Phase 4（ApiHelper採用拡大）

### オプションB: 段階的実行（3日）
- Day 1: Phase 2
- Day 2: Phase 3
- Day 3: Phase 4

### オプションC: 優先度順実行
- Phase 2のみ先行実施（影響大・工数小）
- Phase 3-4は別途計画

---

## リスクと対策

| リスク | 対策 |
|--------|------|
| 一括置換による不具合 | 各Phase完了後にテスト実行 |
| 正規表現置換の誤マッチ | 置換前に差分確認 |
| インポート漏れ | ESLintで未使用import検出 |

---

## 完了基準

1. 全テストファイルが`generateTestId()`を使用
2. モバイル処理が`TestHelper`に統合
3. クリーンアップ処理がAPI経由に移行
4. 全テストが既存と同等の成功率を維持

---

## 作成日
2026-01-27

## 作成者
Claude Code
