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

  // 16. Permissions page (via document ID)
  // Find a document ID first
  const childrenRes = await page.request.get(
    'http://localhost:8080/core/browser/bedroom/root?cmisselector=children&maxItems=20',
    { headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') } },
  );
  const childrenData = await childrenRes.json();
  const docObj = childrenData.objects?.find((o: any) =>
    o.object.properties['cmis:baseTypeId'].value === 'cmis:document');
  if (docObj) {
    const docId = docObj.object.properties['cmis:objectId'].value;

    // 04. Document detail
    await page.goto(`${BASE}/#/documents/${docId}`);
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(OUT, '04-document-detail.png') });
    console.log('✓ 04-document-detail.png');

    // 05. Preview tab
    const previewTab = page.locator('.ant-tabs-tab').filter({ hasText: /プレビュー|Preview/ });
    if (await previewTab.count() > 0) {
      await previewTab.click();
      await page.waitForTimeout(3000);
      await page.screenshot({ path: path.join(OUT, '05-preview.png') });
      console.log('✓ 05-preview.png');
    }

    // 06. Version history tab
    const versionTab = page.locator('.ant-tabs-tab').filter({ hasText: /バージョン|Version/ });
    if (await versionTab.count() > 0) {
      await versionTab.click();
      await page.waitForTimeout(2000);
      await page.screenshot({ path: path.join(OUT, '06-version-history.png') });
      console.log('✓ 06-version-history.png');
    }

    // 18. Secondary type tab
    const secondaryTab = page.locator('.ant-tabs-tab').filter({ hasText: /セカンダリ|Secondary/ });
    if (await secondaryTab.count() > 0) {
      await secondaryTab.click();
      await page.waitForTimeout(2000);
      await page.screenshot({ path: path.join(OUT, '18-secondary-type.png') });
      console.log('✓ 18-secondary-type.png');
    }

    // 19. Relationship tab
    const relTab = page.locator('.ant-tabs-tab').filter({ hasText: /リレーション|Relation/ });
    if (await relTab.count() > 0) {
      await relTab.click();
      await page.waitForTimeout(2000);
      await page.screenshot({ path: path.join(OUT, '19-relationship.png') });
      console.log('✓ 19-relationship.png');
    }

    // 16. Permissions page
    await page.goto(`${BASE}/#/permissions/${docId}`);
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(OUT, '16-permissions.png') });
    console.log('✓ 16-permissions.png');
  }

  // 17. Account settings
  await page.goto(`${BASE}/#/account`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '17-account-settings.png') });
  console.log('✓ 17-account-settings.png');

  // 20. Webhook management
  await page.goto(`${BASE}/#/webhooks`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '20-webhook.png') });
  console.log('✓ 20-webhook.png');

  // 21. Config viewer
  await page.goto(`${BASE}/#/config-viewer`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '21-config-viewer.png') });
  console.log('✓ 21-config-viewer.png');

  // 22. Import/Export
  await page.goto(`${BASE}/#/filesystem-import-export`);
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(OUT, '22-import-export.png') });
  console.log('✓ 22-import-export.png');

  // 00. Login page (fresh context without credentials)
  const loginPage = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await loginPage.goto(`${BASE}/`);
  await loginPage.waitForTimeout(2000);
  await loginPage.screenshot({ path: path.join(OUT, '00-login.png') });
  console.log('✓ 00-login.png');
  await loginPage.close();

  await browser.close();
  console.log(`\nAll screenshots saved to ${OUT}`);
}

main().catch(e => { console.error(e); process.exit(1); });
