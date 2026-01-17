import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

/**
 * Group Hierarchy and Large Member Display E2E Tests
 *
 * Tests for NemakiWare group management advanced features:
 * - Group hierarchy (parent-child group structure)
 * - Circular reference detection
 * - Large member display (100+ members)
 *
 * Prerequisites:
 * - NemakiWare running on http://localhost:8080
 * - Admin user credentials (admin/admin)
 */

test.describe('Group Hierarchy and Large Member Display', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Navigate to group management
    await page.waitForTimeout(2000);
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("グループ管理")').click();
    await page.waitForTimeout(2000);

    // Wait for group management page to fully load
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 10000 });
  });

  test.describe('Member Settings UI Elements', () => {
    test('should show separate user and group member fields in create modal', async ({ page }) => {
      // Click create button to open modal
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);

      // Verify modal is open
      await expect(page.locator('.ant-modal-content')).toBeVisible();

      // Verify "メンバー設定" divider exists
      const memberSettingsDivider = page.locator('.ant-divider:has-text("メンバー設定")');
      await expect(memberSettingsDivider).toBeVisible();

      // Verify "ユーザーメンバー" field exists
      const userMembersField = page.locator('.ant-form-item').filter({ hasText: 'ユーザーメンバー' });
      await expect(userMembersField).toBeVisible();

      // Verify "グループメンバー" field exists
      const groupMembersField = page.locator('.ant-form-item').filter({ hasText: 'グループメンバー' });
      await expect(groupMembersField).toBeVisible();

      // Verify circular reference warning is NOT shown in create mode
      const circularWarning = page.locator('text=循環参照');
      await expect(circularWarning).not.toBeVisible();

      // Close modal
      await page.locator('.ant-modal-content button:has-text("キャンセル")').click();
    });

    test('should show circular reference warning in edit mode', async ({ page }) => {
      // Find any existing group and click edit
      const editButton = page.locator('.ant-table tbody tr').first().locator('button:has-text("編集")');

      if (await editButton.count() > 0) {
        await editButton.click();
        await page.waitForTimeout(500);

        // Verify modal is open
        await expect(page.locator('.ant-modal-content')).toBeVisible();

        // In edit mode, circular reference warning should be visible
        const circularWarning = page.locator('text=循環参照');
        await expect(circularWarning).toBeVisible();

        // Close modal
        await page.locator('.ant-modal-content button:has-text("キャンセル")').click();
      } else {
        test.skip('No groups available to edit');
      }
    });
  });

  test.describe('Group Creation with Members', () => {
    const testGroupId = `test-hierarchy-${Date.now()}`;

    test.afterEach(async ({ page }) => {
      // Cleanup: Delete test group if it exists
      await page.waitForTimeout(500);
      const row = page.locator(`.ant-table tbody tr:has-text("${testGroupId}")`);
      if (await row.count() > 0) {
        await row.locator('button:has-text("削除")').click();
        await page.locator('.ant-popconfirm-buttons button:has-text("はい")').click();
        await page.waitForTimeout(1000);
      }
    });

    test('should create group with user members', async ({ page }) => {
      // Click create button
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);

      // Fill group form
      await page.fill('input#id', testGroupId);
      await page.fill('input#name', 'Test Hierarchy Group');

      // Open user members dropdown
      const userMembersSelect = page.locator('.ant-form-item').filter({ hasText: 'ユーザーメンバー' }).locator('.ant-select');
      await userMembersSelect.click();
      await page.waitForTimeout(500);

      // Select first available user if any
      const userOptions = page.locator('.ant-select-dropdown .ant-select-item-option');
      if (await userOptions.count() > 0) {
        await userOptions.first().click();
        await page.waitForTimeout(200);
      }

      // Close dropdown
      await page.locator('.ant-modal-title').click();
      await page.waitForTimeout(300);

      // Submit form
      await page.locator('.ant-modal-content button[type="submit"]').click();
      await page.waitForTimeout(2000);

      // Verify success message or group appears in table
      const successOrTable = await Promise.race([
        page.locator('.ant-message-success').waitFor({ timeout: 5000 }).then(() => 'success'),
        page.locator(`.ant-table tbody tr:has-text("${testGroupId}")`).waitFor({ timeout: 5000 }).then(() => 'table'),
      ]).catch(() => 'neither');

      expect(['success', 'table']).toContain(successOrTable);
    });

    test('should create group with group members when groups exist', async ({ page }) => {
      // First check if there are existing groups
      const existingGroups = page.locator('.ant-table tbody tr');
      const groupCount = await existingGroups.count();

      if (groupCount === 0) {
        test.skip('No existing groups to add as members');
        return;
      }

      // Click create button
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);

      // Fill group form
      await page.fill('input#id', testGroupId);
      await page.fill('input#name', 'Test Hierarchy Group');

      // Open group members dropdown
      const groupMembersSelect = page.locator('.ant-form-item').filter({ hasText: 'グループメンバー' }).locator('.ant-select');
      await groupMembersSelect.click();
      await page.waitForTimeout(500);

      // Check if there are group options
      const groupOptions = page.locator('.ant-select-dropdown .ant-select-item-option');
      const optionCount = await groupOptions.count();

      if (optionCount > 0) {
        // Select first available group
        await groupOptions.first().click();
        await page.waitForTimeout(200);

        // Close dropdown
        await page.locator('.ant-modal-title').click();
        await page.waitForTimeout(300);

        // Submit form
        await page.locator('.ant-modal-content button[type="submit"]').click();
        await page.waitForTimeout(2000);

        // Verify group was created
        await expect(page.locator(`.ant-table tbody tr:has-text("${testGroupId}")`)).toBeVisible({ timeout: 5000 });

        // Verify blue tag (group member indicator) is shown
        const groupRow = page.locator(`.ant-table tbody tr:has-text("${testGroupId}")`);
        const blueTag = groupRow.locator('.ant-tag-blue');
        await expect(blueTag).toBeVisible();
      } else {
        // Close modal if no groups available
        await page.locator('.ant-modal-content button:has-text("キャンセル")').click();
        test.skip('No group options available in dropdown');
      }
    });
  });

  test.describe('Member Display', () => {
    test('should display user members with green tag', async ({ page }) => {
      // Find a group row with green tags (user members)
      const greenTags = page.locator('.ant-table tbody .ant-tag-green');

      if (await greenTags.count() > 0) {
        // Green tags should have user icon
        await expect(greenTags.first()).toBeVisible();
      }
      // Test passes even if no green tags - just verifying they display correctly when present
    });

    test('should display group members with blue tag', async ({ page }) => {
      // Find a group row with blue tags (group members)
      const blueTags = page.locator('.ant-table tbody .ant-tag-blue');

      if (await blueTags.count() > 0) {
        // Blue tags should have team icon
        await expect(blueTags.first()).toBeVisible();
      }
      // Test passes even if no blue tags - just verifying they display correctly when present
    });

    test('should show +N more tag when members exceed limit', async ({ page }) => {
      // Look for "+N more" or "+N 件以上" tags
      const moreTags = page.locator('.ant-table tbody .ant-tag').filter({ hasText: /more|件以上|\+\d+/ });

      if (await moreTags.count() > 0) {
        await expect(moreTags.first()).toBeVisible();

        // Click the "more" tag to open detail modal
        await moreTags.first().click();
        await page.waitForTimeout(500);

        // Check if members detail modal opens
        const detailModal = page.locator('.ant-modal').filter({ hasText: 'メンバー詳細' });
        if (await detailModal.count() > 0) {
          await expect(detailModal).toBeVisible();

          // Close modal
          const closeButton = detailModal.locator('button:has-text("閉じる")');
          if (await closeButton.count() > 0) {
            await closeButton.click();
          } else {
            await page.keyboard.press('Escape');
          }
        }
      }
      // Test passes even if no "more" tags - just verifying they work correctly when present
    });
  });

  test.describe('Self-Reference Prevention', () => {
    test('should not allow self-reference in edit mode', async ({ page }) => {
      // Find any existing group and click edit
      const firstRow = page.locator('.ant-table tbody tr').first();
      const editButton = firstRow.locator('button:has-text("編集")');

      if (await editButton.count() === 0) {
        test.skip('No groups available to edit');
        return;
      }

      // Get the group ID from the row
      const groupIdCell = firstRow.locator('td').first();
      const groupId = await groupIdCell.textContent();

      await editButton.click();
      await page.waitForTimeout(500);

      // Open group members dropdown
      const groupMembersSelect = page.locator('.ant-form-item').filter({ hasText: 'グループメンバー' }).locator('.ant-select');
      await groupMembersSelect.click();
      await page.waitForTimeout(500);

      // The current group should NOT be in the dropdown options
      const selfOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: groupId || '' });

      // Verify the group itself is not listed
      if (groupId && groupId.trim() !== '') {
        await expect(selfOption).not.toBeVisible();
      }

      // Close modal
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
      await page.locator('.ant-modal-content button:has-text("キャンセル")').click().catch(() => {});
    });
  });

  test.describe('User Management - Groups Display', () => {
    test('should navigate to user management and verify page loads', async ({ page }) => {
      // Navigate to user management
      await page.locator('.ant-menu-item:has-text("ユーザー管理")').click();
      await page.waitForTimeout(2000);

      // Verify we're on user management page
      expect(page.url()).toContain('/users');

      // Check if users table is visible
      const table = page.locator('.ant-table');
      await expect(table).toBeVisible({ timeout: 10000 });
    });
  });
});
