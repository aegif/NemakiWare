/**
 * Office Preview End-to-End Test
 *
 * Tests that Office documents (PowerPoint, Word, Excel) can be previewed
 * through PDF rendition generation and display.
 */
import { test, expect } from '@playwright/test';

// Test folder and document IDs
const TEST_FOLDER_ID = 'f98098cd35962619bad0dcf667069656'; // 手動検証用フォルダ

test.describe('Office Preview E2E Tests', () => {
  test.beforeEach(async ({ page }) => {
    // Login
    await page.goto('http://localhost:8080/core/ui/');
    await page.waitForSelector('input[placeholder="ユーザー名"]', { timeout: 10000 });
    await page.fill('input[placeholder="ユーザー名"]', 'admin');
    await page.fill('input[placeholder="パスワード"]', 'admin');
    await page.click('button:has-text("ログイン")');
    await page.waitForTimeout(3000);
  });

  test('should display preview tab for PowerPoint file', async ({ page }) => {
    // Navigate to test folder
    await page.goto(`http://localhost:8080/core/ui/#/documents?folderId=${TEST_FOLDER_ID}`);
    await page.waitForTimeout(2000);

    // Find PowerPoint file row
    const pptxRow = page.locator('tr:has-text("PowerPointサンプル.pptx")');
    await expect(pptxRow).toBeVisible({ timeout: 10000 });

    // Click the "詳細表示" (detail view) button with EyeOutlined icon to open document viewer
    // The button is in the actions column and has an SVG icon with class containing "eye"
    const viewButton = pptxRow.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });
    await viewButton.click();
    await page.waitForTimeout(2000);

    // Check that preview tab exists
    const previewTab = page.locator('.ant-tabs-tab:has-text("プレビュー")');
    await expect(previewTab).toBeVisible({ timeout: 10000 });

    // Click preview tab
    await previewTab.click();
    await page.waitForTimeout(5000); // Wait for rendition to generate/load

    // Check for PDF preview container or loading state
    const pdfContainer = page.locator('[data-testid="office-preview-pdf"], .react-pdf__Document, .ant-spin');
    await expect(pdfContainer).toBeVisible({ timeout: 30000 });
  });

  test('should load rendition API correctly', async ({ page }) => {
    // Get PowerPoint document ID from API
    const response = await page.request.get(
      `http://localhost:8080/core/browser/bedroom/${TEST_FOLDER_ID}?cmisselector=children`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        }
      }
    );

    const data = await response.json();
    const pptxDoc = data.objects.find((obj: any) =>
      obj.object.properties['cmis:name'].value === 'PowerPointサンプル.pptx'
    );

    expect(pptxDoc).toBeTruthy();
    const objectId = pptxDoc.object.properties['cmis:objectId'].value;

    // Check renditions exist
    const renditionsResponse = await page.request.get(
      `http://localhost:8080/core/rest/repo/bedroom/renditions/${objectId}`,
      {
        headers: {
          'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
        }
      }
    );

    const renditions = await renditionsResponse.json();
    console.log('Renditions:', JSON.stringify(renditions, null, 2));

    expect(renditions.status).toBe('success');
    expect(renditions.count).toBe('1');
    expect(renditions.renditions[0].mimeType).toBe('application/pdf');
  });
});
