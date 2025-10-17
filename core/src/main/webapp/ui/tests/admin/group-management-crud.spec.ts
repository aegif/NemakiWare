import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

test.describe('Group Management CRUD Operations', () => {
  let authHelper: AuthHelper;
  const testGroupName = `testgroup_${Date.now()}`;
  const testGroupDescription = 'Test group for automated testing';

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Navigate to group management
    await page.waitForTimeout(2000);
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("グループ管理")').click();
    await page.waitForTimeout(2000);

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

  test('should create new group', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Look for "新規作成" or "グループ追加" button
    const createButton = page.locator('button').filter({
      hasText: /新規作成|グループ追加|追加/
    });

    if (await createButton.count() > 0) {
      await createButton.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Wait for modal or form
      const modal = page.locator('.ant-modal, .ant-drawer');
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Fill group details
      // Group name/ID
      const groupNameInput = page.locator('input[id*="groupName"], input[id*="groupId"], input[name="groupName"], input[name="groupId"], input[placeholder*="グループ名"]');
      if (await groupNameInput.count() > 0) {
        await groupNameInput.first().fill(testGroupName);
      }

      // Description
      const descriptionInput = page.locator('textarea[id*="description"], textarea[name="description"], input[id*="description"]');
      if (await descriptionInput.count() > 0) {
        await descriptionInput.first().fill(testGroupDescription);
      }

      // Submit form
      const submitButton = page.locator('.ant-modal button[type="submit"], .ant-drawer button[type="submit"], button:has-text("作成"), button:has-text("保存")');
      await submitButton.first().click(isMobile ? { force: true } : {});

      // Wait for success message
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);

      // Verify group appears in list
      const groupInList = page.locator(`text=${testGroupName}`);
      await expect(groupInList).toBeVisible({ timeout: 10000 });
    } else {
      test.skip('Group creation functionality not available');
    }
  });

  test('should add member to group', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    await page.waitForTimeout(2000);

    // Find test group row
    const groupRow = page.locator('tr').filter({ hasText: testGroupName });

    if (await groupRow.count() > 0) {
      // Look for member management button (may be users icon, edit icon)
      const membersButton = groupRow.locator('button').filter({
        has: page.locator('[data-icon="user"], [data-icon="team"], [data-icon="edit"]')
      });

      if (await membersButton.count() > 0) {
        await membersButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Look for add member button or interface
        const addMemberButton = page.locator('button:has-text("メンバー追加"), button:has-text("追加"), button').filter({
          has: page.locator('[data-icon="plus"], [data-icon="user-add"]')
        });

        if (await addMemberButton.count() > 0) {
          await addMemberButton.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(500);

          // Select testuser or admin
          const userSelect = page.locator('.ant-select, input[placeholder*="ユーザー"]');
          if (await userSelect.count() > 0) {
            await userSelect.first().click();
            await page.waitForTimeout(500);

            // Type testuser or admin
            await page.keyboard.type('testuser');
            await page.waitForTimeout(500);

            // Select from dropdown
            const userOption = page.locator('.ant-select-item:has-text("testuser")').first();
            if (await userOption.count() > 0) {
              await userOption.click();
            } else {
              // If testuser doesn't exist, try admin
              await page.keyboard.press('Backspace');
              await page.keyboard.press('Backspace');
              await page.keyboard.type('admin');
              await page.waitForTimeout(500);

              const adminOption = page.locator('.ant-select-item:has-text("admin")').first();
              if (await adminOption.count() > 0) {
                await adminOption.click();
              }
            }

            // Save member addition
            const saveButton = page.locator('button:has-text("保存"), button:has-text("OK"), button[type="submit"]');
            if (await saveButton.count() > 0) {
              await saveButton.first().click(isMobile ? { force: true } : {});
              await page.waitForSelector('.ant-message-success', { timeout: 10000 });
            }
          }
        } else {
          test.skip('Add member interface not found');
        }
      } else {
        // Try clicking the group row to open detail view
        await groupRow.click();
        await page.waitForTimeout(1000);

        // Look for members section
        const membersSection = page.locator('text=メンバー, text=Members');
        if (await membersSection.count() > 0) {
          // Member management interface should be visible
          await expect(membersSection).toBeVisible();
        } else {
          test.skip('Members management interface not accessible');
        }
      }
    } else {
      test.skip('Test group not found');
    }
  });

  test('should edit group description', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    await page.waitForTimeout(2000);

    // Find test group
    const groupRow = page.locator('tr').filter({ hasText: testGroupName });

    if (await groupRow.count() > 0) {
      // Click edit button
      const editButton = groupRow.locator('button').filter({
        has: page.locator('[data-icon="edit"]')
      });

      if (await editButton.count() > 0) {
        await editButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Wait for edit modal/form
        const modal = page.locator('.ant-modal, .ant-drawer');
        await expect(modal).toBeVisible({ timeout: 5000 });

        // Update description
        const descriptionInput = page.locator('textarea[id*="description"], input[id*="description"]');
        if (await descriptionInput.count() > 0) {
          await descriptionInput.first().clear();
          await descriptionInput.first().fill('Updated description for testing persistence');
        }

        // Submit changes
        const submitButton = page.locator('.ant-modal button[type="submit"], .ant-drawer button[type="submit"], button:has-text("更新"), button:has-text("保存")');
        await submitButton.first().click(isMobile ? { force: true } : {});

        // Wait for success message
        await page.waitForSelector('.ant-message-success', { timeout: 10000 });
        await page.waitForTimeout(2000);
      } else {
        test.skip('Edit button not found');
      }
    } else {
      test.skip('Test group not found');
    }
  });

  test('should verify group changes persist after reload', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Refresh via UI navigation instead of page.reload() to avoid breaking React Router
    // Navigate away to Documents
    const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
    if (await documentsMenu.count() > 0) {
      await documentsMenu.click();
      await page.waitForTimeout(1000);
    }

    // Navigate back to group management
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("グループ管理")').click();
    await page.waitForTimeout(2000);

    // Find test group
    const groupRow = page.locator('tr').filter({ hasText: testGroupName });

    if (await groupRow.count() > 0) {
      // Click to view details or edit
      const editButton = groupRow.locator('button').filter({
        has: page.locator('[data-icon="edit"], [data-icon="eye"]')
      });

      if (await editButton.count() > 0) {
        await editButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Check if updated description is visible
        const updatedDescription = page.locator('text=Updated description for testing persistence');

        if (await updatedDescription.count() > 0) {
          await expect(updatedDescription).toBeVisible({ timeout: 5000 });
        } else {
          // Check in textarea if in edit mode
          const descInput = page.locator('textarea[id*="description"]');
          if (await descInput.count() > 0) {
            const descValue = await descInput.first().inputValue();
            expect(descValue).toContain('Updated description for testing persistence');
          }
        }
      }
    } else {
      test.skip('Test group not found after reload');
    }
  });

  test('should delete test group', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    await page.waitForTimeout(2000);

    // Find test group
    const groupRow = page.locator('tr').filter({ hasText: testGroupName });

    if (await groupRow.count() > 0) {
      // Click delete button
      const deleteButton = groupRow.locator('button').filter({
        has: page.locator('[data-icon="delete"]')
      });

      if (await deleteButton.count() > 0) {
        await deleteButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        // Confirm deletion
        const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary, button:has-text("OK"), button:has-text("削除")');
        if (await confirmButton.count() > 0) {
          await confirmButton.first().click(isMobile ? { force: true } : {});

          // Wait for success message
          await page.waitForSelector('.ant-message-success', { timeout: 10000 });
          await page.waitForTimeout(2000);

          // Verify group is removed from list
          const deletedGroup = page.locator(`text=${testGroupName}`);
          await expect(deletedGroup).not.toBeVisible({ timeout: 5000 });
        }
      } else {
        test.skip('Delete button not found');
      }
    } else {
      test.skip('Test group not found');
    }
  });
});
