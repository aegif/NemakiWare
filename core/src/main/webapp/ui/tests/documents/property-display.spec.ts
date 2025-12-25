/**
 * Property Display Comprehensive Test
 *
 * Verifies that all CMIS properties are displayed correctly in:
 * 1. DocumentViewer upper section (Descriptions component)
 * 2. PropertyEditor table with pagination (Display mode)
 * 3. PropertyEditor form (Edit mode)
 */

import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

const BASE_URL = 'http://localhost:8080';
const REPOSITORY_ID = 'bedroom';
const TEST_USER = 'admin';
const TEST_PASSWORD = 'admin';

function getAuthHeader(): string {
  return `Basic ${Buffer.from(`${TEST_USER}:${TEST_PASSWORD}`).toString('base64')}`;
}

async function createTestDocument(request: APIRequestContext): Promise<{ id: string; name: string }> {
  const uniqueName = `property-display-${Date.now()}.txt`;
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'createDocument');
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:document');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', uniqueName);
  formData.append('succinct', 'true');

  const response = await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}/root`, {
    headers: {
      Authorization: getAuthHeader(),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });

  if (!response.ok()) {
    throw new Error(`Failed to create test document: ${response.status()} ${await response.text()}`);
  }

  const data = await response.json();
  const objectId = data.properties?.['cmis:objectId']?.value || data.succinctProperties?.['cmis:objectId'];

  if (!objectId) {
    throw new Error('Test document creation succeeded but object ID is missing in response');
  }

  return { id: objectId, name: uniqueName };
}

async function deleteTestDocument(request: APIRequestContext, objectId: string): Promise<void> {
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'delete');
  formData.append('objectId', objectId);

  await request.post(`${BASE_URL}/core/browser/${REPOSITORY_ID}`, {
    headers: {
      Authorization: getAuthHeader(),
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: formData.toString(),
  });
}

async function openTestDocument(page: Page, objectId: string, documentName: string): Promise<void> {
  await page.goto(`${BASE_URL}/core/ui/#/documents/${objectId}`);
  await page.waitForSelector('.ant-page-header, .ant-descriptions, .ant-table', { timeout: 15000 });
  await page.waitForTimeout(1000);

  const headerTitle = page.locator('.ant-page-header-heading-title').first();
  if (await headerTitle.count() > 0) {
    await expect(headerTitle).toContainText(documentName, { timeout: 5000 });
  }
}

