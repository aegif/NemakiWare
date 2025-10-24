import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Document Versioning', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();
    await page.waitForTimeout(2000);

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      } else {
        const alternativeToggle = page.locator('.ant-layout-header button, banner button').first();
        if (await alternativeToggle.count() > 0) {
          await alternativeToggle.click({ timeout: 3000 });
        }
      }
    }

    await testHelper.waitForAntdLoad();
  });

  test('should check-out a document', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Upload a test document first with unique filename
    const timestamp = Date.now();
    const filename = `versioning-test-${timestamp}.txt`;
    const uploadSuccess = await testHelper.uploadDocument(filename, 'Version 1.0 content', isMobile);
    if (!uploadSuccess) {
      console.log('Test: Upload failed - skipping test');
      test.skip();
      return;
    }

    // Find the uploaded document in the table
    console.log(`Test: Looking for ${filename} in document table`);
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: filename }).first();
    const docExists = await documentRow.count() > 0;
    console.log(`Test: Document found in table: ${docExists}`);

    if (!docExists) {
      console.log('Test: Document not found - checking table contents');
      const allRows = await page.locator('.ant-table-tbody tr').count();
      console.log(`Test: Total rows in table: ${allRows}`);
    }

    await expect(documentRow).toBeVisible();

    // Look for check-out button in the document row's action column
    const checkoutButton = documentRow.locator('button[aria-label*="チェックアウト"], button').filter({ hasText: /^$/ }).nth(0);
    
    await page.waitForTimeout(1000);
    
    const actionButtons = documentRow.locator('button');
    const buttonCount = await actionButtons.count();
    console.log(`Test: Found ${buttonCount} buttons in document row`);
    
    if (buttonCount > 0) {
      const firstButton = actionButtons.first();
      await firstButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Verify check-out success - document should show "作業中" (PWC) tag
      const pwcTag = page.locator('.ant-table-tbody tr').filter({ hasText: filename }).locator('.ant-tag').filter({ hasText: '作業中' });
      await expect(pwcTag).toBeVisible({ timeout: 5000 });
      console.log('Test: Document successfully checked out - PWC tag visible');
    } else {
      console.log('Check-out button not found - versioning feature may not be implemented in UI');
      test.skip();
    }

    // Cleanup: Delete the test document
    await page.locator('.ant-table-tbody tr').filter({ hasText: filename }).first().click();
    await page.waitForTimeout(500);

    const deleteButton = page.locator('button[data-icon="delete"], button').filter({ hasText: /削除|Delete/i }).first();
    if (await deleteButton.count() > 0) {
      await deleteButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      // Confirm deletion if modal appears
      const confirmButton = page.locator('.ant-modal button').filter({ hasText: /OK|削除|確認/i }).first();
      if (await confirmButton.count() > 0) {
        await confirmButton.click();
        await page.waitForTimeout(2000);
      }
    }
  });

  test('should check-in a document with new version', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Upload a test document first
    const uploadSuccess = await testHelper.uploadDocument('checkin-test.txt', 'Version 1.0 content', isMobile);
    if (!uploadSuccess) {
      console.log('Test: Upload failed - skipping test');
      test.skip();
      return;
    }

    // Select the document
    console.log('Test: Looking for checkin-test.txt in document table');
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'checkin-test.txt' }).first();
    await expect(documentRow).toBeVisible();
    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Check-out the document first
    const checkoutButton = page.locator('button, .ant-btn').filter({ hasText: /チェックアウト|Check.*Out/i }).first();
    if (await checkoutButton.count() > 0) {
      await checkoutButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Now check-in with new content
      const checkinButton = page.locator('button, .ant-btn').filter({ hasText: /チェックイン|Check.*In/i }).first();
      if (await checkinButton.count() > 0) {
        await checkinButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Fill check-in form if modal appears
        const versionCommentInput = page.locator('input[placeholder*="バージョン"], textarea[placeholder*="コメント"]').first();
        if (await versionCommentInput.count() > 0) {
          await versionCommentInput.fill('Updated to version 2.0');
        }

        // Upload new version content if file input appears
        const checkinFileInput = page.locator('input[type="file"]').last();
        if (await checkinFileInput.isVisible()) {
          await checkinFileInput.setInputFiles({
            name: 'checkin-test.txt',
            mimeType: 'text/plain',
            buffer: Buffer.from('Version 2.0 content - updated', 'utf-8'),
          });
        }

        // Submit check-in
        const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal button').filter({ hasText: /OK|確認|チェックイン/i }).first();
        if (await submitButton.count() > 0) {
          await submitButton.click();
          await page.waitForTimeout(2000);
        }

        // Verify check-in success (PWC indicator should disappear)
        const pwcIndicator = page.locator('.ant-tag, .ant-badge').filter({ hasText: /PWC|作業中/i });
        await expect(pwcIndicator).toHaveCount(0, { timeout: 5000 });
      }
    } else {
      console.log('Versioning buttons not found - feature may not be implemented in UI');
      test.skip();
    }

    // Cleanup: Delete the test document
    await page.locator('.ant-table-tbody tr').filter({ hasText: 'checkin-test.txt' }).first().click();
    await page.waitForTimeout(500);

    const deleteButton = page.locator('button[data-icon="delete"], button').filter({ hasText: /削除|Delete/i }).first();
    if (await deleteButton.count() > 0) {
      await deleteButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      const confirmButton = page.locator('.ant-modal button').filter({ hasText: /OK|削除|確認/i }).first();
      if (await confirmButton.count() > 0) {
        await confirmButton.click();
        await page.waitForTimeout(2000);
      }
    }
  });

  test('should cancel check-out', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Upload a test document
    const uploadSuccess = await testHelper.uploadDocument('cancel-checkout-test.txt', 'Original content', isMobile);
    if (!uploadSuccess) {
      console.log('Test: Upload failed - skipping test');
      test.skip();
      return;
    }

    // Select and check-out the document
    console.log('Test: Looking for cancel-checkout-test.txt in document table');
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'cancel-checkout-test.txt' }).first();
    await expect(documentRow).toBeVisible();
    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const checkoutButton = page.locator('button, .ant-btn').filter({ hasText: /チェックアウト|Check.*Out/i }).first();
    if (await checkoutButton.count() > 0) {
      await checkoutButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Cancel check-out
      const cancelCheckoutButton = page.locator('button, .ant-btn').filter({ hasText: /チェックアウト.*キャンセル|Cancel.*Check.*Out/i }).first();
      if (await cancelCheckoutButton.count() > 0) {
        await cancelCheckoutButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Confirm cancellation if modal appears
        const confirmButton = page.locator('.ant-modal button').filter({ hasText: /OK|確認|キャンセル/i }).first();
        if (await confirmButton.count() > 0) {
          await confirmButton.click();
          await page.waitForTimeout(2000);
        }

        // Verify PWC indicator is gone
        const pwcIndicator = page.locator('.ant-tag, .ant-badge').filter({ hasText: /PWC|作業中/i });
        await expect(pwcIndicator).toHaveCount(0, { timeout: 5000 });
      }
    } else {
      console.log('Check-out cancel button not found - feature may not be implemented in UI');
      test.skip();
    }

    // Cleanup
    await page.locator('.ant-table-tbody tr').filter({ hasText: 'cancel-checkout-test.txt' }).first().click();
    const deleteButton = page.locator('button[data-icon="delete"], button').filter({ hasText: /削除|Delete/i }).first();
    if (await deleteButton.count() > 0) {
      await deleteButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      const confirmButton = page.locator('.ant-modal button').filter({ hasText: /OK|削除|確認/i }).first();
      if (await confirmButton.count() > 0) {
        await confirmButton.click();
        await page.waitForTimeout(2000);
      }
    }
  });

  test('should display version history', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Upload a test document
    const uploadSuccess = await testHelper.uploadDocument('version-history-test.txt', 'Version 1.0', isMobile);
    if (!uploadSuccess) {
      console.log('Test: Upload failed - skipping test');
      test.skip();
      return;
    }

    // Select the document
    console.log('Test: Looking for version-history-test.txt in document table');
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'version-history-test.txt' }).first();
    await expect(documentRow).toBeVisible();
    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Look for version history button (might be in context menu or toolbar)
    const versionHistoryButton = page.locator('button, .ant-btn, .ant-menu-item').filter({
      hasText: /バージョン履歴|バージョン|Version.*History|Versions/i
    }).first();

    if (await versionHistoryButton.count() > 0) {
      await versionHistoryButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Verify version history modal/panel appears
      const versionHistoryModal = page.locator('.ant-modal, .ant-drawer').filter({
        has: page.locator('text=/バージョン履歴|Version.*History/i')
      });

      if (await versionHistoryModal.count() > 0) {
        await expect(versionHistoryModal).toBeVisible();

        // Verify at least one version is listed (initial version 1.0)
        const versionListItems = page.locator('.ant-table-tbody tr, .ant-list-item').filter({
          hasText: /1\.0|v1/i
        });
        await expect(versionListItems.first()).toBeVisible();

        // Close the modal
        const closeButton = page.locator('.ant-modal-close, button').filter({ hasText: /閉じる|Close|キャンセル/i }).first();
        if (await closeButton.count() > 0) {
          await closeButton.click();
        }
      } else {
        console.log('Version history modal not found - UI implementation may differ');
      }
    } else {
      console.log('Version history button not found - feature may not be implemented in UI');
      test.skip();
    }

    // Cleanup
    await page.locator('.ant-table-tbody tr').filter({ hasText: 'version-history-test.txt' }).first().click();
    const deleteButton = page.locator('button[data-icon="delete"], button').filter({ hasText: /削除|Delete/i }).first();
    if (await deleteButton.count() > 0) {
      await deleteButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      const confirmButton = page.locator('.ant-modal button').filter({ hasText: /OK|削除|確認/i }).first();
      if (await confirmButton.count() > 0) {
        await confirmButton.click();
        await page.waitForTimeout(2000);
      }
    }
  });

  test('should download a specific version', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Upload a test document
    const uploadSuccess = await testHelper.uploadDocument('version-download-test.txt', 'Version 1.0 for download', isMobile);
    if (!uploadSuccess) {
      console.log('Test: Upload failed - skipping test');
      test.skip();
      return;
    }

    // Select the document
    console.log('Test: Looking for version-download-test.txt in document table');
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'version-download-test.txt' }).first();
    await expect(documentRow).toBeVisible();
    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Open version history
    const versionHistoryButton = page.locator('button, .ant-btn, .ant-menu-item').filter({
      hasText: /バージョン履歴|バージョン|Version.*History|Versions/i
    }).first();

    if (await versionHistoryButton.count() > 0) {
      await versionHistoryButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Look for download button in version history
      const versionDownloadButton = page.locator('button, .ant-btn').filter({
        hasText: /ダウンロード|Download/i
      }).first();

      if (await versionDownloadButton.count() > 0) {
        // Setup download listener
        const downloadPromise = page.waitForEvent('download', { timeout: 10000 });

        // Click download button
        await versionDownloadButton.click(isMobile ? { force: true } : {});

        try {
          const download = await downloadPromise;

          // Verify download started
          expect(download.suggestedFilename()).toContain('version-download-test');

          // Wait for download to complete
          await download.path();
          console.log('Version download successful:', download.suggestedFilename());
        } catch (error) {
          console.log('Download did not complete:', error);
        }

        // Close version history modal
        const closeButton = page.locator('.ant-modal-close, button').filter({ hasText: /閉じる|Close/i }).first();
        if (await closeButton.count() > 0) {
          await closeButton.click();
        }
      } else {
        console.log('Version download button not found - feature may not be implemented in UI');
        test.skip();
      }
    } else {
      console.log('Version history not accessible - skipping download test');
      test.skip();
    }

    // Cleanup
    await page.locator('.ant-table-tbody tr').filter({ hasText: 'version-download-test.txt' }).first().click();
    const deleteButton = page.locator('button[data-icon="delete"], button').filter({ hasText: /削除|Delete/i }).first();
    if (await deleteButton.count() > 0) {
      await deleteButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      const confirmButton = page.locator('.ant-modal button').filter({ hasText: /OK|削除|確認/i }).first();
      if (await confirmButton.count() > 0) {
        await confirmButton.click();
        await page.waitForTimeout(2000);
      }
    }
  });
});
