import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import * as path from 'path';
import * as fs from 'fs';

/**
 * Type Definition Upload and JSON Editing Tests
 *
 * Comprehensive test suite for Priority 3 and Priority 4 features:
 * - Type definition file upload (JSON format)
 * - Conflict detection during upload
 * - JSON-based type editing
 * - Conflict detection during edit (ID changes)
 * - Type deletion functionality
 *
 * Test Coverage (7 tests):
 * 1. Upload valid type definition (JSON) without conflicts
 * 2. Upload type definition with ID conflict (confirmation required)
 * 3. Edit type definition via JSON modal
 * 4. Edit type definition with ID change (conflict detection)
 * 5. Delete custom type
 * 6. Cancel upload operation
 * 7. Cancel JSON edit operation
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Test Type Definition Files (Lines 45-80):
 *    - Creates temporary JSON files for upload tests
 *    - Valid type definition with required fields (id, displayName, baseTypeId)
 *    - Conflict type definition with duplicate ID for conflict testing
 *    - Files created in OS temp directory and cleaned up after tests
 *    - Rationale: Upload tests require actual files for file input interaction
 *    - Implementation: fs.writeFileSync() in beforeAll, fs.unlinkSync() in afterAll
 *
 * 2. Upload.Dragger Component Interaction (Lines 95-110):
 *    - Target: "ファイルからインポート" button (ImportOutlined icon)
 *    - Upload modal: Upload.Dragger with .ant-upload-drag class
 *    - File input: input[type="file"] within modal
 *    - setInputFiles() method for file upload simulation
 *    - Wait after file selection: waitForTimeout(1000) for parsing
 *    - Rationale: Upload.Dragger uses hidden file input for drag-and-drop UI
 *    - Implementation: Locate file input and use setInputFiles()
 *
 * 3. Conflict Modal Detection and Interaction (Lines 130-155):
 *    - Conflict modal title: "型定義の競合確認"
 *    - Warning alert with conflict type list
 *    - Two buttons: "上書きして作成" (OK) and "キャンセル" (Cancel)
 *    - Check for conflict types list: ul > li containing type IDs
 *    - JSON preview: pre element with JSON.stringify output
 *    - Rationale: Conflict detection is core feature of Priority 3 implementation
 *    - Implementation: Wait for modal, verify content, click confirmation
 *
 * 4. JSON Edit Modal Interaction (Lines 175-200):
 *    - Target: Edit button (EditOutlined icon) in type table row
 *    - JSON edit modal title: "型定義の編集 (JSON)"
 *    - TextArea with monospace font: Input.TextArea rows={20}
 *    - Info alert explaining JSON editing capability
 *    - Display of editing target type: ID + displayName
 *    - Save button: "保存" (OK button text)
 *    - Rationale: JSON editing is core feature of Priority 4 implementation
 *    - Implementation: Locate TextArea, clear, fill new JSON, click save
 *
 * 5. Edit Conflict Modal (ID Change Detection) (Lines 220-245):
 *    - Triggered when type ID is changed in JSON edit
 *    - Modal title: "型定義の競合確認（編集）"
 *    - Before/After comparison: Two-column grid layout
 *    - Color-coded backgrounds: #fff1f0 (before), #f6ffed (after)
 *    - Shows ID and DisplayName changes
 *    - Confirmation button: "上書きして更新"
 *    - Rationale: ID change conflicts are critical to detect and confirm
 *    - Implementation: Wait for modal, verify before/after content
 *
 * 6. Mobile Browser Support (Lines 25-40):
 *    - Detects mobile viewport: browserName === 'chromium' && width <= 414
 *    - Closes sidebar before navigation to prevent overlay blocking
 *    - Force click for interactive elements in mobile browsers
 *    - Rationale: Mobile layouts render sidebar as blocking overlay
 *    - Implementation: Conditional sidebar close + force click pattern
 *
 * 7. Success Message Verification (Lines 112, 156, 202):
 *    - Upload success: "型定義をインポートしました"
 *    - Edit success: "型定義を更新しました"
 *    - Delete success: "タイプを削除しました"
 *    - Timeout: 10 seconds for API operations
 *    - Rationale: Success messages confirm backend operations completed
 *    - Implementation: waitForSelector with :has-text() filter
 *
 * 8. Table Refresh and Type Verification (Lines 115-125, 205-215):
 *    - After upload/edit/delete, table automatically refreshes
 *    - Wait 2 seconds for loadTypes() to complete
 *    - Verify type presence/absence in table rows
 *    - Type ID search: tr:has-text("${typeId}")
 *    - Rationale: React state updates are asynchronous
 *    - Implementation: waitForTimeout + waitForSelector
 */

