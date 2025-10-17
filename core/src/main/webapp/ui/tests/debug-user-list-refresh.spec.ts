import { test } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';

test('investigate user list refresh behavior', async ({ page }) => {
  test.setTimeout(180000);
  const authHelper = new AuthHelper(page);
  const testUsername = `testuser${Date.now()}`;

  console.log('Step 1: Login and navigate to user management');
  await authHelper.login();
  await page.waitForTimeout(2000);

  const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
  if (await adminMenu.count() > 0) {
    await adminMenu.click();
    await page.waitForTimeout(1000);
  }

  const userManagementItem = page.locator('.ant-menu-item:has-text("ユーザー管理")');
  if (await userManagementItem.count() > 0) {
    await userManagementItem.click();
    await page.waitForTimeout(3000); // Longer wait
  }

  // Get initial user list
  console.log('\nBefore user creation:');
  const initialRows = await page.locator('.ant-table-tbody tr').count();
  console.log('  Total rows:', initialRows);

  // Check for pagination
  const paginationExists = await page.locator('.ant-pagination').count();
  console.log('  Pagination exists:', paginationExists > 0);

  if (paginationExists > 0) {
    try {
      const paginationText = await page.locator('.ant-pagination-total-text').textContent({ timeout: 2000 });
      console.log('  Pagination text:', paginationText);
    } catch (e) {
      console.log('  Pagination text element not found');
      // Get page numbers instead
      const pageItems = await page.locator('.ant-pagination-item').count();
      console.log('  Pagination page count:', pageItems);
    }
  }

  // Get all visible usernames
  const visibleUsernames = await page.locator('.ant-table-tbody tr td:first-child').allTextContents();
  console.log('  Visible usernames (first 5):', visibleUsernames.slice(0, 5));

  console.log('\nStep 2: Create test user');
  const createButton = page.locator('button').filter({
    hasText: /新規ユーザー|新規作成|ユーザー追加|追加/
  });

  if (await createButton.count() > 0) {
    await createButton.first().click();
    await page.waitForTimeout(1000);

    const modal = page.locator('.ant-modal:not(.ant-modal-hidden), .ant-drawer:not(.ant-drawer-hidden)');
    await modal.waitFor({ state: 'visible', timeout: 5000 });

    console.log(`Creating user: ${testUsername}`);
    await modal.locator('input#id').fill(testUsername);
    await modal.locator('input#name').fill(`${testUsername}_display`);
    await modal.locator('input#firstName').fill('Test');
    await modal.locator('input#lastName').fill('User');
    await modal.locator('input#email').fill(`${testUsername}@example.com`);
    await modal.locator('input#password').fill('TestPass123!');

    const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary');
    await submitButton.first().click();

    // Wait for success
    await page.waitForSelector('.ant-message-success, .ant-notification-success', { timeout: 5000 });
    await modal.waitFor({ state: 'hidden', timeout: 5000 });
    console.log('✅ User created successfully');

    // Wait for DB write and potential auto-refresh
    console.log('\nWaiting for user list to auto-refresh (5 seconds)...');
    await page.waitForTimeout(5000);

    // Check if list auto-refreshed
    const rowsAfterCreate = await page.locator('.ant-table-tbody tr').count();
    console.log('After creation (no navigation):');
    console.log('  Total rows:', rowsAfterCreate);
    console.log('  Row count changed:', rowsAfterCreate !== initialRows);

    const foundImmediately = await page.locator(`tr:has-text("${testUsername}")`).count();
    console.log('  User found immediately:', foundImmediately > 0);

    if (foundImmediately === 0) {
      console.log('\nStep 3: Try UI navigation to refresh');

      // Navigate away
      const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
      if (await documentsMenu.count() > 0) {
        await documentsMenu.click();
        await page.waitForTimeout(1000);
      }

      // Navigate back
      const adminMenuBack = page.locator('.ant-menu-submenu:has-text("管理")');
      if (await adminMenuBack.count() > 0) {
        await adminMenuBack.click();
        await page.waitForTimeout(500);
      }

      const userMgmtBack = page.locator('.ant-menu-item:has-text("ユーザー管理")');
      if (await userMgmtBack.count() > 0) {
        await userMgmtBack.click();
        await page.waitForTimeout(3000); // Wait longer
      }

      console.log('After UI navigation:');
      const rowsAfterNav = await page.locator('.ant-table-tbody tr').count();
      console.log('  Total rows:', rowsAfterNav);
      console.log('  Row count changed:', rowsAfterNav !== initialRows);

      const foundAfterNav = await page.locator(`tr:has-text("${testUsername}")`).count();
      console.log('  User found after navigation:', foundAfterNav > 0);

      if (foundAfterNav === 0) {
        console.log('\nStep 4: Check pagination for new user');

        if (paginationExists > 0) {
          // Try going to last page
          const lastPageButton = page.locator('.ant-pagination-item').last();
          if (await lastPageButton.count() > 0) {
            await lastPageButton.click();
            await page.waitForTimeout(2000);

            const foundInLastPage = await page.locator(`tr:has-text("${testUsername}")`).count();
            console.log('  User found in last page:', foundInLastPage > 0);
          }
        }

        // Get all current usernames
        const currentUsernames = await page.locator('.ant-table-tbody tr td:first-child').allTextContents();
        console.log('  Current visible usernames (first 10):', currentUsernames.slice(0, 10));
      }
    }
  }
});
