import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Document Versioning', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const testDocName = `version-test-${Date.now()}.txt`;
  const testDocContent = 'This is version 1.0';
  const updatedDocContent = 'This is version 2.0';

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

  test('should support document check-out operation', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a versionable document first
    const uploadButton = page.locator('button').filter({
      has: page.locator('[data-icon="upload"], span:has-text("アップロード")')
    }).first();

    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const fileInput = page.locator('input[type="file"]');
      if (await fileInput.count() > 0) {
        await fileInput.setInputFiles({
          name: testDocName,
          mimeType: 'text/plain',
          buffer: Buffer.from(testDocContent)
        });

        await page.waitForTimeout(2000);

        const successMessage = await page.locator('.ant-message-success, .ant-upload-success').isVisible({ timeout: 5000 }).catch(() => false);
        expect(successMessage || await page.locator('tr').filter({ hasText: testDocName }).count() > 0).toBeTruthy();
      }
    }

    await page.waitForTimeout(2000);

    // Find the document and check it out
    const docRow = page.locator('tr').filter({ hasText: testDocName });

    if (await docRow.count() > 0) {
      const checkOutButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="lock"], span:has-text("チェックアウト")')
      });

      if (await checkOutButton.count() > 0) {
        await checkOutButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Verify check-out success (document should show as checked out)
        const checkedOutIndicator = await page.locator('.ant-message-success, [data-icon="lock"]').isVisible({ timeout: 5000 }).catch(() => false);
        expect(checkedOutIndicator).toBeTruthy();
      }
    }
  });

  test('should support document check-in operation', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create and check out document first
    const uploadButton = page.locator('button').filter({
      has: page.locator('[data-icon="upload"], span:has-text("アップロード")')
    }).first();

    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const fileInput = page.locator('input[type="file"]');
      if (await fileInput.count() > 0) {
        await fileInput.setInputFiles({
          name: testDocName,
          mimeType: 'text/plain',
          buffer: Buffer.from(testDocContent)
        });
        await page.waitForTimeout(2000);
      }
    }

    await page.waitForTimeout(2000);

    const docRow = page.locator('tr').filter({ hasText: testDocName });

    // Check out first
    if (await docRow.count() > 0) {
      const checkOutButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="lock"], span:has-text("チェックアウト")')
      });

      if (await checkOutButton.count() > 0) {
        await checkOutButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);
      }
    }

    // Now check in with new version
    if (await docRow.count() > 0) {
      const checkInButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="unlock"], span:has-text("チェックイン")')
      });

      if (await checkInButton.count() > 0) {
        await checkInButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Check-in modal should appear
        const modal = page.locator('.ant-modal:visible');
        if (await modal.count() > 0) {
          // Upload new version content
          const fileInput = modal.locator('input[type="file"]');
          if (await fileInput.count() > 0) {
            await fileInput.setInputFiles({
              name: testDocName,
              mimeType: 'text/plain',
              buffer: Buffer.from(updatedDocContent)
            });
            await page.waitForTimeout(1000);
          }

          // Add version comment
          const commentInput = modal.locator('textarea, input[type="text"]');
          if (await commentInput.count() > 0) {
            await commentInput.first().fill('Updated to version 2.0');
          }

          // Submit check-in
          const submitButton = modal.locator('button.ant-btn-primary, button:has-text("OK")');
          if (await submitButton.count() > 0) {
            await submitButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(2000);

            const successMessage = await page.locator('.ant-message-success').isVisible({ timeout: 5000 }).catch(() => false);
            expect(successMessage).toBeTruthy();
          }
        }
      }
    }
  });

  test('should support cancel check-out operation', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create and check out document first
    const uploadButton = page.locator('button').filter({
      has: page.locator('[data-icon="upload"], span:has-text("アップロード")')
    }).first();

    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const fileInput = page.locator('input[type="file"]');
      if (await fileInput.count() > 0) {
        await fileInput.setInputFiles({
          name: testDocName,
          mimeType: 'text/plain',
          buffer: Buffer.from(testDocContent)
        });
        await page.waitForTimeout(2000);
      }
    }

    await page.waitForTimeout(2000);

    const docRow = page.locator('tr').filter({ hasText: testDocName });

    // Check out first
    if (await docRow.count() > 0) {
      const checkOutButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="lock"], span:has-text("チェックアウト")')
      });

      if (await checkOutButton.count() > 0) {
        await checkOutButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);
      }
    }

    // Cancel check-out
    if (await docRow.count() > 0) {
      const cancelCheckOutButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="close"], span:has-text("キャンセル")')
      });

      if (await cancelCheckOutButton.count() > 0) {
        await cancelCheckOutButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        // Confirm cancellation
        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button:has-text("OK")');
        if (await confirmButton.count() > 0) {
          await confirmButton.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(2000);

          const successMessage = await page.locator('.ant-message-success').isVisible({ timeout: 5000 }).catch(() => false);
          expect(successMessage).toBeTruthy();
        }
      }
    }
  });

  test('should display version history', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a versioned document first
    const uploadButton = page.locator('button').filter({
      has: page.locator('[data-icon="upload"], span:has-text("アップロード")')
    }).first();

    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const fileInput = page.locator('input[type="file"]');
      if (await fileInput.count() > 0) {
        await fileInput.setInputFiles({
          name: testDocName,
          mimeType: 'text/plain',
          buffer: Buffer.from(testDocContent)
        });
        await page.waitForTimeout(2000);
      }
    }

    await page.waitForTimeout(2000);

    const docRow = page.locator('tr').filter({ hasText: testDocName });

    if (await docRow.count() > 0) {
      const versionHistoryButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="history"], span:has-text("バージョン履歴")')
      });

      if (await versionHistoryButton.count() > 0) {
        await versionHistoryButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Version history modal should appear
        const modal = page.locator('.ant-modal:visible');
        await expect(modal).toBeVisible({ timeout: 5000 });

        // Should show at least version 1.0
        const versionList = modal.locator('.ant-table-tbody tr, .ant-list-item');
        const versionCount = await versionList.count();
        expect(versionCount).toBeGreaterThan(0);

        // Should show version label (1.0, 2.0, etc.)
        const versionLabel = modal.locator('text=/1\\.0|2\\.0|Version/');
        await expect(versionLabel.first()).toBeVisible({ timeout: 5000 });
      }
    }
  });

  test('should support downloading specific version', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a versioned document first
    const uploadButton = page.locator('button').filter({
      has: page.locator('[data-icon="upload"], span:has-text("アップロード")')
    }).first();

    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const fileInput = page.locator('input[type="file"]');
      if (await fileInput.count() > 0) {
        await fileInput.setInputFiles({
          name: testDocName,
          mimeType: 'text/plain',
          buffer: Buffer.from(testDocContent)
        });
        await page.waitForTimeout(2000);
      }
    }

    await page.waitForTimeout(2000);

    const docRow = page.locator('tr').filter({ hasText: testDocName });

    if (await docRow.count() > 0) {
      const versionHistoryButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="history"], span:has-text("バージョン履歴")')
      });

      if (await versionHistoryButton.count() > 0) {
        await versionHistoryButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        const modal = page.locator('.ant-modal:visible');
        await expect(modal).toBeVisible({ timeout: 5000 });

        // Find download button for version 1.0
        const downloadButton = modal.locator('button').filter({
          has: page.locator('[data-icon="download"]')
        }).first();

        if (await downloadButton.count() > 0) {
          const [download] = await Promise.all([
            page.waitForEvent('download', { timeout: 10000 }).catch(() => null),
            downloadButton.click(isMobile ? { force: true } : {})
          ]);

          if (download) {
            expect(download.suggestedFilename()).toContain(testDocName.replace('.txt', ''));
          }
        }
      }
    }
  });
});
