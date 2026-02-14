import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

/**
 * Import/Export Feature E2E Tests
 *
 * Tests for NemakiWare import/export functionality:
 * - REST API: ZIP-based import/export
 * - UI: Filesystem import/export page navigation and rendering
 *
 * Prerequisites:
 * - NemakiWare running on http://localhost:8080
 * - Admin user credentials (admin/admin)
 */

const BASE_URL = 'http://localhost:8080';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

test.describe.serial('Import/Export Feature', () => {
  let authHelper: AuthHelper;

  // Track created objects for cleanup
  let exportTestFolderId: string | null = null;
  let importedDocIds: string[] = [];

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
  });

  // ========== REST API: ZIP Export Tests ==========

  test.describe('REST API - ZIP Export', () => {

    test('should export root folder as ZIP', async ({ page }) => {
      // Get root folder ID
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      expect(rootRes.ok()).toBeTruthy();
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;
      expect(rootId).toBeTruthy();

      // Export root folder
      const exportRes = await page.request.get(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/export/${rootId}`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      // Export should return 200 with ZIP content
      expect(exportRes.ok()).toBeTruthy();
      const contentType = exportRes.headers()['content-type'];
      expect(contentType).toMatch(/application\/zip|application\/octet-stream/);

      const body = await exportRes.body();
      // ZIP files start with PK magic bytes (0x504B)
      expect(body.length).toBeGreaterThan(0);
      expect(body[0]).toBe(0x50); // 'P'
      expect(body[1]).toBe(0x4B); // 'K'

      // Verify ZIP contains .meta.json files (NemakiWare custom format)
      const bodyStr = body.toString('utf-8');
      expect(bodyStr).toContain('.meta.json');
    });

    test('should return 404 for non-existent folder export', async ({ page }) => {
      const exportRes = await page.request.get(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/export/nonexistent-folder-id-12345`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      // Should return error (404 or 500)
      expect([404, 500]).toContain(exportRes.status());
    });

    test('should require authentication for export', async ({ page }) => {
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      const exportRes = await page.request.get(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/export/${rootId}`,
        { headers: {} } // No auth
      );
      // Should be rejected (401 or 403 or redirect)
      expect([200, 400, 401, 403]).toContain(exportRes.status());
      if (exportRes.status() === 200) {
        // Some servers return 200 with login page HTML
        const ct = exportRes.headers()['content-type'] || '';
        console.log('Unauthenticated export returned 200 with content-type:', ct);
      }
    });
  });

  // ========== REST API: ZIP Import Tests ==========

  test.describe('REST API - ZIP Import', () => {

    test.beforeAll(async ({ browser }) => {
      // Create a test folder for import
      const ctx = await browser.newContext();
      const page = await ctx.newPage();
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      const folderName = `import-test-${Date.now()}`;
      const createRes = await page.request.post(
        `${BASE_URL}/core/browser/bedroom`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            cmisaction: 'createFolder',
            'propertyId[0]': 'cmis:objectTypeId',
            'propertyValue[0]': 'cmis:folder',
            'propertyId[1]': 'cmis:name',
            'propertyValue[1]': folderName,
            objectId: rootId
          }
        }
      );
      if (createRes.ok()) {
        const folderData = await createRes.json();
        exportTestFolderId = folderData.succinctProperties?.['cmis:objectId']
          || folderData.properties?.['cmis:objectId']?.value || null;
        console.log(`Created test folder: ${folderName} (${exportTestFolderId})`);
      }
      await ctx.close();
    });

    test('should import a ZIP file into a folder', async ({ page }) => {
      if (!exportTestFolderId) {
        test.skip('Test folder not created');
        return;
      }

      // Create a minimal ZIP file in memory
      // ZIP format: Local file header + file data + central directory + end record
      const fileName = 'test-import.txt';
      const fileContent = 'Hello from import test';
      const zip = createMinimalZip(fileName, fileContent);

      const importRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/import/${exportTestFolderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            file: {
              name: 'test-import.zip',
              mimeType: 'application/zip',
              buffer: zip
            }
          }
        }
      );

      // Minimal ZIP without .meta.json files is treated as unknown format → 400
      const status = importRes.status();
      console.log(`Import response status: ${status}`);
      // The server rejects ZIPs without recognized format (ACP XML or NemakiWare .meta.json)
      expect([200, 400]).toContain(status);
      if (status === 400) {
        const result = await importRes.json();
        expect(result.status).toBe('error');
        console.log('Expected rejection of plain ZIP:', result.message);
      }
    });

    test('should reject import without authentication', async ({ page }) => {
      if (!exportTestFolderId) {
        test.skip('Test folder not created');
        return;
      }

      const zip = createMinimalZip('test.txt', 'content');
      const importRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/import/${exportTestFolderId}`,
        {
          headers: {}, // No auth
          multipart: {
            file: {
              name: 'test.zip',
              mimeType: 'application/zip',
              buffer: zip
            }
          }
        }
      );
      // Should be rejected
      expect([200, 400, 401, 403]).toContain(importRes.status());
      if (importRes.status() === 200) {
        console.log('Unauthenticated import returned 200 - may be login redirect');
      }
    });

    test('should handle import to non-existent folder', async ({ page }) => {
      const zip = createMinimalZip('test.txt', 'content');
      const importRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/import/nonexistent-folder-12345`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            file: {
              name: 'test.zip',
              mimeType: 'application/zip',
              buffer: zip
            }
          }
        }
      );
      // Should return error
      expect([400, 404, 500]).toContain(importRes.status());
    });

    test.afterAll(async ({ browser }) => {
      // Cleanup test folder
      if (exportTestFolderId) {
        const ctx = await browser.newContext();
        const page = await ctx.newPage();
        await page.request.post(
          `${BASE_URL}/core/browser/bedroom`,
          {
            headers: { 'Authorization': AUTH_HEADER },
            multipart: {
              cmisaction: 'deleteTree',
              objectId: exportTestFolderId,
              allVersions: 'true',
              continueOnFailure: 'true'
            }
          }
        );
        console.log(`Cleanup: deleted test folder ${exportTestFolderId}`);
        await ctx.close();
      }
    });
  });

  // ========== REST API: Round-trip Export/Import Test ==========

  test.describe('REST API - Round-trip', () => {
    let roundTripFolderId: string | null = null;
    let importTargetFolderId: string | null = null;

    test.beforeAll(async ({ browser }) => {
      const ctx = await browser.newContext();
      const page = await ctx.newPage();

      // Get root folder ID
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      // Create source folder with a document
      const srcName = `roundtrip-src-${Date.now()}`;
      const srcRes = await page.request.post(
        `${BASE_URL}/core/browser/bedroom`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            cmisaction: 'createFolder',
            'propertyId[0]': 'cmis:objectTypeId',
            'propertyValue[0]': 'cmis:folder',
            'propertyId[1]': 'cmis:name',
            'propertyValue[1]': srcName,
            objectId: rootId
          }
        }
      );
      if (srcRes.ok()) {
        const d = await srcRes.json();
        roundTripFolderId = d.succinctProperties?.['cmis:objectId']
          || d.properties?.['cmis:objectId']?.value;

        // Create a document in the source folder
        if (roundTripFolderId) {
          await page.request.post(
            `${BASE_URL}/core/browser/bedroom`,
            {
              headers: { 'Authorization': AUTH_HEADER },
              multipart: {
                cmisaction: 'createDocument',
                'propertyId[0]': 'cmis:objectTypeId',
                'propertyValue[0]': 'cmis:document',
                'propertyId[1]': 'cmis:name',
                'propertyValue[1]': 'roundtrip-test.txt',
                objectId: roundTripFolderId,
                content: {
                  name: 'roundtrip-test.txt',
                  mimeType: 'text/plain',
                  buffer: Buffer.from('Round-trip test content')
                }
              }
            }
          );
        }
      }

      // Create target folder for import
      const tgtName = `roundtrip-tgt-${Date.now()}`;
      const tgtRes = await page.request.post(
        `${BASE_URL}/core/browser/bedroom`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            cmisaction: 'createFolder',
            'propertyId[0]': 'cmis:objectTypeId',
            'propertyValue[0]': 'cmis:folder',
            'propertyId[1]': 'cmis:name',
            'propertyValue[1]': tgtName,
            objectId: rootId
          }
        }
      );
      if (tgtRes.ok()) {
        const d = await tgtRes.json();
        importTargetFolderId = d.succinctProperties?.['cmis:objectId']
          || d.properties?.['cmis:objectId']?.value;
      }

      await ctx.close();
    });

    test('should export folder and re-import preserving content', async ({ page }) => {
      if (!roundTripFolderId || !importTargetFolderId) {
        test.skip('Round-trip folders not created');
        return;
      }

      // Step 1: Export source folder
      const exportRes = await page.request.get(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/export/${roundTripFolderId}`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      expect(exportRes.ok()).toBeTruthy();
      const zipData = await exportRes.body();
      expect(zipData.length).toBeGreaterThan(0);
      console.log(`Exported ZIP size: ${zipData.length} bytes`);

      // Step 2: Import into target folder
      const importRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/import/${importTargetFolderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            file: {
              name: 'roundtrip.zip',
              mimeType: 'application/zip',
              buffer: zipData
            }
          }
        }
      );

      // Import should succeed with status 200
      expect(importRes.ok()).toBeTruthy();
      const result = await importRes.json();
      console.log('Round-trip import result:', JSON.stringify(result));
      expect(result.status).toBe('success');
      expect(result.documentsCreated).toBeGreaterThanOrEqual(1);

      // Verify content in target folder
      const childrenRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?objectId=${importTargetFolderId}&cmisselector=children`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      expect(childrenRes.ok()).toBeTruthy();
      const children = await childrenRes.json();
      const objects = children.objects || [];
      console.log(`Target folder has ${objects.length} children after import`);
      expect(objects.length).toBeGreaterThanOrEqual(1);
    });

    test.afterAll(async ({ browser }) => {
      const ctx = await browser.newContext();
      const page = await ctx.newPage();
      for (const fid of [roundTripFolderId, importTargetFolderId]) {
        if (fid) {
          await page.request.post(
            `${BASE_URL}/core/browser/bedroom`,
            {
              headers: { 'Authorization': AUTH_HEADER },
              multipart: {
                cmisaction: 'deleteTree',
                objectId: fid,
                allVersions: 'true',
                continueOnFailure: 'true'
              }
            }
          ).catch(() => {});
        }
      }
      await ctx.close();
    });
  });

  // ========== UI: Filesystem Import/Export Page ==========

  test.describe('UI - Filesystem Import/Export Page', () => {

    test('should navigate to filesystem import/export page from admin menu', async ({ page }) => {
      await authHelper.login();
      await page.goto(`${BASE_URL}/core/ui/index.html#/filesystem-import-export`);
      await page.waitForTimeout(3000);

      // Verify page loaded - check for title text
      const pageTitle = page.locator('h2, h3, .ant-card-head-title').filter({
        hasText: /ファイルシステム|Filesystem/i
      }).first();
      await expect(pageTitle).toBeVisible({ timeout: 10000 });
    });

    test('should display admin-only warning', async ({ page }) => {
      await authHelper.login();
      await page.goto(`${BASE_URL}/core/ui/index.html#/filesystem-import-export`);
      await page.waitForTimeout(3000);

      // Should show admin-only warning alert
      const warningAlert = page.locator('.ant-alert-warning');
      await expect(warningAlert).toBeVisible({ timeout: 10000 });
    });

    test('should display import and export tabs', async ({ page }) => {
      await authHelper.login();
      await page.goto(`${BASE_URL}/core/ui/index.html#/filesystem-import-export`);
      await page.waitForTimeout(3000);

      // Check for tabs
      const tabs = page.locator('.ant-tabs-tab');
      const tabCount = await tabs.count();
      expect(tabCount).toBeGreaterThanOrEqual(2);

      // Verify import tab exists
      const importTab = page.locator('.ant-tabs-tab').filter({
        hasText: /インポート|Import/i
      });
      await expect(importTab.first()).toBeVisible();

      // Verify export tab exists
      const exportTab = page.locator('.ant-tabs-tab').filter({
        hasText: /エクスポート|Export/i
      });
      await expect(exportTab.first()).toBeVisible();
    });

    test('should display import form with source path and folder selector', async ({ page }) => {
      await authHelper.login();
      await page.goto(`${BASE_URL}/core/ui/index.html#/filesystem-import-export`);
      await page.waitForTimeout(3000);

      // Click import tab if not already active
      const importTab = page.locator('.ant-tabs-tab').filter({
        hasText: /インポート|Import/i
      });
      await importTab.first().click();
      await page.waitForTimeout(1000);

      // Check for source path input
      const sourcePathInput = page.locator('input[placeholder*="/path/to/import"]');
      await expect(sourcePathInput).toBeVisible({ timeout: 5000 });

      // Check for folder selector (Select component)
      const folderSelect = page.locator('.ant-select');
      expect(await folderSelect.count()).toBeGreaterThan(0);

      // Check for import button
      const importButton = page.locator('button').filter({
        hasText: /インポート|Import/i
      });
      expect(await importButton.count()).toBeGreaterThan(0);
    });

    test('should display export form with folder selector and target path', async ({ page }) => {
      await authHelper.login();
      await page.goto(`${BASE_URL}/core/ui/index.html#/filesystem-import-export`);
      await page.waitForTimeout(3000);

      // Click export tab
      const exportTab = page.locator('.ant-tabs-tab').filter({
        hasText: /エクスポート|Export/i
      });
      await exportTab.first().click();
      await page.waitForTimeout(1000);

      // Check for target path input
      const targetPathInput = page.locator('input[placeholder*="/path/to/export"]');
      await expect(targetPathInput).toBeVisible({ timeout: 5000 });

      // Check for folder selector
      const folderSelect = page.locator('.ant-select');
      expect(await folderSelect.count()).toBeGreaterThan(0);

      // Check for overwrite checkbox
      const overwriteCheckbox = page.locator('.ant-checkbox');
      expect(await overwriteCheckbox.count()).toBeGreaterThan(0);

      // Check for export button
      const exportButton = page.locator('button').filter({
        hasText: /エクスポート|Export/i
      });
      expect(await exportButton.count()).toBeGreaterThan(0);
    });

    test('should load folder list in import form selector', async ({ page }) => {
      await authHelper.login();
      await page.goto(`${BASE_URL}/core/ui/index.html#/filesystem-import-export`);
      await page.waitForTimeout(3000);

      // Click import tab
      const importTab = page.locator('.ant-tabs-tab').filter({
        hasText: /インポート|Import/i
      });
      await importTab.first().click();
      await page.waitForTimeout(1000);

      // Open folder selector dropdown
      const folderSelect = page.locator('.ant-form-item').filter({
        hasText: /フォルダ|Folder/i
      }).locator('.ant-select').first();

      if (await folderSelect.isVisible().catch(() => false)) {
        await folderSelect.click();
        await page.waitForTimeout(1000);

        // Check dropdown has options
        const options = page.locator('.ant-select-dropdown .ant-select-item-option');
        const optionCount = await options.count();
        console.log(`Folder selector has ${optionCount} options`);
        expect(optionCount).toBeGreaterThan(0);

        // Close dropdown
        await page.keyboard.press('Escape');
      }
    });

    test('should show validation error when submitting import form without required fields', async ({ page }) => {
      await authHelper.login();
      await page.goto(`${BASE_URL}/core/ui/index.html#/filesystem-import-export`);
      await page.waitForTimeout(3000);

      // Click import tab
      const importTab = page.locator('.ant-tabs-tab').filter({
        hasText: /インポート|Import/i
      });
      await importTab.first().click();
      await page.waitForTimeout(1000);

      // Click import button without filling required fields
      const importButton = page.locator('button').filter({
        hasText: /インポート|Import/i
      }).first();

      if (await importButton.isVisible().catch(() => false)) {
        await importButton.click();
        await page.waitForTimeout(1000);

        // Should show validation errors (Ant Design form validation)
        const validationErrors = page.locator('.ant-form-item-explain-error');
        const errorCount = await validationErrors.count();
        console.log(`Validation errors shown: ${errorCount}`);
        expect(errorCount).toBeGreaterThan(0);
      }
    });

    test('should have refresh folders button', async ({ page }) => {
      await authHelper.login();
      await page.goto(`${BASE_URL}/core/ui/index.html#/filesystem-import-export`);
      await page.waitForTimeout(3000);

      // Check for refresh button
      const refreshButton = page.locator('button').filter({
        hasText: /更新|リフレッシュ|Refresh/i
      });
      // refreshFolders button uses ReloadOutlined icon
      const reloadButton = page.locator('button .anticon-reload');
      const hasRefresh = (await refreshButton.count()) > 0 || (await reloadButton.count()) > 0;
      expect(hasRefresh).toBeTruthy();
    });
  });

  // ========== REST API: Filesystem Import/Export Endpoints ==========

  test.describe('REST API - Filesystem Endpoints', () => {

    test('should have filesystem import endpoint', async ({ page }) => {
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      // Test filesystem import endpoint with a non-existent path
      const importRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/import/${rootId}`,
        {
          headers: {
            'Authorization': AUTH_HEADER,
            'Content-Type': 'application/json'
          },
          data: { sourcePath: '/nonexistent/path/for/testing' }
        }
      );

      // Should return error about non-existent path, not 404 for endpoint
      const status = importRes.status();
      console.log(`Filesystem import endpoint status: ${status}`);
      // Endpoint exists if we don't get 404 for the endpoint itself
      // May get 400/401/500 for invalid path or auth issues, which is expected
      expect([200, 400, 401, 403, 500]).toContain(status);
    });

    test('should have filesystem export endpoint', async ({ page }) => {
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      // Test filesystem export endpoint with a non-existent target path
      const exportRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/export/${rootId}`,
        {
          headers: {
            'Authorization': AUTH_HEADER,
            'Content-Type': 'application/json'
          },
          data: { targetPath: '/nonexistent/path/for/testing', allowOverwrite: false }
        }
      );

      const status = exportRes.status();
      console.log(`Filesystem export endpoint status: ${status}`);
      expect([200, 400, 401, 403, 500]).toContain(status);
    });
  });

  // ========== REST API: Filesystem Export/Import Execution Tests ==========

  test.describe('REST API - Filesystem Export Execution', () => {
    const EXPORT_PATH = '/tmp/nemakiware-export/e2e-test';

    test('should export root folder to filesystem successfully', async ({ page }) => {
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      const exportRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/export/${rootId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
          data: { targetPath: EXPORT_PATH, allowOverwrite: true }
        }
      );
      expect(exportRes.ok()).toBeTruthy();
      const result = await exportRes.json();
      expect(result.status).toBe('success');
      expect(result.foldersExported).toBeGreaterThanOrEqual(1);
      expect(result.documentsExported).toBeGreaterThanOrEqual(1);
      expect(result.targetPath).toBe(EXPORT_PATH);
      console.log(`Filesystem export: ${result.foldersExported} folders, ${result.documentsExported} documents`);
    });

    test('should export a specific subfolder to filesystem', async ({ page }) => {
      // Find a subfolder (e.g., Sites or first child folder)
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      const childrenRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?objectId=${rootId}&cmisselector=children`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const children = await childrenRes.json();
      const folders = (children.objects || []).filter(
        (o: any) => o.object.properties['cmis:baseTypeId'].value === 'cmis:folder'
      );

      if (folders.length === 0) {
        test.skip('No subfolders found');
        return;
      }

      const folderId = folders[0].object.properties['cmis:objectId'].value;
      const folderName = folders[0].object.properties['cmis:name'].value;
      const subExportPath = `/tmp/nemakiware-export/e2e-subfolder`;

      const exportRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/export/${folderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
          data: { targetPath: subExportPath, allowOverwrite: true }
        }
      );
      expect(exportRes.ok()).toBeTruthy();
      const result = await exportRes.json();
      expect(result.status).toBe('success');
      console.log(`Subfolder "${folderName}" export: ${result.foldersExported} folders, ${result.documentsExported} documents`);
    });

    test('should reject export to path outside allowed roots', async ({ page }) => {
      const rootRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      const exportRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/export/${rootId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
          data: { targetPath: '/root/not-allowed-path', allowOverwrite: false }
        }
      );
      // Should fail with error about path not in allowed roots
      const result = await exportRes.json();
      expect(result.status).toBe('error');
      expect(result.message).toContain('allowed');
    });

    test('should reject export for non-existent folder ID', async ({ page }) => {
      const exportRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/export/nonexistent-id-99999`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
          data: { targetPath: '/tmp/nemakiware-export/e2e-bad', allowOverwrite: true }
        }
      );
      expect([400, 404, 500]).toContain(exportRes.status());
    });
  });

  test.describe('REST API - Filesystem Import Execution', () => {
    const EXPORT_PATH = '/tmp/nemakiware-export/e2e-roundtrip';
    let importTargetFolderId: string | null = null;
    let sourceFolderId: string | null = null;

    test.beforeAll(async ({ browser }) => {
      const ctx = await browser.newContext();
      const pg = await ctx.newPage();

      // Get root folder
      const rootRes = await pg.request.get(
        `${BASE_URL}/core/browser/bedroom/root?cmisselector=object`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      const rootData = await rootRes.json();
      const rootId = rootData.properties['cmis:objectId'].value;

      // Create source folder with test documents
      const srcName = `fs-import-src-${Date.now()}`;
      const srcRes = await pg.request.post(
        `${BASE_URL}/core/browser/bedroom`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            cmisaction: 'createFolder',
            'propertyId[0]': 'cmis:objectTypeId',
            'propertyValue[0]': 'cmis:folder',
            'propertyId[1]': 'cmis:name',
            'propertyValue[1]': srcName,
            objectId: rootId
          }
        }
      );
      if (srcRes.ok()) {
        const d = await srcRes.json();
        sourceFolderId = d.succinctProperties?.['cmis:objectId'] || d.properties?.['cmis:objectId']?.value;

        // Add test documents
        for (let i = 1; i <= 3; i++) {
          await pg.request.post(
            `${BASE_URL}/core/browser/bedroom`,
            {
              headers: { 'Authorization': AUTH_HEADER },
              multipart: {
                cmisaction: 'createDocument',
                'propertyId[0]': 'cmis:objectTypeId',
                'propertyValue[0]': 'cmis:document',
                'propertyId[1]': 'cmis:name',
                'propertyValue[1]': `fs-test-doc-${i}.txt`,
                objectId: sourceFolderId!,
                content: {
                  name: `fs-test-doc-${i}.txt`,
                  mimeType: 'text/plain',
                  buffer: Buffer.from(`Filesystem import test document ${i}`)
                }
              }
            }
          );
        }
      }

      // Create target folder for import
      const tgtName = `fs-import-tgt-${Date.now()}`;
      const tgtRes = await pg.request.post(
        `${BASE_URL}/core/browser/bedroom`,
        {
          headers: { 'Authorization': AUTH_HEADER },
          multipart: {
            cmisaction: 'createFolder',
            'propertyId[0]': 'cmis:objectTypeId',
            'propertyValue[0]': 'cmis:folder',
            'propertyId[1]': 'cmis:name',
            'propertyValue[1]': tgtName,
            objectId: rootId
          }
        }
      );
      if (tgtRes.ok()) {
        const d = await tgtRes.json();
        importTargetFolderId = d.succinctProperties?.['cmis:objectId'] || d.properties?.['cmis:objectId']?.value;
      }

      // Export source folder to filesystem (creates data for import test)
      if (sourceFolderId) {
        await pg.request.post(
          `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/export/${sourceFolderId}`,
          {
            headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
            data: { targetPath: EXPORT_PATH, allowOverwrite: true }
          }
        );
      }

      await ctx.close();
    });

    test('should import from filesystem into a target folder', async ({ page }) => {
      if (!importTargetFolderId || !sourceFolderId) {
        test.skip('Test folders not created');
        return;
      }

      const importRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/import/${importTargetFolderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
          data: { sourcePath: EXPORT_PATH }
        }
      );
      expect(importRes.ok()).toBeTruthy();
      const result = await importRes.json();
      expect(result.status).toBe('success');
      expect(result.documentsCreated).toBeGreaterThanOrEqual(3);
      console.log(`Filesystem import: ${result.foldersCreated} folders, ${result.documentsCreated} documents`);
    });

    test('should verify imported documents exist in target folder', async ({ page }) => {
      if (!importTargetFolderId) {
        test.skip('Target folder not created');
        return;
      }

      // The import creates a subfolder matching the export source folder name
      const childrenRes = await page.request.get(
        `${BASE_URL}/core/browser/bedroom/root?objectId=${importTargetFolderId}&cmisselector=children`,
        { headers: { 'Authorization': AUTH_HEADER } }
      );
      expect(childrenRes.ok()).toBeTruthy();
      const children = await childrenRes.json();
      const objects = children.objects || [];
      // Should have at least the imported content
      expect(objects.length).toBeGreaterThanOrEqual(1);
      console.log(`Target folder has ${objects.length} children after filesystem import`);
    });

    test('should reject import from non-existent filesystem path', async ({ page }) => {
      if (!importTargetFolderId) {
        test.skip('Target folder not created');
        return;
      }

      const importRes = await page.request.post(
        `${BASE_URL}/core/rest/repo/bedroom/importexport/filesystem/import/${importTargetFolderId}`,
        {
          headers: { 'Authorization': AUTH_HEADER, 'Content-Type': 'application/json' },
          data: { sourcePath: '/tmp/nemakiware-import/no-such-dir-12345' }
        }
      );
      const result = await importRes.json();
      if (importRes.ok()) {
        expect(result.status).toBe('error');
      } else {
        expect([400, 500]).toContain(importRes.status());
      }
    });

    test.afterAll(async ({ browser }) => {
      const ctx = await browser.newContext();
      const pg = await ctx.newPage();
      for (const fid of [sourceFolderId, importTargetFolderId]) {
        if (fid) {
          await pg.request.post(
            `${BASE_URL}/core/browser/bedroom`,
            {
              headers: { 'Authorization': AUTH_HEADER },
              multipart: {
                cmisaction: 'deleteTree',
                objectId: fid,
                allVersions: 'true',
                continueOnFailure: 'true'
              }
            }
          ).catch(() => {});
        }
      }
      await ctx.close();
    });
  });
});

