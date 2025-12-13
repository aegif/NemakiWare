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
async function createTestDocument(request: any, name: string): Promise<string> {
  const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff'; // bedroom root folder

  const formData = new URLSearchParams();
  formData.append('cmisaction', 'createDocument');
  formData.append('folderId', rootFolderId);
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

    // Navigate to a document
    await page.waitForSelector('.ant-table-row', { timeout: 10000 });
    await page.click('.ant-table-row:first-child');

    // Wait for document viewer to load
    await page.waitForSelector('.ant-tabs', { timeout: 10000 });

    // Check for secondary type tab
    const secondaryTypeTab = await page.locator('.ant-tabs-tab:has-text("セカンダリタイプ")');
    await expect(secondaryTypeTab).toBeVisible();
  });

  test('should add secondary type to document via API', async ({ request }) => {
    // Create a test document
    const docName = `test-secondary-type-${Date.now()}.txt`;
    const objectId = await createTestDocument(request, docName);

    try {
      // Get current changeToken
      const getResponse = await request.get(`${BASE_URL}/core/browser/${REPOSITORY_ID}/root/${docName}?cmisselector=object`, {
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
      // Get current changeToken and add secondary type first
      const getResponse1 = await request.get(`${BASE_URL}/core/browser/${REPOSITORY_ID}/root/${docName}?cmisselector=object`, {
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

      // Get updated changeToken
      const getResponse2 = await request.get(`${BASE_URL}/core/browser/${REPOSITORY_ID}/root/${docName}?cmisselector=object`, {
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

      expect(createResponse.status()).toBe(200);

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

    // Navigate to a document
    await page.waitForSelector('.ant-table-row', { timeout: 10000 });
    await page.click('.ant-table-row:first-child');

    // Wait for document viewer to load
    await page.waitForSelector('.ant-tabs', { timeout: 10000 });

    // Check for relationships tab
    const relationshipsTab = await page.locator('.ant-tabs-tab:has-text("関係")');
    await expect(relationshipsTab).toBeVisible();
  });
});

test.describe('UI Integration Tests', () => {

  test('should navigate to secondary type tab and show content', async ({ page }) => {
    await login(page);

    // Navigate to a document
    await page.waitForSelector('.ant-table-row', { timeout: 10000 });
    await page.click('.ant-table-row:first-child');

    // Wait for document viewer to load
    await page.waitForSelector('.ant-tabs', { timeout: 10000 });

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

    // Navigate to a document
    await page.waitForSelector('.ant-table-row', { timeout: 10000 });
    await page.click('.ant-table-row:first-child');

    // Wait for document viewer to load
    await page.waitForSelector('.ant-tabs', { timeout: 10000 });

    // Click on secondary type tab
    await page.click('.ant-tabs-tab:has-text("セカンダリタイプ")');

    // Wait for content to load
    await page.waitForTimeout(1000);

    // Check for selector dropdown or message
    const selector = page.locator('.ant-select');
    const noTypesMessage = page.locator('text=セカンダリタイプが割り当てられていません, text=すべてのセカンダリタイプが割り当て済みです, text=利用可能なセカンダリタイプがありません');

    // Either selector or message should be visible
    const selectorVisible = await selector.first().isVisible().catch(() => false);
    const messageVisible = await noTypesMessage.first().isVisible().catch(() => false);

    expect(selectorVisible || messageVisible).toBe(true);
  });
});
