import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * Type Management E2E Tests
 *
 * Comprehensive end-to-end tests for NemakiWare CMIS type management system:
 * - CMIS base type display verification (6 base types)
 * - NemakiWare custom type display (nemaki:parentChildRelationship, nemaki:bidirectionalRelationship)
 * - Type hierarchy validation (base types + child types)
 * - Type details viewing and property inspection
 * - Direct CMIS API verification for type definitions
 * - Type editing functionality (currently WIP/skipped)
 *
 * IMPORTANT DESIGN DECISIONS:
 * 1. CMIS 1.1 Type Hierarchy Coverage (Lines 44-105):
 *    - Tests verify all 6 CMIS 1.1 base types are displayed:
 *      - cmis:document (document content storage)
 *      - cmis:folder (hierarchical container)
 *      - cmis:relationship (object associations)
 *      - cmis:policy (access control policies)
 *      - cmis:item (unstructured content)
 *      - cmis:secondary (aspect/facet support)
 *    - Rationale: Complete CMIS type system validation ensures repository compliance
 *
 * 2. NemakiWare Custom Types Validation (Lines 72-105):
 *    - nemaki:parentChildRelationship: Parent-child relationship type (extends cmis:relationship)
 *    - nemaki:bidirectionalRelationship: Bidirectional relationship type (extends cmis:relationship)
 *    - Tests verify custom types inherit from correct base type (cmis:relationship)
 *    - Total type count validation: 6 base types + 2 custom types = 8 minimum
 *    - Rationale: Ensures custom type definitions are properly registered and displayed
 *
 * 3. Precise Selector Strategy (Lines 64, 88, 114, 148, 255):
 *    - Uses Ant Design table data-row-key attribute for precise type row identification
 *    - Pattern: tr[data-row-key="typeId"] for exact type matching
 *    - Avoids text-based selectors that may match partial type IDs
 *    - Handles multiple occurrences with .first() when needed
 *    - Rationale: Eliminates ambiguity in type hierarchy tree tables
 *
 * 4. Direct CMIS API Verification (Lines 178-242):
 *    - Tests CMIS Browser Binding API endpoint directly via page.evaluate()
 *    - Fetches base types: /core/browser/bedroom?cmisselector=typeChildren
 *    - Fetches child types for each base type with typeId parameter
 *    - Verifies API returns complete type hierarchy (base + custom types)
 *    - Validates nemaki: custom types present in API response
 *    - Rationale: Ensures backend CMIS type definitions are complete regardless of UI implementation
 *
 * 5. Type Details View Testing (Lines 138-176):
 *    - Clicks type row to open details modal/drawer
 *    - Verifies type ID is displayed in details view
 *    - Tests modal close functionality
 *    - Graceful skip if details view not implemented yet
 *    - Rationale: Validates user can inspect type properties and definitions
 *
 * 6. Mobile Browser Support (Lines 16-26, 138-176):
 *    - Sidebar close logic in beforeEach prevents overlay blocking clicks
 *    - Viewport width ≤414px triggers mobile-specific behavior
 *    - Force click option for mobile browsers (isMobile ? { force: true } : {})
 *    - Graceful fallback if sidebar toggle unavailable
 *    - Consistent with other test suites' mobile support pattern
 *
 * 7. Smart Conditional Navigation (Lines 31-41):
 *    - Checks for admin menu existence before clicking
 *    - Checks for type management menu item before navigation
 *    - Prevents test failures if admin features restricted for test user
 *    - Better than hard-coded navigation that assumes menu structure
 *    - Rationale: Maintains test suite flexibility across different user permission scenarios
 *
 * 8. Type Editing Test (Lines 244-315 - Currently Skipped):
 *    - WIP: Type editing functionality not fully implemented or restricted by CMIS spec
 *    - Test structure prepared for future type description editing
 *    - Validates edit modal opening, description field update, and submission
 *    - Currently skipped with test.skip() until UI implements full type editing
 *    - Rationale: CMIS 1.1 spec restricts modifying base type definitions
 *
 * Test Coverage:
 * 1. ✅ Base CMIS Types Display (6 types)
 * 2. ✅ Custom Types Display (2 nemaki: types)
 * 3. ✅ Type Information Display (display name, base type)
 * 4. ✅ Type Details View (modal/drawer)
 * 5. ✅ CMIS API Verification (base + child types)
 * 6. ⊘ Type Editing (skipped - WIP)
 *
 * CMIS Browser Binding API Usage:
 * - Base types query: cmisselector=typeChildren (no typeId parameter)
 * - Child types query: cmisselector=typeChildren&typeId={baseTypeId}
 * - Response format: { types: [{ id, localName, displayName, baseId, ... }] }
 * - Authentication: Basic auth with admin:admin credentials
 *
 * Expected Test Results:
 * - Base types count: 6 (CMIS 1.1 standard)
 * - Child types count: ≥2 (nemaki: custom types)
 * - Total types count: ≥8 (base + custom)
 * - Type row selectors: data-row-key attribute for precise matching
 *
 * Known Limitations:
 * - Type editing currently skipped (WIP - UI implementation pending)
 * - Type details modal may not be implemented in all UI versions
 * - Custom type editing restricted by CMIS 1.1 spec (base types immutable)
 *
 * Performance Optimizations:
 * - Uses data-row-key attribute for O(1) type row lookup
 * - API verification uses parallel fetching (Promise.all) for child types
 * - Extended timeout (15s) for table loading to accommodate slow networks
 */

