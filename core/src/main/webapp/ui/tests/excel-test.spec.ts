import { test, expect } from '@playwright/test';

test('Excel preview should show PDF content', async ({ page }) => {
  page.on('console', msg => console.log(`[Browser] ${msg.type()}: ${msg.text()}`));

  await page.goto('http://localhost:8080/core/ui/');
  await page.waitForSelector('input[placeholder="ユーザー名"]', { timeout: 30000 });
  await page.fill('input[placeholder="ユーザー名"]', 'admin');
  await page.fill('input[placeholder="パスワード"]', 'admin');
  await page.click('button[type="submit"]');
  await page.waitForSelector('.ant-table-tbody', { timeout: 30000 });
  await page.waitForTimeout(3000);

  // Click Excel file
  const xlsxCell = page.locator('td:has-text("xlsx")').first();
  await expect(xlsxCell).toBeVisible({ timeout: 10000 });
  const row = xlsxCell.locator('xpath=ancestor::tr');
  await row.locator('button.ant-btn-link').first().click();
  await page.waitForTimeout(2000);

  // Click Preview tab
  await page.locator('.ant-tabs-tab').filter({ hasText: 'プレビュー' }).click();

  // Wait longer for PDF to load
  console.log('Waiting for PDF to load...');
  await page.waitForTimeout(8000);

  // Check iframe details
  const pdfIframe = page.locator('iframe[title*="PDF Preview"]');
  const iframeVisible = await pdfIframe.isVisible();
  console.log('PDF iframe visible:', iframeVisible);

  if (iframeVisible) {
    const iframeSrc = await pdfIframe.getAttribute('src');
    console.log('Iframe src:', iframeSrc ? iframeSrc.substring(0, 50) + '...' : 'null');

    // Check iframe dimensions
    const box = await pdfIframe.boundingBox();
    console.log('Iframe dimensions:', box);

    // Take screenshot
    await page.screenshot({ path: '/tmp/excel-preview-detail.png', fullPage: true });

    // Check if blob URL is valid
    if (iframeSrc && iframeSrc.startsWith('blob:')) {
      console.log('✅ Blob URL created successfully');
    }
  }

  // Also check for any error states
  const errorAlert = page.locator('.ant-alert-error');
  const loadingSpinner = page.locator('.ant-spin');
  const fallbackUI = page.locator('text=オフィス文書のプレビュー');

  console.log('Error alert visible:', await errorAlert.isVisible());
  console.log('Loading spinner visible:', await loadingSpinner.isVisible());
  console.log('Fallback UI visible:', await fallbackUI.isVisible());

  expect(iframeVisible).toBe(true);
});