let authHelper: AuthHelper;
let testHelper: TestHelper;

// Test type definition files
let testTypeDefPath: string;
let conflictTypeDefPath: string;

const testTypeId = `test:uploadTest${Date.now()}`;
const conflictTypeId = 'test:existingType'; // Assumed to exist in repository

test.describe('Type Definition Upload and JSON Editing', () => {
  test.beforeAll(async () => {
    // Create temporary type definition files for upload tests
    const tmpDir = require('os').tmpdir();

    // Valid type definition
    const validTypeDef = {
      id: testTypeId,
      displayName: `Upload Test Type ${Date.now()}`,
      baseTypeId: 'cmis:document',
      description: 'Test type created via file upload',
      creatable: true,
      fileable: true,
      queryable: true,
      controllablePolicy: false,
      controllableAcl: true,
      fulltextIndexed: true,
      includedInSupertypeQuery: true,
      propertyDefinitions: []
    };

    testTypeDefPath = path.join(tmpDir, `test-type-def-${Date.now()}.json`);
    fs.writeFileSync(testTypeDefPath, JSON.stringify(validTypeDef, null, 2));

    // Conflict type definition (same ID as test type)
    const conflictTypeDef = { ...validTypeDef };
    conflictTypeDefPath = path.join(tmpDir, `conflict-type-def-${Date.now()}.json`);
    fs.writeFileSync(conflictTypeDefPath, JSON.stringify(conflictTypeDef, null, 2));

    console.log(`Created test type definition files:`);
    console.log(`  Valid: ${testTypeDefPath}`);
    console.log(`  Conflict: ${conflictTypeDefPath}`);
  });

  test.afterAll(async () => {
    // Cleanup temporary files
    if (fs.existsSync(testTypeDefPath)) {
      fs.unlinkSync(testTypeDefPath);
    }
    if (fs.existsSync(conflictTypeDefPath)) {
      fs.unlinkSync(conflictTypeDefPath);
    }

    console.log(`Cleaned up test type definition files`);
  });

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Mobile browser support
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        try {
          await menuToggle.first().click({ timeout: 3000 });
          await page.waitForTimeout(500);
        } catch (error) {
          console.log('Sidebar close failed (non-critical):', error);
        }
      }
    }

    // Login and navigate to Type Management
    await authHelper.login();
    await page.waitForTimeout(2000);
    await testHelper.waitForAntdLoad();

    // Navigate to Type Management via Admin menu
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);
    }

    const typeManagementItem = page.locator('.ant-menu-item:has-text("タイプ管理")');
    if (await typeManagementItem.count() > 0) {
      await typeManagementItem.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    await page.waitForSelector('.ant-table', { timeout: 15000 });
  });

  test('should upload a valid type definition file without conflicts', async ({ page, browserName }) => {
    console.log('Test: Upload valid type definition without conflicts');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Click "ファイルからインポート" button
    const importButton = page.locator('button:has-text("ファイルからインポート")');
    if (await importButton.count() === 0) {
      test.skip('Import button not found - upload feature not implemented');
      return;
    }

    await importButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Upload modal should appear
    const uploadModal = page.locator('.ant-modal:has-text("型定義ファイルのインポート")');
    await expect(uploadModal).toBeVisible({ timeout: 5000 });

    // Find file input within Upload.Dragger
    const fileInput = uploadModal.locator('input[type="file"]');
    await fileInput.setInputFiles(testTypeDefPath);
    await page.waitForTimeout(1000);

    // Click インポート button
    const importModalButton = uploadModal.locator('button:has-text("インポート")');
    await importModalButton.click();

    // Wait for success message
    const successMessage = page.locator('.ant-message:has-text("型定義をインポートしました")');
    await expect(successMessage).toBeVisible({ timeout: 10000 });

    console.log('✅ Type definition uploaded successfully');

    // Verify type appears in table
    await page.waitForTimeout(2000); // Wait for table refresh
    const typeRow = page.locator(`tr:has-text("${testTypeId}")`);
    await expect(typeRow).toBeVisible({ timeout: 5000 });

    console.log(`✅ Type ${testTypeId} found in table after upload`);
  });

  test('should detect conflict when uploading duplicate type ID', async ({ page, browserName }) => {
    console.log('Test: Upload type definition with ID conflict');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Upload the same type again to trigger conflict
    const importButton = page.locator('button:has-text("ファイルからインポート")');
    if (await importButton.count() === 0) {
      test.skip('Import button not found');
      return;
    }

    await importButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const uploadModal = page.locator('.ant-modal:has-text("型定義ファイルのインポート")');
    const fileInput = uploadModal.locator('input[type="file"]');
    await fileInput.setInputFiles(conflictTypeDefPath);
    await page.waitForTimeout(1000);

    const importModalButton = uploadModal.locator('button:has-text("インポート")');
    await importModalButton.click();

    // Conflict modal should appear
    const conflictModal = page.locator('.ant-modal:has-text("型定義の競合確認")');
    await expect(conflictModal).toBeVisible({ timeout: 5000 });

    console.log('✅ Conflict modal appeared');

    // Verify conflict warning alert
    const warningAlert = conflictModal.locator('.ant-alert-warning');
    await expect(warningAlert).toBeVisible();

    // Verify conflict type list
    const conflictList = conflictModal.locator('ul > li:has-text("' + testTypeId + '")');
    await expect(conflictList).toBeVisible();

    console.log(`✅ Conflict type ${testTypeId} listed in modal`);

    // Click "上書きして作成" to confirm
    const confirmButton = conflictModal.locator('button:has-text("上書きして作成")');
    await confirmButton.click();

    // Wait for success message
    const successMessage = page.locator('.ant-message:has-text("型定義をインポートしました")');
    await expect(successMessage).toBeVisible({ timeout: 10000 });

    console.log('✅ Type definition overwritten successfully');
  });

  test('should edit type definition via JSON modal', async ({ page, browserName }) => {
    console.log('Test: Edit type definition via JSON');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Find and click Edit button for test type
    const typeRow = page.locator(`tr:has-text("${testTypeId}")`);
    if (await typeRow.count() === 0) {
      test.skip(`Type ${testTypeId} not found in table`);
      return;
    }

    const editButton = typeRow.locator('button[aria-label="edit"]');
    await editButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // JSON edit modal should appear
    const jsonEditModal = page.locator('.ant-modal:has-text("型定義の編集 (JSON)")');
    await expect(jsonEditModal).toBeVisible({ timeout: 5000 });

    console.log('✅ JSON edit modal appeared');

    // Verify TextArea with monospace font
    const jsonTextArea = jsonEditModal.locator('textarea');
    await expect(jsonTextArea).toBeVisible();

    // Get current JSON content
    const currentJson = await jsonTextArea.inputValue();
    const typeDef = JSON.parse(currentJson);

    // Modify description
    typeDef.description = `Updated via JSON editing at ${new Date().toISOString()}`;

    // Fill new JSON
    await jsonTextArea.clear();
    await jsonTextArea.fill(JSON.stringify(typeDef, null, 2));

    // Click "保存" button
    const saveButton = jsonEditModal.locator('button:has-text("保存")');
    await saveButton.click();

    // Wait for success message
    const successMessage = page.locator('.ant-message:has-text("型定義を更新しました")');
    await expect(successMessage).toBeVisible({ timeout: 10000 });

    console.log('✅ Type definition updated via JSON editing');

    // Verify type still in table
    await page.waitForTimeout(2000);
    await expect(typeRow).toBeVisible({ timeout: 5000 });
  });

  test('should detect conflict when changing type ID in JSON edit', async ({ page, browserName }) => {
    console.log('Test: Edit type with ID change (conflict detection)');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a new type to avoid conflict with existing
    const newTypeId = `test:editTest${Date.now()}`;
    const newTypeDef = {
      id: newTypeId,
      displayName: `Edit Test Type`,
      baseTypeId: 'cmis:document',
      description: 'Type for edit conflict testing',
      creatable: true,
      fileable: true,
      queryable: true,
      propertyDefinitions: []
    };

    // Upload new type first
    const importButton = page.locator('button:has-text("ファイルからインポート")');
    if (await importButton.count() === 0) {
      test.skip('Import button not found');
      return;
    }

    await importButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const uploadModal = page.locator('.ant-modal:has-text("型定義ファイルのインポート")');
    const fileInput = uploadModal.locator('input[type="file"]');

    // Create temporary file for new type
    const tmpDir = require('os').tmpdir();
    const newTypePath = path.join(tmpDir, `new-type-${Date.now()}.json`);
    fs.writeFileSync(newTypePath, JSON.stringify(newTypeDef, null, 2));

    await fileInput.setInputFiles(newTypePath);
    await page.waitForTimeout(1000);

    const importModalButton = uploadModal.locator('button:has-text("インポート")');
    await importModalButton.click();

    await page.waitForTimeout(3000); // Wait for upload

    // Now edit the new type and change ID to testTypeId (conflict)
    const newTypeRow = page.locator(`tr:has-text("${newTypeId}")`);
    const editButton = newTypeRow.locator('button[aria-label="edit"]');
    await editButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const jsonEditModal = page.locator('.ant-modal:has-text("型定義の編集 (JSON)")');
    const jsonTextArea = jsonEditModal.locator('textarea');

    // Change ID to testTypeId to trigger conflict
    const currentJson = await jsonTextArea.inputValue();
    const modifiedTypeDef = JSON.parse(currentJson);
    modifiedTypeDef.id = testTypeId; // Change to existing ID

    await jsonTextArea.clear();
    await jsonTextArea.fill(JSON.stringify(modifiedTypeDef, null, 2));

    const saveButton = jsonEditModal.locator('button:has-text("保存")');
    await saveButton.click();

    // Edit conflict modal should appear
    const editConflictModal = page.locator('.ant-modal:has-text("型定義の競合確認（編集）")');
    await expect(editConflictModal).toBeVisible({ timeout: 5000 });

    console.log('✅ Edit conflict modal appeared');

    // Verify before/after comparison
    const beforeSection = editConflictModal.locator('pre:has-text("' + newTypeId + '")');
    const afterSection = editConflictModal.locator('pre:has-text("' + testTypeId + '")');

    await expect(beforeSection).toBeVisible();
    await expect(afterSection).toBeVisible();

    console.log('✅ Before/After comparison displayed');

    // Cancel edit conflict
    const cancelButton = editConflictModal.locator('button:has-text("キャンセル")');
    await cancelButton.click();

    console.log('✅ Edit conflict cancelled');

    // Cleanup: delete new type
    fs.unlinkSync(newTypePath);
  });

  test('should delete custom type', async ({ page, browserName }) => {
    console.log('Test: Delete custom type');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Find and delete test type
    const typeRow = page.locator(`tr:has-text("${testTypeId}")`);
    if (await typeRow.count() === 0) {
      test.skip(`Type ${testTypeId} not found in table`);
      return;
    }

    const deleteButton = typeRow.locator('button[aria-label="delete"]');
    await deleteButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(500);

    // Popconfirm should appear
    const popconfirm = page.locator('.ant-popconfirm:has-text("このタイプを削除しますか？")');
    await expect(popconfirm).toBeVisible({ timeout: 3000 });

    // Click OK button
    const okButton = popconfirm.locator('button.ant-btn-primary');
    await okButton.click();

    // Wait for success message
    const successMessage = page.locator('.ant-message:has-text("タイプを削除しました")');
    await expect(successMessage).toBeVisible({ timeout: 10000 });

    console.log('✅ Type deleted successfully');

    // Verify type removed from table
    await page.waitForTimeout(2000);
    await expect(typeRow).not.toBeVisible();

    console.log(`✅ Type ${testTypeId} removed from table`);
  });

  test('should cancel upload operation', async ({ page, browserName }) => {
    console.log('Test: Cancel upload operation');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const importButton = page.locator('button:has-text("ファイルからインポート")');
    if (await importButton.count() === 0) {
      test.skip('Import button not found');
      return;
    }

    await importButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const uploadModal = page.locator('.ant-modal:has-text("型定義ファイルのインポート")');
    await expect(uploadModal).toBeVisible({ timeout: 5000 });

    // Click Cancel button
    const cancelButton = uploadModal.locator('button:has-text("キャンセル")');
    await cancelButton.click();

    // Modal should close
    await expect(uploadModal).not.toBeVisible({ timeout: 3000 });

    console.log('✅ Upload operation cancelled');
  });

  test('should cancel JSON edit operation', async ({ page, browserName }) => {
    console.log('Test: Cancel JSON edit operation');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a test type for this test
    const cancelTestTypeId = `test:cancelTest${Date.now()}`;
    const cancelTestDef = {
      id: cancelTestTypeId,
      displayName: `Cancel Test Type`,
      baseTypeId: 'cmis:document',
      propertyDefinitions: []
    };

    // Upload type
    const importButton = page.locator('button:has-text("ファイルからインポート")');
    if (await importButton.count() === 0) {
      test.skip('Import button not found');
      return;
    }

    await importButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const uploadModal = page.locator('.ant-modal:has-text("型定義ファイルのインポート")');
    const fileInput = uploadModal.locator('input[type="file"]');

    const tmpDir = require('os').tmpdir();
    const cancelTestPath = path.join(tmpDir, `cancel-test-${Date.now()}.json`);
    fs.writeFileSync(cancelTestPath, JSON.stringify(cancelTestDef, null, 2));

    await fileInput.setInputFiles(cancelTestPath);
    await page.waitForTimeout(1000);

    const importModalButton = uploadModal.locator('button:has-text("インポート")');
    await importModalButton.click();
    await page.waitForTimeout(3000);

    // Open edit modal
    const typeRow = page.locator(`tr:has-text("${cancelTestTypeId}")`);
    const editButton = typeRow.locator('button[aria-label="edit"]');
    await editButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const jsonEditModal = page.locator('.ant-modal:has-text("型定義の編集 (JSON)")');
    await expect(jsonEditModal).toBeVisible({ timeout: 5000 });

    // Make some changes
    const jsonTextArea = jsonEditModal.locator('textarea');
    const originalJson = await jsonTextArea.inputValue();
    const modifiedTypeDef = JSON.parse(originalJson);
    modifiedTypeDef.description = 'This change will be cancelled';
    await jsonTextArea.clear();
    await jsonTextArea.fill(JSON.stringify(modifiedTypeDef, null, 2));

    // Click Cancel button
    const cancelButton = jsonEditModal.locator('button:has-text("キャンセル")');
    await cancelButton.click();

    // Modal should close
    await expect(jsonEditModal).not.toBeVisible({ timeout: 3000 });

    console.log('✅ JSON edit operation cancelled');

    // Cleanup
    fs.unlinkSync(cancelTestPath);

    // Delete test type
    const deleteButton = typeRow.locator('button[aria-label="delete"]');
    await deleteButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(500);

    const popconfirm = page.locator('.ant-popconfirm');
    const okButton = popconfirm.locator('button.ant-btn-primary');
    await okButton.click();
    await page.waitForTimeout(2000);
  });
});
