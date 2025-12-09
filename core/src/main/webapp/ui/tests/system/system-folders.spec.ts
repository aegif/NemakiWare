import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

test.describe('System Folders (/.system)', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);

    // Login as admin
    await authHelper.login();

    // Wait for UI to load
    await page.waitForTimeout(2000);

    // Close mobile sidebar if needed
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }
  });

  test.afterEach(async ({ page }) => {
    await page.close();
  });

  test('should display /.system folder and its subfolders', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to /.system folder via URL
    await page.goto('http://localhost:8080/core/ui/index.html#/documents');
    await page.waitForTimeout(2000);

    // Look for .system folder in document table
    const systemFolderRow = page.locator('tr:has-text(".system")').first();
    await expect(systemFolderRow).toBeVisible({ timeout: 10000 });

    console.log('✅ Found .system folder in document list');

    // Click to open .system folder
    const folderIcon = systemFolderRow.locator('[data-icon="folder"]');
    await folderIcon.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Verify we're now in .system folder
    const table = page.locator('.ant-table-tbody');
    await expect(table).toBeVisible({ timeout: 10000 });

    // Should see 'users' and 'groups' subfolders
    const usersFolder = page.locator('tr:has-text("users")');
    const groupsFolder = page.locator('tr:has-text("groups")');

    await expect(usersFolder).toBeVisible({ timeout: 5000 });
    await expect(groupsFolder).toBeVisible({ timeout: 5000 });

    console.log('✅ Found users and groups folders in .system');
  });

  test('should display users in /.system/users folder', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate directly via API to get folder ID first
    const foldersResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
      headers: {
        'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
      },
      form: {
        'cmisaction': 'query',
        'statement': "SELECT cmis:objectId FROM cmis:folder WHERE cmis:path = '/.system/users'",
      },
    });

    const foldersData = await foldersResponse.json();
    console.log('Query response:', JSON.stringify(foldersData, null, 2));

    if (!foldersData.results || foldersData.results.length === 0) {
      console.log('⚠️ No /.system/users folder found - may need to be created');
      test.skip();
      return;
    }

    const usersObjectId = foldersData.results[0]['cmis:objectId'];
    console.log('Found /.system/users folder ID:', usersObjectId);

    // Get children of users folder via Browser Binding
    const childrenResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${usersObjectId}?cmisselector=children`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        },
      }
    );

    const childrenData = await childrenResponse.json();
    console.log('Children response:', JSON.stringify(childrenData, null, 2));

    // Verify no ClassCastException error
    expect(childrenData.exception).toBeUndefined();

    // Should have at least admin user
    expect(childrenData.objects).toBeDefined();
    expect(childrenData.objects.length).toBeGreaterThan(0);

    console.log(`✅ Found ${childrenData.objects.length} users in /.system/users`);

    // Check first user object
    const firstUser = childrenData.objects[0];
    console.log('First user:', {
      name: firstUser.object?.properties?.['cmis:name']?.value,
      objectTypeId: firstUser.object?.properties?.['cmis:objectTypeId']?.value,
      objectType: firstUser.object?.properties?.['cmis:objectType']?.value,
    });

    // Verify it's a nemaki:user type
    expect(firstUser.object?.properties?.['cmis:objectTypeId']?.value).toBe('nemaki:user');
  });

  test('should display groups in /.system/groups folder', async ({ page }) => {
    // Get /.system/groups folder ID
    const foldersResponse = await page.request.post('http://localhost:8080/core/browser/bedroom', {
      headers: {
        'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
      },
      form: {
        'cmisaction': 'query',
        'statement': "SELECT cmis:objectId FROM cmis:folder WHERE cmis:path = '/.system/groups'",
      },
    });

    const foldersData = await foldersResponse.json();

    if (!foldersData.results || foldersData.results.length === 0) {
      console.log('⚠️ No /.system/groups folder found - may need to be created');
      test.skip();
      return;
    }

    const groupsObjectId = foldersData.results[0]['cmis:objectId'];
    console.log('Found /.system/groups folder ID:', groupsObjectId);

    // Get children of groups folder
    const childrenResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${groupsObjectId}?cmisselector=children`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        },
      }
    );

    const childrenData = await childrenResponse.json();
    console.log('Groups children response:', JSON.stringify(childrenData, null, 2));

    // Verify no ClassCastException error
    expect(childrenData.exception).toBeUndefined();

    // Should have at least GROUP_EVERYONE
    expect(childrenData.objects).toBeDefined();
    expect(childrenData.objects.length).toBeGreaterThan(0);

    console.log(`✅ Found ${childrenData.objects.length} groups in /.system/groups`);

    // Check first group object
    const firstGroup = childrenData.objects[0];
    console.log('First group:', {
      name: firstGroup.object?.properties?.['cmis:name']?.value,
      objectTypeId: firstGroup.object?.properties?.['cmis:objectTypeId']?.value,
    });

    // Verify it's a nemaki:group type
    expect(firstGroup.object?.properties?.['cmis:objectTypeId']?.value).toBe('nemaki:group');
  });

  test('should handle navigation to system folders via UI', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to root documents page
    await page.goto('http://localhost:8080/core/ui/index.html#/documents');
    await page.waitForTimeout(2000);

    // Find and click .system folder
    const systemFolderRow = page.locator('tr:has-text(".system")').first();
    await expect(systemFolderRow).toBeVisible({ timeout: 10000 });

    const systemFolderIcon = systemFolderRow.locator('[data-icon="folder"]');
    await systemFolderIcon.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find and click users folder
    const usersFolderRow = page.locator('tr:has-text("users")').first();
    await expect(usersFolderRow).toBeVisible({ timeout: 10000 });

    const usersFolderIcon = usersFolderRow.locator('[data-icon="folder"]');
    await usersFolderIcon.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(3000);

    // Verify we can see users (check for table rows)
    const table = page.locator('.ant-table-tbody');
    await expect(table).toBeVisible({ timeout: 10000 });

    const rows = await table.locator('tr').count();
    console.log(`✅ Found ${rows} items in /.system/users folder via UI navigation`);

    // Should have at least 1 user
    expect(rows).toBeGreaterThan(0);
  });
});
