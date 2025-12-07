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
  await page.waitForTimeout(6000);

  // Get iframe details
  const pdfIframe = page.locator('iframe[title*="PDF Preview"]');
  const iframeVisible = await pdfIframe.isVisible();
  console.log('Iframe visible:', iframeVisible);

  if (iframeVisible) {
    const iframeSrc = await pdfIframe.getAttribute('src');
    console.log('Iframe src:', iframeSrc);

    // Check if it's a blob URL and verify blob content
    if (iframeSrc && iframeSrc.startsWith('blob:')) {
      console.log('Blob URL detected, verifying content...');

      // Evaluate in browser context to fetch blob and check content
      const blobInfo = await page.evaluate(async (blobUrl) => {
        try {
          const response = await fetch(blobUrl);
          if (!response.ok) {
            return { error: `Fetch failed: ${response.status} ${response.statusText}` };
          }
          const blob = await response.blob();
          const arrayBuffer = await blob.arrayBuffer();
          const bytes = new Uint8Array(arrayBuffer);

          // Get first 20 bytes to check PDF signature
          const header = Array.from(bytes.slice(0, 20)).map(b => String.fromCharCode(b)).join('');
          const headerHex = Array.from(bytes.slice(0, 20)).map(b => b.toString(16).padStart(2, '0')).join(' ');

          return {
            size: blob.size,
            type: blob.type,
            header: header,
            headerHex: headerHex,
            isPdf: header.startsWith('%PDF'),
            firstBytes: bytes.slice(0, 50)
          };
        } catch (err: any) {
          return { error: `Exception: ${err.message}` };
        }
      }, iframeSrc);

      console.log('Blob info:', JSON.stringify(blobInfo, null, 2));

      if (blobInfo.isPdf) {
        console.log('✅ Valid PDF blob detected');
      } else if (blobInfo.error) {
        console.log('❌ Error fetching blob:', blobInfo.error);
      } else {
        console.log('❌ NOT a valid PDF - header:', blobInfo.header);
      }
    }

    // Check iframe frame content
    const frame = pdfIframe.contentFrame();
    if (frame) {
      console.log('Frame URL:', frame.url());
    }

    // Take screenshots at different stages
    await page.screenshot({ path: '/tmp/excel-debug-1-preview.png', fullPage: true });

    // Check iframe dimensions and content
    const box = await pdfIframe.boundingBox();
    console.log('Iframe bounding box:', box);
  }

  // Also check for any error messages or fallback UI
  const errorAlert = page.locator('.ant-alert-error');
  const fallbackUI = page.locator('text=オフィス文書のプレビュー');
  const loadingSpinner = page.locator('.ant-spin');

  console.log('Error alert visible:', await errorAlert.isVisible());
  console.log('Fallback UI visible:', await fallbackUI.isVisible());
  console.log('Loading spinner visible:', await loadingSpinner.isVisible());

  expect(iframeVisible).toBe(true);
});
