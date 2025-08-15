import { test, expect } from '@playwright/test';

test('UI root loads', async ({ page }) => {
  await page.goto('./');
  await expect(page.locator('body')).toBeVisible();
});
