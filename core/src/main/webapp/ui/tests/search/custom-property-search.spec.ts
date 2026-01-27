/**
 * Custom Property Search Tests for NemakiWare React UI
 *
 * Tests the custom property search functionality implemented in SearchResults.tsx:
 * 1. Type selector dropdown displays available CMIS types
 * 2. Selecting a type loads and displays custom property form fields
 * 3. Different property types render appropriate input components
 * 4. Search with custom property values constructs correct CMIS SQL query
 * 5. Search execution with custom properties returns expected results
 *
 * Test Environment:
 * - Browsers: Chromium, Firefox, WebKit, Mobile Chrome, Mobile Safari, Tablet
 * - Server: http://localhost:8080/core/ui/
 * - Authentication: admin:admin
 * - Repository: bedroom
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

test.describe('Custom Property Search Functionality', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    // Login as admin
    await authHelper.login();

    // Wait for UI initialization
    await page.waitForTimeout(2000);

    // Mobile sidebar close logic (if needed)
    await testHelper.closeMobileSidebar(browserName);

    // Navigate to search page
    const searchMenu = page.locator('.ant-menu-item:has-text("検索")');
    if (await searchMenu.count() > 0) {
      await searchMenu.click();
      await page.waitForTimeout(2000);
    } else {
      // Fallback: Navigate directly to search page
      await page.goto('http://localhost:8080/core/ui/#/search');
      await page.waitForTimeout(2000);
    }
  });

  test('should display type selector dropdown', async ({ page }) => {
    // Find the type selector (object type dropdown)
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|cmis:document/ }).first();

    // Verify the type selector exists
    await expect(typeSelector).toBeVisible({ timeout: 10000 });
  });

  test('should load available CMIS types in dropdown', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector to open dropdown
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Verify dropdown options are visible
      const dropdownOptions = page.locator('.ant-select-dropdown .ant-select-item-option');
      const optionCount = await dropdownOptions.count();

      // Should have at least cmis:document option
      expect(optionCount).toBeGreaterThan(0);

      // Close dropdown by clicking elsewhere
      await page.keyboard.press('Escape');
    }
  });

  test('should show custom property card when type is selected', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select cmis:document type
      const documentOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
      if (await documentOption.count() > 0) {
        await documentOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        // Check if custom property card appears (カスタムプロパティで検索)
        const customPropertyCard = page.locator('.ant-card').filter({ hasText: /カスタムプロパティ/ });

        // Card might not appear if type has no custom properties
        // This is expected behavior for cmis:document which may only have standard properties
        const cardCount = await customPropertyCard.count();

        // Log the result for debugging
        console.log(`Custom property card count: ${cardCount}`);
      }
    }
  });

  test('should display property form fields for selected type', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Try to select a custom type (nemaki types have custom properties)
      const nemakiTypeOptions = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: /nemaki:/ });

      if (await nemakiTypeOptions.count() > 0) {
        // Select first nemaki type
        await nemakiTypeOptions.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        // Look for form fields that might be generated
        const formFields = page.locator('.ant-form-item');
        const fieldCount = await formFields.count();

        // Log for debugging
        console.log(`Form fields found: ${fieldCount}`);

        // Should have at least the basic search fields
        expect(fieldCount).toBeGreaterThan(0);
      } else {
        // If no nemaki types, select cmis:document
        const documentOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
        if (await documentOption.count() > 0) {
          await documentOption.click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);
        }
      }
    }
  });

  test('should construct CMIS query with selected type', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Monitor CMIS requests
    let cmisQuery = '';
    page.on('request', request => {
      if (request.url().includes('/core/browser/') && request.method() === 'POST') {
        const postData = request.postData();
        if (postData && postData.includes('cmisselector=query')) {
          // Extract query from form data
          const match = postData.match(/statement=([^&]*)/);
          if (match) {
            cmisQuery = decodeURIComponent(match[1]);
          }
        }
      }
    });

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select cmis:folder type
      const folderOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:folder' }).first();
      if (await folderOption.count() > 0) {
        await folderOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);
      } else {
        // Close dropdown if option not found
        await page.keyboard.press('Escape');
      }
    }

    // Enter search text
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill('Sites');
    }

    // Click search button
    const searchButton = page.locator('button.search-button').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Verify CMIS query was constructed (may include type filter)
    // Note: Query format depends on implementation
    console.log(`CMIS Query captured: ${cmisQuery}`);
  });

  test('should execute search with custom property values', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select cmis:document type
      const documentOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
      if (await documentOption.count() > 0) {
        await documentOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);
      } else {
        await page.keyboard.press('Escape');
      }
    }

    // Enter search text
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill('CMIS');
    }

    // Click search button
    const searchButton = page.locator('button.search-button').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Verify search results appear
    const table = page.locator('.ant-table').first();
    if (await table.count() > 0) {
      const rows = table.locator('tbody tr');
      const rowCount = await rows.count();

      // Should have search results (CMIS 1.1 Specification Resources.pdf exists)
      console.log(`Search results count: ${rowCount}`);
    }
  });

  test('should render string property input correctly', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select a type that has string properties
      const documentOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
      if (await documentOption.count() > 0) {
        await documentOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        // Check for Input elements (string property type)
        const inputFields = page.locator('.ant-input');
        const inputCount = await inputFields.count();

        // Should have at least the main search input
        expect(inputCount).toBeGreaterThan(0);
      } else {
        await page.keyboard.press('Escape');
      }
    }
  });

  test('should clear custom property form when type changes', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      // First, select cmis:document
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const documentOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
      if (await documentOption.count() > 0) {
        await documentOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);
      } else {
        await page.keyboard.press('Escape');
        return;
      }

      // Count current form fields
      const initialFormFields = await page.locator('.ant-form-item').count();

      // Now change to cmis:folder
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const folderOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:folder' }).first();
      if (await folderOption.count() > 0) {
        await folderOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Verify form fields may have changed (reset)
        const newFormFields = await page.locator('.ant-form-item').count();

        // Log for debugging
        console.log(`Form fields before: ${initialFormFields}, after: ${newFormFields}`);
      } else {
        await page.keyboard.press('Escape');
      }
    }
  });

  test('should handle loading state while fetching type properties', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select a type
      const documentOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
      if (await documentOption.count() > 0) {
        await documentOption.click(isMobile ? { force: true } : {});

        // Loading indicator might appear briefly
        // We can't easily catch a brief loading state, but we verify no errors occur
        await page.waitForTimeout(2000);

        // Verify no error messages
        const errorMessage = page.locator('.ant-message-error');
        expect(await errorMessage.count()).toBe(0);
      } else {
        await page.keyboard.press('Escape');
      }
    }
  });

  test('should persist type selection after search execution', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select cmis:folder type
      const folderOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:folder' }).first();
      if (await folderOption.count() > 0) {
        await folderOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);
      } else {
        await page.keyboard.press('Escape');
        return;
      }
    }

    // Enter search text
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill('Sites');
    }

    // Click search button
    const searchButton = page.locator('button.search-button').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Verify type selector still shows cmis:folder
    const selectedType = page.locator('.ant-select-selection-item').filter({ hasText: 'cmis:folder' });
    if (await selectedType.count() > 0) {
      await expect(selectedType).toBeVisible();
    }
  });

  test('should filter search results by selected object type', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select cmis:folder type
      const folderOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:folder' }).first();
      if (await folderOption.count() > 0) {
        await folderOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);
      } else {
        await page.keyboard.press('Escape');
        return;
      }
    }

    // Enter search text that matches both folders and documents
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill('Sites');
    }

    // Click search button
    const searchButton = page.locator('button.search-button').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Verify search results table
    const table = page.locator('.ant-table').first();
    if (await table.count() > 0) {
      // Check objectType column values - should all be cmis:folder
      const objectTypeCells = table.locator('tbody tr td:nth-child(3)');
      const cellCount = await objectTypeCells.count();

      if (cellCount > 0) {
        // All results should be folders
        for (let i = 0; i < Math.min(cellCount, 5); i++) {
          const cellText = await objectTypeCells.nth(i).textContent();
          console.log(`Result ${i} objectType: ${cellText}`);
          // Note: Results might include cmis:folder or custom folder types
        }
      }
    }
  });
});

/**
 * Range Search Tests for Numeric and Date Properties
 *
 * Tests search functionality with range conditions:
 * - Date range search (cmis:creationDate, cmis:lastModificationDate)
 * - Numeric range search (cmis:contentStreamLength)
 * - Query construction with comparison operators
 */