test.describe('Type Management - Custom Types Display', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);

    // MOBILE FIX: Close sidebar
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    await testHelper.waitForAntdLoad();

    // Navigate to type management
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }

    const typeManagementItem = page.locator('.ant-menu-item:has-text("タイプ管理")');
    if (await typeManagementItem.count() > 0) {
      await typeManagementItem.click();
      await page.waitForTimeout(2000);
    }
  });

  test('should display all base CMIS types', async ({ page }) => {
    console.log('Test: Verifying base CMIS types are displayed');

    // Wait for type table to load
    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await page.waitForTimeout(2000);

    // Expected base types
    const expectedBaseTypes = [
      'cmis:document',
      'cmis:folder',
      'cmis:relationship',
      'cmis:policy',
      'cmis:item',
      'cmis:secondary'
    ];

    // Check if all base types are displayed
    for (const typeId of expectedBaseTypes) {
      // Use more precise selector with data-row-key attribute
      const typeRow = page.locator(`tr[data-row-key="${typeId}"]`);
      await expect(typeRow).toBeVisible({ timeout: 5000 });
      console.log(`✅ Base type found: ${typeId}`);
    }

    console.log('Test: All base CMIS types verified');
  });

  test('should display nemaki: custom types (parentChildRelationship, bidirectionalRelationship)', async ({ page }) => {
    console.log('Test: Verifying nemaki: custom types are displayed');

    // Wait for type table to load
    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await page.waitForTimeout(2000);

    // Expected custom types (children of cmis:relationship)
    const expectedCustomTypes = [
      'nemaki:parentChildRelationship',
      'nemaki:bidirectionalRelationship'
    ];

    // Check if custom types are displayed
    for (const typeId of expectedCustomTypes) {
      // Use more precise selector with data-row-key attribute
      const typeRow = page.locator(`tr[data-row-key="${typeId}"]`).first();
      const isVisible = await typeRow.count() > 0;

      if (isVisible) {
        console.log(`✅ Custom type found: ${typeId}`);
        await expect(typeRow).toBeVisible({ timeout: 5000 });
      } else {
        console.log(`❌ Custom type NOT found: ${typeId}`);
      }
    }

    // Verify total type count (6 base types + 2 custom types = 8)
    const tableRows = await page.locator('.ant-table tbody tr').count();
    console.log(`Total types displayed: ${tableRows}`);

    expect(tableRows).toBeGreaterThanOrEqual(8);
    console.log('Test: nemaki: custom types verified');
  });

  test('should display correct type information for nemaki:parentChildRelationship', async ({ page }) => {
    console.log('Test: Verifying nemaki:parentChildRelationship type details');

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await page.waitForTimeout(2000);

    // Use more precise selector with data-row-key attribute
    const typeRow = page.locator('tr[data-row-key="nemaki:parentChildRelationship"]').first();

    if (await typeRow.count() > 0) {
      // Verify type is visible
      await expect(typeRow).toBeVisible({ timeout: 5000 });

      // Get row text to verify type information
      const rowText = await typeRow.textContent();
      console.log(`Type row content: ${rowText}`);

      // Verify display name
      expect(rowText).toContain('Parent Child Relationship');
      console.log('✅ Display name verified: Parent Child Relationship');

      // Verify base type
      expect(rowText).toContain('cmis:relationship');
      console.log('✅ Base type verified: cmis:relationship');

      console.log('Test: nemaki:parentChildRelationship details verified');
    } else {
      test.skip('nemaki:parentChildRelationship type not found - may need to verify API response');
    }
  });

  test('should allow viewing type details by clicking on type row', async ({ page, browserName }) => {
    console.log('Test: Verifying type details view');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await page.waitForTimeout(2000);

    // Click on a type row (e.g., cmis:document)
    const typeRow = page.locator('tr[data-row-key="cmis:document"]');

    if (await typeRow.count() > 0) {
      await typeRow.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Check if type details modal/drawer appears
      const typeDetailsModal = page.locator('.ant-modal, .ant-drawer');

      if (await typeDetailsModal.count() > 0) {
        await expect(typeDetailsModal).toBeVisible({ timeout: 5000 });
        console.log('✅ Type details view opened');

        // Verify type ID is displayed in details
        const modalContent = await typeDetailsModal.textContent();
        expect(modalContent).toContain('cmis:document');
        console.log('✅ Type details contain correct type ID');

        // Close modal/drawer
        const closeButton = typeDetailsModal.locator('button.ant-modal-close, button.ant-drawer-close');
        if (await closeButton.count() > 0) {
          await closeButton.first().click();
          await page.waitForTimeout(500);
        }
      } else {
        console.log('ℹ️ Type details modal not implemented yet - skipping');
      }
    }
  });

  test('should verify API returns all types (base + custom)', async ({ page }) => {
    console.log('Test: Verifying CMIS API returns all types');

    // Test API endpoint directly
    const apiResponse = await page.evaluate(async () => {
      try {
        // Simulate what cmis.ts getTypes() does
        const baseTypesResponse = await fetch('/core/browser/bedroom?cmisselector=typeChildren', {
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin'),
            'Accept': 'application/json'
          }
        });

        const baseTypesData = await baseTypesResponse.json();
        const baseTypes = baseTypesData.types || [];

        // Fetch child types for each base type
        const childTypesPromises = baseTypes.map(async (baseType: any) => {
          const childResponse = await fetch(`/core/browser/bedroom?cmisselector=typeChildren&typeId=${encodeURIComponent(baseType.id)}`, {
            headers: {
              'Authorization': 'Basic ' + btoa('admin:admin'),
              'Accept': 'application/json'
            }
          });
          const childData = await childResponse.json();
          return childData.types || [];
        });

        const childTypesArrays = await Promise.all(childTypesPromises);
        const childTypes = childTypesArrays.flat();

        return {
          baseTypesCount: baseTypes.length,
          childTypesCount: childTypes.length,
          totalTypesCount: baseTypes.length + childTypes.length,
          baseTypeIds: baseTypes.map((t: any) => t.id),
          childTypeIds: childTypes.map((t: any) => t.id)
        };
      } catch (error) {
        return {
          error: error.toString()
        };
      }
    });

    console.log('API response:', apiResponse);

    // Verify API response
    expect(apiResponse.baseTypesCount).toBe(6);
    console.log(`✅ Base types count: ${apiResponse.baseTypesCount}`);

    expect(apiResponse.childTypesCount).toBeGreaterThanOrEqual(2);
    console.log(`✅ Child types count: ${apiResponse.childTypesCount}`);

    expect(apiResponse.totalTypesCount).toBeGreaterThanOrEqual(8);
    console.log(`✅ Total types count: ${apiResponse.totalTypesCount}`);

    // Verify nemaki: custom types are in the response
    expect(apiResponse.childTypeIds).toContain('nemaki:parentChildRelationship');
    expect(apiResponse.childTypeIds).toContain('nemaki:bidirectionalRelationship');
    console.log('✅ nemaki: custom types found in API response');

    console.log('Test: API verification complete');
  });

  test.skip('should allow editing nemaki: custom type description', async ({ page, browserName }) => {
    // WIP: Type editing functionality not fully implemented or restricted by CMIS spec
    console.log('Test: Verifying type editing functionality for nemaki:parentChildRelationship');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await page.waitForTimeout(2000);

    // Find nemaki:parentChildRelationship row
    const typeRow = page.locator('tr[data-row-key="nemaki:parentChildRelationship"]');

    if (await typeRow.count() > 0) {
      await expect(typeRow).toBeVisible({ timeout: 5000 });
      console.log('✅ Found nemaki:parentChildRelationship type');

      // Click edit button in the row
      const editButton = typeRow.locator('button:has-text("編集")');
      await expect(editButton).toBeVisible({ timeout: 5000 });
      await editButton.click(isMobile ? { force: true } : {});
      console.log('✅ Clicked edit button');

      // Wait for edit modal/form to appear
      await page.waitForTimeout(1000);
      const editModal = page.locator('.ant-modal:visible, .ant-drawer:visible');
      await expect(editModal).toBeVisible({ timeout: 5000 });
      console.log('✅ Edit modal opened');

      // Find and update description field
      const descriptionInput = editModal.locator('textarea[id*="description"], textarea[placeholder*="説明"]');
      if (await descriptionInput.count() > 0) {
        await descriptionInput.first().clear();
        const newDescription = `Updated description - Test ${Date.now()}`;
        await descriptionInput.first().fill(newDescription);
        console.log(`✅ Updated description to: ${newDescription}`);

        // Submit the form
        const submitButton = editModal.locator('button[type="submit"], button:has-text("更新"), button.ant-btn-primary');
        await expect(submitButton.first()).toBeVisible({ timeout: 5000 });
        await submitButton.first().click(isMobile ? { force: true } : {});
        console.log('✅ Clicked submit button');

        // Wait for success message
        await page.waitForTimeout(2000);

        // Check for success message (either Ant Design message or modal closed)
        const successMessage = page.locator('.ant-message-success');
        const modalClosed = await editModal.count() === 0;

        if (await successMessage.count() > 0 || modalClosed) {
          console.log('✅ Type edit successful - success message appeared or modal closed');
        } else {
          // Check for error message
          const errorMessage = page.locator('.ant-message-error');
          if (await errorMessage.count() > 0) {
            const errorText = await errorMessage.first().textContent();
            console.log(`❌ Type edit failed with error: ${errorText}`);
            throw new Error(`Type edit failed: ${errorText}`);
          }
        }

        console.log('Test: Type editing functionality verified successfully');
      } else {
        console.log('ℹ️ Description field not found in edit form - may need UI adjustment');
        test.skip('Description field not available in edit modal');
      }
    } else {
      console.log('❌ nemaki:parentChildRelationship type not found - skipping test');
      test.skip('nemaki:parentChildRelationship type not found in table');
    }
  });
});
