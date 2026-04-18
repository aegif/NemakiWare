/**
 * Capture screenshots for in-app help pages.
 * Run: npx tsx scripts/capture-help-screenshots.ts
 */
import { chromium } from 'playwright';
import * as fs from 'fs';
import * as path from 'path';

const BASE = 'http://localhost:8080/core/ui';
const OUT = path.resolve(__dirname, '..', 'public', 'help-images');

async function main() {
  fs.mkdirSync(OUT, { recursive: true });

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    httpCredentials: { username: 'admin', password: 'admin' },
  });
  const page = await context.newPage();

  // Login
  await page.goto(`${BASE}/login`);
  await page.locator('input[placeholder*="ユーザー"]').fill('admin');
  await page.locator('input[placeholder*="パスワード"]').fill('admin');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/documents/, { timeout: 15000 });
  await page.waitForTimeout(2000);

  // 1. Document list
  await page.screenshot({ path: path.join(OUT, '01-document-list.png') });
  console.log('✓ 01-document-list.png');

  // 2. Folder tree
  const folderTree = page.locator('.ant-tree');
  if (await folderTree.count() > 0) {
    await folderTree.screenshot({ path: path.join(OUT, '02-folder-tree.png') });
    console.log('✓ 02-folder-tree.png');
  }

  // 3. Upload dialog
  const uploadBtn = page.locator('button').filter({ hasText: /アップロード|Upload/ }).first();
  if (await uploadBtn.count() > 0) {
    await uploadBtn.click();
    await page.waitForTimeout(1000);
    const modal = page.locator('.ant-modal:visible');
    if (await modal.count() > 0) {
      await modal.screenshot({ path: path.join(OUT, '03-upload-dialog.png') });
      console.log('✓ 03-upload-dialog.png');
      // Close modal
      const closeBtn = page.locator('.ant-modal-close').first();
      if (await closeBtn.count() > 0) await closeBtn.click();
      await page.waitForTimeout(500);
    }
  }

  // 4. Document detail (click first document)
  const firstDoc = page.locator('.ant-table-tbody tr').first();
  if (await firstDoc.count() > 0) {
    const docLink = firstDoc.locator('a, span.ant-typography').first();
    if (await docLink.count() > 0) {
      await docLink.click();
      await page.waitForTimeout(3000);
      await page.screenshot({ path: path.join(OUT, '04-document-detail.png') });
      console.log('✓ 04-document-detail.png');

      // 5. Preview tab
      const previewTab = page.locator('.ant-tabs-tab').filter({ hasText: /プレビュー|Preview/ });
      if (await previewTab.count() > 0) {
        await previewTab.click();
        await page.waitForTimeout(3000);
        await page.screenshot({ path: path.join(OUT, '05-preview.png') });
        console.log('✓ 05-preview.png');
      }

      // 6. Version tab
      const versionTab = page.locator('.ant-tabs-tab').filter({ hasText: /バージョン|Version/ });
      if (await versionTab.count() > 0) {
        await versionTab.click();
        await page.waitForTimeout(1000);
        await page.screenshot({ path: path.join(OUT, '06-version-history.png') });
        console.log('✓ 06-version-history.png');
      }
    }
  }

  // 7. Search page
  await page.goto(`${BASE}/#/search`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '07-search.png') });
  console.log('✓ 07-search.png');

  // 8. User management (admin)
  await page.goto(`${BASE}/#/users`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '08-user-management.png') });
  console.log('✓ 08-user-management.png');

  // 9. Group management
  await page.goto(`${BASE}/#/groups`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '09-group-management.png') });
  console.log('✓ 09-group-management.png');

  // 10. Type management
  await page.goto(`${BASE}/#/types`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '10-type-management.png') });
  console.log('✓ 10-type-management.png');

  // 11. Archive
  await page.goto(`${BASE}/#/archive`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '11-archive.png') });
  console.log('✓ 11-archive.png');

  // 12. Solr management
  await page.goto(`${BASE}/#/solr`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '12-solr-management.png') });
  console.log('✓ 12-solr-management.png');

  // 13. Integration settings
  await page.goto(`${BASE}/#/integration-settings`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '13-integration-settings.png') });
  console.log('✓ 13-integration-settings.png');

  // 14. Audit dashboard
  await page.goto(`${BASE}/#/audit-dashboard`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '14-audit-dashboard.png') });
  console.log('✓ 14-audit-dashboard.png');

  // 15. Help page itself
  await page.goto(`${BASE}/#/help`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '15-help-page.png') });
  console.log('✓ 15-help-page.png');

  // 16. Login page (logout first)
  await page.goto(`${BASE}/login`);
  await page.waitForTimeout(1000);
  await page.screenshot({ path: path.join(OUT, '00-login.png') });
  console.log('✓ 00-login.png');

  await browser.close();
  console.log(`\nAll screenshots saved to ${OUT}`);
}

main().catch(e => { console.error(e); process.exit(1); });
