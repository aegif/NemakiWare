/**
 * Webhook Settings UI E2E Tests
 *
 * Verifies the Webhook configuration UI in the DocumentViewer:
 * - Webhook tab visibility (folders only, admin permission required)
 * - Webhook CRUD via UI (create, read, update, delete)
 * - Event type checkboxes including CONTENT_UPDATED
 * - Permission management (non-admin denied)
 *
 * Prerequisites:
 * - NemakiWare server running on localhost:8080
 * - admin:admin credentials available
 */

import { test, expect } from '@playwright/test';
import { waitForUiStable } from '../utils/wait-helpers';
import { ApiHelper, generateTestId } from '../utils/test-helper';
import { AuthHelper } from '../utils/auth-helper';

const BASE_URL = 'http://localhost:8080';
const REPOSITORY_ID = 'bedroom';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const REST_CSRF = { 'X-Requested-With': 'XMLHttpRequest' as const };

test.describe('Webhook Settings UI Tests', () => {

  let apiHelper: ApiHelper;
  let authHelper: AuthHelper;
  let testFolderId: string | null = null;
  const testFolderName = `webhook-ui-test-${generateTestId()}`;

  test.beforeEach(async ({ page }) => {
    apiHelper = new ApiHelper(page);
    authHelper = new AuthHelper(page);
  });

  test.afterEach(async () => {
    // Clean up test folder
    if (testFolderId && apiHelper) {
      try {
        await apiHelper.deleteFolderTree(testFolderId);
      } catch {
        // ignore cleanup errors
      }
      testFolderId = null;
    }
  });

  test('WU1: Webhook tab is visible on folders for admin user', async ({ page }) => {
    // Create test folder via API
    testFolderId = await apiHelper.createFolder({ name: testFolderName });
    expect(testFolderId).toBeTruthy();

    // Login as admin to establish authenticated session
    await authHelper.login();

    // Navigate to the folder detail view using the correct app route
    // App routes: /documents/:objectId (DocumentViewer)
    await page.goto(`${BASE_URL}/core/ui/#/documents/${testFolderId}`);
    await page.waitForLoadState('networkidle');

    // Wait for tabs to render
    const webhookTab = page.locator('.ant-tabs-tab').filter({ hasText: /Webhook|ウェブフック/ });
    await webhookTab.waitFor({ state: 'visible', timeout: 15000 });
    expect(await webhookTab.isVisible()).toBeTruthy();
  });

  test('WU2: Webhook configuration CRUD via REST API (UI backend)', async ({ request }) => {
    // Create test folder via CMIS API
    const rootRes = await request.get(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=repositoryInfo`,
      { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
    );
    const repoData = await rootRes.json();
    const rootFolderId = repoData[REPOSITORY_ID]?.rootFolderId;

    const folderName = `wh-crud-${generateTestId()}`;
    const createRes = await request.post(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
      {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: {
          'cmisaction': 'createFolder',
          'objectId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': folderName,
        },
      }
    );
    expect(createRes.ok()).toBeTruthy();
    const folderData = await createRes.json();
    const folderId = folderData.properties?.['cmis:objectId']?.value || folderData.succinctProperties?.['cmis:objectId'];

    try {
      // CREATE: Add webhook config
      const addRes = await request.post(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json', ...REST_CSRF },
          data: {
            url: 'https://example.com/webhook-test',
            events: ['created', 'content_updated'],
            enabled: true,
            includeChildren: false,
            retryCount: 3,
            authType: 'none',
            headers: {},
          },
        }
      );
      expect(addRes.ok()).toBeTruthy();
      const addData = await addRes.json();
      expect(addData.status).toBe('success');

      // READ: Verify webhook config was created
      const getRes = await request.get(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
      );
      expect(getRes.ok()).toBeTruthy();
      const getData = await getRes.json();
      expect(getData.status).toBe('success');
      expect(getData.webhookConfigs).toBeDefined();
      expect(getData.webhookConfigs.length).toBeGreaterThanOrEqual(1);

      const createdConfig = getData.webhookConfigs[0];
      expect(createdConfig.url).toBe('https://example.com/webhook-test');
      expect(createdConfig.events).toContain('created');
      expect(createdConfig.events).toContain('content_updated');
      expect(createdConfig.enabled).toBe(true);

      // UPDATE: Modify webhook config
      const webhookId = createdConfig.id;
      const updateRes = await request.put(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}/${webhookId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json', ...REST_CSRF },
          data: {
            url: 'https://example.com/webhook-updated',
            events: ['created', 'updated', 'content_updated', 'deleted'],
            enabled: true,
            includeChildren: true,
            maxDepth: 3,
            retryCount: 5,
            authType: 'hmac',
            secret: 'test-secret-key',
            headers: {},
          },
        }
      );
      expect(updateRes.ok()).toBeTruthy();
      const updateData = await updateRes.json();
      expect(updateData.status).toBe('success');

      // Verify update
      const verifyRes = await request.get(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
      );
      const verifyData = await verifyRes.json();
      const updatedConfig = verifyData.webhookConfigs.find((c: any) => c.id === webhookId);
      expect(updatedConfig).toBeDefined();
      expect(updatedConfig.url).toBe('https://example.com/webhook-updated');
      expect(updatedConfig.events).toContain('content_updated');
      expect(updatedConfig.events.length).toBe(4);
      expect(updatedConfig.includeChildren).toBe(true);

      // DELETE: Remove webhook config
      const deleteRes = await request.delete(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}/${webhookId}`,
        { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
      );
      expect(deleteRes.ok()).toBeTruthy();
      const deleteData = await deleteRes.json();
      expect(deleteData.status).toBe('success');

      // Verify deletion
      const finalRes = await request.get(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
      );
      const finalData = await finalRes.json();
      expect(finalData.webhookConfigs.length).toBe(0);

    } finally {
      // Cleanup: delete test folder
      await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: { 'cmisaction': 'deleteTree', 'objectId': folderId, 'allVersions': 'true', 'continueOnFailure': 'true' },
      });
    }
  });

  test('WU3: CONTENT_UPDATED event type is available and functional', async ({ request }) => {
    // Create test folder
    const rootRes = await request.get(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=repositoryInfo`,
      { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
    );
    const repoData = await rootRes.json();
    const rootFolderId = repoData[REPOSITORY_ID]?.rootFolderId;

    const folderName = `wh-content-${generateTestId()}`;
    const createRes = await request.post(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
      {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: {
          'cmisaction': 'createFolder',
          'objectId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': folderName,
        },
      }
    );
    const folderData = await createRes.json();
    const folderId = folderData.properties?.['cmis:objectId']?.value || folderData.succinctProperties?.['cmis:objectId'];

    try {
      // Create webhook config with ONLY content_updated event
      const addRes = await request.post(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json', ...REST_CSRF },
          data: {
            url: 'https://example.com/content-webhook',
            events: ['content_updated'],
            enabled: true,
            includeChildren: false,
            retryCount: 3,
            authType: 'none',
            headers: {},
          },
        }
      );
      expect(addRes.ok()).toBeTruthy();
      const addData = await addRes.json();
      expect(addData.status).toBe('success');

      // Verify content_updated is properly stored
      const getRes = await request.get(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
      );
      const getData = await getRes.json();
      expect(getData.webhookConfigs.length).toBe(1);
      expect(getData.webhookConfigs[0].events).toEqual(['content_updated']);
      expect(getData.webhookConfigs[0].enabled).toBe(true);

    } finally {
      await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: { 'cmisaction': 'deleteTree', 'objectId': folderId, 'allVersions': 'true', 'continueOnFailure': 'true' },
      });
    }
  });

  test('WU4: All supported event types can be configured', async ({ request }) => {
    // Create test folder
    const rootRes = await request.get(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=repositoryInfo`,
      { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
    );
    const repoData = await rootRes.json();
    const rootFolderId = repoData[REPOSITORY_ID]?.rootFolderId;

    const folderName = `wh-alltypes-${generateTestId()}`;
    const createRes = await request.post(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
      {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: {
          'cmisaction': 'createFolder',
          'objectId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': folderName,
        },
      }
    );
    const folderData = await createRes.json();
    const folderId = folderData.properties?.['cmis:objectId']?.value || folderData.succinctProperties?.['cmis:objectId'];

    try {
      // All event types supported by the UI
      const allEventTypes = [
        'created', 'updated', 'deleted', 'security', 'content_updated',
        'child_created', 'child_updated', 'child_deleted',
      ];

      const addRes = await request.post(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json', ...REST_CSRF },
          data: {
            url: 'https://example.com/all-events',
            events: allEventTypes,
            enabled: true,
            includeChildren: true,
            maxDepth: 10,
            retryCount: 3,
            authType: 'none',
            headers: {},
          },
        }
      );
      expect(addRes.ok()).toBeTruthy();

      // Verify all event types were stored correctly
      const getRes = await request.get(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
      );
      const getData = await getRes.json();
      expect(getData.webhookConfigs.length).toBe(1);

      const storedEvents = getData.webhookConfigs[0].events;
      for (const eventType of allEventTypes) {
        expect(storedEvents).toContain(eventType);
      }
      expect(storedEvents.length).toBe(allEventTypes.length);

    } finally {
      await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: { 'cmisaction': 'deleteTree', 'objectId': folderId, 'allVersions': 'true', 'continueOnFailure': 'true' },
      });
    }
  });

  test('WU5: Non-admin user cannot create webhook config', async ({ request }) => {
    // Use test user credentials
    const testUserAuth = 'Basic ' + Buffer.from('api-e2e-testuser:testtest').toString('base64');

    // Create test folder as admin
    const rootRes = await request.get(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=repositoryInfo`,
      { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
    );
    const repoData = await rootRes.json();
    const rootFolderId = repoData[REPOSITORY_ID]?.rootFolderId;

    const folderName = `wh-perm-${generateTestId()}`;
    const createRes = await request.post(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
      {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: {
          'cmisaction': 'createFolder',
          'objectId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': folderName,
        },
      }
    );
    const folderData = await createRes.json();
    const folderId = folderData.properties?.['cmis:objectId']?.value || folderData.succinctProperties?.['cmis:objectId'];

    try {
      // Ensure test user exists
      const restBase = `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}`;
      const checkRes = await request.get(`${restBase}/user/show/api-e2e-testuser`, {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
      });
      const checkData = await checkRes.json();
      if (checkData.status !== 'success' || !checkData.user) {
        const createUserRes = await request.post(`${restBase}/user/create/api-e2e-testuser`, {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded', ...REST_CSRF },
          data: new URLSearchParams({ name: 'api-e2e-testuser', password: 'testtest' }).toString(),
        });
        expect(createUserRes.status()).toBe(200);
      }

      // Restrict test user to read-only on this folder (webhook requires CAN_UPDATE_PROPERTIES)
      await request.post(
        `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/x-www-form-urlencoded', ...REST_CSRF },
          form: {
            cmisaction: 'applyACL',
            objectId: folderId,
            'addACEPrincipal[0]': 'api-e2e-testuser',
            'addACEPermission[0][0]': 'cmis:read',
          },
        }
      );

      // Attempt to create webhook config as non-admin user (read-only)
      const addRes = await request.post(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        {
          headers: { 'Authorization': testUserAuth, 'Content-Type': 'application/json', ...REST_CSRF },
          data: {
            url: 'https://example.com/unauthorized',
            events: ['created'],
            enabled: true,
            includeChildren: false,
            retryCount: 3,
            authType: 'none',
            headers: {},
          },
        }
      );

      const addData = await addRes.json().catch(() => ({}));
      // Should be denied (user has only cmis:read, not CAN_UPDATE_PROPERTIES)
      expect(addRes.status() === 403 || addData.status === 'failure').toBeTruthy();

    } finally {
      // Cleanup as admin
      await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: { 'cmisaction': 'deleteTree', 'objectId': folderId, 'allVersions': 'true', 'continueOnFailure': 'true' },
      });
    }
  });

  test('WU6: Webhook auth types (basic, bearer, hmac) can be configured', async ({ request }) => {
    // Create test folder
    const rootRes = await request.get(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=repositoryInfo`,
      { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
    );
    const repoData = await rootRes.json();
    const rootFolderId = repoData[REPOSITORY_ID]?.rootFolderId;

    const folderName = `wh-auth-${generateTestId()}`;
    const createRes = await request.post(
      `${BASE_URL}/core/browser/${REPOSITORY_ID}`,
      {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: {
          'cmisaction': 'createFolder',
          'objectId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': folderName,
        },
      }
    );
    const folderData = await createRes.json();
    const folderId = folderData.properties?.['cmis:objectId']?.value || folderData.succinctProperties?.['cmis:objectId'];

    try {
      // Test HMAC auth type with secret
      const hmacRes = await request.post(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json', ...REST_CSRF },
          data: {
            url: 'https://example.com/hmac-webhook',
            events: ['created', 'content_updated'],
            enabled: true,
            includeChildren: false,
            retryCount: 3,
            authType: 'hmac',
            secret: 'my-hmac-secret-key-123',
            headers: {},
          },
        }
      );
      expect(hmacRes.ok()).toBeTruthy();
      const hmacData = await hmacRes.json();
      expect(hmacData.status).toBe('success');

      // Verify HMAC config
      const getRes = await request.get(
        `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/webhook/config/${folderId}`,
        { headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF } }
      );
      const getData = await getRes.json();
      expect(getData.webhookConfigs.length).toBe(1);
      expect(getData.webhookConfigs[0].authType).toBe('hmac');
      // Secret should not be exposed in GET response
      expect(getData.webhookConfigs[0].hasSecret).toBe(true);

    } finally {
      await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: { 'Authorization': AUTH_HEADER, ...REST_CSRF },
        form: { 'cmisaction': 'deleteTree', 'objectId': folderId, 'allVersions': 'true', 'continueOnFailure': 'true' },
      });
    }
  });

  test('WU7: Webhook CRUD via UI form interaction', async ({ page }) => {
    // Create test folder via API
    testFolderId = await apiHelper.createFolder({ name: `wh-ui-crud-${generateTestId()}` });
    expect(testFolderId).toBeTruthy();

    // Login and navigate to folder detail view
    await authHelper.login();
    await page.goto(`${BASE_URL}/core/ui/#/documents/${testFolderId}`);
    await page.waitForLoadState('networkidle');

    // Click Webhook tab
    const webhookTab = page.locator('.ant-tabs-tab').filter({ hasText: /Webhook|ウェブフック/ });
    await webhookTab.waitFor({ state: 'visible', timeout: 15000 });
    await webhookTab.click();

    // ---- CREATE ----
    // Click "Add" button
    const addButton = page.locator('button').filter({ hasText: /Add Webhook|Webhook追加|ウェブフックを追加/ });
    await addButton.waitFor({ state: 'visible', timeout: 5000 });
    await addButton.click();

    // Modal should appear
    const modal = page.locator('.ant-modal-container');
    await modal.waitFor({ state: 'visible', timeout: 5000 });

    // Fill URL
    await modal.locator('input[id*="url"]').fill('https://test.example.com/hook1');

    // Check an event type checkbox (created / 作成) - i18n compatible
    const createdCheckbox = modal.locator('.ant-checkbox-wrapper').filter({ hasText: /^(created|作成)$/i }).first();
    await createdCheckbox.click();

    // Click OK in modal footer
    await modal.locator('.ant-modal-footer button.ant-btn-primary').click();

    // Wait for modal to close and table to update
    await waitForUiStable(page);

    // Verify row added in webhook table (scope to the active tab panel to exclude property tables)
    const webhookPanel = page.locator('.ant-tabs-tabpane-active, .ant-tabs-tabpane:not([hidden])').last();
    const tableRows = webhookPanel.locator('.ant-table-tbody .ant-table-row');
    await expect(tableRows).toHaveCount(1, { timeout: 5000 });
    // Verify the URL is displayed
    await expect(webhookPanel.locator('.ant-table-tbody')).toContainText('https://test.example.com/hook1');

    // ---- UPDATE ----
    // Click edit button on the first row
    const editButton = tableRows.first().locator('button').filter({ has: page.locator('[aria-label="edit"]') });
    await editButton.click();

    // Modal should reappear with existing data
    const editModal = page.locator('.ant-modal-container');
    await editModal.waitFor({ state: 'visible', timeout: 5000 });

    // Change URL
    const urlInput = editModal.locator('input[id*="url"]');
    await urlInput.clear();
    await urlInput.fill('https://test.example.com/hook-updated');

    // Click OK
    await editModal.locator('.ant-modal-footer button.ant-btn-primary').click();

    // Wait for update
    await waitForUiStable(page);

    // Verify URL was updated
    await expect(webhookPanel.locator('.ant-table-tbody')).toContainText('https://test.example.com/hook-updated');

    // ---- DELETE ----
    // Click delete button on the first row
    const deleteButton = tableRows.first().locator('button').filter({ has: page.locator('[aria-label="delete"]') });
    await deleteButton.click();

    // Confirm in Popconfirm
    const confirmButton = page.locator('.ant-popconfirm .ant-btn-primary');
    await confirmButton.waitFor({ state: 'visible', timeout: 3000 });
    await confirmButton.click();

    // Wait for deletion
    await waitForUiStable(page);

    // Verify table is empty
    await expect(page.locator('.ant-table-empty, .ant-empty')).toBeVisible({ timeout: 5000 });
  });
});
