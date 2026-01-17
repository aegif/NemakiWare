# Complex Scenario Tests for NemakiWare

This directory contains comprehensive Playwright E2E tests for complex scenarios involving multiple NemakiWare features working together. These tests validate the consistency and integrity of the system when performing operations that span across different functional areas.

## Detailed Documentation

Each test suite has detailed documentation in Japanese with step-by-step explanations:

| Test Suite | Documentation |
|------------|---------------|
| Custom Type with Versioning and Search | [docs/custom-type-versioning-search.md](docs/custom-type-versioning-search.md) |
| ACL Inheritance and Custom Type Interaction | [docs/acl-custom-type-interaction.md](docs/acl-custom-type-interaction.md) |
| Secondary Type with Custom Properties | [docs/secondary-type-properties.md](docs/secondary-type-properties.md) |
| Folder Hierarchy with Scoped Search | [docs/folder-hierarchy-search.md](docs/folder-hierarchy-search.md) |
| Version and Property History Consistency | [docs/version-property-history.md](docs/version-property-history.md) |
| Archive and Restore Consistency | [docs/archive-restore-consistency.md](docs/archive-restore-consistency.md) |
| Type Management Consistency | [docs/type-management-consistency.md](docs/type-management-consistency.md) |

## Test Suites Overview

### 1. Custom Type with Versioning and Search (`custom-type-versioning-search.spec.ts`)

**Purpose**: Validates the complete workflow of custom type creation, document registration with validation, search filtering by custom properties, and version management.

**Scenarios Tested**:
- Create a custom document type with required and searchable properties
- Create a document with the custom type and fill required properties
- Search for the document using custom property filters
- Update custom property values and verify search results change
- Create new versions and restore original property values
- Delete versions and verify search behavior changes accordingly

**CMIS Concepts**: Custom Type Definition, Property Validation, CMIS SQL Query, Document Versioning, Solr Indexing

---

### 2. ACL Inheritance and Custom Type Interaction (`acl-custom-type-interaction.spec.ts`)

**Purpose**: Validates the interaction between ACL (Access Control List) permissions and custom type documents.

**Scenarios Tested**:
- Create a test folder for ACL testing
- Create a document inside the test folder
- Set ACL permissions on the folder
- Break ACL inheritance on the document
- Verify the document has independent ACL settings

**CMIS Concepts**: ACL Management, ACL Inheritance, Permission Propagation

---

### 3. Secondary Type with Custom Properties (`secondary-type-properties.spec.ts`)

**Purpose**: Validates secondary type (aspect) functionality including application, property management, and search behavior.

**Scenarios Tested**:
- Create a secondary type with custom properties
- Create a test document
- Apply the secondary type to the document
- Search by secondary type properties
- Remove the secondary type and verify search behavior

**CMIS Concepts**: Secondary Type Definition (cmis:secondary), Aspect Application, Property Inheritance

---

### 4. Folder Hierarchy with Scoped Search (`folder-hierarchy-search.spec.ts`)

**Purpose**: Validates folder hierarchy operations and scoped search functionality.

**Scenarios Tested**:
- Create a folder hierarchy (root folder with subfolders)
- Create documents in different subfolders
- Search with folder scope (IN_TREE predicate)
- Move documents between folders
- Verify search results update after move operations

**CMIS Concepts**: Folder Hierarchy, Document Filing, IN_FOLDER/IN_TREE Predicates, moveObject Operation

---

### 5. Version and Property History Consistency (`version-property-history.spec.ts`)

**Purpose**: Validates the consistency between document versioning and property values across versions.

**Scenarios Tested**:
- Create initial document (Version 1.0)
- Check out and create Version 2.0 with different content
- Create Version 3.0 with different content
- View version history and verify all versions exist
- Delete latest version and verify rollback to previous version
- Verify document content matches previous version after rollback

**CMIS Concepts**: Version Series Management, PWC Operations, Property Persistence, Version History Navigation

---

### 6. Archive and Restore Consistency (`archive-restore-consistency.spec.ts`)

**Purpose**: Validates the archive (soft delete) and restore functionality.

**Scenarios Tested**:
- Create test folder and document
- Archive the document
- View archived document in archive/trash view
- Restore document from archive
- Verify restored document is back in original location with preserved properties
- Archive and permanently delete document

