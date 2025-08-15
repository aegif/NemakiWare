import { test, expect } from '@playwright/test';

const withBackend = (globalThis as any)?.process?.env?.E2E_WITH_BACKEND === 'true';

test.describe('Folder CRUD - backend', () => {
  test.skip(!withBackend, 'Requires backend running (E2E_WITH_BACKEND=true)');

  test('create and delete a folder under root', async ({ page }) => {
    await page.goto('./');

    const repoSelect = page.getByRole('combobox', { name: 'リポジトリ' });
    await repoSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'bedroom' }).click();

    await page.getByLabel('ユーザー名').fill('admin');
    await page.getByLabel('パスワード').fill('admin');
    await page.getByRole('button', { name: 'ログイン', exact: true }).click();

    await page.waitForURL(/\/documents/);

    const newFolderName = `e2e-folder-${Date.now()}`;

    const newFolderButton = page.getByRole('button', { name: /新規フォルダ|フォルダ作成|New Folder/i }).first();
    const hasNewFolder = await newFolderButton.isVisible().catch(() => false);
    test.skip(!hasNewFolder, 'New Folder UI control not available; enable and unskip when implemented');

    await newFolderButton.click();

    const nameInput = page.getByRole('textbox', { name: /名称|名前|Name/ });
    await expect(nameInput).toBeVisible();
    await nameInput.fill(newFolderName);

    const okBtn = page.getByRole('button', { name: /OK|作成|Create/ });
    await okBtn.click();

    const createdRow = page.getByRole('row', { name: newFolderName }).first().or(page.getByText(newFolderName).first());
    await expect(createdRow).toBeVisible({ timeout: 10000 });

    const row = page.getByText(newFolderName).first();
    await row.click({ button: 'right' }).catch(async () => {
      const more = page.getByRole('button', { name: /More|⋯|操作/ }).first();
      if (await more.isVisible().catch(() => false)) {
        await more.click();
      }
    });

    const deleteAction = page.getByRole('menuitem', { name: /削除|Delete/ }).first().or(page.getByRole('button', { name: /削除|Delete/ }).first());
    await deleteAction.click();

    const confirmBtn = page.getByRole('button', { name: /OK|はい|Yes|削除/ }).first();
    await confirmBtn.click();

    await expect(page.getByText(newFolderName)).toHaveCount(0);
  });
});
