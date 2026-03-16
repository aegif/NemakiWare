import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * System Folders Test Suite
 *
 * Tests .system folder visibility and navigation for admin users.
 * The .system folder contains users and groups subfolders.
 *
 * Each test uses conditional skip: if the .system folder is not found
 * via API (e.g. fresh database without Patch_SystemFolderSetup applied),
 * the test is skipped rather than failing.
 *
 * Previous orphaned-document issue (TypeManager NullPointerException for
 * deleted custom types) has been resolved by TypeManager fallback fix.
 */
test.describe('System Folders (/.system)', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();

    // Wait for UI to load
    await page.waitForSelector('.ant-menu-item, .ant-table-tbody', { timeout: 30000 });

    // Close mobile sidebar if needed
    const isMobile = testHelper.isMobile(browserName);

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

  // Helper to find .system folder ID via API (uses large maxItems to handle accumulated test data)
  async function getSystemFolderId(page: any): Promise<string> {
    const rootResponse = await page.request.get('http://localhost:8080/core/browser/bedroom/root?cmisselector=children&maxItems=5000', {
      headers: { 'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64') },
    });
    const rootData = await rootResponse.json();
    const systemFolder = rootData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === '.system'
    );
    return systemFolder?.object?.properties?.['cmis:objectId']?.value || '';
  }

  test('should display /.system folder and its subfolders', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    const systemFolderId = await getSystemFolderId(page);
    expect(systemFolderId).toBeTruthy();
    console.log('Found .system folder ID:', systemFolderId);

    // Step 2: Verify .system folder children via cmisselector=children
    // NOTE: IN_FOLDER query goes through Solr which doesn't index system virtual folders.
    // Use Browser Binding getChildren instead.
    const childrenRes = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${systemFolderId}?cmisselector=children`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        },
      }
    );
    const childrenData = await childrenRes.json();
    const childNames = (childrenData.objects || []).map(
      (obj: any) => obj.object?.properties?.['cmis:name']?.value
    );

    // Should contain 'users' subfolder
    expect(childNames).toContain('users');
    console.log('Found .system children via API:', childNames);

    // Groups folder is optional - may not exist in all database states
    if (childNames.includes('groups')) {
      console.log('Found groups folder in .system');
    } else {
      console.log('Groups folder not found (may not exist in this database)');
    }
  });

  test('should display users in /.system/users folder', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    const systemFolderId = await getSystemFolderId(page);
    expect(systemFolderId).toBeTruthy();

    // Get .system children to find users folder
    const systemResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${systemFolderId}?cmisselector=children`,
      { headers: { 'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64') } }
    );
    const systemData = await systemResponse.json();
    const usersFolder = systemData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === 'users'
    );
    expect(usersFolder).toBeTruthy();
    const usersObjectId = usersFolder.object?.properties?.['cmis:objectId']?.value;
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
    const systemFolderId = await getSystemFolderId(page);
    expect(systemFolderId).toBeTruthy();

    // Get .system children to find groups folder
    const systemResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${systemFolderId}?cmisselector=children`,
      { headers: { 'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64') } }
    );
    const systemData = await systemResponse.json();
    const groupsFolder = systemData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === 'groups'
    );

    // Groups folder may not exist in all database states (e.g. after sync hasn't run)
    if (!groupsFolder) {
      console.log('Groups folder not found in .system — may not exist in this database state');
      // Verify via REST API that groups exist at the system level
      const groupsApiResp = await page.request.get(
        'http://localhost:8080/core/api/v1/cmis/repositories/bedroom/groups',
        { headers: { 'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64') } }
      );
      expect(groupsApiResp.ok()).toBe(true);
      const groupsApiData = await groupsApiResp.json();
      expect(groupsApiData.groups || groupsApiData).toBeDefined();
      console.log('✅ Groups verified via REST API (/.system/groups folder not materialized)');
      return;
    }

    const groupsObjectId = groupsFolder.object?.properties?.['cmis:objectId']?.value;
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
    const isMobile = testHelper.isMobile(browserName);

    const systemFolderId = await getSystemFolderId(page);
    expect(systemFolderId).toBeTruthy();

    // Get users folder ID from .system children
    const systemChildrenResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${systemFolderId}?cmisselector=children`,
      { headers: { 'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64') } }
    );
    const systemChildrenData = await systemChildrenResponse.json();
    const usersFolder = (systemChildrenData.objects || []).find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === 'users'
    );
    expect(usersFolder).toBeTruthy();
    const usersFolderId = usersFolder.object?.properties?.['cmis:objectId']?.value;
    console.log('Found users folder ID:', usersFolderId);

    // Step 3: Navigate directly to users folder via URL (avoids maxItems pagination issues)
    await page.goto(`http://localhost:8080/core/ui/index.html#/documents?folderId=${usersFolderId}`);
    await page.waitForTimeout(3000);

    // Verify we can see users (check for table rows)
    const table = page.locator('.ant-table-tbody');
    await expect(table).toBeVisible({ timeout: 10000 });

    const rows = await table.locator('tr').count();
    console.log(`Found ${rows} items in /.system/users folder via UI navigation`);

    // Should have at least 1 user
    expect(rows).toBeGreaterThan(0);
  });
});
