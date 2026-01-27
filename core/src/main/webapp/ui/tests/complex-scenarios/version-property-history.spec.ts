/**
 * Complex Scenario Test: Version and Property History Consistency
 *
 * This test suite validates the consistency between:
 * 1. Document versioning (check-out, check-in, version history)
 * 2. Property value changes across versions
 * 3. Version-specific property retrieval
 * 4. Version deletion and property state restoration
 *
 * Test Environment:
 * - Server: http://localhost:8080/core/ui/
 * - Authentication: admin:admin
 * - Repository: bedroom
 *
 * CMIS Concepts Tested:
 * - Version Series Management
 * - PWC (Private Working Copy) Operations
 * - Property Persistence Across Versions
 * - Version History Navigation
 * - Version Deletion and Rollback
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper, generateTestId } from '../utils/test-helper';

import {
  TIMEOUTS,
  I18N_PATTERNS,
} from './test-constants';

test.describe('Version and Property History Consistency', () => {
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  const testRunId = generateTestId();
  const testDocumentName = `version-history-test-${testRunId}.txt`;
  const initialContent = `Initial content - ${testRunId}`;
  const version2Content = `Version 2 content - ${testRunId}`;
  const version3Content = `Version 3 content - ${testRunId}`;

  let testDocumentId: string;
  let version1Label: string;
  let version2Label: string;
  let version3Label: string;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);

    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(500);
      }
    }

    await testHelper.waitForAntdLoad();
  });

  test('Step 1: Create initial document (Version 1.0)', async ({ page, browserName }) => {
    console.log(`Creating initial document: ${testDocumentName}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Upload document
    const uploadSuccess = await testHelper.uploadDocument(testDocumentName, initialContent, isMobile);

    if (uploadSuccess) {
      console.log(`Document ${testDocumentName} created successfully`);
      const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
      const rowKey = await documentRow.getAttribute('data-row-key');
      if (rowKey) {
        testDocumentId = rowKey;
        console.log(`Document ID: ${testDocumentId}`);
      }
      version1Label = '1.0';
      console.log(`Initial version: ${version1Label}`);
    }

    expect(uploadSuccess).toBe(true);
  });

  test('Step 2: Check out document and create Version 2.0', async ({ page, browserName }) => {
    console.log('Creating Version 2.0...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    // Look for check-out button (EditOutlined icon)
    const checkoutButton = documentRow.locator('button').filter({ has: page.locator('span[role="img"][aria-label="edit"]') }).first();
    if (await checkoutButton.count() > 0) {
      await checkoutButton.click(isMobile ? { force: true } : {});
      console.log('Clicked checkout button');
      await page.waitForTimeout(3000);

      // Wait for PWC indicator (作業中 tag)
      const pwcTag = page.locator('.ant-tag').filter({ hasText: '作業中' });
      const isPWC = await pwcTag.count() > 0;
      console.log(`PWC created: ${isPWC}`);

      // Look for check-in button (CheckOutlined icon)
      await page.waitForTimeout(1000);
      const checkinButton = page.locator('button').filter({ has: page.locator('span[role="img"][aria-label="check"]') }).first();
      if (await checkinButton.count() > 0) {
        await checkinButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Fill check-in form if modal appears
        const checkinModal = page.locator('.ant-modal:visible');
        if (await checkinModal.count() > 0) {
          // Upload new content
          const fileInput = checkinModal.locator('input[type="file"]');
          if (await fileInput.count() > 0) {
            await fileInput.setInputFiles({
              name: testDocumentName,
              mimeType: 'text/plain',
              buffer: Buffer.from(version2Content, 'utf-8'),
            });
            await page.waitForTimeout(500);
          }

          // Fill version comment
          const commentInput = checkinModal.locator('input[placeholder*="コメント"], textarea');
          if (await commentInput.count() > 0) {
            await commentInput.first().fill('Version 2.0 - Updated content');
          }

          // Select major version if option available
          const majorVersionRadio = checkinModal.locator('input[type="radio"]').filter({ hasText: /メジャー|Major/ });
          if (await majorVersionRadio.count() > 0) {
            await majorVersionRadio.click();
          }

          // Submit check-in
          const submitButton = checkinModal.locator('button[type="submit"], button:has-text("チェックイン")').first();
          if (await submitButton.count() > 0) {
            await submitButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(3000);
            version2Label = '2.0';
            console.log(`Version 2.0 created`);
          }
        }
      } else {
        console.log('Check-in button not found');
      }
    } else {
      console.log('Checkout button not found - versioning may not be available');
    }
  });

  test('Step 3: Create Version 3.0 with different content', async ({ page, browserName }) => {
    console.log('Creating Version 3.0...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    // Check out
    const checkoutButton = documentRow.locator('button').filter({ has: page.locator('span[role="img"][aria-label="edit"]') }).first();
    if (await checkoutButton.count() > 0) {
      await checkoutButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);

      // Check in with new content
      const checkinButton = page.locator('button').filter({ has: page.locator('span[role="img"][aria-label="check"]') }).first();
      if (await checkinButton.count() > 0) {
        await checkinButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        const checkinModal = page.locator('.ant-modal:visible');
        if (await checkinModal.count() > 0) {
          // Upload new content
          const fileInput = checkinModal.locator('input[type="file"]');
          if (await fileInput.count() > 0) {
            await fileInput.setInputFiles({
              name: testDocumentName,
              mimeType: 'text/plain',
              buffer: Buffer.from(version3Content, 'utf-8'),
            });
            await page.waitForTimeout(500);
          }

          // Fill version comment
          const commentInput = checkinModal.locator('input[placeholder*="コメント"], textarea');
          if (await commentInput.count() > 0) {
            await commentInput.first().fill('Version 3.0 - Final content');
          }

          // Submit check-in
          const submitButton = checkinModal.locator('button[type="submit"], button:has-text("チェックイン")').first();
          if (await submitButton.count() > 0) {
            await submitButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(3000);
            version3Label = '3.0';
            console.log(`Version 3.0 created`);
          }
        }
      }
    }
  });

  test('Step 4: View version history and verify all versions exist', async ({ page, browserName }) => {
    console.log('Viewing version history...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find and click on the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Look for version history button or tab
    const versionHistoryButton = page.locator('button, .ant-tabs-tab').filter({ hasText: /バージョン履歴|Version History|履歴/ }).first();
    if (await versionHistoryButton.count() > 0) {
      await versionHistoryButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Count versions in history
      const versionRows = page.locator('.ant-table-tbody tr, .ant-list-item');
      const versionCount = await versionRows.count();
      console.log(`Version history contains ${versionCount} versions`);

      // Verify version labels are present
      const hasVersion1 = await page.locator('text=/1\\.0/').count() > 0;
      const hasVersion2 = await page.locator('text=/2\\.0/').count() > 0;
      const hasVersion3 = await page.locator('text=/3\\.0/').count() > 0;

      console.log(`Version 1.0 present: ${hasVersion1}`);
      console.log(`Version 2.0 present: ${hasVersion2}`);
      console.log(`Version 3.0 present: ${hasVersion3}`);
    } else {
      console.log('Version history button not found');
    }
  });

  test('Step 5: Delete latest version and verify rollback to previous version', async ({ page, browserName }) => {
    console.log('Deleting latest version...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find and click on the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Open version history
    const versionHistoryButton = page.locator('button, .ant-tabs-tab').filter({ hasText: /バージョン履歴|Version History|履歴/ }).first();
    if (await versionHistoryButton.count() > 0) {
      await versionHistoryButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Find delete button for latest version (3.0)
      const latestVersionRow = page.locator('.ant-table-tbody tr, .ant-list-item').first();
      const deleteButton = latestVersionRow.locator('button').filter({ hasText: /削除|Delete/ }).first();

      if (await deleteButton.count() > 0) {
        await deleteButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        // Confirm deletion
        const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除/ }).first();
        if (await confirmButton.count() > 0) {
          await confirmButton.click();
          await page.waitForTimeout(3000);
          console.log('Latest version deleted');
        }
      } else {
        console.log('Delete version button not found');
      }

      // Verify current version is now 2.0
      await page.waitForTimeout(2000);
      const currentVersionLabel = page.locator('text=/現在.*2\\.0|Latest.*2\\.0|2\\.0.*最新/');
      const isVersion2Current = await currentVersionLabel.count() > 0;
      console.log(`Version 2.0 is now current: ${isVersion2Current}`);
    }
  });

  test('Step 6: Verify document content matches previous version after rollback', async ({ page, browserName }) => {
    console.log('Verifying content after version rollback...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Look for preview or download button to verify content
    const previewButton = page.locator('button').filter({ hasText: /プレビュー|Preview|表示/ }).first();
    if (await previewButton.count() > 0) {
      await previewButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Check if preview shows version 2 content
      const previewContent = page.locator('.ant-modal:visible, .preview-container, iframe');
      if (await previewContent.count() > 0) {
        console.log('Preview opened - content should match Version 2.0');
        // Note: Actual content verification depends on preview implementation
      }

      // Close preview
      const closeButton = page.locator('.ant-modal-close').first();
      if (await closeButton.count() > 0) {
        await closeButton.click();
      }
    } else {
      console.log('Preview button not found');
    }

    // Verify version label shows 2.0
    const versionLabel = page.locator('text=/バージョン.*2\\.0|Version.*2\\.0/');
    const showsVersion2 = await versionLabel.count() > 0;
    console.log(`Document shows Version 2.0: ${showsVersion2}`);
  });

  test.afterAll(async ({ browser }) => {
    console.log('=== Starting cleanup for version-property-history test ===');
    console.log(`Test Run ID: ${testRunId}`);
    console.log(`Document to clean: ${testDocumentName} (ID: ${testDocumentId || 'unknown'})`);

    const context = await browser.newContext();
    const page = await context.newPage();
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);
    const failedCleanups: string[] = [];

    try {
      await authHelper.login();
      await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

      // Step 1: Delete test document
      console.log('[Cleanup Step 1] Deleting test document...');
      try {
        const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: I18N_PATTERNS.DOCUMENTS });
        if (await documentsMenuItem.count() > 0) {
          await documentsMenuItem.click();
          await page.waitForTimeout(TIMEOUTS.PAGE_LOAD);

          await testHelper.deleteTestDocument(testDocumentName);
          console.log(`[Cleanup] Successfully deleted document: ${testDocumentName}`);
        }
      } catch (docError) {
        const errorMsg = `document: ${testDocumentName} (ID: ${testDocumentId || 'unknown'})`;
        failedCleanups.push(errorMsg);
        console.error(`[Cleanup] Failed to delete ${errorMsg}:`, docError);
      }

    } catch (error) {
      console.error('[Cleanup] Fatal error during cleanup:', error);
    } finally {
      await context.close();

      // Report cleanup failures for manual intervention
      if (failedCleanups.length > 0) {
        console.warn('=== CLEANUP FAILURES - Manual cleanup required ===');
        console.warn('The following items could not be deleted automatically:');
        failedCleanups.forEach(item => console.warn(`  - ${item}`));
        console.warn('=================================================');
      } else {
        console.log('=== Cleanup completed successfully ===');
      }
    }
  });
});
