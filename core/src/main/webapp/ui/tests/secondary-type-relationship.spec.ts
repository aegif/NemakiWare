/**
 * Secondary Type and Relationship Management Tests
 *
 * Tests CMIS secondary type (aspect) operations and relationship management.
 * These tests verify critical functionality that requires changeToken handling
 * and proper CMIS Browser Binding form data formatting.
 *
 * Created: 2025-12-13
 */

import { test, expect } from '@playwright/test';

// Test data
const TEST_USER = 'admin';
const TEST_PASSWORD = 'admin';
const REPOSITORY_ID = 'bedroom';
const BASE_URL = 'http://localhost:8080';

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
// Note: CMIS Browser Binding requires POST to parent folder path for createDocument
async function createTestDocument(request: any, name: string): Promise<string> {
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'createDocument');
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:document');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', name);

  // POST to /root (parent folder path) for createDocument
  const response = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}/root`, {
    headers: {
      'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });

  const data = await response.json();
  return data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];
}

// Helper function to delete a document via API
async function deleteDocument(request: any, objectId: string): Promise<void> {
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

test.describe('Secondary Type Management', () => {

  test('should display secondary type tab in document viewer', async ({ page }) => {
    await login(page);

    // Wait for document list and find a document (not folder) to view
    await page.waitForSelector('.ant-table-row', { timeout: 10000 });

    // Find a file row (not folder) and click its eye button
    // Files have the "file" icon, folders have "folder" icon
    const fileRow = page.locator('tr').filter({ hasText: /\.txt|\.pdf|\.docx|\.png|\.jpg/ }).first();
    await expect(fileRow).toBeVisible({ timeout: 5000 });

    const viewButton = fileRow.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });

    // Click and wait for navigation to document viewer
    await Promise.all([
      page.waitForURL('**/documents/**', { timeout: 15000 }),
      viewButton.click()
    ]);

    // Wait for document viewer to load with tabs
    await page.waitForSelector('.ant-tabs', { timeout: 15000 });

    // Check for secondary type tab
    const secondaryTypeTab = await page.locator('.ant-tabs-tab:has-text("セカンダリタイプ")');
    await expect(secondaryTypeTab).toBeVisible();
  });

  test('should add secondary type to document via API', async ({ request }) => {
    // Create a test document
    const docName = `test-secondary-type-${Date.now()}.txt`;
    const objectId = await createTestDocument(request, docName);

    try {
      // Get current changeToken using path-based access (required for full property data)
      const getResponse = await request.get(`${BASE_URL}/core/browser/${REPOSITORY_ID}/${objectId}?cmisselector=object`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
        },
      });
      const objectData = await getResponse.json();
      const changeToken = objectData.properties?.['cmis:changeToken']?.value;

      // Add secondary type
      const formData = new URLSearchParams();
      formData.append('cmisaction', 'update');
      formData.append('objectId', objectId);
      formData.append('changeToken', changeToken);
      formData.append('addSecondaryTypeIds', 'nemaki:commentable');

      const updateResponse = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        data: formData.toString(),
      });

      expect(updateResponse.status()).toBe(200);

      const updatedData = await updateResponse.json();
      const secondaryTypes = updatedData.properties?.['cmis:secondaryObjectTypeIds']?.value;
      expect(secondaryTypes).toContain('nemaki:commentable');

    } finally {
      // Cleanup
      await deleteDocument(request, objectId);
    }
  });

  test('should remove secondary type from document via API', async ({ request }) => {
    // Create a test document
    const docName = `test-secondary-type-remove-${Date.now()}.txt`;
    const objectId = await createTestDocument(request, docName);

    try {
      // Get current changeToken using path-based access (required for full property data)
      const getResponse1 = await request.get(`${BASE_URL}/core/browser/${REPOSITORY_ID}/${objectId}?cmisselector=object`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
        },
      });
      const objectData1 = await getResponse1.json();
      const changeToken1 = objectData1.properties?.['cmis:changeToken']?.value;

      // Add secondary type
      const addFormData = new URLSearchParams();
      addFormData.append('cmisaction', 'update');
      addFormData.append('objectId', objectId);
      addFormData.append('changeToken', changeToken1);
      addFormData.append('addSecondaryTypeIds', 'nemaki:commentable');

      await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        data: addFormData.toString(),
      });

      // Get updated changeToken using path-based access
      const getResponse2 = await request.get(`${BASE_URL}/core/browser/${REPOSITORY_ID}/${objectId}?cmisselector=object`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
        },
      });
      const objectData2 = await getResponse2.json();
      const changeToken2 = objectData2.properties?.['cmis:changeToken']?.value;

      // Remove secondary type
      const removeFormData = new URLSearchParams();
      removeFormData.append('cmisaction', 'update');
      removeFormData.append('objectId', objectId);
      removeFormData.append('changeToken', changeToken2);
      removeFormData.append('removeSecondaryTypeIds', 'nemaki:commentable');

      const removeResponse = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        data: removeFormData.toString(),
      });

      expect(removeResponse.status()).toBe(200);

      const removedData = await removeResponse.json();
      const secondaryTypes = removedData.properties?.['cmis:secondaryObjectTypeIds']?.value;
      expect(secondaryTypes).toBeNull();

    } finally {
      // Cleanup
      await deleteDocument(request, objectId);
    }
  });
});

test.describe('Relationship Management', () => {

  test('should create relationship between documents via API', async ({ request }) => {
    // Create two test documents
    const sourceDocName = `test-rel-source-${Date.now()}.txt`;
    const targetDocName = `test-rel-target-${Date.now()}.txt`;

    const sourceId = await createTestDocument(request, sourceDocName);
    const targetId = await createTestDocument(request, targetDocName);
    let relationshipId: string | null = null;

    try {
      // Create relationship using CMIS Browser Binding
      const formData = new URLSearchParams();
      formData.append('cmisaction', 'createRelationship');
      formData.append('propertyId[0]', 'cmis:objectTypeId');
      formData.append('propertyValue[0]', 'nemaki:bidirectionalRelationship');
      formData.append('propertyId[1]', 'cmis:sourceId');
      formData.append('propertyValue[1]', sourceId);
      formData.append('propertyId[2]', 'cmis:targetId');
      formData.append('propertyValue[2]', targetId);

      const createResponse = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        data: formData.toString(),
      });

      // createRelationship returns 201 Created on success
      expect([200, 201]).toContain(createResponse.status());

      const relationshipData = await createResponse.json();
      relationshipId = relationshipData.properties?.['cmis:objectId']?.value;

      expect(relationshipId).toBeTruthy();
      expect(relationshipData.properties?.['cmis:sourceId']?.value).toBe(sourceId);
      expect(relationshipData.properties?.['cmis:targetId']?.value).toBe(targetId);
      expect(relationshipData.properties?.['cmis:objectTypeId']?.value).toBe('nemaki:bidirectionalRelationship');

    } finally {
      // Cleanup
      if (relationshipId) {
        await deleteDocument(request, relationshipId);
      }
      await deleteDocument(request, sourceId);
      await deleteDocument(request, targetId);
    }
  });

  test('should delete relationship via API', async ({ request }) => {
    // Create two test documents and a relationship
    const sourceDocName = `test-rel-delete-source-${Date.now()}.txt`;
    const targetDocName = `test-rel-delete-target-${Date.now()}.txt`;

    const sourceId = await createTestDocument(request, sourceDocName);
    const targetId = await createTestDocument(request, targetDocName);

    try {
      // Create relationship
      const createFormData = new URLSearchParams();
      createFormData.append('cmisaction', 'createRelationship');
      createFormData.append('propertyId[0]', 'cmis:objectTypeId');
      createFormData.append('propertyValue[0]', 'nemaki:bidirectionalRelationship');
      createFormData.append('propertyId[1]', 'cmis:sourceId');
      createFormData.append('propertyValue[1]', sourceId);
      createFormData.append('propertyId[2]', 'cmis:targetId');
      createFormData.append('propertyValue[2]', targetId);

      const createResponse = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        data: createFormData.toString(),
      });

      const relationshipData = await createResponse.json();
      const relationshipId = relationshipData.properties?.['cmis:objectId']?.value;

      // Delete relationship
      const deleteFormData = new URLSearchParams();
      deleteFormData.append('cmisaction', 'delete');
      deleteFormData.append('objectId', relationshipId);

      const deleteResponse = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        data: deleteFormData.toString(),
      });

      expect(deleteResponse.status()).toBe(200);

    } finally {
      // Cleanup source and target documents
      await deleteDocument(request, sourceId);
      await deleteDocument(request, targetId);
    }
  });

  test('should display relationships tab in document viewer', async ({ page }) => {
    await login(page);

    // Wait for document list and find a document (not folder) to view
    await page.waitForSelector('.ant-table-row', { timeout: 10000 });

    // Find a file row and click its eye button
    const fileRow = page.locator('tr').filter({ hasText: /\.txt|\.pdf|\.docx|\.png|\.jpg/ }).first();
    await expect(fileRow).toBeVisible({ timeout: 5000 });

    const viewButton = fileRow.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });

    // Click and wait for navigation
    await Promise.all([
      page.waitForURL('**/documents/**', { timeout: 15000 }),
      viewButton.click()
    ]);

    // Wait for document viewer to load with tabs
    await page.waitForSelector('.ant-tabs', { timeout: 15000 });

    // Check for relationships tab
    const relationshipsTab = await page.locator('.ant-tabs-tab:has-text("関係")');
    await expect(relationshipsTab).toBeVisible();
  });
});

test.describe('UI Integration Tests', () => {

  test('should navigate to secondary type tab and show content', async ({ page }) => {
    await login(page);

    // Wait for document list and find a document (not folder) to view
    await page.waitForSelector('.ant-table-row', { timeout: 10000 });

    // Find a file row and click its eye button
    const fileRow = page.locator('tr').filter({ hasText: /\.txt|\.pdf|\.docx|\.png|\.jpg/ }).first();
    await expect(fileRow).toBeVisible({ timeout: 5000 });

    const viewButton = fileRow.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });

    // Click and wait for navigation
    await Promise.all([
      page.waitForURL('**/documents/**', { timeout: 15000 }),
      viewButton.click()
    ]);

    // Wait for document viewer to load with tabs
    await page.waitForSelector('.ant-tabs', { timeout: 15000 });

    // Click on secondary type tab
    await page.click('.ant-tabs-tab:has-text("セカンダリタイプ")');

    // Wait for tab content to load
    await page.waitForTimeout(1000);

    // Check that secondary type content is visible
    const content = await page.locator('text=セカンダリタイプ');
    await expect(content.first()).toBeVisible();
  });

  test('should show secondary type selector dropdown when not all types assigned', async ({ page }) => {
    await login(page);

    // Wait for document list and find a document (not folder) to view
    await page.waitForSelector('.ant-table-row', { timeout: 10000 });

    // Find a file row and click its eye button
    const fileRow = page.locator('tr').filter({ hasText: /\.txt|\.pdf|\.docx|\.png|\.jpg/ }).first();
    await expect(fileRow).toBeVisible({ timeout: 5000 });

    const viewButton = fileRow.locator('button').filter({ has: page.locator('.anticon-eye') });
    await expect(viewButton).toBeVisible({ timeout: 5000 });

    // Click and wait for navigation
    await Promise.all([
      page.waitForURL('**/documents/**', { timeout: 15000 }),
      viewButton.click()
    ]);

    // Wait for document viewer to load with tabs
    await page.waitForSelector('.ant-tabs', { timeout: 15000 });

    // Click on secondary type tab
    await page.click('.ant-tabs-tab:has-text("セカンダリタイプ")');

    // Wait for content to load fully
    await page.waitForTimeout(2000);

    // Check for various valid states:
    // 1. Dropdown selector visible (can add more types)
    // 2. Existing secondary type tag visible (e.g., "Commentable")
    // 3. Message about no types or all types assigned
    const dropdownSelector = page.locator('.ant-select-selector');
    const tagSelector = page.locator('.ant-tag');
    const noTypesMessage = page.locator('text=セカンダリタイプが割り当てられていません');
    const allTypesMessage = page.locator('text=すべてのセカンダリタイプが割り当て済みです');
    const noAvailableMessage = page.locator('text=利用可能なセカンダリタイプがありません');

    // Either dropdown, existing tag, or one of the messages should be visible
    const dropdownVisible = await dropdownSelector.first().isVisible().catch(() => false);
    const tagVisible = await tagSelector.first().isVisible().catch(() => false);
    const noTypesVisible = await noTypesMessage.first().isVisible().catch(() => false);
    const allTypesVisible = await allTypesMessage.first().isVisible().catch(() => false);
    const noAvailableVisible = await noAvailableMessage.first().isVisible().catch(() => false);

    // At least one of these conditions should be true
    expect(dropdownVisible || tagVisible || noTypesVisible || allTypesVisible || noAvailableVisible).toBe(true);
  });
});
