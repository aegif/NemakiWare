import { test } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';
import { generateTestId } from './utils/test-helper';
import * as path from 'path';
import * as fs from 'fs';

test('debug upload operation with console capture', async ({ page }) => {
  // Capture browser console logs
  page.on('console', msg => {
    const type = msg.type();
    const text = msg.text();
    console.log(`[BROWSER ${type.toUpperCase()}]`, text);
  });

  // Capture page errors
  page.on('pageerror', error => {
    console.error('[BROWSER ERROR]', error);
  });

  const authHelper = new AuthHelper(page);

  // Login
  await authHelper.login();
  await page.waitForTimeout(2000);

  // Navigate to Type Management
  const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
  if (await adminMenu.count() > 0) {
    await adminMenu.click();
    await page.waitForTimeout(1000);
  }

  const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: /タイプ管理|Type Management/i });
  if (await typeManagementItem.count() > 0) {
    await typeManagementItem.click();
    await page.waitForTimeout(2000);
  }

  await page.waitForSelector('.ant-table', { timeout: 15000 });
  console.log('[TEST] Type Management page loaded');

  // Click import button
  const importButton = page.locator('button:has-text("ファイルからインポート")');
  console.log('[TEST] Import button found:', await importButton.count());
  await importButton.click();
  await page.waitForTimeout(1000);

  // Upload modal should appear
  const uploadModal = page.locator('.ant-modal:has-text("型定義ファイルのインポート")');
  console.log('[TEST] Upload modal visible:', await uploadModal.isVisible());

  // Create test file
  const testTypeDef = {
    id: `test:debugUpload${generateTestId()}`,
    displayName: 'Debug Upload Test',
    baseTypeId: 'cmis:document',
    propertyDefinitions: []
  };

  const tmpDir = require('os').tmpdir();
  const testFile = path.join(tmpDir, `debug-upload-${generateTestId()}.json`);
  fs.writeFileSync(testFile, JSON.stringify(testTypeDef, null, 2));
  console.log('[TEST] Created test file:', testFile);

  // Upload file
  const fileInput = uploadModal.locator('input[type="file"]');
  await fileInput.setInputFiles(testFile);
  await fileInput.dispatchEvent('change');
  await page.waitForTimeout(1000);

  console.log('[TEST] File uploaded, clicking import button');

  // Click import
  const importModalButton = uploadModal.locator('button:has-text("インポート")');
  await importModalButton.click();

  console.log('[TEST] Import button clicked, waiting 10 seconds to observe console logs...');

  // Wait to see what happens
  await page.waitForTimeout(10000);

  console.log('[TEST] Done waiting');

  // Cleanup
  fs.unlinkSync(testFile);
});