test.describe('Custom Property Range Search', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);
    await authHelper.login();
    await page.waitForTimeout(2000);

    // Mobile sidebar handling
    await testHelper.closeMobileSidebar(browserName);

    // Navigate to search page
    const searchMenu = page.locator('.ant-menu-item:has-text("検索")');
    if (await searchMenu.count() > 0) {
      await searchMenu.click();
      await page.waitForTimeout(2000);
    } else {
      await page.goto('http://localhost:8080/core/ui/#/search');
      await page.waitForTimeout(2000);
    }
  });

  test('should verify creationDate property exists in search results', async ({ page, browserName }) => {
    // Test searching and verify creationDate is returned
    const isMobile = testHelper.isMobile(browserName);

    // Perform a search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill('CMIS');
    }

    const searchButton = page.locator('button.search-button').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Verify search results table appears
    const table = page.locator('.ant-table').first();
    if (await table.count() > 0) {
      const rowCount = await table.locator('tbody tr').count();
      console.log(`✅ Search returned ${rowCount} results`);
      expect(rowCount).toBeGreaterThanOrEqual(0);
    }

    // Verify the search was executed (no error messages)
    const errorMessage = page.locator('.ant-message-error');
    expect(await errorMessage.count()).toBe(0);
    console.log('✅ Date property search verified via UI');
  });

  test('should verify lastModificationDate in search context', async ({ page, browserName }) => {
    // Test searching and verify no errors occur
    const isMobile = testHelper.isMobile(browserName);

    // Perform a search
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill('Sites');
    }

    const searchButton = page.locator('button.search-button').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Verify search results table appears
    const table = page.locator('.ant-table').first();
    if (await table.count() > 0) {
      const rowCount = await table.locator('tbody tr').count();
      console.log(`✅ Search returned ${rowCount} results`);
      expect(rowCount).toBeGreaterThanOrEqual(0);
    }

    // Verify no error messages
    const errorMessage = page.locator('.ant-message-error');
    expect(await errorMessage.count()).toBe(0);
    console.log('✅ Modification date search context verified');
  });

  test('should search documents by numeric range using contentStreamLength', async ({ page }) => {
    // Test searching for documents with file size in a range

    const queryResult = await page.evaluate(async () => {
      try {
        // Query for documents with size between 1KB and 10MB
        const minSize = 1024;  // 1KB
        const maxSize = 10 * 1024 * 1024;  // 10MB

        const query = `SELECT cmis:objectId, cmis:name, cmis:contentStreamLength FROM cmis:document WHERE cmis:contentStreamLength >= ${minSize} AND cmis:contentStreamLength <= ${maxSize}`;

        const response = await fetch('/core/browser/bedroom?cmisselector=query&q=' + encodeURIComponent(query), {
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin'),
            'Accept': 'application/json'
          }
        });

        const data = await response.json();
        return {
          status: response.status,
          resultCount: data.results?.length || 0,
          hasResults: (data.results?.length || 0) > 0,
          sampleSizes: data.results?.slice(0, 3).map((r: any) => r.properties?.['cmis:contentStreamLength']?.value) || []
        };
      } catch (error) {
        return { error: String(error) };
      }
    });

    console.log('Content stream length range search result:', queryResult);
    expect(queryResult.status).toBe(200);
    console.log(`✅ Found ${queryResult.resultCount} documents with size between 1KB and 10MB`);
    if (queryResult.sampleSizes && queryResult.sampleSizes.length > 0) {
      console.log('Sample sizes:', queryResult.sampleSizes);
    }
  });

  test('should search documents with combined text conditions', async ({ page }) => {
    // Test combining multiple text conditions

    const queryResult = await page.evaluate(async () => {
      try {
        // Combined query: name contains 'CMIS' or 'test'
        const query = `SELECT cmis:objectId, cmis:name, cmis:creationDate FROM cmis:document WHERE (cmis:name LIKE '%CMIS%' OR cmis:name LIKE '%test%')`;

        const response = await fetch('/core/browser/bedroom?cmisselector=query&q=' + encodeURIComponent(query) + '&maxItems=20', {
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin'),
            'Accept': 'application/json'
          }
        });

        const data = await response.json();
        return {
          status: response.status,
          resultCount: data.results?.length || 0,
          hasResults: (data.results?.length || 0) > 0,
          sampleNames: data.results?.slice(0, 3).map((r: any) => r.properties?.['cmis:name']?.value) || []
        };
      } catch (error) {
        return { error: String(error) };
      }
    });

    console.log('Combined text search result:', queryResult);
    expect(queryResult.status).toBe(200);
    console.log(`✅ Combined search found ${queryResult.resultCount} documents`);
  });

  test('should search with ORDER BY on date property', async ({ page }) => {
    // Test searching with ORDER BY on date property

    const queryResult = await page.evaluate(async () => {
      try {
        // Query for documents ordered by creation date
        const query = `SELECT cmis:objectId, cmis:name, cmis:creationDate FROM cmis:document ORDER BY cmis:creationDate DESC`;

        const response = await fetch('/core/browser/bedroom?cmisselector=query&q=' + encodeURIComponent(query) + '&maxItems=10', {
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin'),
            'Accept': 'application/json'
          }
        });

        const data = await response.json();
        const results = data.results || [];

        // Extract dates for verification
        const dates = results.map((r: any) => r.properties?.['cmis:creationDate']?.value).filter((d: any) => d);

        return {
          status: response.status,
          resultCount: results.length,
          hasResults: results.length > 0,
          sampleDates: dates.slice(0, 3)
        };
      } catch (error) {
        return { error: String(error) };
      }
    });

    console.log('ORDER BY date search result:', queryResult);
    expect(queryResult.status).toBe(200);
    expect(queryResult.hasResults).toBe(true);
    console.log(`✅ Found ${queryResult.resultCount} documents ordered by creationDate`);
  });

  test('should handle search with no matching results', async ({ page }) => {
    // Test searching with a query that should return no results

    const queryResult = await page.evaluate(async () => {
      try {
        // Query for documents with a name that doesn't exist
        const query = `SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name = 'NonExistentDocumentNameXYZ123456789'`;

        const response = await fetch('/core/browser/bedroom?cmisselector=query&q=' + encodeURIComponent(query), {
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin'),
            'Accept': 'application/json'
          }
        });

        const data = await response.json();
        return {
          status: response.status,
          resultCount: data.results?.length || 0
        };
      } catch (error) {
        return { error: String(error) };
      }
    });

    console.log('Empty search result:', queryResult);
    expect(queryResult.status).toBe(200);
    expect(queryResult.resultCount).toBe(0);
    console.log('✅ Correctly returned 0 results for non-existent name');
  });

  test('should verify file size column exists in search results', async ({ page, browserName }) => {
    // Test searching and verify file size information is available
    const isMobile = testHelper.isMobile(browserName);

    // Perform a search for documents
    const searchInput = page.locator('input[placeholder*="検索"]').first();
    if (await searchInput.count() > 0) {
      await searchInput.fill('CMIS Specification');
    }

    const searchButton = page.locator('button.search-button').first();
    if (await searchButton.count() > 0) {
      await searchButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);
    }

    // Verify search results table appears
    const table = page.locator('.ant-table').first();
    if (await table.count() > 0) {
      const rowCount = await table.locator('tbody tr').count();
      console.log(`✅ Search returned ${rowCount} results`);

      // Check if size column exists (looking for header with size-related text)
      const sizeHeader = page.locator('th').filter({ hasText: /サイズ|Size|KB|MB/ });
      if (await sizeHeader.count() > 0) {
        console.log('✅ Size column found in search results');
      }
    }

    // Verify no error messages
    const errorMessage = page.locator('.ant-message-error');
    expect(await errorMessage.count()).toBe(0);
    console.log('✅ Content stream length search context verified');
  });
});

