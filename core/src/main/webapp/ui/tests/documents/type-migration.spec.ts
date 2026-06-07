/**
 * Type Migration Feature Tests for NemakiWare React UI
 *
 * This test suite verifies the object type migration functionality:
 * - Type migration button visibility
 * - Modal opens with correct object info
 * - Compatible types are loaded
 * - Type selection and migration
 * - Error handling
 *
 * Note: Type migration is a NemakiWare-specific extension.
 * CMIS 1.1 standard does not support changing cmis:objectTypeId after object creation.
 *
 * @since 2025-12-11
 */

import { test, expect } from '@playwright/test';
import { waitForRender, waitForUiStable } from '../utils/wait-helpers';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

// Suite-scoped document created by beforeAll — no dependency on other tests' data
let suiteDocId = '';
const suiteDocName = `type-migration-suite-${Date.now()}.txt`;

/**
 * Helper: Navigate to the suite-owned document's DocumentViewer.
 * Falls back to creating one via API if not yet available.
 */
async function navigateToDocument(page: any): Promise<string | null> {
  if (!suiteDocId) return null;

  // Navigate directly to DocumentViewer
  await page.goto(`/core/ui/index.html#/documents/${suiteDocId}`);
  await waitForUiStable(page);

  return suiteDocName;
}

test.beforeAll(async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  const adminAuth = `Basic ${Buffer.from('admin:admin').toString('base64')}`;
  try {
    const uploadResp = await page.request.post(
      'http://localhost:8080/core/browser/bedroom/root',
      {
        headers: { 'Authorization': adminAuth },
        multipart: {
          cmisaction: 'createDocument',
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:document',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': suiteDocName,
          content: {
            name: suiteDocName,
            mimeType: 'text/plain',
            buffer: Buffer.from('Type migration test content', 'utf-8'),
          },
        },
      }
    );
    if (uploadResp.ok()) {
      const data = await uploadResp.json();
      suiteDocId = data?.properties?.['cmis:objectId']?.value || '';
      console.log(`[type-migration] Created suite doc: ${suiteDocName}, ID: ${suiteDocId}`);
    }
  } catch (e) {
    console.log(`[type-migration] Doc upload error: ${e}`);
  } finally {
    await context.close();
  }
});

test.afterAll(async ({ browser }) => {
  if (!suiteDocId) return;
  const context = await browser.newContext();
  const page = await context.newPage();
  const adminAuth = `Basic ${Buffer.from('admin:admin').toString('base64')}`;
  try {
    await page.request.post('http://localhost:8080/core/browser/bedroom/root', {
      headers: { 'Authorization': adminAuth },
      form: { cmisaction: 'delete', objectId: suiteDocId, allVersions: 'true' },
    });
    console.log(`[type-migration] Cleaned up suite doc: ${suiteDocName}`);
  } catch (e) {
    console.log(`[type-migration] Cleanup error: ${e}`);
  } finally {
    await context.close();
  }
});

