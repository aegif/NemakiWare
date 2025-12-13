/**
 * Relationship Creation and Back Button Navigation Tests
 *
 * This file tests two critical features:
 * 1. Creating relationships from UI and verifying they appear in the relationship list
 * 2. Back button navigation preserving the current folder context
 *
 * Created: 2025-12-13
 */

import { test, expect } from '@playwright/test';

// Test data
const TEST_USER = 'admin';
const TEST_PASSWORD = 'admin';
const REPOSITORY_ID = 'bedroom';
const BASE_URL = 'http://localhost:8080';
const ROOT_FOLDER_ID = 'e02f784f8360a02cc14d1314c10038ff';
const TECHNICAL_DOCS_FOLDER_ID = 'e6f59f9d5df575bb1c1b35272b002f7b';

// Helper function to login
async function login(page: any) {
  await page.goto(`${BASE_URL}/core/ui/`);

  // Wait for login page
  await page.waitForSelector('input[placeholder*="ユーザー名"], input[placeholder*="Username"]', { timeout: 10000 });

  // Fill credentials
  await page.fill('input[placeholder*="ユーザー名"], input[placeholder*="Username"]', TEST_USER);
  await page.fill('input[type="password"]', TEST_PASSWORD);

  // Submit login
  await page.click('button[type="submit"]');

  // Wait for navigation to documents page
  await page.waitForURL('**/documents**', { timeout: 30000 });
}

// Helper function to create a test document via API
async function createTestDocument(request: any, name: string, folderId?: string): Promise<string> {
  const targetFolderId = folderId || ROOT_FOLDER_ID;

  const formData = new URLSearchParams();
  formData.append('cmisaction', 'createDocument');
  formData.append('folderId', targetFolderId);
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:document');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', name);

  const response = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
    headers: {
      'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });

  const data = await response.json();
  return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
}

// Helper function to create a test folder via API
async function createTestFolder(request: any, name: string, parentFolderId?: string): Promise<string> {
  const targetFolderId = parentFolderId || ROOT_FOLDER_ID;

  const formData = new URLSearchParams();
  formData.append('cmisaction', 'createFolder');
  formData.append('folderId', targetFolderId);
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:folder');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', name);

  const response = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
    headers: {
      'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });

  const data = await response.json();
  return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
}

// Helper function to delete an object via API
async function deleteObject(request: any, objectId: string): Promise<void> {
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'delete');
  formData.append('objectId', objectId);

  await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
    headers: {
      'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });
}

// Helper function to delete a folder with all contents via API
async function deleteFolder(request: any, objectId: string): Promise<void> {
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'deleteTree');
  formData.append('objectId', objectId);
  formData.append('allVersions', 'true');
  formData.append('continueOnFailure', 'true');

  await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
    headers: {
      'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });
}

// Helper function to navigate into a folder by clicking its name button
async function navigateToFolder(page: any, folderName: string, expectedFolderId: string) {
  // Find and click the folder name button (not the whole row)
  const folderButton = page.locator(`button:has-text("${folderName}")`).first();
  await expect(folderButton).toBeVisible({ timeout: 10000 });
  await folderButton.click();

  // Wait for navigation - URL should update with folder ID
  await page.waitForFunction(
    (folderId: string) => window.location.href.includes(`folderId=${folderId}`),
    expectedFolderId,
    { timeout: 10000 }
  );

  // Wait for content to load
  await page.waitForTimeout(1000);
}

