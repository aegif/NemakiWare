import { waitForUiStable } from '../utils/wait-helpers';
import { test, expect } from '@playwright/test';
import { AuthHelper } from './utils/auth-helper';
import { TestHelper, ApiHelper } from './utils/test-helper';

/**
 * PDF Preview with react-pdf
 *
 * Verifies that PDF documents render using react-pdf in the preview tab.
 * This test uploads a PDF file via API, then checks the preview UI.
 *
 * Note: comprehensive-preview.spec.ts also covers PDF preview.
 */

let uploadedPdfId: string = '';
const testPdfName = `pdf-preview-test-${Date.now()}.pdf`;

test.beforeAll(async ({ browser }) => {
  // Upload a minimal PDF file via API to ensure one exists
  const context = await browser.newContext();
  const page = await context.newPage();
  const adminAuth = `Basic ${Buffer.from('admin:admin').toString('base64')}`;

  try {
    // Get root folder ID
    const rootResp = await page.request.get(
      'http://localhost:8080/core/browser/bedroom/root?cmisselector=object',
      { headers: { 'Authorization': adminAuth } }
    );
    const rootData = await rootResp.json();
    const rootFolderId = rootData?.properties?.['cmis:objectId']?.value;
    if (!rootFolderId) {
      console.log('Could not get root folder ID');
      return;
    }

    // Create a minimal valid PDF
    const pdfContent = '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/MediaBox[0 0 612 792]/Parent 2 0 R/Resources<<>>>>endobj\nxref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \ntrailer<</Size 4/Root 1 0 R>>\nstartxref\n206\n%%EOF';

    // Upload via CMIS Browser Binding
    const formData = new URLSearchParams();
    // Use multipart form data via fetch
    const uploadResp = await page.request.post(
      'http://localhost:8080/core/browser/bedroom/root',
      {
        headers: { 'Authorization': adminAuth },
        multipart: {
          cmisaction: 'createDocument',
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:document',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testPdfName,
          content: {
            name: testPdfName,
            mimeType: 'application/pdf',
            buffer: Buffer.from(pdfContent, 'utf-8'),
          },
        },
      }
    );

    if (uploadResp.ok()) {
      const data = await uploadResp.json();
      uploadedPdfId = data?.properties?.['cmis:objectId']?.value || '';
      console.log(`Uploaded test PDF: ${testPdfName}, ID: ${uploadedPdfId}`);
    } else {
      console.log(`PDF upload failed: ${uploadResp.status()}`);
    }
  } catch (e) {
    console.log(`PDF upload error: ${e}`);
  } finally {
    await context.close();
  }
});

test.afterAll(async ({ browser }) => {
  if (!uploadedPdfId) return;
  const context = await browser.newContext();
  const page = await context.newPage();
  const adminAuth = `Basic ${Buffer.from('admin:admin').toString('base64')}`;
  try {
    await page.request.post(
      'http://localhost:8080/core/browser/bedroom/root',
      {
        headers: { 'Authorization': adminAuth },
        form: {
          cmisaction: 'delete',
          objectId: uploadedPdfId,
          allVersions: 'true',
        },
      }
    );
    console.log(`Cleaned up test PDF: ${testPdfName}`);
  } catch (e) {
    console.log(`Cleanup error: ${e}`);
  } finally {
    await context.close();
  }
});

test('PDF preview should render with react-pdf', async ({ page }) => {
  test.setTimeout(180000);

  expect(uploadedPdfId).toBeTruthy();

  // Login and navigate directly to the uploaded PDF's DocumentViewer
  await page.goto('http://localhost:8080/core/ui/');
  await page.waitForSelector('input[placeholder*="ユーザー"], input[placeholder*="User"]', { timeout: 15000 });
  await page.fill('input[placeholder*="ユーザー"], input[placeholder*="User"]', 'admin');
  await page.fill('input[type="password"]', 'admin');
  await page.click('button[type="submit"], button:has-text("ログイン"), button:has-text("Login")');
  await page.waitForURL(/\/#\/documents/, { timeout: 45000 });

  // Navigate directly to DocumentViewer for the uploaded PDF
  await page.goto(`http://localhost:8080/core/ui/index.html#/documents/${uploadedPdfId}`);
  await page.waitForSelector('.ant-tabs-tab', { timeout: 20000 });

  // Click Preview tab
  const previewTab = page.locator('.ant-tabs-tab').filter({ hasText: /プレビュー|Preview/i });
  await expect(previewTab).toBeVisible({ timeout: 10000 });
  await previewTab.click();
  await waitForUiStable(page, { timeout: 15000 }); // rendering

  // Check for react-pdf elements
  const pdfDocument = page.locator('.react-pdf__Document');
  const pdfCanvas = page.locator('.react-pdf__Page__canvas');

  const hasDocument = await pdfDocument.isVisible();
  const hasCanvas = await pdfCanvas.isVisible();

  console.log('PDF Document visible:', hasDocument);
  console.log('PDF Canvas visible:', hasCanvas);

  const success = hasCanvas || hasDocument;
  console.log(success ? 'PDF preview rendered' : 'PDF preview not rendered');

  if (!success) {
    // Retry with longer wait
    await waitForUiStable(page, { timeout: 15000 }); // rendering
    const hasCanvasRetry = await pdfCanvas.isVisible();
    const hasDocumentRetry = await pdfDocument.isVisible();
    const successRetry = hasCanvasRetry || hasDocumentRetry;
    console.log(successRetry ? 'PDF preview rendered on retry' : 'PDF preview not rendered on retry');
    expect(successRetry).toBe(true);
  }
});