**CMIS Concepts**: Archive/Soft Delete, Restore from Archive, Permanent Deletion, Property Preservation

---

### 7. Type Management Consistency (`type-management-consistency.spec.ts`)

**Purpose**: Validates the consistency between type management operations and document operations.

**Scenarios Tested**:
- Create a custom document type with properties
- Create a document with the custom type
- Attempt to delete the custom type (should fail due to existing documents)
- Preview document with custom type and verify custom properties are displayed
- Delete the document and then successfully delete the custom type

**CMIS Concepts**: Type Definition Management, Type Mutability Constraints, Document-Type Relationships

---

## Test Environment

- **Server**: http://localhost:8080/core/ui/
- **Authentication**: admin:admin
- **Repository**: bedroom
- **Browsers**: Chromium, Firefox, WebKit, Mobile Chrome, Mobile Safari

### Pre-execution Checklist / 事前チェック手順

Before running complex scenario tests, verify the following:

1. **Docker環境の起動確認**
   ```bash
   docker-compose ps  # すべてのコンテナがUpであることを確認
   ```

2. **サーバー接続確認**
   ```bash
   curl -s http://localhost:8080/core/ui/ | head -1  # HTMLが返ることを確認
   ```

3. **Solr接続確認**
   ```bash
   curl -s "http://localhost:8983/solr/bedroom/admin/ping" | grep -q "OK"
   ```

4. **CouchDB接続確認**
   ```bash
   curl -s http://localhost:5984/ | grep -q "couchdb"
   ```

### Environment Reset / 環境リセット手順

If tests fail due to leftover data, reset the test environment:

```bash
# テストデータのクリーンアップ（必要に応じて）
# 注意: 本番データには使用しないでください
docker-compose down && docker-compose up -d
# Solrインデックスの再構築を待つ（約30秒）
sleep 30
```

## Running the Tests

### Recommended Execution / 推奨実行方法

```bash
# 複雑シナリオテストは serial 実行のため --workers=1 を推奨
npx playwright test tests/complex-scenarios/ --workers=1

# Run all complex scenario tests (default)
npx playwright test tests/complex-scenarios/

# Run a specific test file
npx playwright test tests/complex-scenarios/custom-type-versioning-search.spec.ts

# Run with UI mode for debugging
npx playwright test tests/complex-scenarios/ --ui

# Run with headed browser
npx playwright test tests/complex-scenarios/ --headed
```

### Execution Time Estimates / 実行時間目安

| Test Suite | Estimated Time |
|------------|----------------|
| custom-type-versioning-search | 3-5 min |
| acl-custom-type-interaction | 2-3 min |
| secondary-type-properties | 2-3 min |
| folder-hierarchy-search | 3-4 min |
| version-property-history | 3-4 min |
| archive-restore-consistency | 2-3 min |
| type-management-consistency | 2-3 min |
| **Total (all suites)** | **15-25 min** |

**Note**: Times may vary based on Solr indexing delays and server performance.

## Test Design Principles

1. **Serial Execution**: Tests within each suite run serially (`test.describe.configure({ mode: 'serial' })`) because they share state and depend on previous test results.

2. **Unique Naming**: All test data uses UUID-based unique naming to prevent conflicts in parallel test execution across different browser profiles.

3. **Comprehensive Cleanup**: Each test suite includes an `afterAll` hook that cleans up all test data (documents, folders, custom types) to prevent test pollution. Cleanup order follows dependency: documents → folders → types.

4. **Mobile Browser Support**: Tests detect mobile viewports and apply appropriate handling (force clicks, sidebar management).

5. **Graceful Degradation**: Tests use conditional skipping when UI features are not available, allowing the test suite to adapt to different implementation states.

6. **Detailed Logging**: Tests include comprehensive console logging for debugging in CI/CD environments.

7. **i18n対応セレクタ**: Use regex patterns for text matching to support both Japanese and English UI:
   ```typescript
   // 推奨: 正規表現で日英両対応
   page.getByRole('button', { name: /管理|Admin/i })
   page.locator('text=/タイプ管理|Type Management/i')
   
   // 推奨: data-testid を優先（UIが対応している場合）
   page.locator('[data-testid="type-management-button"]')
   ```