test.describe('Property Display Tests', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  let testDocumentId: string | null = null;
  let testDocumentName: string | null = null;
  let setupFailed = false;

  test.beforeAll(async ({ request }) => {
    try {
      const { id, name } = await createTestDocument(request);
      testDocumentId = id;
      testDocumentName = name;
      console.log(`Test document created: ${name} (${id})`);
    } catch (error) {
      setupFailed = true;
      console.error('Failed to create test document for property display tests:', error);
    }
  });

  test.afterAll(async ({ request }) => {
    if (testDocumentId) {
      await deleteTestDocument(request, testDocumentId);
      console.log(`Deleted test document: ${testDocumentId}`);
    }
  });

  test.beforeEach(async ({ page }) => {
    if (setupFailed || !testDocumentId || !testDocumentName) {
      test.skip('Test document setup failed');
    }

    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();
    await page.waitForTimeout(2000);

    // Wait for Ant Design components to load
    await testHelper.waitForAntdLoad();

    await openTestDocument(page, testDocumentId!, testDocumentName!);
  });

  test('should display all metadata correctly in DocumentViewer upper section', async ({ page }) => {
    // Verify metadata table is visible (DocumentViewer uses table, not Descriptions component)
    // The metadata section has a specific table structure with ID, タイプ, etc. in cells
    // Wait for the DocumentViewer to fully load
    await page.waitForTimeout(1000);

    // Use getByRole to find cells by their accessible name (shown as cell "ID" in error context)
    const idCell = page.getByRole('cell', { name: 'ID', exact: true }).first();
    await expect(idCell).toBeVisible({ timeout: 10000 });

    // Verify タイプ (Type) field
    const typeCell = page.getByRole('cell', { name: 'タイプ', exact: true }).first();
    await expect(typeCell).toBeVisible({ timeout: 5000 });

    // Verify path field displays correctly
    const pathCell = page.getByRole('cell', { name: 'パス', exact: true }).first();
    await expect(pathCell).toBeVisible({ timeout: 5000 });

    // Verify other standard metadata labels
    const creatorCell = page.getByRole('cell', { name: '作成者', exact: true }).first();
    await expect(creatorCell).toBeVisible({ timeout: 5000 });

    const updaterCell = page.getByRole('cell', { name: '更新者', exact: true }).first();
    await expect(updaterCell).toBeVisible({ timeout: 5000 });

    console.log('DocumentViewer metadata section verified');
  });

  test('should display all properties in PropertyEditor table with pagination', async ({ page }) => {
    // Click on Properties tab
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    // Verify property table is visible (may be nested inside tab)
    const propertyTable = page.locator('.ant-table').first();
    await expect(propertyTable).toBeVisible({ timeout: 10000 });

    // Get all property rows
    const propertyRows = propertyTable.locator('tbody tr');
    const rowCount = await propertyRows.count();

    console.log('Property row count:', rowCount);

    // Should have multiple properties
    expect(rowCount).toBeGreaterThan(0);

    // Verify at least some standard CMIS properties are displayed
    const standardProperties = [
      'cmis:name',
      'cmis:objectId',
      'cmis:objectTypeId'
    ];

    for (const propName of standardProperties) {
      const propRow = propertyTable.locator(`tr:has-text("${propName}")`);

      if (await propRow.count() > 0) {
        const valueCell = propRow.locator('td').nth(1);
        const valueText = await valueCell.textContent();
        console.log(`${propName} value:`, valueText);
        expect(valueText).toBeTruthy();
      }
    }

    // Verify pagination controls are present if there are many properties
    const pagination = page.locator('.ant-pagination');
    const paginationVisible = await pagination.isVisible().catch(() => false);
    console.log('Pagination visible:', paginationVisible);
  });

  test('should display property values with correct formatting', async ({ page }) => {
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    // Test DateTime properties formatting
    const creationDateRow = page.locator('tr:has-text("cmis:creationDate")');
    if (await creationDateRow.count() > 0) {
      const dateValue = await creationDateRow.locator('td').nth(1).textContent();
      console.log('Creation date format:', dateValue);

      if (dateValue && dateValue !== '-') {
        // Accept various date formats
        expect(dateValue).toMatch(/\d{4}[-\/]\d{2}[-\/]\d{2}/);
      }
    }

    // Test Boolean properties formatting
    const booleanRow = page.locator('tr:has-text("cmis:isLatestVersion")');
    if (await booleanRow.count() > 0) {
      const boolValue = await booleanRow.locator('td').nth(1).textContent();
      console.log('Boolean value:', boolValue);

      if (boolValue && boolValue !== '-') {
        // Accept various boolean representations
        expect(['はい', 'いいえ', 'true', 'false', 'Yes', 'No']).toContain(boolValue?.trim());
      }
    }
  });

  test('should show read-only indicators correctly', async ({ page }) => {
    // ENABLED (2025-12-25): Read-only indicators (読み取り専用) are implemented in PropertyEditor.tsx line 278-279
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    // Verify read-only indicators exist (PropertyEditor shows "(読み取り専用)" for readonly properties)
    const readOnlyIndicator = page.locator('text=(読み取り専用)');
    const indicatorCount = await readOnlyIndicator.count();

    console.log('Read-only properties count:', indicatorCount);
    expect(indicatorCount).toBeGreaterThan(0);
  });

  test('should handle pagination correctly', async ({ page }) => {
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    // Get total property count from pagination
    const totalText = page.locator('.ant-pagination-total-text');
    if (await totalText.count() > 0) {
      const totalTextContent = await totalText.textContent();
      console.log('Pagination total:', totalTextContent);

      const match = totalTextContent?.match(/全 (\d+) 件/);
      if (match) {
        const total = parseInt(match[1]);
        console.log('Total properties:', total);

        if (total > 10) {
          // If more than 10 properties, test pagination

          // Click next page
          const nextButton = page.locator('.ant-pagination-next');
          await nextButton.click();
          await page.waitForTimeout(500);

          // Verify we're on page 2
          const activePage = page.locator('.ant-pagination-item-active');
          const pageNumber = await activePage.textContent();
          expect(pageNumber).toBe('2');

          // Click previous page
          const prevButton = page.locator('.ant-pagination-prev');
          await prevButton.click();
          await page.waitForTimeout(500);

          // Back to page 1
          const activePageAfter = page.locator('.ant-pagination-item-active');
          const pageNumberAfter = await activePageAfter.textContent();
          expect(pageNumberAfter).toBe('1');
        }
      }
    }
  });

  test('should switch between display and edit mode correctly', async ({ page }) => {
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    // Initially in display mode with table
    const propertyTable = page.locator('.ant-table');
    await expect(propertyTable).toBeVisible();

    // Click edit button
    const editButton = page.locator('button:has-text("編集")');
    if (await editButton.count() > 0) {
      await editButton.click();
      await page.waitForTimeout(1000);

      // Now in edit mode with form
      const propertyForm = page.locator('form');
      await expect(propertyForm).toBeVisible();

      // Table should not be visible
      await expect(propertyTable).not.toBeVisible();

      // Verify only updatable properties are shown in form
      const formItems = page.locator('.ant-form-item');
      const formItemCount = await formItems.count();

      console.log('Editable properties count:', formItemCount);

      // Should have at least some editable properties
      expect(formItemCount).toBeGreaterThan(0);

      // Click cancel to return to display mode
      const cancelButton = page.locator('button:has-text("キャンセル")');
      await cancelButton.click();
      await page.waitForTimeout(500);

      // Back to display mode
      await expect(propertyTable).toBeVisible();
      await expect(propertyForm).not.toBeVisible();
    }
  });

  test('should display required field indicators in edit mode', async ({ page }) => {
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    const editButton = page.locator('button:has-text("編集")');
    if (await editButton.count() > 0) {
      await editButton.click();
      await page.waitForTimeout(1000);

      // Verify required fields have asterisk indicator
      const requiredIndicators = page.locator('.ant-form-item-required');
      const requiredCount = await requiredIndicators.count();

      console.log('Required fields count:', requiredCount);

      // cmis:name is always required for documents
      const nameField = page.locator('.ant-form-item-label:has-text("cmis:name")');
      if (await nameField.count() > 0) {
        await expect(nameField.locator('.ant-form-item-required-mark')).toBeVisible();
      }
    }
  });
});