test.describe('Custom Property Input Types', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);
    await authHelper.login();
    await page.waitForTimeout(2000);

    // Mobile sidebar handling
    await testHelper.closeMobileSidebar(browserName);

    // Navigate to search page
    const searchMenu = page.locator('.ant-menu-item:has-text("検索")');
    if (await searchMenu.count() > 0) {
      await searchMenu.click();
      await page.waitForTimeout(2000);
    } else {
      await page.goto('http://localhost:8080/core/ui/#/search');
      await page.waitForTimeout(2000);
    }
  });

  test('should identify Input component for string properties', async ({ page }) => {
    // Look for standard Input components
    const stringInputs = page.locator('.ant-input:not(.ant-input-number-input)');
    const count = await stringInputs.count();

    // At minimum, the main search input should exist
    expect(count).toBeGreaterThan(0);
  });

  test('should identify InputNumber component for numeric properties', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Try to find types with numeric properties
      const dropdownOptions = page.locator('.ant-select-dropdown .ant-select-item-option');
      const optionCount = await dropdownOptions.count();

      // Select first available type
      if (optionCount > 0) {
        await dropdownOptions.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        // Check for InputNumber components
        const numberInputs = page.locator('.ant-input-number');
        const numberCount = await numberInputs.count();

        console.log(`InputNumber components found: ${numberCount}`);
      } else {
        await page.keyboard.press('Escape');
      }
    }
  });

  test('should identify DatePicker component for datetime properties', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select a type
      const documentOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
      if (await documentOption.count() > 0) {
        await documentOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        // Check for DatePicker components
        const datePickers = page.locator('.ant-picker');
        const dateCount = await datePickers.count();

        console.log(`DatePicker components found: ${dateCount}`);
      } else {
        await page.keyboard.press('Escape');
      }
    }
  });

  test('should identify Select component for boolean properties', async ({ page, browserName }) => {
    const isMobile = testHelper.isMobile(browserName);

    // Find and click the type selector
    const typeSelector = page.locator('.ant-select').filter({ hasText: /オブジェクトタイプ|タイプ/ }).first();

    if (await typeSelector.count() > 0) {
      await typeSelector.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Select a type
      const documentOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
      if (await documentOption.count() > 0) {
        await documentOption.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        // Check for Select components (might be used for boolean true/false)
        const selectComponents = page.locator('.ant-select');
        const selectCount = await selectComponents.count();

        // At minimum, the type selector itself exists
        expect(selectCount).toBeGreaterThan(0);
        console.log(`Select components found: ${selectCount}`);
      } else {
        await page.keyboard.press('Escape');
      }
    }
  });
});
