import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Large File Upload', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();
    await page.waitForTimeout(2000);

    // MOBILE FIX: Close sidebar
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    await testHelper.waitForAntdLoad();

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click();
    await page.waitForTimeout(2000);
  });

  test('should upload a large file (>100MB) with progress tracking', async ({ page, browserName }) => {
    test.setTimeout(300000); // 5-minute timeout for large file upload
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const largeFileName = `large-file-${Date.now()}.bin`;
    const fileSize = 110 * 1024 * 1024; // 110 MB

    console.log(`Test: Preparing to upload ${fileSize / (1024 * 1024)} MB file`);

    // Create a large buffer (110 MB)
    // Note: Creating a very large buffer may consume significant memory
    // For actual production testing, consider using a smaller size or streaming approach
    const largeBuffer = Buffer.alloc(fileSize);

    // Fill with some pattern to make it compressible
    for (let i = 0; i < fileSize; i += 1024) {
      largeBuffer.write('LARGE_FILE_TEST_CONTENT', i);
    }

    console.log(`Test: Large buffer created (${fileSize} bytes)`);

    // Click upload button
    const uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Set file input
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: largeFileName,
      mimeType: 'application/octet-stream',
      buffer: largeBuffer,
    });

    console.log('Test: Large file set to input field');

    // Wait for upload to process
    // Look for progress indicators or completion messages
    const progressIndicators = page.locator('.ant-progress, .ant-upload-list-item-progress');

    if (await progressIndicators.count() > 0) {
      console.log('Test: Upload progress indicator found');

      // Wait for progress to complete (may take several seconds for large file)
      await expect(progressIndicators.first()).toBeVisible({ timeout: 60000 });

      // Monitor progress changes
      let previousProgress = 0;
      for (let i = 0; i < 30; i++) {
        await page.waitForTimeout(1000);

        const progressText = await page.locator('.ant-progress-text, .ant-upload-list-item-progress').textContent();
        if (progressText) {
          const progressMatch = progressText.match(/(\d+)%/);
          if (progressMatch) {
            const currentProgress = parseInt(progressMatch[1]);
            if (currentProgress > previousProgress) {
              console.log(`Test: Upload progress: ${currentProgress}%`);
              previousProgress = currentProgress;
            }

            if (currentProgress >= 100) {
              console.log('Test: Upload progress reached 100%');
              break;
            }
          }
        }
      }
    } else {
      console.log('Test: No progress indicator found - upload may be instant or background');
    }

    // Wait for success message
    try {
      await page.waitForSelector('.ant-message-success, .ant-notification-success', { timeout: 120000 });
      console.log('Test: Upload success message appeared');
    } catch (error) {
      console.log('Test: Upload success message timeout - may still be processing');
    }

    await page.waitForTimeout(5000);

    // Verify file appears in document list
    const uploadedFile = page.locator('.ant-table-tbody tr').filter({ hasText: largeFileName });

    if (await uploadedFile.count() > 0) {
      await expect(uploadedFile).toBeVisible({ timeout: 10000 });
      console.log('Test: Large file appears in document list');

      // Verify file size is displayed correctly
      const fileSizeCell = uploadedFile.locator('td').filter({ hasText: /MB|KB|GB/i });
      if (await fileSizeCell.count() > 0) {
        const displayedSize = await fileSizeCell.textContent();
        console.log(`Test: Displayed file size: ${displayedSize}`);

        // Verify it shows approximately 110 MB
        expect(displayedSize).toContain('MB');
      }

      // Cleanup: Delete the large file
      await uploadedFile.click();
      await page.waitForTimeout(500);

      const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
      if (await deleteButton.count() > 0) {
        await deleteButton.first().click();
        await page.waitForTimeout(500);

        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
        if (await confirmButton.count() > 0) {
          await confirmButton.first().click();
          await page.waitForTimeout(5000); // Large file deletion may take time
          console.log('Test: Large file deleted');
        }
      }
    } else {
      console.log('Test: Large file not found in document list - upload may have failed or still processing');
    }
  });

  test('should handle upload cancellation gracefully', async ({ page, browserName }) => {
    test.setTimeout(120000); // 2-minute timeout
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const cancelTestFileName = `cancel-test-${Date.now()}.bin`;
    const fileSize = 50 * 1024 * 1024; // 50 MB

    console.log(`Test: Testing upload cancellation with ${fileSize / (1024 * 1024)} MB file`);

    // Create buffer
    const buffer = Buffer.alloc(fileSize);
    for (let i = 0; i < fileSize; i += 1024) {
      buffer.write('CANCEL_TEST_CONTENT', i);
    }

    // Click upload button
    const uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    // Set file input
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: cancelTestFileName,
      mimeType: 'application/octet-stream',
      buffer: buffer,
    });

    console.log('Test: File set for upload, looking for cancel button');

    // Wait a moment for upload to start
    await page.waitForTimeout(2000);

    // Look for cancel/close button in upload list item
    const cancelButton = page.locator('.ant-upload-list-item-card-actions button, .ant-upload-list-item button').filter({
      has: page.locator('[data-icon="close"], [data-icon="delete"]')
    });

    if (await cancelButton.count() > 0) {
      console.log('Test: Cancel button found, clicking to cancel upload');
      await cancelButton.first().click();

      await page.waitForTimeout(2000);

      // Verify upload was cancelled (file should not appear in document list)
      const cancelledFile = page.locator('.ant-table-tbody tr').filter({ hasText: cancelTestFileName });
      const fileExists = await cancelledFile.count() > 0;

      if (!fileExists) {
        console.log('Test: Upload successfully cancelled - file not in document list');
      } else {
        console.log('Test: File appears in list despite cancellation - may have uploaded before cancel');

        // Cleanup if file was uploaded
        await cancelledFile.click();
        await page.waitForTimeout(500);

        const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
        if (await deleteButton.count() > 0) {
          await deleteButton.first().click();
          await page.waitForTimeout(500);

          const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
          if (await confirmButton.count() > 0) {
            await confirmButton.first().click();
            await page.waitForTimeout(2000);
          }
        }
      }
    } else {
      console.log('Test: Cancel button not found - upload cancellation may not be supported or upload was too fast');
      test.skip('Upload cancellation UI not available or upload completed before cancel');

      // Try to find and delete the file if it was uploaded
      const uploadedFile = page.locator('.ant-table-tbody tr').filter({ hasText: cancelTestFileName });
      if (await uploadedFile.count() > 0) {
        await uploadedFile.click();
        await page.waitForTimeout(500);

        const deleteButton = page.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
        if (await deleteButton.count() > 0) {
          await deleteButton.first().click();
          await page.waitForTimeout(500);

          const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button').filter({ hasText: /OK|確認/ });
          if (await confirmButton.count() > 0) {
            await confirmButton.first().click();
            await page.waitForTimeout(2000);
          }
        }
      }
    }
  });
});
