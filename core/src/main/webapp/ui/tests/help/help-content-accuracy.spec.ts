import { test, expect } from '@playwright/test';

/**
 * Help page content accuracy tests.
 *
 * Verifies that HelpPage content matches the actual UI behavior:
 * - ACL is accessed via button, not tab
 * - Folder tree uses re-click, not double-click
 * - Cloud import buttons are provider-specific and conditional
 * - Authentication methods are described as conditional
 * - Feature toggle alert is shown in admin guide
 * - Screenshots load correctly
 */

const BASE = 'http://localhost:8080/core/ui';

/** Expand a Collapse panel by header substring and return its full text. */
async function expandAndGetText(page: import('@playwright/test').Page, headerSubstring: string): Promise<string | null> {
  const panels = page.locator('.ant-collapse-item');
  for (let i = 0; i < await panels.count(); i++) {
    const header = await panels.nth(i).locator('.ant-collapse-header').textContent();
    if (header && header.includes(headerSubstring)) {
      const isOpen = await panels.nth(i).evaluate(el => el.classList.contains('ant-collapse-item-active'));
      if (!isOpen) {
        await panels.nth(i).locator('.ant-collapse-header').click();
        await page.waitForTimeout(800);
      }
      return await panels.nth(i).textContent() || '';
    }
  }
  return null;
}

test.describe('Help Page Content Accuracy', () => {

  test.beforeEach(async ({ page }) => {
    // Login for authenticated tests (individual tests that need public access use browser directly)
    await page.goto(`${BASE}/`);
    await page.waitForTimeout(2000);
    const userInput = page.locator('input[placeholder*="ユーザー"]');
    if (await userInput.count() > 0) {
      await userInput.fill('admin');
      await page.locator('input[placeholder*="パスワード"]').fill('admin');
      await page.locator('button[type="submit"]').click();
      await page.waitForTimeout(3000);
    }
  });

  test('should be accessible without login (public)', async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await context.newPage();
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(3000);

    await expect(page.locator('h2')).toBeVisible();
    expect(await page.locator('.ant-layout-sider').count()).toBe(0);
    expect(await page.locator('.ant-tabs-tab').filter({ hasText: /管理者/ }).count()).toBe(0);
    await context.close();
  });

  test('should have sidebar and admin tab when authenticated', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    expect(await page.locator('.ant-layout-sider').count()).toBeGreaterThan(0);
    expect(await page.locator('.ant-tabs-tab').filter({ hasText: '管理者ガイド' }).count()).toBe(1);
  });

  test('login section mentions conditional auth methods', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    const text = await expandAndGetText(page, 'ログイン');
    expect(text).toContain('場合のみ');
  });

  test('folder tree says re-click, not double-click', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    const text = await expandAndGetText(page, 'ドキュメント一覧');
    expect(text).toContain('もう一度クリック');
    expect(text).not.toContain('ダブルクリック');
  });

  test('ACL section says button, not tab', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    const text = await expandAndGetText(page, '権限（ACL）');
    expect(text).toContain('ボタン');
    expect(text).toContain('独立した');
    expect(text).toContain('継承');
  });

  test('cloud section mentions provider-specific conditional buttons', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    const text = await expandAndGetText(page, 'クラウドドライブ');
    expect(text).toContain('Google Drive');
    expect(text).toContain('OneDrive');
    expect(text).toContain('スキップ');
  });

  test('real UI: ACL is button not tab', async ({ page }) => {
    // Navigate to a document detail page
    const res = await page.request.get(
      'http://localhost:8080/core/browser/bedroom/root?cmisselector=children&maxItems=20',
      { headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') } },
    );
    const data = await res.json();
    const docObj = data.objects?.find((o: any) => o.object.properties['cmis:baseTypeId'].value === 'cmis:document');
    if (!docObj) return; // no document to test

    const docId = docObj.object.properties['cmis:objectId'].value;
    await page.goto(`${BASE}/#/documents/${docId}`);
    await page.waitForTimeout(3000);

    expect(await page.locator('button:has-text("権限管理")').count()).toBeGreaterThan(0);
    expect(await page.locator('.ant-tabs-tab').filter({ hasText: /^権限$/ }).count()).toBe(0);
  });

  test('admin guide shows feature toggle alert', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    await page.locator('.ant-tabs-tab').filter({ hasText: '管理者ガイド' }).click();
    await page.waitForTimeout(1000);
    const content = await page.locator('.ant-tabs-tabpane-active').textContent() || '';
    expect(content).toContain('無効化');
  });

  test('help images load correctly', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    // Expand all panels
    const panels = page.locator('.ant-collapse-item');
    for (let i = 0; i < await panels.count(); i++) {
      const active = await panels.nth(i).evaluate(el => el.classList.contains('ant-collapse-item-active'));
      if (!active) { await panels.nth(i).locator('.ant-collapse-header').click(); await page.waitForTimeout(200); }
    }
    await page.waitForTimeout(500);
    const imgs = page.locator('img[src*="help-images"]');
    expect(await imgs.count()).toBeGreaterThanOrEqual(8);
    const w = await imgs.first().evaluate(el => (el as HTMLImageElement).naturalWidth);
    expect(w).toBeGreaterThan(0);
  });
});
