import { test, expect } from '@playwright/test';

const withBackend = process.env.E2E_WITH_BACKEND === 'true';

test.describe('Documents - backend', () => {
  test.skip(!withBackend, 'Requires backend running (E2E_WITH_BACKEND=true)');

  test('after login, documents view loads and lists container is visible', async ({ page }) => {
    await page.goto('./');

    const repoSelect = page.getByRole('combobox', { name: 'リポジトリ' });
    await repoSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'bedroom' }).click();

    await page.getByLabel('ユーザー名').fill('admin');
    await page.getByLabel('パスワード').fill('admin');
    await page.getByRole('button', { name: 'ログイン', exact: true }).click();

    await page.waitForURL(/\/documents/);

    const table = page.locator('.ant-table, .ant-list, [role="table"]');
    await expect(table).toBeVisible();
  });
});