test.describe('Relationship Creation from UI', () => {

  test('should create relationship from UI and verify it appears in relationship list', async ({ page, request }) => {
    // Create two test documents
    const timestamp = Date.now();
    const sourceDocName = `rel-source-${timestamp}.txt`;
    const targetDocName = `rel-target-${timestamp}.txt`;

    const sourceId = await createTestDocument(request, sourceDocName);
    const targetId = await createTestDocument(request, targetDocName);

    try {
      // Login and navigate directly to documents page
      await login(page);

      // Reload to see newly created documents
      await page.reload();
      await page.waitForSelector('.ant-table-row', { timeout: 15000 });

      // Click on source document name button to open DocumentViewer
      const sourceButton = page.locator(`button:has-text("${sourceDocName}")`).first();
      await expect(sourceButton).toBeVisible({ timeout: 15000 });
      await sourceButton.click();

      // Wait for DocumentViewer to load
      await page.waitForSelector('.ant-tabs', { timeout: 10000 });

      // Click on relationship tab ("関係")
      const relationshipTab = page.locator('.ant-tabs-tab:has-text("関係")');
      await expect(relationshipTab).toBeVisible();
      await relationshipTab.click();

      // Wait for relationship tab content to load
      await page.waitForTimeout(500);

      // Click "関係を追加" button
      const addRelationshipButton = page.locator('button:has-text("関係を追加")');
      await expect(addRelationshipButton).toBeVisible();
      await addRelationshipButton.click();

      // Wait for relationship modal to appear
      await page.waitForSelector('.ant-modal-content', { timeout: 5000 });

      // Click "オブジェクトを選択" button to open ObjectPicker
      const selectObjectButton = page.locator('.ant-modal-content button:has-text("オブジェクトを選択")');
      await expect(selectObjectButton).toBeVisible();
      await selectObjectButton.click();

      // Wait for ObjectPicker modal (second modal)
      await page.waitForTimeout(500);

      // Find the ObjectPicker modal and wait for its table to load
      const objectPickerModal = page.locator('.ant-modal-wrap').last();
      await objectPickerModal.locator('.ant-table-row').first().waitFor({ timeout: 10000 });

      // Find and click target document in ObjectPicker
      const targetRow = objectPickerModal.locator(`.ant-table-row:has-text("${targetDocName}")`);
      await expect(targetRow).toBeVisible({ timeout: 10000 });
      await targetRow.click();

      // Wait for ObjectPicker to close
      await page.waitForTimeout(500);

      // Verify selected target is shown in the form
      const selectedTargetText = page.locator(`.ant-modal-content:has-text("関係を追加") >> text=${targetDocName}`);
      await expect(selectedTargetText).toBeVisible({ timeout: 5000 });

      // Click "作成" button to create relationship
      const createButton = page.locator('.ant-modal-content:has-text("関係を追加") button:has-text("作成")');
      await expect(createButton).toBeEnabled();
      await createButton.click();

      // Wait for success message
      await expect(page.locator('.ant-message-success, .ant-message:has-text("作成")')).toBeVisible({ timeout: 10000 });

      // Wait for relationship list to update
      await page.waitForTimeout(1000);

      // Verify relationship appears in the table
      const relationshipTable = page.locator('.ant-tabs-tabpane-active .ant-table');
      await expect(relationshipTable).toBeVisible();

      // Check that target ID appears in the relationship table
      const targetIdCell = page.locator(`.ant-tabs-tabpane-active .ant-table-row`).filter({ hasText: targetId }).first();
      await expect(targetIdCell).toBeVisible({ timeout: 10000 });

    } finally {
      // Cleanup: delete test documents (relationships will be deleted automatically)
      await deleteObject(request, sourceId);
      await deleteObject(request, targetId);
    }
  });

  test('should delete relationship from UI', async ({ page, request }) => {
    // Create two test documents and a relationship via API
    const timestamp = Date.now();
    const sourceDocName = `rel-delete-source-${timestamp}.txt`;
    const targetDocName = `rel-delete-target-${timestamp}.txt`;

    const sourceId = await createTestDocument(request, sourceDocName);
    const targetId = await createTestDocument(request, targetDocName);

    // Create relationship via API
    const relFormData = new URLSearchParams();
    relFormData.append('cmisaction', 'createRelationship');
    relFormData.append('propertyId[0]', 'cmis:objectTypeId');
    relFormData.append('propertyValue[0]', 'nemaki:bidirectionalRelationship');
    relFormData.append('propertyId[1]', 'cmis:sourceId');
    relFormData.append('propertyValue[1]', sourceId);
    relFormData.append('propertyId[2]', 'cmis:targetId');
    relFormData.append('propertyValue[2]', targetId);

    await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
      headers: {
        'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      data: relFormData.toString(),
    });

    try {
      // Login
      await login(page);

      // Reload to see newly created documents
      await page.reload();
      await page.waitForSelector('.ant-table-row', { timeout: 15000 });

      // Click on source document name button to open DocumentViewer
      const sourceButton = page.locator(`button:has-text("${sourceDocName}")`).first();
      await expect(sourceButton).toBeVisible({ timeout: 15000 });
      await sourceButton.click();

      // Wait for DocumentViewer to load
      await page.waitForSelector('.ant-tabs', { timeout: 10000 });

      // Click on relationship tab ("関係")
      const relationshipTab = page.locator('.ant-tabs-tab:has-text("関係")');
      await relationshipTab.click();

      // Wait for relationship table to load
      await page.waitForTimeout(500);

      // Verify relationship exists in table
      const relationshipRow = page.locator(`.ant-tabs-tabpane-active .ant-table-row`).filter({ hasText: targetId }).first();
      await expect(relationshipRow).toBeVisible({ timeout: 10000 });

      // Click delete button
      const deleteButton = relationshipRow.locator('button:has-text("削除")');
      await expect(deleteButton).toBeVisible();
      await deleteButton.click();

      // Confirm deletion in popconfirm
      const confirmButton = page.locator('.ant-popconfirm-buttons button:has-text("はい")');
      await expect(confirmButton).toBeVisible({ timeout: 5000 });
      await confirmButton.click();

      // Wait for success message
      await expect(page.locator('.ant-message-success, .ant-message:has-text("削除")')).toBeVisible({ timeout: 10000 });

      // Verify relationship is removed from table
      await page.waitForTimeout(1000);
      await expect(relationshipRow).not.toBeVisible({ timeout: 5000 });

    } finally {
      // Cleanup: delete test documents
      await deleteObject(request, sourceId);
      await deleteObject(request, targetId);
    }
  });
});

