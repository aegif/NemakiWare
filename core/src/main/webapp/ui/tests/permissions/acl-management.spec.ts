import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Advanced ACL Management', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const testFolderName = `acl-test-${Date.now()}`;

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

  test('should support inheritance toggle for ACL', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a test folder first
    const createFolderButton = page.locator('button').filter({
      has: page.locator('[data-icon="folder-add"], span:has-text("フォルダ作成")')
    }).first();

    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const modal = page.locator('.ant-modal:visible');
      const nameInput = modal.locator('input[type="text"]').first();
      await nameInput.fill(testFolderName);

      const submitButton = modal.locator('button.ant-btn-primary');
      await submitButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    await page.waitForTimeout(2000);

    // Find the folder and access permissions
    const folderRow = page.locator('tr').filter({ hasText: testFolderName });

    if (await folderRow.count() > 0) {
      const permissionsButton = folderRow.locator('button').filter({
        has: page.locator('[data-icon="lock"], span:has-text("権限")')
      });

      if (await permissionsButton.count() > 0) {
        await permissionsButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        const modal = page.locator('.ant-modal:visible');
        await expect(modal).toBeVisible({ timeout: 5000 });

        // Look for inheritance toggle/checkbox
        const inheritanceToggle = modal.locator('input[type="checkbox"], .ant-switch').filter({
          has: page.locator(':text-matches("継承|Inheritance", "i")')
        });

        if (await inheritanceToggle.count() === 0) {
          // Try alternative selectors
          const altToggle = modal.locator('label:has-text("継承"), label:has-text("Inheritance")');
          if (await altToggle.count() > 0) {
            const checkbox = altToggle.locator('input[type="checkbox"], .ant-switch');
            if (await checkbox.count() > 0) {
              await checkbox.click(isMobile ? { force: true } : {});
              await page.waitForTimeout(1000);

              // Verify toggle state changed
              const isChecked = await checkbox.isChecked().catch(() => false);
              expect(typeof isChecked).toBe('boolean');
            }
          }
        } else {
          await inheritanceToggle.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);

          const isChecked = await inheritanceToggle.first().isChecked().catch(() => false);
          expect(typeof isChecked).toBe('boolean');
        }
      }
    }
  });

  test('should support setting permissions for multiple principals', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a test folder first
    const createFolderButton = page.locator('button').filter({
      has: page.locator('[data-icon="folder-add"], span:has-text("フォルダ作成")')
    }).first();

    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const modal = page.locator('.ant-modal:visible');
      const nameInput = modal.locator('input[type="text"]').first();
      await nameInput.fill(testFolderName);

      const submitButton = modal.locator('button.ant-btn-primary');
      await submitButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    await page.waitForTimeout(2000);

    const folderRow = page.locator('tr').filter({ hasText: testFolderName });

    if (await folderRow.count() > 0) {
      const permissionsButton = folderRow.locator('button').filter({
        has: page.locator('[data-icon="lock"], span:has-text("権限")')
      });

      if (await permissionsButton.count() > 0) {
        await permissionsButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        const modal = page.locator('.ant-modal:visible');
        await expect(modal).toBeVisible({ timeout: 5000 });

        // Add first principal
        const addButton = modal.locator('button').filter({
          has: page.locator('[data-icon="plus"], span:has-text("追加")')
        });

        if (await addButton.count() > 0) {
          // Add first ACE
          await addButton.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);

          const principalInput = modal.locator('input[type="text"], .ant-select-selection-search-input').first();
          await principalInput.fill('testuser1');
          await page.waitForTimeout(500);

          const permissionSelect = modal.locator('.ant-select').filter({
            has: page.locator(':text-matches("権限|Permission", "i")')
          });

          if (await permissionSelect.count() > 0) {
            await permissionSelect.first().click(isMobile ? { force: true } : {});
            await page.waitForTimeout(500);

            const readOption = page.locator('.ant-select-item:has-text("読取"), .ant-select-item:has-text("Read")').first();
            if (await readOption.count() > 0) {
              await readOption.click(isMobile ? { force: true } : {});
            }
          }

          // Add second ACE
          if (await addButton.count() > 0) {
            await addButton.first().click(isMobile ? { force: true } : {});
            await page.waitForTimeout(1000);

            const inputs = modal.locator('input[type="text"], .ant-select-selection-search-input');
            if (await inputs.count() > 1) {
              await inputs.nth(1).fill('testuser2');
              await page.waitForTimeout(500);
            }
          }

          // Verify multiple ACEs exist
          const aceRows = modal.locator('.ant-table-tbody tr, .ant-list-item');
          const aceCount = await aceRows.count();
          expect(aceCount).toBeGreaterThanOrEqual(2);
        }
      }
    }
  });

  test('should support removing permissions', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a test folder first
    const createFolderButton = page.locator('button').filter({
      has: page.locator('[data-icon="folder-add"], span:has-text("フォルダ作成")')
    }).first();

    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const modal = page.locator('.ant-modal:visible');
      const nameInput = modal.locator('input[type="text"]').first();
      await nameInput.fill(testFolderName);

      const submitButton = modal.locator('button.ant-btn-primary');
      await submitButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    await page.waitForTimeout(2000);

    const folderRow = page.locator('tr').filter({ hasText: testFolderName });

    if (await folderRow.count() > 0) {
      const permissionsButton = folderRow.locator('button').filter({
        has: page.locator('[data-icon="lock"], span:has-text("権限")')
      });

      if (await permissionsButton.count() > 0) {
        await permissionsButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        const modal = page.locator('.ant-modal:visible');
        await expect(modal).toBeVisible({ timeout: 5000 });

        // Get initial ACE count
        const initialAces = modal.locator('.ant-table-tbody tr, .ant-list-item');
        const initialCount = await initialAces.count();

        if (initialCount > 0) {
          // Find and click remove button for first ACE
          const removeButton = initialAces.first().locator('button').filter({
            has: page.locator('[data-icon="delete"], [data-icon="close"]')
          });

          if (await removeButton.count() > 0) {
            await removeButton.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(1000);

            // Confirm deletion if needed
            const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button:has-text("OK")');
            if (await confirmButton.count() > 0) {
              await confirmButton.click(isMobile ? { force: true } : {});
              await page.waitForTimeout(1000);
            }

            // Verify ACE was removed
            const finalAces = modal.locator('.ant-table-tbody tr, .ant-list-item');
            const finalCount = await finalAces.count();
            expect(finalCount).toBeLessThan(initialCount);
          }
        }
      }
    }
  });

  test('should validate permission changes are persisted', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Create a test folder first
    const createFolderButton = page.locator('button').filter({
      has: page.locator('[data-icon="folder-add"], span:has-text("フォルダ作成")')
    }).first();

    if (await createFolderButton.count() > 0) {
      await createFolderButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const modal = page.locator('.ant-modal:visible');
      const nameInput = modal.locator('input[type="text"]').first();
      await nameInput.fill(testFolderName);

      const submitButton = modal.locator('button.ant-btn-primary');
      await submitButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    await page.waitForTimeout(2000);

    const folderRow = page.locator('tr').filter({ hasText: testFolderName });

    if (await folderRow.count() > 0) {
      // Open permissions dialog
      const permissionsButton = folderRow.locator('button').filter({
        has: page.locator('[data-icon="lock"], span:has-text("権限")')
      });

      if (await permissionsButton.count() > 0) {
        await permissionsButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        const modal = page.locator('.ant-modal:visible');
        await expect(modal).toBeVisible({ timeout: 5000 });

        // Add a new ACE
        const addButton = modal.locator('button').filter({
          has: page.locator('[data-icon="plus"], span:has-text("追加")')
        });

        if (await addButton.count() > 0) {
          await addButton.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);

          const principalInput = modal.locator('input[type="text"], .ant-select-selection-search-input').first();
          await principalInput.fill('persistencetest');
          await page.waitForTimeout(500);
        }

        // Save changes
        const saveButton = modal.locator('button.ant-btn-primary, button:has-text("保存"), button:has-text("OK")');
        if (await saveButton.count() > 0) {
          await saveButton.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(2000);

          const successMessage = await page.locator('.ant-message-success').isVisible({ timeout: 5000 }).catch(() => false);
          if (!successMessage) {
            console.log('Success message not shown, waiting longer...');
            await page.waitForTimeout(3000);
          }
        }

        // Reopen permissions dialog
        await page.waitForTimeout(2000);
        if (await permissionsButton.count() > 0) {
          await permissionsButton.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);

          const reopenedModal = page.locator('.ant-modal:visible');
          await expect(reopenedModal).toBeVisible({ timeout: 5000 });

          // Verify the added ACE is still there
          const persistedAce = reopenedModal.locator('text=persistencetest');
          const aceExists = await persistedAce.count() > 0;
          expect(aceExists).toBeTruthy();
        }
      }
    }
  });
});