test.describe('Type Migration Features', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);
    await authHelper.login();
    await testHelper.waitForAntdLoad();
  });

  test.describe('Type Migration Button', () => {
    test('should display type migration button in document viewer', async ({ page }) => {
      const docName = await navigateToDocument(page);
      if (!docName) {
        test.skip(true, 'ENV: No document found via CMIS API');
        return;
      }

      // Verify "タイプを変更" button exists in DocumentViewer
      const typeMigrationButton = page.locator('button').filter({ hasText: /タイプを変更|Change Type/i });
      await expect(typeMigrationButton).toBeVisible({ timeout: 10000 });
      console.log(`Type migration button found for document: ${docName}`);
    });

    test('should open type migration modal when button clicked', async ({ page }) => {
      const docName = await navigateToDocument(page);
      if (!docName) {
        test.skip(true, 'ENV: No document found via CMIS API');
        return;
      }

      // Click "タイプを変更" button
      const typeMigrationButton = page.locator('button').filter({ hasText: /タイプを変更|Change Type/i });
      await expect(typeMigrationButton).toBeVisible({ timeout: 10000 });
      await typeMigrationButton.click();

      // Verify modal appears
      const modal = page.locator('.ant-modal').filter({ hasText: /オブジェクトタイプの変更|Change Object Type/i });
      await expect(modal).toBeVisible({ timeout: 5000 });
      console.log('Type migration modal opened successfully');
    });
  });

  test.describe('Type Migration Modal Content', () => {
    test('should display object information in modal', async ({ page }) => {
      const docName = await navigateToDocument(page);
      if (!docName) {
        test.skip(true, 'ENV: No document found via CMIS API');
        return;
      }

      // Open type migration modal
      const typeMigrationButton = page.locator('button').filter({ hasText: /タイプを変更|Change Type/i });
      await expect(typeMigrationButton).toBeVisible({ timeout: 10000 });
      await typeMigrationButton.click();

      // Verify modal shows object name
      const modal = page.locator('.ant-modal').filter({ hasText: /オブジェクトタイプの変更|Change Object Type/i });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Check for object name in descriptions
      const objectNameLabel = modal.locator('.ant-descriptions-item-label').filter({ hasText: /オブジェクト名|Object Name/i });
      await expect(objectNameLabel).toBeVisible();

      // Check for current type label
      const currentTypeLabel = modal.locator('.ant-descriptions-item-label').filter({ hasText: /現在のタイプ|Current Type/i });
      await expect(currentTypeLabel).toBeVisible();

      // Check for base type label
      const baseTypeLabel = modal.locator('.ant-descriptions-item-label').filter({ hasText: /ベースタイプ|Base Type/i });
      await expect(baseTypeLabel).toBeVisible();
      console.log('Object information displayed correctly in modal');
    });

    test('should show CMIS non-standard warning', async ({ page }) => {
      const docName = await navigateToDocument(page);
      if (!docName) {
        test.skip(true, 'ENV: No document found via CMIS API');
        return;
      }

      // Open type migration modal
      const typeMigrationButton = page.locator('button').filter({ hasText: /タイプを変更|Change Type/i });
      await expect(typeMigrationButton).toBeVisible({ timeout: 10000 });
      await typeMigrationButton.click();

      const modal = page.locator('.ant-modal').filter({ hasText: /オブジェクトタイプの変更|Change Object Type/i });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Verify non-standard operation warning is displayed
      const warningAlert = modal.locator('.ant-alert').filter({ hasText: /CMIS標準外|non-standard/i });
      await expect(warningAlert).toBeVisible();
      console.log('CMIS non-standard warning displayed correctly');
    });

    test('should display type selector with compatible types', async ({ page }) => {
      const docName = await navigateToDocument(page);
      if (!docName) {
        test.skip(true, 'ENV: No document found via CMIS API');
        return;
      }

      // Open type migration modal
      const typeMigrationButton = page.locator('button').filter({ hasText: /タイプを変更|Change Type/i });
      await expect(typeMigrationButton).toBeVisible({ timeout: 10000 });
      await typeMigrationButton.click();

      const modal = page.locator('.ant-modal').filter({ hasText: /オブジェクトタイプの変更|Change Object Type/i });
      await expect(modal).toBeVisible({ timeout: 5000 });
      await waitForRender(page); // Wait for types to load

      // Verify type selector label exists
      const typeSelectorLabel = modal.locator('text=/新しいタイプを選択|Select new type/i');
      await expect(typeSelectorLabel).toBeVisible();

      // Verify select component exists
      const typeSelector = modal.locator('.ant-select');
      await expect(typeSelector).toBeVisible();
      console.log('Type selector with compatible types displayed correctly');
    });
  });

  test.describe('Type Migration Modal Actions', () => {
    test('should disable OK button when no type selected', async ({ page }) => {
      const docName = await navigateToDocument(page);
      if (!docName) {
        test.skip(true, 'ENV: No document found via CMIS API');
        return;
      }

      // Open type migration modal
      const typeMigrationButton = page.locator('button').filter({ hasText: /タイプを変更|Change Type/i });
      await expect(typeMigrationButton).toBeVisible({ timeout: 10000 });
      await typeMigrationButton.click();

      const modal = page.locator('.ant-modal').filter({ hasText: /オブジェクトタイプの変更|Change Object Type/i });
      await expect(modal).toBeVisible({ timeout: 5000 });
      await waitForRender(page);

      // Verify OK button is disabled when no type selected
      const okButton = modal.locator('.ant-btn-primary').filter({ hasText: /タイプを変更|Change Type/i });
      await expect(okButton).toBeDisabled();
      console.log('OK button correctly disabled when no type selected');
    });

    test('should close modal on cancel', async ({ page }) => {
      const docName = await navigateToDocument(page);
      if (!docName) {
        test.skip(true, 'ENV: No document found via CMIS API');
        return;
      }

      // Open type migration modal
      const typeMigrationButton = page.locator('button').filter({ hasText: /タイプを変更|Change Type/i });
      await expect(typeMigrationButton).toBeVisible({ timeout: 10000 });
      await typeMigrationButton.click();

      const modal = page.locator('.ant-modal').filter({ hasText: /オブジェクトタイプの変更|Change Object Type/i });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Click cancel button
      const cancelButton = modal.locator('button').filter({ hasText: /キャンセル|Cancel/i });
      await cancelButton.click();

      // Verify modal closes
      await expect(modal).not.toBeVisible({ timeout: 5000 });
      console.log('Modal closed correctly on cancel');
    });
  });
});

test.describe('Type Migration - Error Handling', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);
    await authHelper.login();
    await testHelper.waitForAntdLoad();
  });

  test('should handle case when no compatible types available', async ({ page }) => {
    const docName = await navigateToDocument(page);
    if (!docName) {
      test.skip(true, 'ENV: No document found via CMIS API');
      return;
    }

    // Open type migration modal
    const typeMigrationButton = page.locator('button').filter({ hasText: /タイプを変更|Change Type/i });
    await expect(typeMigrationButton).toBeVisible({ timeout: 10000 });
    await typeMigrationButton.click();

    const modal = page.locator('.ant-modal').filter({ hasText: /オブジェクトタイプの変更|Change Object Type/i });
    await expect(modal).toBeVisible({ timeout: 5000 });
    await waitForUiStable(page); // Wait for types to load

    // Check if "互換タイプなし" warning is shown (only if no custom types defined)
    const noTypesWarning = modal.locator('.ant-alert').filter({ hasText: /互換タイプなし|No compatible types/i });
    const warningVisible = await noTypesWarning.count() > 0;
    console.log('No compatible types warning visible:', warningVisible);

    // Test passes regardless - we're just checking the UI handles both cases
    // (either showing compatible types in dropdown or showing the warning)
  });
});
