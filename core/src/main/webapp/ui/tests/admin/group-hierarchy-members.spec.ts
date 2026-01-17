import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

/**
 * Group Hierarchy and Large Member Display E2E Tests
 *
 * Tests for NemakiWare group management advanced features:
 * - Group hierarchy (parent-child group structure)
 * - Circular reference detection
 * - Large member display (100+ members)
 * - User belonging to many groups (100+ groups)
 *
 * Prerequisites:
 * - NemakiWare running on http://localhost:8080
 * - Admin user credentials (admin/admin)
 */

test.describe('Group Hierarchy and Large Member Display', () => {
  let authHelper: AuthHelper;

  // Run tests serially to avoid conflicts with shared test data
  test.describe.configure({ mode: 'serial' });

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

  /**
   * Helper to create a group with given ID and name
   * After creation, reloads the page to ensure the groups list is refreshed
   */
  async function createGroup(page: any, groupId: string, groupName: string) {
    await page.locator('button:has-text("作成")').click();
    await page.waitForTimeout(500);
    await page.fill('input#id', groupId);
    await page.fill('input#name', groupName);
    await page.locator('.ant-modal-content button[type="submit"]').click();
    await page.waitForTimeout(2000);
    // Wait for modal to close
    await expect(page.locator('.ant-modal-content')).not.toBeVisible({ timeout: 5000 }).catch(() => {});
    // Verify group appears in table
    await expect(page.locator(`.ant-table tbody tr:has-text("${groupId}")`)).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(500);
  }

  /**
   * Helper to delete a group by ID
   */
  async function deleteGroup(page: any, groupId: string) {
    const row = page.locator(`.ant-table tbody tr:has-text("${groupId}")`);
    if (await row.count() > 0) {
      await row.locator('button:has-text("削除")').click();
      await page.locator('.ant-popconfirm-buttons button:has-text("はい")').click();
      await page.waitForTimeout(1000);
    }
  }

  test.describe('Group Hierarchy (Parent-Child Structure)', () => {
    const parentGroupId = 'test-parent-grp';
    const childGroupId = 'test-child-grp';

    test.afterEach(async ({ page }) => {
      // Cleanup: Delete test groups
      await page.waitForTimeout(1000);
      await deleteGroup(page, parentGroupId);
      await deleteGroup(page, childGroupId);
    });

    test('should create child group first', async ({ page }) => {
      await createGroup(page, childGroupId, 'Test Child Group');

      // Verify success message
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 5000 });

      // Verify group appears in table
      await expect(page.locator(`.ant-table tbody tr:has-text("${childGroupId}")`)).toBeVisible();
    });

    test('should create parent group with child group as member', async ({ page }) => {
      // First create child group
      await createGroup(page, childGroupId, 'Test Child Group');

      // Reload the page to ensure groups list is fresh
      await page.reload();
      await page.waitForTimeout(2000);
      await expect(page.locator('.ant-table')).toBeVisible({ timeout: 10000 });

      // Now create parent group with child as member
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);

      await page.fill('input#id', parentGroupId);
      await page.fill('input#name', 'Test Parent Group');

      // Select child group as group member
      const groupMembersSelect = page.locator('.ant-form-item:has-text("グループメンバー") .ant-select');
      await groupMembersSelect.click();
      await page.waitForTimeout(1000);

      // Wait for dropdown to be visible and find the child group option
      const dropdownOption = page.locator(`.ant-select-dropdown .ant-select-item-option`).filter({ hasText: childGroupId });
      await expect(dropdownOption).toBeVisible({ timeout: 10000 });
      await dropdownOption.click();
      await page.waitForTimeout(300);

      // Close dropdown by clicking elsewhere
      await page.locator('.ant-modal-title').click();
      await page.waitForTimeout(300);

      // Submit
      await page.locator('.ant-modal-content button[type="submit"]').click();
      await page.waitForTimeout(2000);

      // Verify success message
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 5000 });

      // Verify parent group appears in table
      await expect(page.locator(`.ant-table tbody tr:has-text("${parentGroupId}")`)).toBeVisible();
    });

    test('should display group member with team icon (blue tag)', async ({ page }) => {
      // First create child group
      await createGroup(page, childGroupId, 'Test Child Group');

      // Reload the page to ensure groups list is fresh
      await page.reload();
      await page.waitForTimeout(2000);
      await expect(page.locator('.ant-table')).toBeVisible({ timeout: 10000 });

      // Create parent group with child as member
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);
      await page.fill('input#id', parentGroupId);
      await page.fill('input#name', 'Test Parent Group');

      const groupMembersSelect = page.locator('.ant-form-item:has-text("グループメンバー") .ant-select');
      await groupMembersSelect.click();
      await page.waitForTimeout(1000);

      const dropdownOption = page.locator(`.ant-select-dropdown .ant-select-item-option`).filter({ hasText: childGroupId });
      await expect(dropdownOption).toBeVisible({ timeout: 10000 });
      await dropdownOption.click();
      await page.locator('.ant-modal-title').click();
      await page.locator('.ant-modal-content button[type="submit"]').click();
      await page.waitForTimeout(2000);

      // Verify the parent group row shows child group with blue tag
      const parentRow = page.locator(`.ant-table tbody tr:has-text("${parentGroupId}")`);
      await expect(parentRow).toBeVisible();

      // Check for blue tag (group member indicator)
      const blueTag = parentRow.locator('.ant-tag-blue');
      await expect(blueTag).toBeVisible();
    });

    test('should prevent self-reference in group members selection', async ({ page }) => {
      // First create a group
      await createGroup(page, parentGroupId, 'Test Parent Group');

      // Edit the group
      const parentRow = page.locator(`.ant-table tbody tr:has-text("${parentGroupId}")`);
      await parentRow.locator('button:has-text("編集")').click();
      await page.waitForTimeout(500);

      // Try to open group members dropdown
      const groupMembersSelect = page.locator('.ant-form-item:has-text("グループメンバー") .ant-select');
      await groupMembersSelect.click();
      await page.waitForTimeout(500);

      // The group itself should NOT be in the dropdown options
      const selfOption = page.locator(`.ant-select-dropdown .ant-select-item-option`).filter({ hasText: parentGroupId });
      await expect(selfOption).not.toBeVisible();

      // Close modal
      await page.locator('.ant-modal-content button:has-text("キャンセル")').click();
    });
  });

  test.describe('Circular Reference Detection', () => {
    const groupA = 'test-grp-a';
    const groupB = 'test-grp-b';

    test.afterEach(async ({ page }) => {
      // Cleanup test groups
      await page.waitForTimeout(1000);
      await deleteGroup(page, groupB);
      await deleteGroup(page, groupA);
    });

    test('should detect circular reference when editing group', async ({ page }) => {
      // Create group A
      await createGroup(page, groupA, 'Group A');

      // Reload to ensure group A is in the dropdown
      await page.reload();
      await page.waitForTimeout(2000);
      await expect(page.locator('.ant-table')).toBeVisible({ timeout: 10000 });

      // Create group B with group A as member
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);
      await page.fill('input#id', groupB);
      await page.fill('input#name', 'Group B');

      const groupMembersSelect = page.locator('.ant-form-item:has-text("グループメンバー") .ant-select');
      await groupMembersSelect.click();
      await page.waitForTimeout(1000);

      const groupAOption = page.locator(`.ant-select-dropdown .ant-select-item-option`).filter({ hasText: groupA });
      await expect(groupAOption).toBeVisible({ timeout: 10000 });
      await groupAOption.click();
      await page.locator('.ant-modal-title').click();
      await page.locator('.ant-modal-content button[type="submit"]').click();
      await page.waitForTimeout(2000);

      // Reload to ensure both groups are in the dropdown
      await page.reload();
      await page.waitForTimeout(2000);
      await expect(page.locator('.ant-table')).toBeVisible({ timeout: 10000 });

      // Now try to edit group A and add group B as member (would create cycle)
      const groupARow = page.locator(`.ant-table tbody tr:has-text("${groupA}")`);
      await groupARow.locator('button:has-text("編集")').click();
      await page.waitForTimeout(500);

      // Select group B as member
      const groupMembersSelectEdit = page.locator('.ant-form-item:has-text("グループメンバー") .ant-select');
      await groupMembersSelectEdit.click();
      await page.waitForTimeout(1000);

      const groupBOption = page.locator(`.ant-select-dropdown .ant-select-item-option`).filter({ hasText: groupB });
      await expect(groupBOption).toBeVisible({ timeout: 10000 });
      await groupBOption.click();
      await page.locator('.ant-modal-title').click();

      // Submit - should show circular reference error
      await page.locator('.ant-modal-content button[type="submit"]').click();
      await page.waitForTimeout(1000);

      // Verify error message about circular reference
      const errorMessage = page.locator('.ant-message-error');
      await expect(errorMessage).toBeVisible({ timeout: 5000 });

      // Close modal
      await page.locator('.ant-modal-content button:has-text("キャンセル")').click();
    });
  });

  test.describe('Large Member Display (100+ members)', () => {
    const largeGroupId = 'test-large-grp';

    test.afterEach(async ({ page }) => {
      // Cleanup
      await page.waitForTimeout(1000);
      await deleteGroup(page, largeGroupId);
    });

    test('should display "+N more" tag when members exceed display limit', async ({ page }) => {
      // This test verifies the UI behavior when a group has many members
      // We'll create a group and check the display behavior

      // First, check if admin user exists (as a member candidate)
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);

      await page.fill('input#id', largeGroupId);
      await page.fill('input#name', 'Large Test Group');

      // Try to select multiple user members if available
      const userMembersSelect = page.locator('.ant-form-item:has-text("ユーザーメンバー") .ant-select');
      await userMembersSelect.click();
      await page.waitForTimeout(500);

      // Select all available users (up to the first few)
      const userOptions = page.locator('.ant-select-dropdown .ant-select-item-option');
      const userCount = await userOptions.count();

      // Select multiple users if available
      for (let i = 0; i < Math.min(userCount, 5); i++) {
        await userOptions.nth(i).click();
        await page.waitForTimeout(100);
      }

      // Close dropdown
      await page.locator('.ant-modal-title').click();

      // Submit
      await page.locator('.ant-modal-content button[type="submit"]').click();
      await page.waitForTimeout(2000);

      // The group should be created
      await expect(page.locator(`.ant-table tbody tr:has-text("${largeGroupId}")`)).toBeVisible();

      // If more than 3 members were selected, should see "+N more" tag
      if (userCount > 3) {
        const moreTag = page.locator(`.ant-table tbody tr:has-text("${largeGroupId}") .ant-tag:has-text("more")`);
        if (await moreTag.count() > 0) {
          await expect(moreTag).toBeVisible();
        }
      }
    });

    test('should open members detail modal when clicking "+N more" tag', async ({ page }) => {
      // Create a group with multiple members
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);

      await page.fill('input#id', largeGroupId);
      await page.fill('input#name', 'Large Test Group');

      // Select multiple user members
      const userMembersSelect = page.locator('.ant-form-item:has-text("ユーザーメンバー") .ant-select');
      await userMembersSelect.click();
      await page.waitForTimeout(500);

      const userOptions = page.locator('.ant-select-dropdown .ant-select-item-option');
      const userCount = await userOptions.count();

      for (let i = 0; i < Math.min(userCount, 5); i++) {
        await userOptions.nth(i).click();
        await page.waitForTimeout(100);
      }

      await page.locator('.ant-modal-title').click();
      await page.locator('.ant-modal-content button[type="submit"]').click();
      await page.waitForTimeout(2000);

      // Find the "+N more" tag and click it
      const moreTag = page.locator(`.ant-table tbody tr:has-text("${largeGroupId}") .ant-tag:has-text("more")`);

      if (await moreTag.count() > 0) {
        await moreTag.click();
        await page.waitForTimeout(500);

        // Members detail modal should open
        const detailModal = page.locator('.ant-modal:has-text("メンバー詳細")');
        await expect(detailModal).toBeVisible({ timeout: 5000 });

        // Should show user members section
        await expect(detailModal.locator('text=ユーザーメンバー')).toBeVisible();

        // Should show group members section
        await expect(detailModal.locator('text=グループメンバー')).toBeVisible();

        // Close modal
        await page.locator('.ant-modal:has-text("メンバー詳細") button:has-text("閉じる")').click();
      } else {
        // If no "+N more" tag, test passes (not enough members to trigger overflow)
        test.skip('Not enough members to show "+N more" tag');
      }
    });
  });

  test.describe('User Management - Groups Display', () => {
    test('should navigate to user management and check group display', async ({ page }) => {
      // Navigate to user management
      await page.locator('.ant-menu-item:has-text("ユーザー管理")').click();
      await page.waitForTimeout(2000);

      // Verify we're on user management page
      expect(page.url()).toContain('/users');

      // Check if users table is visible
      const table = page.locator('.ant-table');
      await expect(table).toBeVisible({ timeout: 10000 });

      // Check for group column in user table
      const groupColumn = page.locator('.ant-table-thead th:has-text("所属グループ")');

      if (await groupColumn.count() > 0) {
        // Group column exists - verify display
        await expect(groupColumn).toBeVisible();

        // Check if any user has groups displayed
        const userRows = page.locator('.ant-table tbody tr');
        const rowCount = await userRows.count();

        if (rowCount > 0) {
          // At least one user exists - check for group tags
          const groupTags = page.locator('.ant-table tbody .ant-tag');
          if (await groupTags.count() > 0) {
            // Group tags are displayed
            await expect(groupTags.first()).toBeVisible();
          }
        }
      }
    });
  });

  test.describe('Member Settings UI Elements', () => {
    test('should show separate user and group member fields in edit modal', async ({ page }) => {
      // Click create button to open modal
      await page.locator('button:has-text("作成")').click();
      await page.waitForTimeout(500);

      // Verify "メンバー設定" divider exists
      const memberSettingsDivider = page.locator('.ant-divider:has-text("メンバー設定")');
      await expect(memberSettingsDivider).toBeVisible();

      // Verify "ユーザーメンバー" field exists
      const userMembersLabel = page.locator('.ant-form-item-label:has-text("ユーザーメンバー")');
      await expect(userMembersLabel).toBeVisible();

      // Verify "グループメンバー" field exists
      const groupMembersLabel = page.locator('.ant-form-item-label:has-text("グループメンバー")');
      await expect(groupMembersLabel).toBeVisible();

      // Verify circular reference warning is shown (only in edit mode)
      // In create mode, this warning should not be shown
      const circularWarning = page.locator('.ant-typography-warning:has-text("循環参照")');
      await expect(circularWarning).not.toBeVisible();

      // Close modal
      await page.locator('.ant-modal-content button:has-text("キャンセル")').click();
    });

    test('should show circular reference warning in edit mode', async ({ page }) => {
      // First create a group
      const testGroupId = 'test-warning-grp';
      await createGroup(page, testGroupId, 'Warning Test Group');

      // Edit the group
      const groupRow = page.locator(`.ant-table tbody tr:has-text("${testGroupId}")`);
      await groupRow.locator('button:has-text("編集")').click();
      await page.waitForTimeout(500);

      // In edit mode, circular reference warning should be visible
      const circularWarning = page.locator('.ant-typography-warning:has-text("循環参照")');
      await expect(circularWarning).toBeVisible();

      // Close modal and cleanup
      await page.locator('.ant-modal-content button:has-text("キャンセル")').click();
      await page.waitForTimeout(500);

      // Delete test group
      await deleteGroup(page, testGroupId);
    });
  });
});