8. **Solr索引待ち戦略**: Search-related tests must account for Solr indexing delays:
   ```typescript
   // 基本戦略: 固定待機 + リトライ
   await page.waitForTimeout(3000); // Solr索引更新を待つ
   
   // 推奨戦略: ポーリングによる確認
   await expect(async () => {
     await page.click('button:has-text("検索")');
     await expect(page.locator('.search-result')).toContainText(expectedText);
   }).toPass({ timeout: 15000, intervals: [1000, 2000, 3000] });
   ```

## Common Patterns

### Authentication
```typescript
const authHelper = new AuthHelper(page);
await authHelper.login();
```

### Document Upload
```typescript
const testHelper = new TestHelper(page);
await testHelper.uploadDocument(fileName, content, isMobile);
```

### Mobile Browser Detection
```typescript
const viewportSize = page.viewportSize();
const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;
```

### Ant Design Component Interaction
```typescript
// Select dropdown
const select = page.locator('.ant-select').first();
await select.click();
await page.waitForTimeout(300);
const option = page.locator('.ant-select-item-option').filter({ hasText: 'Option Text' });
await option.click();

// Modal handling
const modal = page.locator('.ant-modal:visible');
await expect(modal).toBeVisible({ timeout: 5000 });
```

## Troubleshooting

### Tests Skip Due to Missing UI Elements
- Check if the feature is implemented in the current UI version
- Verify the server is running and accessible
- Check browser console for JavaScript errors

### Tests Fail Due to Timing Issues
- Increase `waitForTimeout` values for slow CI environments
- Add explicit waits for specific elements before interaction
- Check Solr indexing delays for search-related tests

### Cleanup Failures
- Check if test data was created with expected names
- Verify permissions allow deletion
- Check for orphaned documents blocking type deletion

**Cleanup Failure Detection / クリーンアップ失敗時の残骸検出**:

When cleanup fails, the test should log the object IDs for manual cleanup:

```typescript
// afterAll cleanup with failure logging
test.afterAll(async ({ page }) => {
  const failedCleanups: string[] = [];
  
  try {
    // Delete documents first
    if (testDocumentId) {
      await deleteDocument(page, testDocumentId);
    }
  } catch (e) {
    failedCleanups.push(`Document: ${testDocumentId}`);
    console.error(`Failed to cleanup document: ${testDocumentId}`, e);
  }
  
  try {
    // Then delete folders
    if (testFolderId) {
      await deleteFolder(page, testFolderId);
    }
  } catch (e) {
    failedCleanups.push(`Folder: ${testFolderId}`);
    console.error(`Failed to cleanup folder: ${testFolderId}`, e);
  }
  
  try {
    // Finally delete types
    if (testTypeId) {
      await deleteType(page, testTypeId);
    }
  } catch (e) {
    failedCleanups.push(`Type: ${testTypeId}`);
    console.error(`Failed to cleanup type: ${testTypeId}`, e);
  }
  
  if (failedCleanups.length > 0) {
    console.warn('=== CLEANUP FAILURES - Manual cleanup required ===');
    console.warn(failedCleanups.join('\n'));
    console.warn('================================================');
  }
});
```

### Manual Cleanup Commands / 手動クリーンアップ

If automated cleanup fails, use the CMIS API or UI to manually remove test data:

```bash
# CouchDBから直接削除（開発環境のみ）
curl -X DELETE "http://localhost:5984/bedroom/{document_id}?rev={rev}"

# または UI から管理者としてログインして削除
```

## Test Modification Policy / テスト修正ポリシー

When implementing UI features that require test modifications:

1. **セレクタ変更**: UIの実装に合わせてセレクタを調整可能
2. **待機条件変更**: タイミング問題に対応するための待機条件調整可能
3. **テストロジック変更**: 機能仕様の変更に伴うテストロジック変更は要相談
4. **新規テスト追加**: 新機能に対応するテスト追加は推奨

## Completion Report Template / 完了報告テンプレート

When completing work on these tests, use this format:

```markdown
## 実装内容
- [ ] 実装したUI差分（ファイル名、変更概要）
- [ ] 追加/修正したテスト差分（ファイル名、変更概要）

## テスト実行結果
- 実行コマンド: `npx playwright test tests/complex-scenarios/ --workers=1`
- 結果: X passed / Y failed / Z skipped

## 既知の課題
- （あれば記載）

## 次のステップ
- （あれば記載）
```
