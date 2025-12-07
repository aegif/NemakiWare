import { test, expect } from '@playwright/test';

test('Excel preview should render with react-pdf', async ({ page }) => {
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

  // Wait for rendition to load
  console.log('Waiting for PDF preview to load...');
  await page.waitForTimeout(8000);

  // Take screenshot
  await page.screenshot({ path: '/tmp/excel-reactpdf-preview.png', fullPage: true });

  // Check for react-pdf elements
  // react-pdf renders PDFs using canvas elements inside a div with class "react-pdf__Document"
  const pdfDocument = page.locator('.react-pdf__Document');
  const pdfPage = page.locator('.react-pdf__Page');
  const pdfCanvas = page.locator('.react-pdf__Page__canvas');

  const hasDocument = await pdfDocument.isVisible();
  const hasPage = await pdfPage.isVisible();
  const hasCanvas = await pdfCanvas.isVisible();

  console.log('PDF Document visible:', hasDocument);
  console.log('PDF Page visible:', hasPage);
  console.log('PDF Canvas visible:', hasCanvas);

  // Check for toolbar buttons (react-pdf toolbar)
  const prevButton = page.locator('button:has-text("前へ")');
  const nextButton = page.locator('button:has-text("次へ")');
  const zoomInButton = page.locator('button:has-text("拡大")');
  const zoomOutButton = page.locator('button:has-text("縮小")');

  console.log('Prev button visible:', await prevButton.isVisible());
  console.log('Next button visible:', await nextButton.isVisible());
  console.log('Zoom buttons visible:', await zoomInButton.isVisible(), await zoomOutButton.isVisible());

  // Check page counter
  const pageCounter = page.locator('text=/\\d+ \\/ \\d+/');
  const pageCounterText = await pageCounter.textContent();
  console.log('Page counter:', pageCounterText);

  // Check for any error messages
  const errorAlert = page.locator('.ant-alert-error');
  const fallbackUI = page.locator('text=オフィス文書のプレビュー');
  const loadingSpinner = page.locator('.ant-spin');

  console.log('Error alert visible:', await errorAlert.isVisible());
  console.log('Fallback UI visible:', await fallbackUI.isVisible());
  console.log('Loading spinner visible:', await loadingSpinner.isVisible());

  // Success criteria: either canvas is visible (PDF rendered) or toolbar is visible
  const success = hasCanvas || (await prevButton.isVisible());
  console.log(success ? '✅ PDF preview rendered successfully!' : '❌ PDF preview failed to render');

  expect(success).toBe(true);
});
