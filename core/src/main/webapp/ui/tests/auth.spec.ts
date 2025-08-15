import { test, expect } from '@playwright/test';

test.describe('Authentication UI', () => {
  test('Login page renders with required fields and validates inputs', async ({ page }) => {
    await page.goto('./');

    await expect(page.getByRole('img', { name: 'NemakiWare' })).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'リポジトリ' })).toBeVisible();
    await expect(page.getByLabel('ユーザー名')).toBeVisible();
    await expect(page.getByLabel('パスワード')).toBeVisible();
    await expect(page.getByRole('button', { name: 'ログイン', exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'ログイン', exact: true }).click();
    await expect(page.getByText('リポジトリを選択してください')).toBeVisible();
    await expect(page.getByText('ユーザー名を入力してください')).toBeVisible();
    await expect(page.getByText('パスワードを入力してください')).toBeVisible();
  });

  test('Repository select contains fallback option when backend is unavailable', async ({ page }) => {
    await page.goto('./');

    const repoSelect = page.getByRole('combobox', { name: 'リポジトリ' });

    let found = false;
    for (let i = 0; i < 5 && !found; i++) {
      await repoSelect.click();
      await page.waitForTimeout(500);
      const count = await page.locator('.ant-select-dropdown .ant-select-item-option').count();
      if (count > 0) {
        found = true;
        break;
      }
      await repoSelect.click();
      await page.waitForTimeout(500);
    }

    await page.waitForSelector('.ant-select-dropdown .ant-select-item-option', { timeout: 20000 });

    const option = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'bedroom' });
    await expect(option).toBeVisible({ timeout: 20000 });
    await option.click();
  });

  test('Protected route shows login (unauthenticated)', async ({ page }) => {
    await page.goto('./documents');

    await expect(page.getByRole('button', { name: 'ログイン', exact: true })).toBeVisible();
  });
});
