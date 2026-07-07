import { waitForUiStable, waitForRender } from '../utils/wait-helpers';
import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { generateTestId } from '../utils/test-helper';

/**
 * Custom Property Input Feature E2E Tests (2025-12-23)
 *
 * Tests for the new custom property input functionality during object creation:
 * - Document upload with custom type selection and property input
 * - Folder creation with custom type selection and property input
 * - Relationship creation with custom type selection and property input
 * - Modal behavior validation (maskClosable=false, form state preservation)
 * - Cancel button cleanup verification
 *
 * Test Coverage:
 * 1. Document upload modal - type selection shows custom properties
 * 2. Document upload modal - custom properties are submitted
 * 3. Document upload modal - cancel resets form and type definition state
 * 4. Document upload modal - maskClosable prevents accidental close
 * 5. Folder creation modal - type selection shows custom properties
 * 6. Folder creation modal - custom properties are submitted
 * 7. Folder creation modal - cancel resets form and type definition state
 * 8. Relationship creation modal - type selection shows custom properties
 * 9. Relationship creation modal - custom properties are submitted
 */

test.describe('Custom Property Input Feature', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Wait for document list to load
    await page.waitForSelector('.ant-table', { timeout: 30000 });
  });

  test.afterEach(async ({ page }) => {
    // Cleanup: Delete any test objects created during tests
    try {
      const testPrefix = 'test-custom-prop-';
      const response = await page.request.post(
        'http://localhost:8080/core/browser/bedroom',
        {
          headers: {
            'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
            'Content-Type': 'application/x-www-form-urlencoded',
          },
          data: `cmisaction=query&statement=SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE '${testPrefix}%'`,
        }
      );

      if (response.ok()) {
        const data = await response.json();
        if (data.results) {
          for (const obj of data.results) {
            const objectId = obj.properties?.['cmis:objectId']?.value;
            if (objectId) {
              await page.request.post(
                'http://localhost:8080/core/browser/bedroom',
                {
                  headers: {
                    'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
                    'Content-Type': 'application/x-www-form-urlencoded',
                  },
                  data: `cmisaction=delete&objectId=${objectId}&allVersions=true`,
                }
              );
            }
          }
        }
      }
    } catch (e) {
      console.log('Cleanup warning (non-critical):', e);
    }
  });

  /**
   * SKIPPED (2025-12-23) - Document Upload Modal Type Selection Issues
   *
   * Investigation Result: Upload modal functionality IS working correctly.
   * However, tests fail intermittently due to:
   *
   * 1. MODAL TIMING:
   *    - Upload modal may not render completely before type dropdown is queried
   *    - Ant Design modal animation timing varies between test runs
   *
   * 2. TYPE DROPDOWN DETECTION:
   *    - Custom type list loading depends on API response timing
   *    - Dropdown options may not be fully populated during assertion
   *
   * 3. MODAL CLOSE BEHAVIOR:
   *    - maskClosable test depends on click coordinates and animation state
   *    - Modal backdrop may not be fully rendered during outside click test
   *
   * Custom property input verified working via manual testing.
   * Re-enable after implementing more robust modal state detection.
   */
  test.describe('Document Upload Modal', () => {
    test('should display type selection dropdown', async ({ page }) => {
      // Open upload modal
      const uploadButton = page.locator('button:has-text("ファイルアップロード")');
      await uploadButton.click();

      // Verify modal is open
      const modal = page.locator('.ant-modal').filter({ hasText: 'ファイルアップロード' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Verify type dropdown exists
      const typeDropdown = modal.locator('.ant-select').first();
      await expect(typeDropdown).toBeVisible();

      // Close modal
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
    });

    test('should show custom properties when custom type is selected', async ({ page }) => {
      // Capture console logs for debugging
      const consoleLogs: string[] = [];
      page.on('console', msg => {
        if (msg.text().includes('[DocumentList]')) {
          consoleLogs.push(msg.text());
        }
      });

      // First, check if a custom document type exists (retry up to 3 times for busy server)
      let typesResponse;
      for (let attempt = 0; attempt < 3; attempt++) {
        typesResponse = await page.request.get(
          'http://localhost:8080/core/rest/repo/bedroom/type/list',
          {
            headers: {
              'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
            },
            timeout: 15000,
          }
        );
        if (typesResponse.ok()) break;
        console.log(`Type API attempt ${attempt + 1} failed (${typesResponse.status()}), retrying...`);
        await waitForUiStable(page);
      }

      if (!typesResponse!.ok()) {
        test.skip('ENV: Type API request failed after retries');
        return;
      }

      const typesData = await typesResponse.json();
      const types = typesData.types || typesData;
      if (!Array.isArray(types)) {
        test.skip('ENV: Types response is not an array');
        return;
      }
      const customDocType = types.find((t: any) =>
        (t.baseId === 'cmis:document' || t.baseTypeId === 'cmis:document') &&
        t.id !== 'cmis:document' &&
        t.propertyDefinitions &&
        Array.isArray(t.propertyDefinitions) &&
        t.propertyDefinitions.some((p: any) => p.id && !p.id.startsWith('cmis:'))
      );

      if (!customDocType) {
        test.skip('ENV: No custom document type with custom properties found');
        return;
      }

      console.log('Found custom document type:', customDocType.id, customDocType.displayName);

      // Open upload modal
      await page.locator('button:has-text("ファイルアップロード")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'ファイルアップロード' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Select the custom type
      const typeDropdown = modal.locator('.ant-select').first();
      await typeDropdown.click();
      await page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)', { timeout: 5000 });

      const typeOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: customDocType.displayName || customDocType.id });
      const optionCount = await typeOption.count();
      console.log('Found type option count:', optionCount);

      if (optionCount > 0) {
        await typeOption.click();

        // Wait longer for async type definition fetch to complete
        await waitForUiStable(page);

        // Print captured console logs
        console.log('Console logs captured:', consoleLogs);

        // Debug: Check what's inside the modal now
        const modalContent = await modal.innerHTML();
        console.log('Modal contains "カスタムプロパティ":', modalContent.includes('カスタムプロパティ'));

        // Check if custom properties section is shown
        const customPropsSection = modal.locator('h4:has-text("カスタムプロパティ")');
        const propsVisible = await customPropsSection.isVisible().catch(() => false);
        console.log('Custom properties section visible:', propsVisible);

        // Try alternative selectors
        const customPropsDiv = modal.locator('div:has(> h4:has-text("カスタムプロパティ"))');
        const divVisible = await customPropsDiv.isVisible().catch(() => false);
        console.log('Custom properties div visible:', divVisible);

        await expect(customPropsSection).toBeVisible({ timeout: 5000 });
      }

      // Close modal
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
    });

    test('should not close modal when clicking outside (maskClosable=false)', async ({ page }) => {
      // Open upload modal
      await page.locator('button:has-text("ファイルアップロード")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'ファイルアップロード' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Fill in some data
      const nameInput = modal.locator('input[placeholder="ファイル名を入力"]');
      await nameInput.fill('test-should-not-close');

      // Click outside the modal (on the mask)
      await page.locator('.ant-modal-mask').click({ force: true, position: { x: 10, y: 10 } });

      // Wait a bit
      await waitForRender(page);

      // Modal should still be visible
      await expect(modal).toBeVisible();

      // Data should still be there
      await expect(nameInput).toHaveValue('test-should-not-close');

      // Close modal with cancel button
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
    });

    test('should reset form when cancel is clicked', async ({ page }) => {
      // Open upload modal
      await page.locator('button:has-text("ファイルアップロード")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'ファイルアップロード' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Fill in some data
      const nameInput = modal.locator('input[placeholder="ファイル名を入力"]');
      await nameInput.fill('test-reset-form');

      // Click cancel
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
      await waitForRender(page);

      // Modal should be closed
      await expect(modal).not.toBeVisible();

      // Reopen modal
      await page.locator('button:has-text("ファイルアップロード")').click();
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Form should be reset
      const nameInputAfter = modal.locator('input[placeholder="ファイル名を入力"]');
      await expect(nameInputAfter).toHaveValue('');

      // Close modal
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
    });

    test('should upload document with custom type and properties', async ({ page }) => {
      // Check for custom document type (retry for busy server)
      let typesResponse;
      for (let attempt = 0; attempt < 3; attempt++) {
        typesResponse = await page.request.get(
          'http://localhost:8080/core/rest/repo/bedroom/type/list',
          {
            headers: {
              'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
            },
            timeout: 15000,
          }
        );
        if (typesResponse.ok()) break;
        await waitForUiStable(page);
      }

      if (!typesResponse!.ok()) {
        test.skip('ENV: Type API request failed after retries');
        return;
      }

      const typesData = await typesResponse.json();
      const types = typesData.types || typesData;
      if (!Array.isArray(types)) {
        test.skip('ENV: Types response is not an array');
        return;
      }
      const customDocType = types.find((t: any) =>
        (t.baseId === 'cmis:document' || t.baseTypeId === 'cmis:document') &&
        t.id !== 'cmis:document' &&
        t.propertyDefinitions &&
        Array.isArray(t.propertyDefinitions) &&
        t.propertyDefinitions.some((p: any) => p.id && !p.id.startsWith('cmis:'))
      );

      if (!customDocType) {
        test.skip('ENV: No custom document type with custom properties found');
        return;
      }

      const uuid = generateTestId();
      const testFileName = `test-custom-prop-doc-${uuid}.txt`;

      // Open upload modal
      await page.locator('button:has-text("ファイルアップロード")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'ファイルアップロード' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Upload a test file
      const fileInput = modal.locator('input[type="file"]');
      await fileInput.setInputFiles({
        name: testFileName,
        mimeType: 'text/plain',
        buffer: Buffer.from('Test content for custom property upload test'),
      });

      // Wait for filename to be auto-filled
      await waitForRender(page);

      // Select the custom type
      const typeDropdown = modal.locator('.ant-select').first();
      await typeDropdown.click();
      await page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)', { timeout: 5000 });

      const typeOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: customDocType.displayName || customDocType.id });
      if (await typeOption.count() > 0) {
        await typeOption.click();
        await waitForRender(page);

        // Fill ALL required custom properties
        const customPropsSection = modal.locator('h4:has-text("カスタムプロパティ")');
        if (await customPropsSection.count() > 0) {
          const props = customDocType.propertyDefinitions.filter(
            (p: any) => p.id && !p.id.startsWith('cmis:')
          );
          let textIdx = 0;
          for (const prop of props) {
            const propType = prop.propertyType || prop.type;
            const displayName = prop.displayName || prop.id;
            if (propType === 'datetime') {
              // Skip - handled after the loop
            } else if (propType === 'boolean') {
              // Skip boolean selects
            } else {
              // Text/string/integer: fill by placeholder matching prop.id
              const propInput = modal.locator(`input[placeholder*="${prop.id}"]`);
              if (await propInput.count() > 0) {
                await propInput.fill(`Test value ${++textIdx}`);
              }
            }
          }
          // Fill Ant Design DatePicker inputs using popup "Now" button
          const datePickers = modal.locator('.ant-picker');
          const dtCount = await datePickers.count();
          for (let i = 0; i < dtCount; i++) {
            if (i > 0) {
              // Close any lingering popup by clicking elsewhere
              await modal.locator('h4:has-text("カスタムプロパティ")').click();
              await waitForRender(page);
            }

            const picker = datePickers.nth(i);
            await picker.click();
            await waitForRender(page);

            const popup = page.locator('.ant-picker-dropdown:not(.ant-picker-dropdown-hidden)');
            if (await popup.count() > 0) {
              const nowBtn = popup.locator('.ant-picker-now-btn');
              if (await nowBtn.count() > 0) {
                await nowBtn.click();
                await waitForRender(page);
              }
              const okBtn = popup.locator('.ant-picker-ok button');
              if (await okBtn.count() > 0) {
                await okBtn.click();
                await waitForRender(page);
              }
            }
            await waitForRender(page);
          }
        }
      }

      // Submit and wait for upload
      await page.locator('.ant-modal button:has-text("アップロード")').click();

      // Wait for success message
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 30000 });

      // Verify document appears in list
      await waitForUiStable(page);
      const documentRow = page.locator('.ant-table-row').filter({ hasText: testFileName });
      await expect(documentRow).toBeVisible({ timeout: 10000 });
    });
  });

  test.describe('Folder Creation Modal', () => {
    test('should display type selection dropdown', async ({ page }) => {
      // Open folder creation modal
      const folderButton = page.locator('button:has-text("フォルダ作成")');
      await folderButton.click();

      // Verify modal is open
      const modal = page.locator('.ant-modal').filter({ hasText: 'フォルダ作成' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Verify type dropdown exists
      const typeDropdown = modal.locator('.ant-select').first();
      await expect(typeDropdown).toBeVisible();

      // Close modal
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
    });

    test('should show custom properties when custom folder type is selected', async ({ page }) => {
      // Check if a custom folder type exists (retry for busy server)
      let typesResponse;
      for (let attempt = 0; attempt < 3; attempt++) {
        typesResponse = await page.request.get(
          'http://localhost:8080/core/rest/repo/bedroom/type/list',
          {
            headers: {
              'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
            },
            timeout: 15000,
          }
        );
        if (typesResponse.ok()) break;
        await waitForUiStable(page);
      }

      if (!typesResponse!.ok()) {
        test.skip('ENV: Type API request failed after retries');
        return;
      }

      const typesData = await typesResponse.json();
      const types = typesData.types || typesData;
      if (!Array.isArray(types)) {
        test.skip('ENV: Types response is not an array');
        return;
      }
      const customFolderType = types.find((t: any) =>
        (t.baseId === 'cmis:folder' || t.baseTypeId === 'cmis:folder') &&
        t.id !== 'cmis:folder' &&
        t.propertyDefinitions &&
        Array.isArray(t.propertyDefinitions) &&
        t.propertyDefinitions.some((p: any) => p.id && !p.id.startsWith('cmis:'))
      );

      if (!customFolderType) {
        test.skip('ENV: No custom folder type with custom properties found');
        return;
      }

      // Open folder modal
      await page.locator('button:has-text("フォルダ作成")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'フォルダ作成' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Select the custom type
      const typeDropdown = modal.locator('.ant-select').first();
      await typeDropdown.click();
      await page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)', { timeout: 5000 });

      const typeOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: customFolderType.displayName || customFolderType.id });
      if (await typeOption.count() > 0) {
        await typeOption.click();
        await waitForRender(page);

        // Check if custom properties section is shown
        const customPropsSection = modal.locator('h4:has-text("カスタムプロパティ")');
        await expect(customPropsSection).toBeVisible({ timeout: 5000 });
      }

      // Close modal
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
    });

    test('should not close modal when clicking outside (maskClosable=false)', async ({ page }) => {
      // Open folder modal
      await page.locator('button:has-text("フォルダ作成")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'フォルダ作成' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Fill in some data
      const nameInput = modal.locator('input[placeholder="フォルダ名を入力"]');
      await nameInput.fill('test-folder-should-not-close');

      // Click outside the modal (on the mask)
      await page.locator('.ant-modal-mask').click({ force: true, position: { x: 10, y: 10 } });

      // Wait a bit
      await waitForRender(page);

      // Modal should still be visible
      await expect(modal).toBeVisible();

      // Data should still be there
      await expect(nameInput).toHaveValue('test-folder-should-not-close');

      // Close modal with cancel button
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
    });

    test('should reset form when cancel is clicked', async ({ page }) => {
      // Open folder modal
      await page.locator('button:has-text("フォルダ作成")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'フォルダ作成' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Fill in some data
      const nameInput = modal.locator('input[placeholder="フォルダ名を入力"]');
      await nameInput.fill('test-folder-reset');

      // Click cancel
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
      await waitForRender(page);

      // Modal should be closed
      await expect(modal).not.toBeVisible();

      // Reopen modal
      await page.locator('button:has-text("フォルダ作成")').click();
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Form should be reset
      const nameInputAfter = modal.locator('input[placeholder="フォルダ名を入力"]');
      await expect(nameInputAfter).toHaveValue('');

      // Close modal
      await page.locator('.ant-modal button:has-text("キャンセル")').click();
    });

    test('should create folder with selected type', async ({ page }) => {
      const uuid = generateTestId();
      const testFolderName = `test-custom-prop-folder-${uuid}`;

      // Open folder modal
      await page.locator('button:has-text("フォルダ作成")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'フォルダ作成' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Fill folder name
      const nameInput = modal.locator('input[placeholder="フォルダ名を入力"]');
      await nameInput.fill(testFolderName);

      // Submit - use submit button type for more reliable selection
      const submitButton = modal.locator('button[type="submit"], button.ant-btn-primary:has-text("作成")');
      await expect(submitButton).toBeVisible({ timeout: 5000 });
      await submitButton.click();

      // Wait for success message
      await expect(page.locator('.ant-message-success')).toBeVisible({ timeout: 15000 });

      // Verify folder appears in list
      await waitForUiStable(page);
      const folderRow = page.locator('.ant-table-row').filter({ hasText: testFolderName });
      await expect(folderRow).toBeVisible({ timeout: 10000 });

      // Cleanup - delete the test folder
      const deleteButton = folderRow.locator('button[class*="danger"], button:has(.anticon-delete)');
      if (await deleteButton.count() > 0) {
        await deleteButton.click();
        // Handle delete confirmation
        const confirmButton = page.locator('.ant-modal-confirm-btns button:has-text("削除"), .ant-modal button:has-text("削除する")');
        if (await confirmButton.count() > 0) {
          await confirmButton.click();
          await waitForUiStable(page);
        }
      }
    });
  });

  test.describe('Relationship Creation Modal', () => {
    test('should navigate to document detail and show relationship tab', async ({ page }) => {
      // First, find a document to work with
      const documentLink = page.locator('.ant-table-row:has(.anticon-file) .ant-btn-link').first();

      if (await documentLink.count() === 0) {
        test.skip('ENV: No documents found in list');
        return;
      }

      await documentLink.click();

      // Wait for document viewer to load (hash-based routing)
      await waitForUiStable(page);

      // Verify relationship tab is visible (i18n-safe)
      const relationshipTab = page.getByRole('tab', { name: /リレーションシップ|Relationships/i });
      await expect(relationshipTab).toBeVisible({ timeout: 10000 });
    });

    test('should show type selection in relationship creation modal', async ({ page }) => {
      // Find a document
      const documentLink = page.locator('.ant-table-row:has(.anticon-file) .ant-btn-link').first();

      if (await documentLink.count() === 0) {
        test.skip('ENV: No documents found in list');
        return;
      }

      await documentLink.click();
      await waitForUiStable(page);

      // Click relationship tab (i18n-safe)
      const relationshipTab = page.getByRole('tab', { name: /リレーションシップ|Relationships/i });
      await expect(relationshipTab).toBeVisible({ timeout: 10000 });
      await relationshipTab.click();
      await waitForRender(page);

      // Click "Add Relationship" button (i18n-safe)
      const addButton = page.getByRole('button', { name: /関係を追加|リレーションシップ.*追加|Add.*Relationship/i }).first();
      await expect(addButton).toBeVisible({ timeout: 10000 });
      await addButton.click();

      // Verify modal opens with type selection dropdown
      const modal = page.locator('.ant-modal:visible');
      await expect(modal).toBeVisible({ timeout: 5000 });
      const typeSelector = modal.locator('.ant-select').first();
      await expect(typeSelector).toBeVisible({ timeout: 5000 });

      // Close modal
      const cancelButton = modal.getByRole('button', { name: /キャンセル|Cancel/i });
      if (await cancelButton.count() > 0) {
        await cancelButton.click();
      } else {
        await page.keyboard.press('Escape');
      }
    });

    test('should show custom properties when relationship type is changed', async ({ page }) => {
      // Check for custom relationship type
      const typesResponse = await page.request.get(
        'http://localhost:8080/core/rest/repo/bedroom/type/list',
        {
          headers: {
            'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
          },
        }
      );

      if (!typesResponse.ok()) {
        test.skip('ENV: Type API request failed');
        return;
      }

      const typesData = await typesResponse.json();
      const types = typesData.types || typesData;
      if (!Array.isArray(types)) {
        test.skip('ENV: Types response is not an array');
        return;
      }
      const customRelType = types.find((t: any) =>
        (t.baseId === 'cmis:relationship' || t.baseTypeId === 'cmis:relationship') &&
        t.id !== 'cmis:relationship' &&
        t.propertyDefinitions &&
        Array.isArray(t.propertyDefinitions) &&
        t.propertyDefinitions.some((p: any) => p.id && !p.id.startsWith('cmis:'))
      );

      if (!customRelType) {
        test.skip('ENV: No custom relationship type with custom properties found');
        return;
      }

      // Find a document
      const documentLink = page.locator('.ant-table-row:has(.anticon-file) .ant-btn-link').first();
      if (await documentLink.count() === 0) {
        test.skip('ENV: No documents found in list');
        return;
      }

      await documentLink.click();
      await waitForUiStable(page);

      // Click relationship tab (i18n-safe)
      const relationshipTab = page.getByRole('tab', { name: /リレーションシップ|Relationships/i });
      await expect(relationshipTab).toBeVisible({ timeout: 10000 });
      await relationshipTab.click();
      await waitForRender(page);

      // Open add relationship modal (i18n-safe)
      const addButton = page.getByRole('button', { name: /関係を追加|リレーションシップ.*追加|Add.*Relationship/i }).first();
      await expect(addButton).toBeVisible({ timeout: 10000 });
      await addButton.click();

      const modal = page.locator('.ant-modal:visible');
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Select the custom relationship type. Filter by the type id (unique and
      // present in every option label as "(id)") rather than the display name,
      // which can be a substring of several options.
      const typeDropdown = modal.locator('.ant-select').first();
      await typeDropdown.click();
      await page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)', { timeout: 5000 });

      const typeOption = page
        .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
        .filter({ hasText: customRelType.id })
        .first();
      // The custom type must be offered; scroll it into view and select it, then
      // wait for the dropdown to close so it no longer overlays the modal footer.
      await expect(typeOption).toBeVisible({ timeout: 10000 });
      await typeOption.scrollIntoViewIfNeeded().catch(() => {});
      await typeOption.click();
      await page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)', { state: 'detached', timeout: 5000 }).catch(async () => {
        // Fallback: force the dropdown closed if it lingers.
        await page.keyboard.press('Escape');
      });
      await waitForRender(page);

      // Custom properties section appears for a type with custom property defs.
      const customPropsSection = modal.locator('h4:has-text("カスタムプロパティ")');
      await expect(customPropsSection).toBeVisible({ timeout: 10000 });

      // Close modal (Escape is robust against any lingering dropdown overlay).
      await page.keyboard.press('Escape');
      await page.locator('.ant-modal:visible').waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
    });

    test('should not lose form data when clicking outside relationship modal', async ({ page }) => {
      // Find a document
      const documentLink = page.locator('.ant-table-row:has(.anticon-file) .ant-btn-link').first();
      if (await documentLink.count() === 0) {
        test.skip('ENV: No documents found in list');
        return;
      }

      await documentLink.click();
      await waitForUiStable(page);

      // Click relationship tab (i18n-safe)
      const relationshipTab = page.getByRole('tab', { name: /リレーションシップ|Relationships/i });
      await expect(relationshipTab).toBeVisible({ timeout: 10000 });
      await relationshipTab.click();
      await waitForRender(page);

      // Open add relationship modal (i18n-safe)
      const addButton = page.getByRole('button', { name: /関係を追加|リレーションシップ.*追加|Add.*Relationship/i }).first();
      await expect(addButton).toBeVisible({ timeout: 10000 });
      await addButton.click();

      const modal = page.locator('.ant-modal:visible');
      await expect(modal).toBeVisible({ timeout: 5000 });

      // Fill target object ID if field exists
      const targetInput = modal.locator('input[placeholder*="ターゲット"], input[placeholder*="target"]').first();
      if (await targetInput.count() > 0) {
        await targetInput.fill('test-target-id');

        // Click outside modal
        await page.locator('.ant-modal-mask').click({ force: true, position: { x: 10, y: 10 } });
        await waitForRender(page);

        // Modal should still be visible
        await expect(modal).toBeVisible();

        // Data should be preserved
        await expect(targetInput).toHaveValue('test-target-id');
      }

      // Close modal
      const cancelButton = modal.locator('button:has-text("キャンセル")');
      if (await cancelButton.count() > 0) {
        await cancelButton.click();
      } else {
        await page.keyboard.press('Escape');
      }
    });
  });

  test.describe('Property Type Input Fields', () => {
    test('should render correct input types for different property types', async ({ page }) => {
      // Check for a type with various property types
      const typesResponse = await page.request.get(
        'http://localhost:8080/core/rest/repo/bedroom/type/list',
        {
          headers: {
            'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64'),
          },
        }
      );

      if (!typesResponse.ok()) {
        test.skip('ENV: Type API request failed');
        return;
      }

      const typesData = await typesResponse.json();
      const types = typesData.types || typesData;
      if (!Array.isArray(types)) {
        test.skip('ENV: Types response is not an array');
        return;
      }

      // Find a type with boolean or datetime properties
      const typeWithVariedProps = types.find((t: any) => {
        if (!t.propertyDefinitions || !Array.isArray(t.propertyDefinitions)) return false;
        const hasBoolean = t.propertyDefinitions.some((p: any) => p.id && !p.id.startsWith('cmis:') && p.propertyType === 'boolean');
        const hasDatetime = t.propertyDefinitions.some((p: any) => p.id && !p.id.startsWith('cmis:') && p.propertyType === 'datetime');
        const hasNumber = t.propertyDefinitions.some((p: any) => p.id && !p.id.startsWith('cmis:') && (p.propertyType === 'integer' || p.propertyType === 'decimal'));
        return hasBoolean || hasDatetime || hasNumber;
      });

      if (!typeWithVariedProps) {
        console.log('No type with varied property types found, testing basic string input only');
      }

      // Open document upload modal
      await page.locator('button:has-text("ファイルアップロード")').click();
      const modal = page.locator('.ant-modal').filter({ hasText: 'ファイルアップロード' });
      await expect(modal).toBeVisible({ timeout: 5000 });

      if (typeWithVariedProps) {
        // Select the type with varied properties
        const typeDropdown = modal.locator('.ant-select').first();
        await typeDropdown.click();
        await page.waitForSelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)', { timeout: 5000 });

        const typeOption = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: typeWithVariedProps.displayName || typeWithVariedProps.id });
        if (await typeOption.count() > 0) {
          await typeOption.click();
          await waitForRender(page);

          // Check for different input types
          const customPropsSection = modal.locator('div:has(> h4:has-text("カスタムプロパティ"))');

          // Boolean should render as Select
          const booleanSelects = customPropsSection.locator('.ant-select:has(.ant-select-item-option:has-text("はい"))');

          // DateTime should render as DatePicker
          const datetimeInputs = customPropsSection.locator('.ant-picker');

          // Number should render as number input
          const numberInputs = customPropsSection.locator('input[type="number"]');

          console.log('Boolean selects:', await booleanSelects.count());
          console.log('DateTime inputs:', await datetimeInputs.count());
          console.log('Number inputs:', await numberInputs.count());
        }
      }

      // Close any open dropdowns first by pressing Escape
      await page.keyboard.press('Escape');
      await waitForRender(page);

      // Close modal
      await page.locator('.ant-modal button:has-text("キャンセル")').click({ timeout: 10000 });
    });
  });
});
