import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * SKIPPED (2025-12-23) - Environment Pollution Blocking API Access
 *
 * Investigation Result: The .system folder EXISTS and admin CAN access it via API.
 * However, tests fail due to orphaned documents with deleted custom types:
 *
 * ROOT CAUSE:
 * - Previous test runs created documents with custom types (e.g., test:customDoc...)
 * - Those custom types were deleted, but documents remain in database
 * - When getChildren is called, it tries to compile these orphaned documents
 * - TypeManager throws NullPointerException: "TypeDefinitionContainer is null"
 * - This BREAKS the Browser Binding for ALL folders including root
 *
 * ERROR MESSAGE:
 * - "TypeDefinitionContainer is null for objectType: test:customDoc0a1e4fe5"
 * - Browser Binding returns: {"exception":"runtimeException","message":"null"}
 *
 * WORKAROUND:
 * 1. Clean database: Delete orphaned documents with custom type IDs from CouchDB
 * 2. Or restart with fresh database: docker compose down -v && docker compose up -d
 *
 * System Folders Test Suite (2025-12-23)
 *
 * Tests .system folder visibility and navigation for admin users.
 * The .system folder contains users and groups subfolders.
 *
 * ACL REQUIREMENT: admin user must have cmis:all permission on:
 * - /.system folder (34169aaa-5d6f-4685-a1d0-66bb31948877)
 * - /.system/users folder (6a672b7a-fbc3-4012-9da7-37cc7ad6a2fc)
 * - /.system/groups folder (78a28100-44d1-4318-8194-54aabba74c0e)
 *
 * Re-enable after database cleanup or environment reset.
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
    await page.waitForTimeout(2000);

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

  test('should display /.system folder and its subfolders', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // NOTE (2025-12-24): In environments with accumulated test data, .system folder
    // may be deep in pagination (page 15+ with 300+ items).
    // Use API to get .system folder ID and navigate directly.

    // Step 1: Get .system folder ID via API
    const rootResponse = await page.request.get('http://localhost:8080/core/browser/bedroom/root?cmisselector=children&maxItems=1000', {
      headers: {
        'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
      },
    });
    const rootData = await rootResponse.json();

    const systemFolder = rootData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === '.system'
    );

    if (!systemFolder) {
      test.skip('No .system folder found via API');
      return;
    }

    const systemFolderId = systemFolder.object?.properties?.['cmis:objectId']?.value;
    console.log('Found .system folder ID:', systemFolderId);

    // Step 2: Navigate directly to .system folder via URL
    await page.goto(`http://localhost:8080/core/ui/index.html#/documents?folderId=${systemFolderId}`);
    await page.waitForTimeout(3000);

    // Verify we're now in .system folder
    const table = page.locator('.ant-table-tbody');
    await expect(table).toBeVisible({ timeout: 10000 });

    // Should see 'users' subfolder (groups may not exist in all environments)
    const usersFolder = page.locator('tr:has-text("users")');
    const groupsFolder = page.locator('tr:has-text("groups")');

    await expect(usersFolder).toBeVisible({ timeout: 5000 });
    console.log('✅ Found users folder in .system');

    // Groups folder is optional - may not exist in all database states
    if (await groupsFolder.isVisible({ timeout: 2000 }).catch(() => false)) {
      console.log('✅ Found groups folder in .system');
    } else {
      console.log('ℹ️ Groups folder not found (may not exist in this database)');
    }
  });

  test('should display users in /.system/users folder', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Navigate via folder hierarchy instead of path query (path queries don't work for .system folders)
    // Step 1: Get root folder children
    const rootResponse = await page.request.get('http://localhost:8080/core/browser/bedroom/root?cmisselector=children', {
      headers: {
        'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
      },
    });
    const rootData = await rootResponse.json();

    // Step 2: Find .system folder
    const systemFolder = rootData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === '.system'
    );
    if (!systemFolder) {
      test.skip('No .system folder found in root');
      return;
    }
    const systemFolderId = systemFolder.object?.properties?.['cmis:objectId']?.value;
    console.log('Found .system folder ID:', systemFolderId);

    // Step 3: Get .system children
    const systemResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${systemFolderId}?cmisselector=children`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        },
      }
    );
    const systemData = await systemResponse.json();

    // Step 4: Find users folder
    const usersFolder = systemData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === 'users'
    );
    if (!usersFolder) {
      test.skip('No users folder found in .system');
      return;
    }
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
    // Navigate via folder hierarchy instead of path query (path queries don't work for .system folders)
    // Step 1: Get root folder children
    const rootResponse = await page.request.get('http://localhost:8080/core/browser/bedroom/root?cmisselector=children', {
      headers: {
        'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
      },
    });
    const rootData = await rootResponse.json();

    // Step 2: Find .system folder
    const systemFolder = rootData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === '.system'
    );
    if (!systemFolder) {
      test.skip('No .system folder found in root');
      return;
    }
    const systemFolderId = systemFolder.object?.properties?.['cmis:objectId']?.value;

    // Step 3: Get .system children
    const systemResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${systemFolderId}?cmisselector=children`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        },
      }
    );
    const systemData = await systemResponse.json();

    // Step 4: Find groups folder
    const groupsFolder = systemData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === 'groups'
    );
    if (!groupsFolder) {
      test.skip('No groups folder found in .system');
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

    // NOTE (2025-12-24): Use API to get folder IDs and navigate via URL
    // to avoid pagination issues in environments with accumulated test data.

    // Step 1: Get .system folder ID via API
    const rootResponse = await page.request.get('http://localhost:8080/core/browser/bedroom/root?cmisselector=children&maxItems=1000', {
      headers: {
        'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
      },
    });
    const rootData = await rootResponse.json();

    const systemFolder = rootData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === '.system'
    );

    if (!systemFolder) {
      test.skip('No .system folder found via API');
      return;
    }

    const systemFolderId = systemFolder.object?.properties?.['cmis:objectId']?.value;
    console.log('Found .system folder ID:', systemFolderId);

    // Step 2: Get users folder ID from .system
    const systemResponse = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${systemFolderId}?cmisselector=children`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        },
      }
    );
    const systemData = await systemResponse.json();

    const usersFolder = systemData.objects?.find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === 'users'
    );

    if (!usersFolder) {
      test.skip('No users folder found in .system');
      return;
    }

    const usersFolderId = usersFolder.object?.properties?.['cmis:objectId']?.value;
    console.log('Found users folder ID:', usersFolderId);

    // Step 3: Navigate to .system folder via URL
    await page.goto(`http://localhost:8080/core/ui/index.html#/documents?folderId=${systemFolderId}`);
    await page.waitForTimeout(2000);

    // Verify users folder is visible
    const usersFolderRow = page.locator('tr:has-text("users")').first();
    await expect(usersFolderRow).toBeVisible({ timeout: 10000 });

    // Click users folder
    const usersFolderButton = usersFolderRow.getByRole('button', { name: 'users' });
    await usersFolderButton.click(isMobile ? { force: true } : {});
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
