import { test, expect } from '@playwright/test';

const withBackend = (globalThis as any)?.process?.env?.E2E_WITH_BACKEND === 'true';

test.describe('Search - backend', () => {
  test.skip(!withBackend, 'Requires backend running (E2E_WITH_BACKEND=true)');

  test('navigate to search after login and see search UI', async ({ page }) => {
    await page.goto('./');

    const repoSelect = page.getByRole('combobox', { name: 'リポジトリ' });
    await repoSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'bedroom' }).click();

    await page.getByLabel('ユーザー名').fill('admin');
    await page.getByLabel('パスワード').fill('admin');
    await page.getByRole('button', { name: 'ログイン', exact: true }).click();

    await page.waitForURL(/\/documents/);

    await page.goto('./search');

    const searchInput = page.getByRole('textbox').first();
    await expect(searchInput).toBeVisible();

    await searchInput.fill('test');
    const searchButton = page.getByRole('button', { name: /検索|Search/ });
    await searchButton.first().click();

    const results = page.locator('.ant-table, .ant-list, [role="table"]');
    await expect(results).toBeVisible();
  });
});
