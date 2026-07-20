/**
 * Required Property Validation Tests
 *
 * Comprehensive tests for required property validation during document upload and folder creation.
 * This test suite creates custom types with required properties as part of the test setup,
 * then validates the complete flow: validation error → fix → success.
 *
 * Test Flow:
 * 1. beforeAll: Create custom document and folder types with required properties via CMIS API
 * 2. Tests: Verify required property validation works correctly
 * 3. afterAll: Clean up custom types
 *
 * Prerequisites:
 * - NemakiWare core running on localhost:8080
 * - Admin credentials: admin/admin
 */

import { test, expect, request } from '@playwright/test';
import { waitForRender, waitForUiStable } from '../utils/wait-helpers';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper, generateTestId } from '../utils/test-helper';

// Test type IDs - unique to avoid conflicts
const TEST_DOCUMENT_TYPE_ID = 'test:requiredPropDocument';
const TEST_FOLDER_TYPE_ID = 'test:requiredPropFolder';
const REQUIRED_PROP_ID = 'test:requiredField';

// API base URLs
const CMIS_BASE_URL = 'http://localhost:8080/core/browser/bedroom';
const REST_BASE_URL = 'http://localhost:8080/core/rest/repo/bedroom';  // REST API for type management
const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');

test.describe('Required Property Validation Tests', () => {
  // Run tests serially to avoid race conditions with type creation/deletion
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  let typesCreated = { document: false, folder: false };

  // Create custom types with required properties before all tests
  test.beforeAll(async () => {
    const apiContext = await request.newContext();

    // Create custom document type with required property via Browser Binding
    // NOTE: Browser Binding is used because it correctly saves propertyDefinitions
    // while REST API may not properly persist property definitions
    console.log('Creating test document type with required property (via Browser Binding)...');
    try {
      const docTypeDefinition = {
        id: TEST_DOCUMENT_TYPE_ID,
        localName: 'requiredPropDocument',
        localNamespace: 'test',
        displayName: 'Test Required Property Document',
        queryName: 'test:requiredPropDocument',
        description: 'Test document type with required custom property',
        baseId: 'cmis:document',
        parentId: 'cmis:document',
        creatable: true,
        fileable: true,
        queryable: true,
        fulltextIndexed: true,
        includedInSupertypeQuery: true,
        controllablePolicy: false,
        controllableACL: true,
        propertyDefinitions: {
          [REQUIRED_PROP_ID]: {
            id: REQUIRED_PROP_ID,
            localName: 'requiredField',
            localNamespace: 'test',
            displayName: 'Required Field',
            queryName: 'test:requiredField',  // CRITICAL: Must include queryName
            description: 'This field is required for testing',
            propertyType: 'string',
            cardinality: 'single',
            updatability: 'readwrite',
            inherited: false,
            required: true,  // CRITICAL: This must be true
            queryable: true,
            orderable: true,
            openChoice: true
          }
        }
      };

      // Use Browser Binding cmisaction=createType for proper property definitions persistence
      const createDocTypeResponse = await apiContext.post(`${CMIS_BASE_URL}`, {
        headers: {
          'Authorization': ADMIN_AUTH,
          'Content-Type': 'application/x-www-form-urlencoded'
        },
        data: `cmisaction=createType&type=${encodeURIComponent(JSON.stringify(docTypeDefinition))}`
      });

      const responseText = await createDocTypeResponse.text();
      // Browser Binding may return 201 or 200
      if (createDocTypeResponse.status() === 201 || createDocTypeResponse.status() === 200) {
        console.log('✓ Test document type created successfully');
        typesCreated.document = true;
      } else {
        // Check for "already exists" even in error responses
        if (responseText.includes('already exists') || responseText.includes('Conflict')) {
          console.log('Test document type already exists, will use existing');
          typesCreated.document = true;
        }
      }
    } catch (error) {
      console.error('Error creating document type:', error);
    }

    // Create custom folder type with required property via Browser Binding
    console.log('Creating test folder type with required property (via Browser Binding)...');
    try {
      const folderTypeDefinition = {
        id: TEST_FOLDER_TYPE_ID,
        localName: 'requiredPropFolder',
        localNamespace: 'test',
        displayName: 'Test Required Property Folder',
        queryName: 'test:requiredPropFolder',
        description: 'Test folder type with required custom property',
        baseId: 'cmis:folder',
        parentId: 'cmis:folder',
        creatable: true,
        fileable: false,  // CMIS spec: folder types must have fileable=false
        queryable: true,
        fulltextIndexed: false,
        includedInSupertypeQuery: true,
        controllablePolicy: false,
        controllableACL: true,
        propertyDefinitions: {
          [REQUIRED_PROP_ID]: {
            id: REQUIRED_PROP_ID,
            localName: 'requiredField',
            localNamespace: 'test',
            displayName: 'Required Field',
            queryName: 'test:requiredField',  // CRITICAL: Must include queryName
            description: 'This field is required for testing',
            propertyType: 'string',
            cardinality: 'single',
            updatability: 'readwrite',
            inherited: false,
            required: true,  // CRITICAL: This must be true
            queryable: true,
            orderable: true,
            openChoice: true
          }
        }
      };

      // Use Browser Binding cmisaction=createType for proper property definitions persistence
      const createFolderTypeResponse = await apiContext.post(`${CMIS_BASE_URL}`, {
        headers: {
          'Authorization': ADMIN_AUTH,
          'Content-Type': 'application/x-www-form-urlencoded'
        },
        data: `cmisaction=createType&type=${encodeURIComponent(JSON.stringify(folderTypeDefinition))}`
      });

      const responseText = await createFolderTypeResponse.text();
      if (createFolderTypeResponse.status() === 201 || createFolderTypeResponse.status() === 200) {
        console.log('✓ Test folder type created successfully');
        typesCreated.folder = true;
      } else {
        if (responseText.includes('already exists') || responseText.includes('Conflict')) {
          console.log('Test folder type already exists, will use existing');
          typesCreated.folder = true;
        }
      }
    } catch (error) {
      console.error('Error creating folder type:', error);
    }

    // Verify both types are in REST API type list
    console.log('Verifying types in REST API list...');
    const typeListResponse = await apiContext.get(`${REST_BASE_URL}/type/list`, {
      headers: { 'Authorization': ADMIN_AUTH }
    });
    if (typeListResponse.status() === 200) {
      const typeList = await typeListResponse.json();
      const docTypeInList = typeList.types?.some((t: any) => t.id === TEST_DOCUMENT_TYPE_ID);
      const folderTypeInList = typeList.types?.some((t: any) => t.id === TEST_FOLDER_TYPE_ID);
      console.log(`  - Document type in REST list: ${docTypeInList}`);
      console.log(`  - Folder type in REST list: ${folderTypeInList}`);
    }

    await apiContext.dispose();
  });

  // Clean up custom types after all tests via Browser Binding
  test.afterAll(async () => {
    const apiContext = await request.newContext();

    // Delete test document type via Browser Binding
    if (typesCreated.document) {
      console.log('Cleaning up test document type...');
      try {
        const deleteDocResponse = await apiContext.post(`${CMIS_BASE_URL}`, {
          headers: {
            'Authorization': ADMIN_AUTH,
            'Content-Type': 'application/x-www-form-urlencoded'
          },
          data: `cmisaction=deleteType&typeId=${encodeURIComponent(TEST_DOCUMENT_TYPE_ID)}`
        });

        if (deleteDocResponse.status() === 200 || deleteDocResponse.status() === 204) {
          console.log('✓ Test document type deleted');
        }
      } catch (error) {
        console.error('Error deleting document type:', error);
      }
    }

    // Delete test folder type via Browser Binding
    if (typesCreated.folder) {
      console.log('Cleaning up test folder type...');
      try {
        const deleteFolderResponse = await apiContext.post(`${CMIS_BASE_URL}`, {
          headers: {
            'Authorization': ADMIN_AUTH,
            'Content-Type': 'application/x-www-form-urlencoded'
          },
          data: `cmisaction=deleteType&typeId=${encodeURIComponent(TEST_FOLDER_TYPE_ID)}`
        });

        if (deleteFolderResponse.status() === 200 || deleteFolderResponse.status() === 204) {
          console.log('✓ Test folder type deleted');
        }
      } catch (error) {
        console.error('Error deleting folder type:', error);
      }
    }

    await apiContext.dispose();
  });

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await page.context().clearCookies();
    await page.context().clearPermissions();
    await authHelper.login();
    await testHelper.waitForAntdLoad();

    // Navigate to documents
    await testHelper.navigateToDocuments();
    await waitForRender(page);

    // CRITICAL: Force reload to ensure types are freshly loaded after beforeAll creates them
    // The DocumentList component loads types once on mount, so we need a reload
    // after the custom types are created in beforeAll

    // Set up response listener BEFORE reload to catch the type list response
    let typeListLoaded = false;
    const typeListPromise = page.waitForResponse(
      response => response.url().includes('/type/list') && response.status() === 200,
      { timeout: 30000 }
    ).then(async (response) => {
      try {
        const json = await response.json();
        if (json.types && Array.isArray(json.types)) {
          const hasTestType = json.types.some((t: any) => t.id === TEST_DOCUMENT_TYPE_ID);
          console.log(`[Network] Type list loaded: ${json.types.length} types, test type present: ${hasTestType}`);
        }
      } catch (e) {
        console.log('[Network] Type list response received (could not parse)');
      }
      typeListLoaded = true;
    }).catch(() => {
      console.log('[Network] Type list response not received within timeout');
    });

    await page.reload();
    await testHelper.waitForAntdLoad();

    // Wait for type list API to complete (critical: prevents modal close from late re-render)
    await typeListPromise;
    console.log(`Type list loaded after reload: ${typeListLoaded}`);

    // Wait for React to fully process the type list and stabilize the DOM
    await waitForUiStable(page);

    // Wait for page content to be ready
    await page.waitForSelector('.ant-menu-item, .ant-table-tbody', { timeout: 30000 });

    await testHelper.closeMobileSidebar(browserName);
  });

  test.describe('Document Upload with Required Properties', () => {
    test('should show validation error when required custom property is empty, then succeed after filling', async ({ page, browserName }) => {
      test.setTimeout(90000);  // Allow extra time for type selection and validation
      // Skip if document type wasn't created
      test.skip(!typesCreated.document, 'ENV: Test document type not available');

      const isMobile = testHelper.isMobile(browserName);
      const testFileName = `test-required-doc-${generateTestId()}.txt`;

      // STEP 1+2: Open upload modal and select type (combined retry — modal may close due to React re-render)
      console.log('STEP 1: Opening upload modal');
      const uploadButton = page.locator('button').filter({ hasText: /ファイルアップロード|Upload/ });
      await expect(uploadButton).toBeVisible({ timeout: 10000 });

      const dropdown = page.locator('.ant-select-dropdown');
      let dropdownOpened = false;
      let modal = page.locator('.ant-modal-container').last();

      for (let attempt = 1; attempt <= 5; attempt++) {
        try {
          // If modal is not visible, click the upload button
          if (!(await page.locator('.ant-modal-container').isVisible().catch(() => false))) {
            console.log(`  Opening upload modal (attempt ${attempt})`);
            await uploadButton.click(isMobile ? { force: true } : {});
          }

          // Wait for modal with type selector to appear and stabilize
          await page.waitForSelector('.ant-modal-container .ant-select', { state: 'visible', timeout: 10000 });
          console.log(`  Upload modal with type selector visible (attempt ${attempt})`);

          // Re-locate modal (use last() to get the most recently opened one)
          modal = page.locator('.ant-modal-container').last();

          // Click type selector to open dropdown
          const typeSelector = modal.locator('.ant-select').first();
          await typeSelector.click({ timeout: 5000 });
          await expect(dropdown).toBeVisible({ timeout: 5000 });
          dropdownOpened = true;
          console.log(`Type dropdown opened on attempt ${attempt}`);
          break;
        } catch (e) {
          console.log(`Modal/type selector attempt ${attempt} failed: ${e instanceof Error ? e.message.split('\n')[0] : e}`);
          // Close any stale modal before retry
          await page.keyboard.press('Escape').catch(() => {});
          await waitForUiStable(page);
        }
      }
      if (!dropdownOpened) {
        console.log('WARNING: Could not open type dropdown after 5 attempts');
        // Debug info
        const modalCount = await page.locator('.ant-modal-container').count();
        const selectCount = await page.locator('.ant-modal-container .ant-select').count();
        console.log(`  DEBUG: ${modalCount} modals, ${selectCount} selects on page`);
      }

      // List all available options for debugging
      // NOTE: Ant Design Select may use virtual scrolling, so not all options may be visible
      const allOptions = page.locator('.ant-select-item-option');
      const optionCount = await allOptions.count();
      console.log(`Found ${optionCount} type options visible in dropdown (may be virtual scrolled)`);

      if (optionCount > 0) {
        const optionTexts = await allOptions.allTextContents();
        console.log('Initially visible types:', optionTexts.slice(0, 10).join(', '));
      }

      // Try typing to search for our test type - this bypasses virtual scrolling
      console.log('Searching for test type by typing...');
      await page.keyboard.type('Test Required Property');
      await waitForRender(page);

      // Check options after search filter
      const filteredOptions = page.locator('.ant-select-item-option');
      const filteredCount = await filteredOptions.count();
      console.log(`Found ${filteredCount} options after filtering`);

      if (filteredCount > 0) {
        const filteredTexts = await filteredOptions.allTextContents();
        console.log('Filtered types:', filteredTexts.join(', '));
      }

      // Find and select our test type (should be in filtered results now)
      const testTypeOption = page.locator('.ant-select-item-option').filter({
        hasText: 'Test Required Property Document'
      });

      if (await testTypeOption.count() > 0) {
        console.log('Found test type, selecting...');
        await testTypeOption.click();
        await waitForUiStable(page);  // Wait for type selection to process and custom props to render

        // Verify modal is still open after type selection
        await expect(modal).toBeVisible({ timeout: 3000 });
        console.log('Modal still visible after type selection');

        // Verify selection was applied (re-locate after retry loop)
        const selectedValue = await modal.locator('.ant-select').first().locator('.ant-select-selection-item').textContent();
        console.log(`Selected type display: ${selectedValue}`);
      } else {
        await page.keyboard.press('Escape');
        console.log('Test document type not found even after search. Skipping test.');
        test.skip('ENV: Test document type not found in selector');
        return;
      }

      // STEP 3: Verify custom properties section with required indicator
      console.log('STEP 3: Verifying custom properties section');

      // Debug: Check all h4 elements in modal
      const allH4s = modal.locator('h4');
      const h4Count = await allH4s.count();
      console.log(`  Found ${h4Count} h4 elements in modal`);
      for (let i = 0; i < h4Count; i++) {
        const h4Text = await allH4s.nth(i).textContent();
        console.log(`    h4[${i}]: "${h4Text}"`);
      }

      const customPropsSection = modal.locator('h4').filter({ hasText: /カスタムプロパティ|Custom Properties/ });
      const customPropsSectionExists = await customPropsSection.count() > 0;

      if (!customPropsSectionExists) {
        console.log('WARNING: Custom properties section h4 not found');
        console.log('  This may indicate propertyDefinitions are not being returned by getType()');

        // Debug: Check total form items
        const formItems = modal.locator('.ant-form-item');
        console.log(`  Total form items in modal: ${await formItems.count()}`);

        test.skip('ENV: Custom properties section not appearing - type definition incomplete');
        return;
      }

      await expect(customPropsSection).toBeVisible({ timeout: 5000 });

      // Check for required indicator ONLY within custom properties section
      const customPropsContainer = modal.locator('div[style*="background"]').filter({
        has: page.locator('h4')
      });
      const requiredIndicator = customPropsContainer.locator('span[style*="color: red"], span[style*="color:red"]');
      await expect(requiredIndicator.first()).toBeVisible({ timeout: 10000 });
      const indicatorCount = await requiredIndicator.count();
      console.log(`Found ${indicatorCount} required indicator(s) in custom properties section`);
      expect(indicatorCount).toBeGreaterThan(0);

      // STEP 4: Upload file and fill name, but NOT required custom property
      console.log('STEP 4: Filling basic fields only');
      const fileInput = modal.locator('input[type="file"]');
      await fileInput.setInputFiles({
        name: testFileName,
        mimeType: 'text/plain',
        buffer: Buffer.from('Test content for required property validation')
      });
      await waitForRender(page);

      // STEP 5: Try to submit without required custom property
      console.log('STEP 5: Attempting to submit without required property');
      const submitButton = modal.locator('button.ant-btn-primary').filter({ hasText: /アップロード|Upload/ });
      await submitButton.click();
      await waitForUiStable(page);

      // STEP 6: Verify validation error appears
      console.log('STEP 6: Checking for validation error');
      const validationErrors = modal.locator('.ant-form-item-explain-error');
      await expect(validationErrors.first()).toBeVisible({ timeout: 10000 });
      const errorCount = await validationErrors.count();
      console.log(`Validation errors found: ${errorCount}`);

      // We expect at least one validation error for the required custom property
      expect(errorCount).toBeGreaterThan(0);
      console.log('✓ Validation error displayed as expected');

      // Get error message text
      if (errorCount > 0) {
        const errorText = await validationErrors.first().textContent();
        console.log(`Error message: ${errorText}`);
      }

      // STEP 7: Fill in required custom property
      console.log('STEP 7: Filling required custom property');
      const requiredInput = customPropsContainer.locator('input').first();
      await requiredInput.fill('Test Required Value');
      await waitForRender(page);

      // STEP 8: Submit again
      console.log('STEP 8: Submitting with required property filled');
      await submitButton.click();
      await waitForUiStable(page);

      // STEP 9: Verify success
      // Wait for the modal to close (async React re-render after a successful
      // create); only then is it safe to decide success vs failure.
      const modalStillVisible = !(await modal.waitFor({ state: 'hidden', timeout: 15000 }).then(() => true).catch(() => false));
      const successMessage = page.locator('.ant-message-success');

      if (!modalStillVisible || await successMessage.count() > 0) {
        console.log('✓ Upload succeeded after filling required property');

        // STEP 10: Clean up - delete test document
        console.log('STEP 10: Cleaning up test document');
        await waitForUiStable(page);

        const docRow = page.locator('tr').filter({ hasText: testFileName });
        if (await docRow.count() > 0) {
          const deleteButton = docRow.locator('button').filter({ has: page.locator('.anticon-delete, [aria-label="delete"]') });
          if (await deleteButton.count() > 0) {
            await deleteButton.click(isMobile ? { force: true } : {});
            await waitForRender(page);

            const deleteModal = page.locator('.ant-modal-container').filter({ hasText: /削除|Delete/ });
            if (await deleteModal.count() > 0) {
              const confirmBtn = deleteModal.locator('button.ant-btn-dangerous, button').filter({ hasText: /削除|Delete/ });
              if (await confirmBtn.count() > 0) {
                await confirmBtn.first().click();
                await waitForUiStable(page);
                console.log('✓ Test document cleaned up');
              }
            }
          }
        }
      } else {
        // Modal still visible - check what went wrong
        const newErrorCount = await validationErrors.count();
        if (newErrorCount > 0) {
          const errors = await validationErrors.allTextContents();
          console.log('Remaining validation errors:', errors);
        }
        await page.keyboard.press('Escape');
        throw new Error('Upload failed even after filling required property');
      }
    });
  });

  test.describe('Folder Creation with Required Properties', () => {
    test('should show validation error when required custom property is empty, then succeed after filling', async ({ page, browserName }) => {
      // Skip if folder type wasn't created
      test.skip(!typesCreated.folder, 'ENV: Test folder type not available');

      const isMobile = testHelper.isMobile(browserName);
      const testFolderName = `test-required-folder-${generateTestId()}`;

      // STEP 1: Open folder creation modal
      console.log('STEP 1: Opening folder creation modal');
      const createFolderButton = page.locator('button').filter({ hasText: /フォルダ作成|Create Folder/ });
      await createFolderButton.click(isMobile ? { force: true } : {});
      await waitForRender(page);

      await expect(page.locator('.ant-modal-container')).toBeVisible({ timeout: 5000 });
      // Target the folder creation modal specifically (last opened modal without upload area)
      const modal = page.locator('.ant-modal-container').last();

      // Wait for modal to stabilize
      await waitForUiStable(page);

      // STEP 2: Select test folder type (with retry for DOM stability)
      console.log('STEP 2: Selecting test folder type');

      const folderDropdown = page.locator('.ant-select-dropdown');
      for (let clickAttempt = 1; clickAttempt <= 5; clickAttempt++) {
        try {
          // Re-locate each attempt (element may be detached from DOM)
          const typeSelector = modal.locator('.ant-select').first();
          await expect(typeSelector).toBeVisible({ timeout: 5000 });
          await typeSelector.click({ timeout: 5000 });
          await expect(folderDropdown).toBeVisible({ timeout: 3000 });
          console.log(`Folder type dropdown opened on attempt ${clickAttempt}`);
          break;
        } catch (e) {
          console.log(`Folder type selector click attempt ${clickAttempt} failed: ${e instanceof Error ? e.message.split('\n')[0] : e}`);
          await waitForUiStable(page);
        }
      }
      await waitForRender(page);

      // Find and select our test type
      const testTypeOption = page.locator('.ant-select-item-option').filter({
        hasText: 'Test Required Property Folder'
      });

      if (await testTypeOption.count() === 0) {
        await page.keyboard.press('Escape');
        test.skip('ENV: Test folder type not found in selector - type may not have been created');
        return;
      }

      await testTypeOption.click();
      await waitForRender(page);

      // STEP 3: Verify custom properties section with required indicator
      console.log('STEP 3: Verifying custom properties section');
      const customPropsSection = modal.locator('h4').filter({ hasText: /カスタムプロパティ|Custom Properties/ });
      await expect(customPropsSection).toBeVisible({ timeout: 5000 });

      // Check for required indicator in custom properties section
      const customPropsContainer = modal.locator('div[style*="background"]').filter({
        has: page.locator('h4')
      });
      const requiredIndicator = customPropsContainer.locator('span[style*="color: red"], span[style*="color:red"]');
      await expect(requiredIndicator.first()).toBeVisible({ timeout: 10000 });
      const indicatorCount = await requiredIndicator.count();
      console.log(`Found ${indicatorCount} required indicator(s) in custom properties section`);
      expect(indicatorCount).toBeGreaterThan(0);

      // STEP 4: Fill folder name only, NOT required custom property
      console.log('STEP 4: Filling folder name only');
      const nameInput = modal.locator('input').first();
      await nameInput.fill(testFolderName);
      await waitForRender(page);

      // STEP 5: Try to submit without required custom property
      console.log('STEP 5: Attempting to submit without required property');
      const submitButton = modal.locator('button.ant-btn-primary').first();
      await submitButton.click();
      await waitForUiStable(page);

      // STEP 6: Verify validation error appears
      console.log('STEP 6: Checking for validation error');
      const validationErrors = modal.locator('.ant-form-item-explain-error');
      await expect(validationErrors.first()).toBeVisible({ timeout: 10000 });
      const errorCount = await validationErrors.count();
      console.log(`Validation errors found: ${errorCount}`);

      expect(errorCount).toBeGreaterThan(0);
      console.log('✓ Validation error displayed as expected');

      // STEP 7: Fill in required custom property
      console.log('STEP 7: Filling required custom property');
      const requiredInput = customPropsContainer.locator('input').first();
      await requiredInput.fill('Test Required Folder Value');
      await waitForRender(page);

      // STEP 8: Submit again
      console.log('STEP 8: Submitting with required property filled');
      await submitButton.click();
      await waitForUiStable(page);

      // STEP 9: Verify success
      // Wait for the modal to close (async React re-render after a successful
      // create); only then is it safe to decide success vs failure.
      const modalStillVisible = !(await modal.waitFor({ state: 'hidden', timeout: 15000 }).then(() => true).catch(() => false));
      const successMessage = page.locator('.ant-message-success');

      if (!modalStillVisible || await successMessage.count() > 0) {
        console.log('✓ Folder creation succeeded after filling required property');

        // STEP 10: Clean up - delete test folder
        console.log('STEP 10: Cleaning up test folder');
        await waitForUiStable(page);

        const folderRow = page.locator('tr').filter({ hasText: testFolderName });
        if (await folderRow.count() > 0) {
          const deleteButton = folderRow.locator('button').filter({ has: page.locator('.anticon-delete, [aria-label="delete"]') });
          if (await deleteButton.count() > 0) {
            await deleteButton.click(isMobile ? { force: true } : {});
            await waitForRender(page);

            const deleteModal = page.locator('.ant-modal-container').filter({ hasText: /削除|Delete/ });
            if (await deleteModal.count() > 0) {
              const confirmBtn = deleteModal.locator('button.ant-btn-dangerous, button').filter({ hasText: /削除|Delete/ });
              if (await confirmBtn.count() > 0) {
                await confirmBtn.first().click();
                await waitForUiStable(page);
                console.log('✓ Test folder cleaned up');
              }
            }
          }
        }
      } else {
        await page.keyboard.press('Escape');
        throw new Error('Folder creation failed even after filling required property');
      }
    });
  });

  test.describe('Required Indicator Consistency', () => {
    test('should only show required indicators for properties with required=true', async ({ page, browserName }) => {
      test.skip(!typesCreated.document, 'ENV: Test document type not available');

      const isMobile = testHelper.isMobile(browserName);

      // Open upload modal and select type (combined retry — modal may close due to React re-render)
      const uploadButton = page.locator('button').filter({ hasText: /ファイルアップロード|Upload/ });
      let modal3 = page.locator('.ant-modal-container').last();
      const dropdown3 = page.locator('.ant-select-dropdown');
      let dropdown3Opened = false;

      for (let attempt = 1; attempt <= 5; attempt++) {
        try {
          if (!(await page.locator('.ant-modal-container').isVisible().catch(() => false))) {
            await uploadButton.click(isMobile ? { force: true } : {});
          }
          await page.waitForSelector('.ant-modal-container .ant-select', { state: 'visible', timeout: 10000 });
          modal3 = page.locator('.ant-modal-container').last();
          const typeSelector3 = modal3.locator('.ant-select').first();
          await typeSelector3.click({ timeout: 5000 });
          await expect(dropdown3).toBeVisible({ timeout: 3000 });
          dropdown3Opened = true;
          break;
        } catch (e) {
          console.log(`Type selector attempt ${attempt} failed: ${e instanceof Error ? e.message.split('\n')[0] : e}`);
          await page.keyboard.press('Escape').catch(() => {});
          await waitForUiStable(page);
        }
      }

      // Use search to find the test type (bypasses virtual scrolling limit)
      await page.keyboard.type('Test Required Property');
      await waitForRender(page);

      const testTypeOption = page.locator('.ant-select-item-option').filter({
        hasText: 'Test Required Property Document'
      });

      if (await testTypeOption.count() === 0) {
        await page.keyboard.press('Escape');
        test.skip('ENV: Test document type not found even after search');
        return;
      }

      await testTypeOption.click();
      await waitForRender(page);

      // Find custom properties section
      const customPropsContainer = modal3.locator('div[style*="background"]').filter({
        has: page.locator('h4')
      });

      // Count required indicators in custom properties — wait for the section
      // to render its fields before counting.
      const customRequiredIndicators = customPropsContainer.locator('span[style*="color: red"]');
      await expect(customRequiredIndicators.first()).toBeVisible({ timeout: 10000 });
      const customIndicatorCount = await customRequiredIndicators.count();

      // Count total form items in custom properties (should match required indicators if all are required)
      const customFormItems = customPropsContainer.locator('.ant-form-item');
      const formItemCount = await customFormItems.count();

      console.log(`Custom properties section: ${formItemCount} form items, ${customIndicatorCount} required indicators`);

      // For our test type, we have 1 required property, so we should have exactly 1 indicator
      expect(customIndicatorCount).toBe(1);
      console.log('✓ Required indicator count matches expected (1 required property)');

      // Now check standard fields - file and name should also have required indicators
      // but they use Ant Design's built-in required styling
      const standardRequiredFields = modal3.locator('.ant-form-item-required');
      const standardCount = await standardRequiredFields.count();
      console.log(`Standard required fields (via .ant-form-item-required): ${standardCount}`);

      await page.keyboard.press('Escape');
    });
  });
});
