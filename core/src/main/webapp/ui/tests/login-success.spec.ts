import { test, expect } from '@playwright/test';

const withBackend = process.env.E2E_WITH_BACKEND === 'true';

test.describe('Backend-enabled', () => {
  test.skip(!withBackend, 'Requires backend running (E2E_WITH_BACKEND=true)');

  test('login with admin and navigate to documents', async ({ page }) => {
    await page.goto('./');

    const repoSelect = page.getByRole('combobox', { name: 'リポジトリ' });
    await repoSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'bedroom' }).click();
    await page.getByLabel('ユーザー名').fill('admin');
    await page.getByLabel('パスワード').fill('admin');
    await page.getByRole('button', { name: 'ログイン', exact: true }).click();

    await page.waitForURL(/\/documents/);
    await expect(page.getByRole('button', { name: 'ログイン', exact: true })).toHaveCount(0);
  });
});
