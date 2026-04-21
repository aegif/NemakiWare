import { test, expect } from '@playwright/test';

/**
 * Help page content accuracy tests.
 *
 * Verifies that HelpPage content matches the actual UI behavior.
 * Language-independent: uses regex patterns matching both ja and en.
 */

const BASE = 'http://localhost:8080/core/ui';

/** Expand a Collapse panel by header substring and return its full text. */
async function expandAndGetText(page: import('@playwright/test').Page, headerPattern: RegExp): Promise<string | null> {
  const panels = page.locator('.ant-collapse-item');
  for (let i = 0; i < await panels.count(); i++) {
    const header = await panels.nth(i).locator('.ant-collapse-header').textContent();
    if (header && headerPattern.test(header)) {
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
    await page.goto(`${BASE}/`);
    await page.waitForTimeout(2000);
    // Login — match both Japanese and English placeholders
    const userInput = page.locator('input[placeholder*="ユーザー"], input[placeholder*="username" i]');
    if (await userInput.count() > 0) {
      await userInput.first().fill('admin');
      const pwInput = page.locator('input[placeholder*="パスワード"], input[placeholder*="password" i]');
      await pwInput.first().fill('admin');
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
    // No admin tab — match both languages
    expect(await page.locator('.ant-tabs-tab').filter({ hasText: /管理者|Admin Guide/i }).count()).toBe(0);
    await context.close();
  });

  test('should have sidebar and admin tab when authenticated', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    expect(await page.locator('.ant-layout-sider').count()).toBeGreaterThan(0);
    expect(await page.locator('.ant-tabs-tab').filter({ hasText: /管理者ガイド|Admin Guide/i }).count()).toBe(1);
  });

  test('login section mentions conditional auth methods', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    const text = await expandAndGetText(page, /ログイン|Login/i);
    // Should mention conditions: 場合のみ (ja) or "only shown when" (en)
    expect(text).toMatch(/場合のみ|only shown when|server.*enabled/i);
  });

  test('folder tree says re-click, not double-click', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    const text = await expandAndGetText(page, /ドキュメント一覧|Document List/i);
    expect(text).toMatch(/もう一度クリック|already-selected.*again/i);
    expect(text).not.toMatch(/ダブルクリック|double.click/i);
  });

  test('ACL section says button, not tab', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    const text = await expandAndGetText(page, /権限|Permission|ACL/i);
    expect(text).toMatch(/ボタン|button/i);
    expect(text).toMatch(/独立した|independent/i);
    expect(text).toMatch(/継承|inherit/i);
  });

  test('cloud section mentions provider-specific conditional buttons', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    const text = await expandAndGetText(page, /クラウド|Cloud/i);
    expect(text).toContain('Google Drive');
    expect(text).toContain('OneDrive');
    expect(text).toMatch(/スキップ|skip/i);
  });

  test('real UI: ACL is button not tab', async ({ page }) => {
    const res = await page.request.get(
      'http://localhost:8080/core/browser/bedroom/root?cmisselector=children&maxItems=20',
      { headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') } },
    );
    const data = await res.json();
    const docObj = data.objects?.find((o: any) => o.object.properties['cmis:baseTypeId'].value === 'cmis:document');
    if (!docObj) return;

    const docId = docObj.object.properties['cmis:objectId'].value;
    await page.goto(`${BASE}/#/documents/${docId}`);
    await page.waitForTimeout(3000);

    expect(await page.locator('button').filter({ hasText: /権限管理|Permission/i }).count()).toBeGreaterThan(0);
    // No standalone "権限" or "Permissions" tab (tabs are プロパティ/セカンダリ/プレビュー/バージョン/リレーション)
    const tabTexts: string[] = [];
    const tabs = page.locator('.ant-tabs-tab');
    for (let i = 0; i < await tabs.count(); i++) {
      tabTexts.push(await tabs.nth(i).textContent() || '');
    }
    const hasPermTab = tabTexts.some(t => /^権限$|^Permissions$/i.test(t.trim()));
    expect(hasPermTab).toBe(false);
  });

  test('admin guide shows feature toggle alert', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
    await page.locator('.ant-tabs-tab').filter({ hasText: /管理者ガイド|Admin Guide/i }).click();
    await page.waitForTimeout(1000);
    const content = await page.locator('.ant-tabs-tabpane-active').textContent() || '';
    expect(content).toMatch(/無効化|disabled|feature toggle/i);
  });

  test('help images load correctly', async ({ page }) => {
    await page.goto(`${BASE}/#/help`);
    await page.waitForTimeout(2000);
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
