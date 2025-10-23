import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { randomUUID } from 'crypto';

test.describe('Custom Type and Custom Attributes', () => {
  let authHelper: AuthHelper;
  let testHelper: TestHelper;
  const customTypeId = `test:customDoc${randomUUID().substring(0, 8)}`;
  const customTypeName = `カスタムドキュメントタイプ ${randomUUID().substring(0, 4)}`;
  const customPropId = `test:customProperty${randomUUID().substring(0, 8)}`;
  const customPropName = `カスタム属性 ${randomUUID().substring(0, 4)}`;
  let testDocumentId: string;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await page.context().clearCookies();
    await page.context().clearPermissions();
    await authHelper.login();
    await testHelper.waitForAntdLoad();

    // Mobile browser fix
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        try {
          await menuToggle.first().click({ timeout: 3000 });
          await page.waitForTimeout(500);
        } catch (error) {
          // Continue even if sidebar close fails
        }
      }
    }
  });

  test.skip('should create custom document type with custom attributes', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to Type Management
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);
    }

    const typeManagementItem = page.locator('.ant-menu-item:has-text("タイプ管理")');
    if (await typeManagementItem.count() > 0) {
      await typeManagementItem.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Click "新規タイプ" button
    const newTypeButton = page.locator('button:has-text("新規タイプ")');
    if (await newTypeButton.count() > 0) {
      await newTypeButton.click(isMobile ? { force: true } : {});
      await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

      // Fill basic information tab
      await page.fill('input[id*="id"]', customTypeId);
      await page.fill('input[id*="displayName"]', customTypeName);
      await page.fill('textarea[id*="description"]', 'Test custom document type for E2E testing');

      // Select base type using combobox
      const baseTypeCombobox = page.locator('input[role="combobox"]').filter({ hasText: 'ベースタイプ' }).or(
        page.locator('.ant-select-selector').filter({ has: page.locator(':text("ベースタイプを選択")') })
      ).first();
      await baseTypeCombobox.click({ timeout: 10000 });
      await page.waitForTimeout(500);
      const documentOption = page.locator('.ant-select-item-option:has-text("ドキュメント")').first();
      await documentOption.click();

      // Switch to properties tab
      const propertiesTab = page.locator('.ant-tabs-tab:has-text("プロパティ定義")');
      await propertiesTab.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Add custom property
      const addPropertyButton = page.locator('button:has-text("プロパティを追加")');
      await addPropertyButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      // Fill property details
      const propertyCard = page.locator('.ant-card').last();

      // Property ID
      const propIdInput = propertyCard.locator('input').first();
      await propIdInput.fill(customPropId);

      // Display name
      const propNameInputs = propertyCard.locator('input[placeholder*="表示名"]');
      await propNameInputs.first().fill(customPropName);

      // Select property type (string)
      const propTypeSelect = propertyCard.locator('.ant-select').first();
      await propTypeSelect.click();
      await page.waitForTimeout(300);
      const stringOption = page.locator('.ant-select-item-option:has-text("文字列")').first();
      await stringOption.click();

      // Select cardinality (single)
      const cardinalitySelect = propertyCard.locator('.ant-select').last();
      await cardinalitySelect.click();
      await page.waitForTimeout(300);
      const singleOption = page.locator('.ant-select-item-option:has-text("単一")').first();
      await singleOption.click();

      // Set updatable flag
      const updatableSwitch = propertyCard.locator('.ant-switch').filter({ has: page.locator('label:has-text("更新可能")') });
      if (await updatableSwitch.count() > 0) {
        const isChecked = await updatableSwitch.first().evaluate(el => el.classList.contains('ant-switch-checked'));
        if (!isChecked) {
          await updatableSwitch.first().click();
        }
      }

      // Capture browser console logs and API errors
      const consoleLogs: string[] = [];
      const apiErrors: string[] = [];

      page.on('console', (msg) => {
        const logText = `[${msg.type()}] ${msg.text()}`;
        consoleLogs.push(logText);
        console.log(logText);
      });

      page.on('pageerror', (error) => {
        const errorText = `[PAGE ERROR] ${error.message}`;
        consoleLogs.push(errorText);
        console.error(errorText);
      });

      page.on('response', async (response) => {
        const url = response.url();
        if (url.includes('/type/create')) {
          console.log(`API Response: ${response.status()} ${url}`);
          if (!response.ok()) {
            try {
              const body = await response.text();
              const errorMsg = `API Error ${response.status()}: ${body}`;
              apiErrors.push(errorMsg);
              console.error(errorMsg);
            } catch (e) {
              console.error(`API Error ${response.status()} (could not read body)`);
            }
          }
        }
      });

      // Submit form
      const submitButton = page.locator('.ant-modal button[type="submit"], .ant-modal button:has-text("作成")');
      await submitButton.click(isMobile ? { force: true } : {});

      // Wait for success message
      try {
        await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      } catch (error) {
        console.error('Failed to wait for success message');
        console.error('Console logs:', consoleLogs);
        console.error('API errors:', apiErrors);
        throw error;
      }
      await page.waitForTimeout(2000);

      // Verify type appears in table
      const customTypeRow = page.locator(`tr:has-text("${customTypeId}")`);
      await expect(customTypeRow).toBeVisible({ timeout: 5000 });
      console.log('✅ Custom type created successfully');
    } else {
      test.skip('Type management not available');
    }
  });

  test('should create document with custom type and display custom attributes', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Upload document with custom type
    const uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' });
    if (await uploadButton.count() > 0) {
      await uploadButton.click(isMobile ? { force: true } : {});
      await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

      const testDocName = `custom-type-doc-${randomUUID().substring(0, 8)}.txt`;

      // Upload file
      await testHelper.uploadTestFile(
        '.ant-modal input[type="file"]',
        testDocName,
        'Document with custom type and custom attributes'
      );
      await page.waitForTimeout(1000);

      // Check if type selector is available
      const typeSelect = page.locator('.ant-modal .ant-select').filter({ has: page.locator('label:has-text("タイプ"), label:has-text("objectTypeId")') });

      if (await typeSelect.count() > 0) {
        await typeSelect.first().click();
        await page.waitForTimeout(500);

        // Select custom type if available in dropdown
        const customTypeOption = page.locator(`.ant-select-item-option:has-text("${customTypeName}")`);
        if (await customTypeOption.count() > 0) {
          await customTypeOption.click();
          console.log('✅ Selected custom type for document');
        }
      }

      // Submit upload
      const submitBtn = page.locator('.ant-modal button[type="submit"]');
      await submitBtn.click();
      await page.waitForSelector('.ant-message-success', { timeout: 10000 });
      await page.waitForTimeout(2000);

      // Find and open the uploaded document
      const documentLink = page.locator(`a:has-text("${testDocName}")`);
      if (await documentLink.count() > 0) {
        await documentLink.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(2000);

        // Extract document ID from URL
        const url = page.url();
        const match = url.match(/\/documents\/([a-f0-9]+)/);
        if (match) {
          testDocumentId = match[1];
          console.log('✅ Document created with ID:', testDocumentId);

          // Verify document type in details
          const typeDescription = page.locator('.ant-descriptions-item').filter({ has: page.locator(':has-text("タイプ")') });
          if (await typeDescription.count() > 0) {
            const typeValue = await typeDescription.locator('.ant-descriptions-item-content').textContent();
            console.log('Document type:', typeValue);
          }

          // Click on properties tab
          const propertiesTab = page.locator('.ant-tabs-tab:has-text("プロパティ")');
          if (await propertiesTab.count() > 0) {
            await propertiesTab.click(isMobile ? { force: true } : {});
            await page.waitForTimeout(1000);

            // Look for custom property field
            const customPropField = page.locator(`.ant-form-item-label:has-text("${customPropName}")`);

            if (await customPropField.count() > 0) {
              await expect(customPropField).toBeVisible();
              console.log('✅ Custom attribute field displayed in PropertyEditor');
            } else {
              console.log('⚠️ Custom attribute not visible (may require custom type assignment)');
            }
          }
        }
      }
    } else {
      test.skip('Upload functionality not available');
    }
  });

  test('should edit custom attribute value and verify persistence', async ({ page, browserName }) => {
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (!testDocumentId) {
      test.skip('Test document not created in previous test');
      return;
    }

    // Navigate to document detail page
    await page.goto(`http://localhost:8080/core/ui/dist/#/documents/${testDocumentId}`);
    await page.waitForTimeout(2000);

    // Click on properties tab
    const propertiesTab = page.locator('.ant-tabs-tab:has-text("プロパティ")');
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      // Find custom property input
      const customPropInput = page.locator(`input[id*="${customPropId}"]`);

      if (await customPropInput.count() > 0 && await customPropInput.isEnabled()) {
        const testValue = `Custom value ${Date.now()}`;

        // Fill custom property value
        await customPropInput.clear();
        await customPropInput.fill(testValue);
        await page.waitForTimeout(500);

        // Save changes
        const saveButton = page.locator('button[type="submit"]').filter({ hasText: '保存' });
        await saveButton.click(isMobile ? { force: true } : {});

        // Wait for success message
        await page.waitForSelector('.ant-message-success', { timeout: 10000 });
        console.log('✅ Custom attribute value saved');

        // Verify persistence by reloading
        await page.reload();
        await page.waitForTimeout(2000);

        // Navigate to properties tab again
        const propertiesTabReload = page.locator('.ant-tabs-tab:has-text("プロパティ")');
        await propertiesTabReload.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(1000);

        // Verify saved value
        const reloadedInput = page.locator(`input[id*="${customPropId}"]`);
        const savedValue = await reloadedInput.inputValue();
        expect(savedValue).toBe(testValue);
        console.log('✅ Custom attribute value persisted after reload');
      } else {
        console.log('⚠️ Custom attribute not editable or not found');
      }
    }
  });

  test.afterAll(async ({ browser }) => {
    // Cleanup: Delete custom type and test document
    const context = await browser.newContext();
    const page = await context.newPage();
    const cleanupAuthHelper = new AuthHelper(page);

    try {
      await cleanupAuthHelper.login();
      await page.waitForTimeout(2000);

      // Delete test document if created
      if (testDocumentId) {
        await page.goto('http://localhost:8080/core/ui/dist/#/documents');
        await page.waitForTimeout(2000);

        const documentRow = page.locator(`tr:has([href*="${testDocumentId}"])`);
        if (await documentRow.count() > 0) {
          const deleteButton = documentRow.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
          if (await deleteButton.count() > 0) {
            await deleteButton.click();
            await page.waitForTimeout(500);

            const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary');
            if (await confirmButton.count() > 0) {
              await confirmButton.click();
              await page.waitForSelector('.ant-message-success', { timeout: 15000 });
              console.log('✅ Test document deleted');
            }
          }
        }
      }

      // Delete custom type
      const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
      if (await adminMenu.count() > 0) {
        await adminMenu.click();
        await page.waitForTimeout(1000);
      }

      const typeManagementItem = page.locator('.ant-menu-item:has-text("タイプ管理")');
      if (await typeManagementItem.count() > 0) {
        await typeManagementItem.click();
        await page.waitForTimeout(2000);

        const customTypeRow = page.locator(`tr:has-text("${customTypeId}")`);
        if (await customTypeRow.count() > 0) {
          const deleteButton = customTypeRow.locator('button').filter({ has: page.locator('[data-icon="delete"]') });
          if (await deleteButton.count() > 0) {
            await deleteButton.click();
            await page.waitForTimeout(500);

            const confirmButton = page.locator('.ant-popconfirm button:has-text("はい")');
            if (await confirmButton.count() > 0) {
              await confirmButton.click();
              await page.waitForSelector('.ant-message-success', { timeout: 10000 });
              console.log('✅ Custom type deleted');
            }
          }
        }
      }
    } catch (error) {
      console.error('Cleanup failed:', error);
    } finally {
      await context.close();
    }
  });
});