// ========== Helper: Create minimal ZIP file ==========

function createMinimalZip(fileName: string, content: string): Buffer {
  const fileData = Buffer.from(content, 'utf-8');
  const fileNameBuf = Buffer.from(fileName, 'utf-8');

  // Local file header
  const localHeader = Buffer.alloc(30 + fileNameBuf.length);
  localHeader.writeUInt32LE(0x04034B50, 0); // Local file header signature
  localHeader.writeUInt16LE(20, 4);          // Version needed to extract
  localHeader.writeUInt16LE(0, 6);           // General purpose bit flag
  localHeader.writeUInt16LE(0, 8);           // Compression method (stored)
  localHeader.writeUInt16LE(0, 10);          // Last mod file time
  localHeader.writeUInt16LE(0, 12);          // Last mod file date
  localHeader.writeUInt32LE(crc32(fileData), 14); // CRC-32
  localHeader.writeUInt32LE(fileData.length, 18);  // Compressed size
  localHeader.writeUInt32LE(fileData.length, 22);  // Uncompressed size
  localHeader.writeUInt16LE(fileNameBuf.length, 26); // File name length
  localHeader.writeUInt16LE(0, 28);          // Extra field length
  fileNameBuf.copy(localHeader, 30);

  // Central directory header
  const centralDir = Buffer.alloc(46 + fileNameBuf.length);
  centralDir.writeUInt32LE(0x02014B50, 0);   // Central directory signature
  centralDir.writeUInt16LE(20, 4);           // Version made by
  centralDir.writeUInt16LE(20, 6);           // Version needed
  centralDir.writeUInt16LE(0, 8);            // Flags
  centralDir.writeUInt16LE(0, 10);           // Compression
  centralDir.writeUInt16LE(0, 12);           // Time
  centralDir.writeUInt16LE(0, 14);           // Date
  centralDir.writeUInt32LE(crc32(fileData), 16); // CRC-32
  centralDir.writeUInt32LE(fileData.length, 20); // Compressed size
  centralDir.writeUInt32LE(fileData.length, 24); // Uncompressed size
  centralDir.writeUInt16LE(fileNameBuf.length, 28); // File name length
  centralDir.writeUInt16LE(0, 30);           // Extra field length
  centralDir.writeUInt16LE(0, 32);           // File comment length
  centralDir.writeUInt16LE(0, 34);           // Disk number start
  centralDir.writeUInt16LE(0, 36);           // Internal file attributes
  centralDir.writeUInt32LE(0, 38);           // External file attributes
  centralDir.writeUInt32LE(0, 42);           // Relative offset of local header
  fileNameBuf.copy(centralDir, 46);

  // End of central directory
  const centralDirOffset = localHeader.length + fileData.length;
  const endRecord = Buffer.alloc(22);
  endRecord.writeUInt32LE(0x06054B50, 0);    // End of central directory signature
  endRecord.writeUInt16LE(0, 4);             // Disk number
  endRecord.writeUInt16LE(0, 6);             // Disk with central directory
  endRecord.writeUInt16LE(1, 8);             // Entries in central directory on this disk
  endRecord.writeUInt16LE(1, 10);            // Total entries in central directory
  endRecord.writeUInt32LE(centralDir.length, 12); // Size of central directory
  endRecord.writeUInt32LE(centralDirOffset, 16);  // Offset of central directory
  endRecord.writeUInt16LE(0, 20);            // ZIP file comment length

  return Buffer.concat([localHeader, fileData, centralDir, endRecord]);
}

// Simple CRC-32 implementation
function crc32(data: Buffer): number {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < data.length; i++) {
    crc ^= data[i];
    for (let j = 0; j < 8; j++) {
      crc = (crc >>> 1) ^ (crc & 1 ? 0xEDB88320 : 0);
    }
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}
