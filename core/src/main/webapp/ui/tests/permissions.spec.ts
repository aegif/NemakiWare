import { test, expect } from '@playwright/test';

const withBackend = (globalThis as any)?.process?.env?.E2E_WITH_BACKEND === 'true';

test.describe('Permissions read - backend', () => {
  test.skip(!withBackend, 'Requires backend running (E2E_WITH_BACKEND=true)');

  test('open a document details and view permissions panel if available', async ({ page }) => {
    await page.goto('./');

    const repoSelect = page.getByRole('combobox', { name: 'リポジトリ' });
    await repoSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'bedroom' }).click();

    await page.getByLabel('ユーザー名').fill('admin');
    await page.getByLabel('パスワード').fill('admin');
    await page.getByRole('button', { name: 'ログイン', exact: true }).click();

    await page.waitForURL(/\/documents/);

    const firstRow = page.locator('.ant-table-row, [role="row"]').nth(1);
    const hasRow = await firstRow.isVisible().catch(() => false);
    test.skip(!hasRow, 'No document rows visible to open details');

    await firstRow.click();

    const detailsBtn = page.getByRole('button', { name: /詳細|Details|プロパティ/ }).first();
    const hasDetails = await detailsBtn.isVisible().catch(() => false);
    test.skip(!hasDetails, 'Details UI control not available; enable and unskip when implemented');

    await detailsBtn.click();

    const permPanel = page.getByText(/権限|Permissions/).first().or(page.locator('[data-testid="permissions-panel"]').first());
    await expect(permPanel).toBeVisible();
  });
});
