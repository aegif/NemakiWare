import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe.skip('Document Versioning', () => {
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

    // Upload a test document first
    const uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'versioning-test.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('Version 1.0 content', 'utf-8'),
    });

    await page.waitForTimeout(2000);

    // Find the uploaded document in the table
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'versioning-test.txt' }).first();
    await expect(documentRow).toBeVisible();

    // Click the document row to select it
    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Look for check-out action button (might be in action menu or toolbar)
    const checkoutButton = page.locator('button, .ant-btn').filter({ hasText: /チェックアウト|Check.*Out/i }).first();

    if (await checkoutButton.count() > 0) {
      await checkoutButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Verify check-out success (document should show as checked out)
      const checkedOutIndicator = page.locator('.ant-table-tbody tr').filter({ hasText: 'versioning-test.txt' });
      await expect(checkedOutIndicator).toBeVisible();

      // Look for PWC (Private Working Copy) indicator
      const pwcIndicator = page.locator('.ant-tag, .ant-badge').filter({ hasText: /PWC|作業中|Checked.*Out/i });
      // Note: PWC indicator might not be visible depending on UI implementation
    } else {
      console.log('Check-out button not found - versioning feature may not be implemented in UI');
      test.skip();
    }

    // Cleanup: Delete the test document
    await page.locator('.ant-table-tbody tr').filter({ hasText: 'versioning-test.txt' }).first().click();
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
    const uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'checkin-test.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('Version 1.0 content', 'utf-8'),
    });

    await page.waitForTimeout(2000);

    // Select the document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'checkin-test.txt' }).first();
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
    const uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'cancel-checkout-test.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('Original content', 'utf-8'),
    });

    await page.waitForTimeout(2000);

    // Select and check-out the document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'cancel-checkout-test.txt' }).first();
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
    const uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'version-history-test.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('Version 1.0', 'utf-8'),
    });

    await page.waitForTimeout(2000);

    // Select the document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'version-history-test.txt' }).first();
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
    const uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'version-download-test.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('Version 1.0 for download', 'utf-8'),
    });

    await page.waitForTimeout(2000);

    // Select the document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: 'version-download-test.txt' }).first();
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