test.describe('Back Button Navigation - Current Folder Preservation', () => {

  test('should return to Technical Documents folder when clicking back button from PDF detail', async ({ page }) => {
    // Capture console messages for debugging
    const consoleLogs: string[] = [];
    page.on('console', msg => {
      if (msg.text().includes('[DEBUG]')) {
        consoleLogs.push(msg.text());
        console.log('BROWSER:', msg.text());
      }
    });

    // Use existing Technical Documents folder and PDF file
    const techDocsFolderName = 'Technical Documents';
    const pdfFileName = 'Welcome to NemakiWare.pdf';
    const techDocsFolderId = TECHNICAL_DOCS_FOLDER_ID;

    // Login
    await login(page);

    // Wait for root folder to load
    await page.waitForSelector('.ant-table-row', { timeout: 15000 });

    // Navigate to Technical Documents folder by clicking on its name button
    await navigateToFolder(page, techDocsFolderName, techDocsFolderId);

    // Verify the URL contains the folder ID
    const urlAfterNav = page.url();
    expect(urlAfterNav).toContain(`folderId=${techDocsFolderId}`);

    // Wait for folder contents to load and verify the PDF is visible
    const pdfButton = page.locator(`button:has-text("${pdfFileName}")`).first();
    await expect(pdfButton).toBeVisible({ timeout: 15000 });

    // Click on the PDF to open detail view
    await pdfButton.click();

    // Wait for DocumentViewer to load
    await page.waitForSelector('.ant-card', { timeout: 10000 });
    await page.waitForSelector('h2', { timeout: 10000 });

    // Verify we're on the document detail page
    const documentTitle = page.locator(`h2:has-text("${pdfFileName}")`);
    await expect(documentTitle).toBeVisible({ timeout: 5000 });

    // CRITICAL CHECK: Verify the URL contains the folderId parameter
    const detailUrl = page.url();
    expect(detailUrl).toContain(`folderId=${techDocsFolderId}`);

    // Click the back button
    const backButton = page.locator('button:has-text("戻る")');
    await expect(backButton).toBeVisible();

    // Print debug logs collected so far (before back button click)
    console.log('=== DEBUG LOGS BEFORE BACK CLICK ===');
    consoleLogs.forEach(log => console.log(log));
    console.log('=====================================');

    await backButton.click();

    // Wait for navigation back to documents list
    await page.waitForURL('**/documents**', { timeout: 10000 });
    await page.waitForTimeout(2000);

    // Print all debug logs (including back button click)
    console.log('=== DEBUG LOGS AFTER BACK CLICK ===');
    consoleLogs.forEach(log => console.log(log));
    console.log('====================================');

    // Verify the URL after clicking back
    const urlAfterBack = page.url();
    console.log('=== URL AFTER BACK ===');
    console.log(urlAfterBack);
    console.log('======================');

    // Check URL first before checking element visibility
    expect(urlAfterBack).toContain(`folderId=${techDocsFolderId}`);

    // Additional verification: root folder items should NOT be visible
    // Technical Documents contains PDFs, not folders like "Sites"
    const sitesFolder = page.locator('button:has-text("Sites")');
    await expect(sitesFolder).not.toBeVisible({ timeout: 3000 });
  });

  test('should preserve folder context through nested folder navigation', async ({ page, request }) => {
    // Create nested folder structure: TestParent -> TestChild
    const timestamp = Date.now();
    const parentFolderName = `TestParent-Nav-${timestamp}`;
    const childFolderName = `TestChild-Nav-${timestamp}`;
    const testDocName = `nested-doc-${timestamp}.txt`;

    // Create parent folder
    const parentFolderId = await createTestFolder(request, parentFolderName);

    // Create child folder inside parent
    const childFolderId = await createTestFolder(request, childFolderName, parentFolderId);

    // Create test document inside child folder
    const docId = await createTestDocument(request, testDocName, childFolderId);

    try {
      // Login
      await login(page);

      // Reload to see newly created folders
      await page.reload();
      await page.waitForSelector('.ant-table-row', { timeout: 15000 });

      // Navigate to parent folder
      await navigateToFolder(page, parentFolderName, parentFolderId);

      // Navigate to child folder
      await navigateToFolder(page, childFolderName, childFolderId);

      // Verify document is visible
      const docButton = page.locator(`button:has-text("${testDocName}")`).first();
      await expect(docButton).toBeVisible({ timeout: 10000 });

      // Verify URL has child folder ID
      const urlInChild = page.url();
      expect(urlInChild).toContain(`folderId=${childFolderId}`);

      // Click on document to open detail view
      await docButton.click();

      // Wait for DocumentViewer
      await page.waitForSelector('.ant-card', { timeout: 10000 });
      await page.waitForSelector('h2', { timeout: 10000 });

      // Verify URL has correct folderId (child folder)
      const detailUrl = page.url();
      expect(detailUrl).toContain(`folderId=${childFolderId}`);

      // Click back button
      const backButton = page.locator('button:has-text("戻る")');
      await backButton.click();

      // Wait for navigation
      await page.waitForURL('**/documents**', { timeout: 10000 });
      await page.waitForTimeout(1000);

      // CRITICAL VERIFICATION: We should be back in child folder (not parent or root)
      await expect(docButton).toBeVisible({ timeout: 10000 });

      // Verify URL still points to child folder
      const urlAfterBack = page.url();
      expect(urlAfterBack).toContain(`folderId=${childFolderId}`);

      // Parent folder and root items should NOT be visible
      const rootItem = page.locator('button:has-text("Sites")');
      await expect(rootItem).not.toBeVisible({ timeout: 3000 });

    } finally {
      // Cleanup: delete parent folder (cascades to child and document)
      await deleteFolder(request, parentFolderId);
    }
  });
});
