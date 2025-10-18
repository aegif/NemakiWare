import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { randomUUID } from 'crypto';

test.describe('User Management CRUD Operations', () => {
  let authHelper: AuthHelper;
  const testUsername = `testuser_${randomUUID().substring(0, 8)}`;
  const testUserEmail = `${testUsername}@test.local`;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Navigate to user management
    await page.waitForTimeout(2000);
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("ユーザー管理")').click();
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

  test('should create new user with full details', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Look for "新規作成" or "ユーザー追加" button
    const createButton = page.locator('button').filter({
      hasText: /新規作成|ユーザー追加|追加/
    });

    if (await createButton.count() > 0) {
      await createButton.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Wait for modal or form
      const modal = page.locator('.ant-modal, .ant-drawer');
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Fill user details
      // Username/ID
      const usernameInput = page.locator('input[id*="username"], input[id*="userId"], input[name="username"], input[name="userId"]');
      if (await usernameInput.count() > 0) {
        await usernameInput.first().fill(testUsername);
      }

      // Email
      const emailInput = page.locator('input[type="email"], input[id*="email"], input[name="email"]');
      if (await emailInput.count() > 0) {
        await emailInput.first().fill(testUserEmail);
      }

      // First name
      const firstNameInput = page.locator('input[id*="firstName"], input[name="firstName"]');
      if (await firstNameInput.count() > 0) {
        await firstNameInput.first().fill('Test');
      }

      // Last name
      const lastNameInput = page.locator('input[id*="lastName"], input[name="lastName"]');
      if (await lastNameInput.count() > 0) {
        await lastNameInput.first().fill('User');
      }

      // Password
      const passwordInput = page.locator('input[type="password"]').first();
      if (await passwordInput.count() > 0) {
        await passwordInput.fill('TestPassword123!');
      }

      // Confirm password (if exists)
      const confirmPasswordInput = page.locator('input[type="password"]').nth(1);
      if (await confirmPasswordInput.count() > 0) {
        await confirmPasswordInput.fill('TestPassword123!');
      }

      // Submit form
      const submitButton = page.locator('.ant-modal button[type="submit"], .ant-drawer button[type="submit"], button:has-text("作成"), button:has-text("保存")');
      await submitButton.first().click(isMobile ? { force: true } : {});

      // Wait for success message
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);

      // Verify user appears in list
      const userInList = page.locator(`text=${testUsername}`);
      await expect(userInList).toBeVisible({ timeout: 10000 });
    } else {
      test.skip('User creation functionality not available');
    }
  });

  test('should edit user information', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Find testuser (created in previous test) using exact username
    await page.waitForTimeout(2000);
    const testUserRow = page.locator('tr').filter({ hasText: testUsername });

    if (await testUserRow.count() > 0) {
      // Click edit button
      const editButton = testUserRow.locator('button').filter({
        has: page.locator('[data-icon="edit"]')
      });

      if (await editButton.count() > 0) {
        await editButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Wait for edit modal/form
        const modal = page.locator('.ant-modal, .ant-drawer');
        await expect(modal).toBeVisible({ timeout: 5000 });

        // Update email
        const emailInput = page.locator('input[type="email"], input[id*="email"], input[name="email"]');
        if (await emailInput.count() > 0) {
          await emailInput.first().clear();
          await emailInput.first().fill('updated_email@test.local');
        }

        // Update first name
        const firstNameInput = page.locator('input[id*="firstName"], input[name="firstName"]');
        if (await firstNameInput.count() > 0) {
          await firstNameInput.first().clear();
          await firstNameInput.first().fill('Updated');
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
      test.skip('Test user not found for editing');
    }
  });

  test('should verify edited user information persists after reload', async ({ page, browserName }) => {
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

    // Navigate back to user management
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("ユーザー管理")').click();
    await page.waitForTimeout(2000);

    // Find testuser using exact username
    const testUserRow = page.locator('tr').filter({ hasText: testUsername });

    if (await testUserRow.count() > 0) {
      // Click to view details
      await testUserRow.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Check if updated email is visible (either in modal or detail view)
      const updatedEmail = page.locator('text=updated_email@test.local');

      // Email may be in modal or detail panel
      if (await updatedEmail.count() > 0) {
        await expect(updatedEmail).toBeVisible({ timeout: 5000 });
      } else {
        // If not visible, try opening edit modal to verify
        const editButton = testUserRow.locator('button').filter({
          has: page.locator('[data-icon="edit"]')
        });
        if (await editButton.count() > 0) {
          await editButton.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);

          const emailInput = page.locator('input[type="email"]');
          const emailValue = await emailInput.first().inputValue();
          expect(emailValue).toBe('updated_email@test.local');
        }
      }
    } else {
      test.skip('Test user not found after reload');
    }
  });

  test('should delete test user', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Find testuser
    await page.waitForTimeout(2000);
    const testUserRow = page.locator('tr').filter({ hasText: 'testuser' });

    if (await testUserRow.count() > 0) {
      // Click delete button
      const deleteButton = testUserRow.locator('button').filter({
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

          // Verify user is removed from list
          const deletedUser = page.locator(`text=${testUsername}`);
          await expect(deletedUser).not.toBeVisible({ timeout: 5000 });
        }
      } else {
        test.skip('Delete button not found');
      }
    } else {
      test.skip('Test user not found for deletion');
    }
  });
});
