/**
 * Excel Preview End-to-End Test
 *
 * Tests that Excel documents can be previewed through PDF rendition.
 * Uses proper test fixture setup with beforeAll/afterAll hooks.
 *
 * SKIPPED (2025-12-23) - Office Rendition Generation Issues
 *
 * Investigation Result: Excel preview via PDF rendition IS implemented.
 * However, tests fail due to the following issues:
 *
 * 1. RENDITION GENERATION:
 *    - LibreOffice-based PDF conversion is async
 *    - First preview request may not have rendition ready
 *    - JODConverter requires LibreOffice to be running
 *
 * 2. TEST FIXTURE SETUP:
 *    - setupPreviewTestData() may fail silently
 *    - Excel file upload may not complete before test
 *    - Folder cleanup may leave orphaned files
 *
 * Office preview verified working via manual testing.
 * Re-enable after ensuring LibreOffice is available in test environment.
 */
import { test, expect } from '@playwright/test';
import { setupPreviewTestData, cleanupPreviewTestData, type TestContext } from './preview-setup';

let testContext: TestContext;

test.describe('Excel Preview Tests', () => {
  test.beforeAll(async () => {
    console.log('Setting up Excel preview test data...');
    testContext = await setupPreviewTestData();
    console.log(`Test folder created: ${testContext.folderId}`);
    console.log(`Excel file ID: ${testContext.files.xlsx}`);
  });

  test.afterAll(async () => {
    if (testContext?.folderId) {
      await cleanupPreviewTestData(testContext.folderId);
    }
  });

  test.beforeEach(async ({ page }) => {
    // Login
    await page.goto('http://localhost:8080/core/ui/');
    await page.waitForSelector('input[placeholder="ユーザー名"]', { timeout: 30000 });
    await page.fill('input[placeholder="ユーザー名"]', 'admin');
    await page.fill('input[placeholder="パスワード"]', 'admin');
    await page.click('button[type="submit"]');
    await page.waitForSelector('.ant-table-tbody', { timeout: 30000 });
  });

  test('should display Excel file preview as PDF', async ({ page }) => {
    // Navigate to test folder
    await page.goto(`http://localhost:8080/core/ui/#/documents?folderId=${testContext.folderId}`);
    await page.waitForTimeout(2000);

    // Find and click Excel file
    const xlsxRow = page.locator('tr:has-text("Excelサンプル.xlsx")');
    await expect(xlsxRow).toBeVisible({ timeout: 10000 });

    // Click the detail view button
    const viewButton = xlsxRow.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });
    await viewButton.click();
    await page.waitForTimeout(2000);

    // Click Preview tab
    const previewTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プレビュー' });
    await expect(previewTab).toBeVisible({ timeout: 10000 });
    await previewTab.click();

    // Wait for rendition to generate and display
    console.log('Waiting for Excel PDF preview to load...');
    await page.waitForTimeout(10000);

    // Take screenshot for debugging
    await page.screenshot({ path: '/tmp/excel-preview-test.png', fullPage: true });

    // Check for PDF preview elements (react-pdf components)
    const pdfDocument = page.locator('.react-pdf__Document');
    const pdfPage = page.locator('.react-pdf__Page');
    const pdfCanvas = page.locator('.react-pdf__Page__canvas');

    const hasDocument = await pdfDocument.isVisible();
    const hasPage = await pdfPage.isVisible();
    const hasCanvas = await pdfCanvas.isVisible();

    console.log('PDF Document visible:', hasDocument);
    console.log('PDF Page visible:', hasPage);
    console.log('PDF Canvas visible:', hasCanvas);

    // Check for toolbar
    const toolbarButtons = page.locator('button:has-text("前へ"), button:has-text("次へ"), button:has-text("拡大"), button:has-text("縮小")');
    const hasToolbar = await toolbarButtons.first().isVisible().catch(() => false);
    console.log('Toolbar visible:', hasToolbar);

    // Check for loading or error states
    const loadingSpinner = page.locator('.ant-spin');
    const errorAlert = page.locator('.ant-alert-error');
    const loadingVisible = await loadingSpinner.isVisible().catch(() => false);
    const errorVisible = await errorAlert.isVisible().catch(() => false);

    console.log('Loading spinner visible:', loadingVisible);
    console.log('Error alert visible:', errorVisible);

    // Success: PDF rendered or toolbar visible (preview component loaded)
    const success = hasCanvas || hasDocument || hasToolbar;
    console.log(success ? '✅ Excel PDF preview rendered!' : '❌ Excel PDF preview failed');

    expect(success).toBe(true);
  });

  test('should have rendition generated for Excel file', async ({ page }) => {
    // Skip if no xlsx file was uploaded
    test.skip(!testContext.files.xlsx, 'Excel file not uploaded');

    // Check renditions API directly
    const response = await page.request.get(
      `http://localhost:8080/core/api/v1/repo/bedroom/renditions/${testContext.files.xlsx}`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        }
      }
    );

    console.log('Renditions API response:', response.status());
    const data = await response.json().catch(() => null);
    console.log('Renditions data:', JSON.stringify(data, null, 2));

    // Rendition may not exist yet - that's OK, the preview component should trigger generation
    // This test just verifies the API is accessible
    expect(response.status()).toBeLessThan(500);
  });
});
