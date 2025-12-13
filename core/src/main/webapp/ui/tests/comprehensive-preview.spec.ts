/**
 * Comprehensive Preview Test Suite
 *
 * Tests all preview types (PDF, Image, Text) using dynamically created test files.
 * Automatically sets up test folder and files before tests, cleans up after.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const BASE_URL = 'http://localhost:8080/core/browser/bedroom';
const ROOT_FOLDER_ID = 'e02f784f8360a02cc14d1314c10038ff';
const AUTH_HEADER = 'Basic ' + Buffer.from('admin:admin').toString('base64');

// Test folder ID will be set during setup
let testFolderId: string;
let testFolderName: string;

// Helper to make CMIS API calls from Node.js context
async function cmisRequest(url: string, options: RequestInit = {}): Promise<Response> {
  const headers = {
    'Authorization': AUTH_HEADER,
    ...options.headers,
  };
  return fetch(url, { ...options, headers });
}

// Create test folder
async function createTestFolder(): Promise<{ id: string; name: string }> {
  const timestamp = Date.now();
  const folderName = `playwright-preview-test-${timestamp}`;

  const formData = new FormData();
  formData.append('cmisaction', 'createFolder');
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:folder');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', folderName);
  formData.append('succinct', 'true');

  const response = await cmisRequest(`${BASE_URL}?objectId=${ROOT_FOLDER_ID}`, {
    method: 'POST',
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`Failed to create folder: ${response.status}`);
  }

  const data = await response.json();
  return {
    id: data.succinctProperties['cmis:objectId'],
    name: data.succinctProperties['cmis:name'],
  };
}

// Upload file to folder
async function uploadFile(folderId: string, fileName: string, content: Buffer | string, mimeType: string): Promise<string> {
  const formData = new FormData();
  formData.append('cmisaction', 'createDocument');
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:document');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', fileName);
  formData.append('succinct', 'true');

  const blob = new Blob([content], { type: mimeType });
  formData.append('content', blob, fileName);

  const response = await cmisRequest(`${BASE_URL}?objectId=${folderId}`, {
    method: 'POST',
    body: formData,
  });

  if (!response.ok) {
    throw new Error(`Failed to upload ${fileName}: ${response.status}`);
  }

  const data = await response.json();
  return data.succinctProperties['cmis:objectId'];
}

// Delete folder and contents
async function deleteTestFolder(folderId: string): Promise<void> {
  const formData = new FormData();
  formData.append('cmisaction', 'deleteTree');
  formData.append('folderId', folderId);
  formData.append('allVersions', 'true');
  formData.append('continueOnFailure', 'true');

  await cmisRequest(BASE_URL, {
    method: 'POST',
    body: formData,
  });
}

// Sample file contents
const SAMPLE_TEXT = `これはプレビューテスト用のサンプルテキストファイルです。
NemakiWare CMIS Document Management System

日本語テキストのテスト
Lorem ipsum dolor sit amet, consectetur adipiscing elit.
`;

const SAMPLE_PDF = `%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
endobj
4 0 obj
<< /Length 44 >>
stream
BT /F1 24 Tf 100 700 Td (Sample PDF) Tj ET
endstream
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
xref
0 6
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
0000000266 00000 n
0000000359 00000 n
trailer
<< /Size 6 /Root 1 0 R >>
startxref
434
%%EOF`;

// Minimal valid PNG (1x1 red pixel)
const SAMPLE_PNG = Buffer.from([
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
  0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
  0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53, 0xDE, 0x00, 0x00, 0x00,
  0x0C, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0xCF, 0xC0, 0x00,
  0x00, 0x00, 0x03, 0x00, 0x01, 0x00, 0x05, 0xFE, 0xD4, 0x00, 0x00, 0x00,
  0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82
]);

test.describe('Comprehensive Preview Tests', () => {
  // Set up test folder and files before all tests
  test.beforeAll(async () => {
    console.log('Setting up test data...');
    try {
      // Create test folder
      const folder = await createTestFolder();
      testFolderId = folder.id;
      testFolderName = folder.name;
      console.log(`Created test folder: ${testFolderName} (${testFolderId})`);

      // Upload sample files
      await uploadFile(testFolderId, 'テキストサンプル.txt', SAMPLE_TEXT, 'text/plain');
      console.log('Uploaded: テキストサンプル.txt');

      await uploadFile(testFolderId, 'PDFサンプル.pdf', SAMPLE_PDF, 'application/pdf');
      console.log('Uploaded: PDFサンプル.pdf');

      await uploadFile(testFolderId, '画像サンプル.png', SAMPLE_PNG, 'image/png');
      console.log('Uploaded: 画像サンプル.png');

      console.log('Test data setup complete!');
    } catch (error) {
      console.error('Failed to set up test data:', error);
      throw error;
    }
  });

  // Clean up test folder after all tests
  test.afterAll(async () => {
    if (testFolderId) {
      console.log(`Cleaning up test folder: ${testFolderId}`);
      try {
        await deleteTestFolder(testFolderId);
        console.log('Test data cleanup complete!');
      } catch (error) {
        console.warn('Warning: Failed to clean up test data:', error);
      }
    }
  });

  test.beforeEach(async ({ page }) => {
    // Login
    await page.goto('http://localhost:8080/core/ui/');
    await page.waitForSelector('input[placeholder="ユーザー名"]', { timeout: 10000 });
    await page.fill('input[placeholder="ユーザー名"]', 'admin');
    await page.fill('input[placeholder="パスワード"]', 'admin');
    await page.click('button:has-text("ログイン")');
    await page.waitForTimeout(3000);
  });

  test('PDF Preview - PDFサンプル.pdf', async ({ page }) => {
    await page.goto(`http://localhost:8080/core/ui/#/documents?folderId=${testFolderId}`);
    await page.waitForTimeout(2000);

    // Find and click on PDF file
    const row = page.locator('tr:has-text("PDFサンプル.pdf")');
    await expect(row).toBeVisible({ timeout: 10000 });

    // Click the view button
    const viewButton = row.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });
    await viewButton.click();
    await page.waitForTimeout(2000);

    // Check preview tab exists
    const previewTab = page.locator('.ant-tabs-tab:has-text("プレビュー")');
    await expect(previewTab).toBeVisible({ timeout: 10000 });

    // Click preview tab
    await previewTab.click();
    await page.waitForTimeout(3000);

    // Check for PDF preview component (react-pdf Document)
    const pdfContainer = page.locator('.react-pdf__Document, [data-testid="pdf-preview"]');
    await expect(pdfContainer).toBeVisible({ timeout: 30000 });
  });

  test('Image Preview - 画像サンプル.png', async ({ page }) => {
    await page.goto(`http://localhost:8080/core/ui/#/documents?folderId=${testFolderId}`);
    await page.waitForTimeout(2000);

    // Find and click on image file
    const row = page.locator('tr:has-text("画像サンプル.png")');
    await expect(row).toBeVisible({ timeout: 10000 });

    // Click the view button
    const viewButton = row.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });
    await viewButton.click();
    await page.waitForTimeout(2000);

    // Check preview tab exists
    const previewTab = page.locator('.ant-tabs-tab:has-text("プレビュー")');
    await expect(previewTab).toBeVisible({ timeout: 10000 });

    // Click preview tab
    await previewTab.click();
    await page.waitForTimeout(3000);

    // Check for image preview (img element inside the active tab panel)
    const imageElement = page.locator('.ant-tabs-tabpane-active img').first();
    await expect(imageElement).toBeVisible({ timeout: 30000 });
  });

  test('Text Preview - テキストサンプル.txt', async ({ page }) => {
    await page.goto(`http://localhost:8080/core/ui/#/documents?folderId=${testFolderId}`);
    await page.waitForTimeout(2000);

    // Find and click on text file
    const row = page.locator('tr:has-text("テキストサンプル.txt")');
    await expect(row).toBeVisible({ timeout: 10000 });

    // Click the view button
    const viewButton = row.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });
    await viewButton.click();
    await page.waitForTimeout(2000);

    // Check preview tab exists
    const previewTab = page.locator('.ant-tabs-tab:has-text("プレビュー")');
    await expect(previewTab).toBeVisible({ timeout: 10000 });

    // Click preview tab
    await previewTab.click();
    await page.waitForTimeout(3000);

    // Check for text preview (Monaco Editor renders h4 with filename in preview panel)
    const textHeader = page.locator('.ant-tabs-tabpane-active h4:has-text("テキストサンプル.txt")');
    await expect(textHeader).toBeVisible({ timeout: 30000 });
  });
});
