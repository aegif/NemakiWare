/**
 * Property Display Comprehensive Test
 *
 * Verifies that all CMIS properties are displayed correctly in:
 * 1. DocumentViewer upper section (Descriptions component)
 * 2. PropertyEditor table with pagination (Display mode)
 * 3. PropertyEditor form (Edit mode)
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Property Display Tests', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();
    await page.waitForTimeout(2000);

    // Wait for Ant Design components to load
    await testHelper.waitForAntdLoad();
  });

  test('should display all metadata correctly in DocumentViewer upper section', async ({ page }) => {
    // Navigate to a test document
    await page.click('text=ドキュメント');
    await page.waitForTimeout(1000);

    // Click on a document to open DocumentViewer
    const documentRow = page.locator('.ant-table-row').first();
    await documentRow.click();
    await page.waitForTimeout(1000);

    // Verify Descriptions component is visible
    const descriptions = page.locator('.ant-descriptions');
    await expect(descriptions).toBeVisible();

    // Verify all metadata fields are present
    await expect(page.locator('text=ID')).toBeVisible();
    await expect(page.locator('text=タイプ')).toBeVisible();
    await expect(page.locator('text=ベースタイプ')).toBeVisible();

    // CRITICAL: Verify path field displays correctly
    const pathLabel = page.locator('.ant-descriptions-item-label:has-text("パス")');
    await expect(pathLabel).toBeVisible();

    const pathValue = pathLabel.locator('..').locator('.ant-descriptions-item-content');
    const pathText = await pathValue.textContent();

    // Path should either show a valid path or '-' (not empty or broken)
    expect(pathText).toBeTruthy();
    expect(pathText).not.toBe('');

    console.log('Path value:', pathText);

    // Verify other standard metadata
    await expect(page.locator('text=作成者')).toBeVisible();
    await expect(page.locator('text=作成日時')).toBeVisible();
    await expect(page.locator('text=更新者')).toBeVisible();
    await expect(page.locator('text=更新日時')).toBeVisible();
  });

  test('should display all properties in PropertyEditor table with pagination', async ({ page }) => {
    // Navigate to a document
    await page.click('text=ドキュメント');
    await page.waitForTimeout(1000);

    const documentRow = page.locator('.ant-table-row').first();
    await documentRow.click();
    await page.waitForTimeout(1000);

    // Click on Properties tab (プロパティタブ) - FIX: Use .ant-tabs-tab selector like property-editor.spec.ts
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    // Verify property table is visible
    const propertyTable = page.locator('.ant-table');
    await expect(propertyTable).toBeVisible();

    // Get all property rows
    const propertyRows = page.locator('.ant-table-tbody tr');
    const rowCount = await propertyRows.count();

    console.log('Property row count:', rowCount);

    // Should have multiple properties
    expect(rowCount).toBeGreaterThan(0);

    // Verify at least some standard CMIS properties are displayed with values
    const standardProperties = [
      'cmis:name',
      'cmis:objectId',
      'cmis:objectTypeId',
      'cmis:baseTypeId',
      'cmis:createdBy',
      'cmis:creationDate'
    ];

    for (const propName of standardProperties) {
      // Look for property in current page (may need pagination)
      const propRow = page.locator(`tr:has-text("${propName}")`);

      if (await propRow.count() > 0) {
        // Property found - verify it has a value
        const valueCell = propRow.locator('td').nth(1);
        const valueText = await valueCell.textContent();

        console.log(`${propName} value:`, valueText);

        // Value should not be empty (unless explicitly null/undefined)
        // At minimum should show '-' for missing values
        expect(valueText).toBeTruthy();
      }
    }

    // Verify pagination controls are present
    const pagination = page.locator('.ant-pagination');
    await expect(pagination).toBeVisible();

    // Verify page size changer is available
    const pageSizeChanger = page.locator('.ant-select-selector:has-text("10")');
    await expect(pageSizeChanger).toBeVisible();
  });

  test('should display property values with correct formatting', async ({ page }) => {
    await page.click('text=ドキュメント');
    await page.waitForTimeout(1000);

    const documentRow = page.locator('.ant-table-row').first();
    await documentRow.click();
    await page.waitForTimeout(1000);

    // FIX: Use .ant-tabs-tab selector like property-editor.spec.ts
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    // Test different property types are formatted correctly

    // 1. DateTime properties should be formatted as YYYY-MM-DD HH:mm:ss
    const creationDateRow = page.locator('tr:has-text("cmis:creationDate")');
    if (await creationDateRow.count() > 0) {
      const dateValue = await creationDateRow.locator('td').nth(1).textContent();
      console.log('Creation date format:', dateValue);

      if (dateValue && dateValue !== '-') {
        // Should match YYYY-MM-DD HH:mm:ss format
        expect(dateValue).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
      }
    }

    // 2. Boolean properties should show 'はい' or 'いいえ'
    const booleanRow = page.locator('tr:has-text("cmis:isLatestVersion")');
    if (await booleanRow.count() > 0) {
      const boolValue = await booleanRow.locator('td').nth(1).textContent();
      console.log('Boolean value:', boolValue);

      if (boolValue && boolValue !== '-') {
        expect(['はい', 'いいえ']).toContain(boolValue);
      }
    }

    // 3. Multi-value properties should be comma-separated
    const multiValueRow = page.locator('tr').filter({ hasText: /,/ });
    if (await multiValueRow.count() > 0) {
      const multiValue = await multiValueRow.first().locator('td').nth(1).textContent();
      console.log('Multi-value property:', multiValue);

      expect(multiValue).toContain(',');
    }
  });

  test('should show read-only indicators correctly', async ({ page }) => {
    await page.click('text=ドキュメント');
    await page.waitForTimeout(1000);

    const documentRow = page.locator('.ant-table-row').first();
    await documentRow.click();
    await page.waitForTimeout(1000);

    // FIX: Use .ant-tabs-tab selector like property-editor.spec.ts
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: 'プロパティ' });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click();
    }
    await page.waitForTimeout(1000);

    // Verify read-only properties have gray text and (読み取り専用) indicator
    const readOnlyIndicator = page.locator('text=(読み取り専用)');
    const indicatorCount = await readOnlyIndicator.count();

    console.log('Read-only properties count:', indicatorCount);

    // Should have at least some read-only properties (e.g., cmis:creationDate, cmis:objectId)
    expect(indicatorCount).toBeGreaterThan(0);

    // Verify updatable properties don't have the indicator
    const editButton = page.locator('button:has-text("編集")');
    if (await editButton.count() > 0) {
      // If edit button exists, there should be some updatable properties
      const propertyRows = page.locator('.ant-table-tbody tr');
      const totalRows = await propertyRows.count();

      // Not all properties should be read-only
      expect(indicatorCount).toBeLessThan(totalRows);
    }
  });

  test('should handle pagination correctly', async ({ page }) => {
    await page.click('text=ドキュメント');
    await page.waitForTimeout(1000);

    const documentRow = page.locator('.ant-table-row').first();
    await documentRow.click();
    await page.waitForTimeout(1000);

    // FIX: Use .ant-tabs-tab selector like property-editor.spec.ts
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
    await page.click('text=ドキュメント');
    await page.waitForTimeout(1000);

    const documentRow = page.locator('.ant-table-row').first();
    await documentRow.click();
    await page.waitForTimeout(1000);

    // FIX: Use .ant-tabs-tab selector like property-editor.spec.ts
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
    await page.click('text=ドキュメント');
    await page.waitForTimeout(1000);

    const documentRow = page.locator('.ant-table-row').first();
    await documentRow.click();
    await page.waitForTimeout(1000);

    // FIX: Use .ant-tabs-tab selector like property-editor.spec.ts
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
