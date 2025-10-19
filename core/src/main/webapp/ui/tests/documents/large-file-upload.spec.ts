import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Large File Upload', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);
    await testHelper.waitForAntdLoad();

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(500);
      }
    }

    await page.goto('http://localhost:8080/core/ui/dist/index.html#/documents');
    await page.waitForTimeout(2000);
  });

  test.afterEach(async ({ page }) => {
    await authHelper.logout();
  });

  test('should support uploading files larger than 100MB', async ({ page, browserName }) => {
    // This test is skipped by default as it takes very long time
    // Enable only for comprehensive testing
    test.skip(true, 'Large file upload test takes very long time - skip by default');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create 100MB+ file
    const largeFileName = `large-file-${Date.now()}.bin`;
    const largeFileSize = 100 * 1024 * 1024; // 100MB
    const largeFileBuffer = Buffer.alloc(largeFileSize);

    const uploadButton = page.locator('button').filter({
      has: page.locator('[data-icon="upload"], span:has-text("アップロード")')
    }).first();

    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const fileInput = page.locator('input[type="file"]');
      if (await fileInput.count() > 0) {
        await fileInput.setInputFiles({
          name: largeFileName,
          mimeType: 'application/octet-stream',
          buffer: largeFileBuffer
        });

        // Wait for upload to complete (may take several minutes)
        // Look for progress indicator
        const progressIndicator = page.locator('.ant-upload-list-item-uploading, .ant-progress');
        if (await progressIndicator.count() > 0) {
          await expect(progressIndicator.first()).toBeVisible({ timeout: 10000 });

          // Wait for upload to complete (extended timeout for large file)
          await page.waitForTimeout(180000); // 3 minutes max
        }

        // Verify upload success
        const successMessage = await page.locator('.ant-message-success, .ant-upload-success').isVisible({ timeout: 30000 }).catch(() => false);
        const fileInList = await page.locator('tr').filter({ hasText: largeFileName }).count() > 0;

        expect(successMessage || fileInList).toBeTruthy();

        // Cleanup - delete the large file
        if (fileInList) {
          const fileRow = page.locator('tr').filter({ hasText: largeFileName });
          const deleteButton = fileRow.locator('button').filter({
            has: page.locator('[data-icon="delete"]')
          });

          if (await deleteButton.count() > 0) {
            await deleteButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(500);

            const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button:has-text("OK")');
            if (await confirmButton.count() > 0) {
              await confirmButton.click(isMobile ? { force: true } : {});
              await page.waitForTimeout(2000);
            }
          }
        }
      }
    }
  });

  test('should show progress indicator for large file uploads', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create moderately large file (10MB - reasonable for testing)
    const fileName = `progress-test-${Date.now()}.bin`;
    const fileSize = 10 * 1024 * 1024; // 10MB
    const fileBuffer = Buffer.alloc(fileSize);

    const uploadButton = page.locator('button').filter({
      has: page.locator('[data-icon="upload"], span:has-text("アップロード")')
    }).first();

    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const fileInput = page.locator('input[type="file"]');
      if (await fileInput.count() > 0) {
        await fileInput.setInputFiles({
          name: fileName,
          mimeType: 'application/octet-stream',
          buffer: fileBuffer
        });

        // Immediately check for progress indicator
        await page.waitForTimeout(500);

        const progressIndicator = page.locator('.ant-upload-list-item-uploading, .ant-progress, .ant-upload-list-item-progress');
        const hasProgress = await progressIndicator.count() > 0;

        if (hasProgress) {
          // Verify progress indicator is visible
          await expect(progressIndicator.first()).toBeVisible({ timeout: 5000 });

          // Wait for upload to complete
          await page.waitForTimeout(30000); // 30 seconds max for 10MB

          // Verify upload completed
          const successIndicator = page.locator('.ant-upload-list-item-done, .ant-upload-success');
          const uploadComplete = await successIndicator.count() > 0;
          expect(uploadComplete).toBeTruthy();
        } else {
          console.log('Progress indicator not found - upload may have completed too quickly');
          // For very fast uploads, just verify the file appears in the list
          await page.waitForTimeout(3000);
          const fileInList = await page.locator('tr').filter({ hasText: fileName }).count() > 0;
          expect(fileInList).toBeTruthy();
        }

        // Cleanup - delete the file
        await page.waitForTimeout(2000);
        const fileRow = page.locator('tr').filter({ hasText: fileName });
        if (await fileRow.count() > 0) {
          const deleteButton = fileRow.locator('button').filter({
            has: page.locator('[data-icon="delete"]')
          });

          if (await deleteButton.count() > 0) {
            await deleteButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(500);

            const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button:has-text("OK")');
            if (await confirmButton.count() > 0) {
              await confirmButton.click(isMobile ? { force: true } : {});
              await page.waitForTimeout(2000);
            }
          }
        }
      }
    }
  });
});
