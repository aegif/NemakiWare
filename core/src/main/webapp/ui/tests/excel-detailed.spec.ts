import { test, expect } from '@playwright/test';

test('Excel preview detailed debug', async ({ page }) => {
  page.on('console', msg => console.log(`[Browser ${msg.type()}] ${msg.text()}`));
  page.on('pageerror', err => console.log(`[Page Error] ${err.message}`));

  await page.goto('http://localhost:8080/core/ui/');
  await page.waitForSelector('input[placeholder="ユーザー名"]', { timeout: 30000 });
  await page.fill('input[placeholder="ユーザー名"]', 'admin');
  await page.fill('input[placeholder="パスワード"]', 'admin');
  await page.click('button[type="submit"]');
  await page.waitForSelector('.ant-table-tbody', { timeout: 30000 });
  await page.waitForTimeout(3000);

  // Find and click Excel file
  const xlsxCell = page.locator('td:has-text("xlsx")').first();
  await expect(xlsxCell).toBeVisible({ timeout: 10000 });
  const row = xlsxCell.locator('xpath=ancestor::tr');
  await row.locator('button.ant-btn-link').first().click();
  await page.waitForTimeout(2000);

  // Click Preview tab
  await page.locator('.ant-tabs-tab').filter({ hasText: 'プレビュー' }).click();

  // Wait for loading to finish and PDF to load
  console.log('Waiting for preview to load...');
  await page.waitForTimeout(10000);

  // Check for react-pdf elements (current implementation uses react-pdf, not iframe)
  const pdfDocument = page.locator('.react-pdf__Document');
  const pdfPage = page.locator('.react-pdf__Page');
  const pdfCanvas = page.locator('.react-pdf__Page__canvas');

  const hasDocument = await pdfDocument.isVisible();
  const hasPage = await pdfPage.isVisible();
  const hasCanvas = await pdfCanvas.isVisible();

  console.log('PDF Document visible:', hasDocument);
  console.log('PDF Page visible:', hasPage);
  console.log('PDF Canvas visible:', hasCanvas);

  // Take screenshot
  await page.screenshot({ path: '/tmp/excel-debug-1-preview.png', fullPage: true });

  // Check for toolbar
  const toolbarButtons = page.locator('button:has-text("前へ"), button:has-text("次へ"), button:has-text("拡大"), button:has-text("縮小")');
  const hasToolbar = await toolbarButtons.first().isVisible();
  console.log('Toolbar visible:', hasToolbar);

  // Also check for any error messages or fallback UI
  const errorAlert = page.locator('.ant-alert-error');
  const loadingSpinner = page.locator('.ant-spin');

  const errorVisible = await errorAlert.isVisible();
  const loadingVisible = await loadingSpinner.isVisible();
  console.log('Error alert visible:', errorVisible);
  console.log('Loading spinner visible:', loadingVisible);

  // Success: either canvas is visible (PDF rendered) or toolbar is visible
  const success = hasCanvas || hasDocument || hasToolbar;
  console.log(success ? '✅ Excel PDF preview rendered successfully!' : '❌ Excel PDF preview failed to render');

  expect(success).toBe(true);
});
