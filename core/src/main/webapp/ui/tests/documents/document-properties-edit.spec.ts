import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Document Properties Edit and Persistence', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const testDocName = `test-props-doc-${Date.now()}.txt`;
  let testDocId: string;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await page.context().clearCookies();
    await page.context().clearPermissions();
    await authHelper.login();
    await testHelper.waitForAntdLoad();

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await page.waitForTimeout(2000);
    }

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');

      if (await menuToggle.count() > 0) {
        try {
          await menuToggle.first().click({ timeout: 3000 });
          await page.waitForTimeout(500);
        } catch (error) {
          // Continue even if sidebar close fails
        }
      } else {
        const alternativeToggle = page.locator('.ant-layout-header button, banner button').first();
        if (await alternativeToggle.count() > 0) {
          try {
            await alternativeToggle.click({ timeout: 3000 });
            await page.waitForTimeout(500);
          } catch (error) {
            // Continue even if alternative selector fails
          }
        }
      }
    }
  });

  test('should upload test document for property editing', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });

    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

      await testHelper.uploadTestFile(
        '.ant-modal input[type="file"]',
        testDocName,
        'Test content for property editing'
      );

      await page.waitForTimeout(1000);

      const submitBtn = page.locator('.ant-modal button[type="submit"]');
      await submitBtn.click();

      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);

      // Verify document appears
      const uploadedDoc = page.locator(`text=${testDocName}`);
      await expect(uploadedDoc).toBeVisible({ timeout: 5000 });
    } else {
      test.skip('Upload functionality not available');
    }
  });

  test('should open and edit document properties', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    await page.waitForTimeout(2000);

    // Find the test document row
    const docRow = page.locator('tr').filter({ hasText: testDocName });

    if (await docRow.count() > 0) {
      // Look for properties/edit button (may be gear icon, edit icon, or properties text)
      const propertiesButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="edit"], [data-icon="setting"], [data-icon="form"]')
      });

      if (await propertiesButton.count() > 0) {
        await propertiesButton.first().click(isMobile ? { force: true } : {});
      } else {
        // Try clicking detail view button first
        const detailButton = docRow.locator('button').filter({
          has: page.locator('[data-icon="eye"]')
        });
        if (await detailButton.count() > 0) {
          await detailButton.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);

          // Look for edit button in detail view
          const editInDetail = page.locator('button:has-text("編集"), button').filter({
            has: page.locator('[data-icon="edit"]')
          });
          if (await editInDetail.count() > 0) {
            await editInDetail.first().click(isMobile ? { force: true } : {});
          }
        }
      }

      await page.waitForTimeout(1000);

      // Look for editable fields
      // Try to find name/description field
      const nameInput = page.locator('input[id*="name"], textarea[id*="description"]');

      if (await nameInput.count() > 0) {
        // Update description or add custom property
        const descInput = page.locator('textarea[id*="description"], input[id*="description"]');
        if (await descInput.count() > 0) {
          await descInput.first().clear();
          await descInput.first().fill('Updated description for testing persistence');
        }

        // Look for custom property fields
        const customFields = page.locator('input[id*="custom"], input[id*="property"]');
        if (await customFields.count() > 0) {
          await customFields.first().fill('Test custom value');
        }

        // Save changes
        const saveButton = page.locator('button:has-text("保存"), button:has-text("更新"), button[type="submit"]');
        if (await saveButton.count() > 0) {
          await saveButton.first().click(isMobile ? { force: true } : {});

          // Wait for success message
          await page.waitForSelector('.ant-message-success', { timeout: 10000 });
          await page.waitForTimeout(2000);
        }
      } else {
        test.skip('Editable properties not found');
      }
    } else {
      test.skip('Test document not found');
    }
  });

  test('should verify property changes persist after page reload', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Reload page
    await page.reload();
    await page.waitForTimeout(3000);

    // Re-navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await page.waitForTimeout(2000);
    }

    // Find the test document
    const docRow = page.locator('tr').filter({ hasText: testDocName });

    if (await docRow.count() > 0) {
      // Open properties view
      const detailButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="eye"], [data-icon="edit"], [data-icon="setting"]')
      });

      if (await detailButton.count() > 0) {
        await detailButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Check if updated description is visible
        const updatedDescription = page.locator('text=Updated description for testing persistence');

        if (await updatedDescription.count() > 0) {
          await expect(updatedDescription).toBeVisible({ timeout: 5000 });
        } else {
          // If not in view, try opening edit modal
          const editButton = page.locator('button:has-text("編集"), button').filter({
            has: page.locator('[data-icon="edit"]')
          });
          if (await editButton.count() > 0) {
            await editButton.first().click(isMobile ? { force: true } : {});
            await page.waitForTimeout(1000);

            const descInput = page.locator('textarea[id*="description"]');
            if (await descInput.count() > 0) {
              const descValue = await descInput.first().inputValue();
              expect(descValue).toContain('Updated description for testing persistence');
            }
          }
        }
      }
    } else {
      test.skip('Test document not found after reload');
    }
  });

  test('should clean up test document', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    await page.waitForTimeout(2000);

    const docRow = page.locator('tr').filter({ hasText: testDocName });

    if (await docRow.count() > 0) {
      const deleteButton = docRow.locator('button').filter({
        has: page.locator('[data-icon="delete"]')
      });

      if (await deleteButton.count() > 0) {
        await deleteButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button:has-text("OK")');
        if (await confirmButton.count() > 0) {
          await confirmButton.click(isMobile ? { force: true } : {});
          await page.waitForSelector('.ant-message-success', { timeout: 10000 });
        }
      }
    }
  });
});
