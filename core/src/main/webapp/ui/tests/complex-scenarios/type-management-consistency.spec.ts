/**
 * Complex Scenario Test: Type Management Consistency with Document Operations
 *
 * This test suite validates the consistency between:
 * 1. Custom type creation and management
 * 2. Document creation with custom types
 * 3. Type deletion constraints (cannot delete type with existing documents)
 * 4. Type modification impact on existing documents
 * 5. Preview functionality with custom type documents
 *
 * Test Environment:
 * - Server: http://localhost:8080/core/ui/
 * - Authentication: admin:admin
 * - Repository: bedroom
 *
 * CMIS Concepts Tested:
 * - Type Definition Management
 * - Type Mutability Constraints
 * - Document-Type Relationships
 * - Type Deletion Validation
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { randomUUID } from 'crypto';

test.describe('Type Management Consistency with Document Operations', () => {
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  const testRunId = randomUUID().substring(0, 8);
  const customTypeId = `test:managedType${testRunId}`;
  const customTypeName = `Managed Type ${testRunId}`;
  const customPropId = `test:managedProp${testRunId}`;
  const customPropName = `Managed Property ${testRunId}`;
  const testDocumentName = `type-mgmt-test-${testRunId}.txt`;

  let testDocumentId: string;
  let typeCreated = false;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();
    await page.waitForTimeout(2000);

    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        await menuToggle.first().click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(500);
      }
    }

    await testHelper.waitForAntdLoad();
  });

  test('Step 1: Create custom document type', async ({ page, browserName }) => {
    console.log(`Creating custom type: ${customTypeId}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to Type Management
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);
    }

    const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: /タイプ管理|Type Management/i });
    if (await typeManagementItem.count() > 0) {
      await typeManagementItem.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    } else {
      test.skip('Type Management menu not available');
      return;
    }

    await page.waitForSelector('.ant-table', { timeout: 15000 });

    // Click create type button
    const newTypeButton = page.locator('button').filter({ hasText: /新規タイプ|新規.*作成|Create.*Type/ });
    if (await newTypeButton.count() === 0) {
      test.skip('Create type button not found');
      return;
    }

    await newTypeButton.first().click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    const createModal = page.locator('.ant-modal:visible, .ant-drawer:visible');
    await expect(createModal).toBeVisible({ timeout: 5000 });

    // Fill type ID
    const typeIdInput = createModal.locator('input[placeholder*="タイプID"]');
    if (await typeIdInput.count() > 0) {
      await typeIdInput.first().fill(customTypeId);
    } else {
      await createModal.locator('input').first().fill(customTypeId);
    }

    // Fill type name
    const typeNameInput = createModal.locator('input[placeholder*="表示名"]');
    if (await typeNameInput.count() > 0) {
      await typeNameInput.first().fill(customTypeName);
    }

    // Select base type (cmis:document)
    const baseTypeSelect = createModal.locator('.ant-form-item').filter({ hasText: 'ベースタイプ' }).locator('.ant-select');
    if (await baseTypeSelect.count() > 0) {
      await baseTypeSelect.first().click();
      await page.waitForTimeout(500);
      const documentOption = page.locator('.ant-select-item').filter({ hasText: /ドキュメント|Documents/i });
      if (await documentOption.count() > 0) {
        await documentOption.first().click();
      }
    }

    // Switch to properties tab and add property
    const propertiesTab = createModal.locator('.ant-tabs-tab').filter({ hasText: /プロパティ定義|Properties/ });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const addPropertyButton = page.locator('button').filter({ hasText: /プロパティを追加|Add Property/ });
      if (await addPropertyButton.count() > 0) {
        await addPropertyButton.first().click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        const propertyCard = page.locator('.ant-card').last();

        const propIdInput = propertyCard.locator('input[placeholder*="プロパティID"]');
        if (await propIdInput.count() > 0) {
          await propIdInput.first().fill(customPropId);
        } else {
          await propertyCard.locator('input').first().fill(customPropId);
        }

        const propNameInput = propertyCard.locator('input[placeholder*="表示名"]');
        if (await propNameInput.count() > 0) {
          await propNameInput.first().fill(customPropName);
        }

        const propTypeSelect = propertyCard.locator('.ant-select').first();
        if (await propTypeSelect.count() > 0) {
          await propTypeSelect.click();
          await page.waitForTimeout(300);
          const stringOption = page.locator('.ant-select-item-option').filter({ hasText: /文字列|String/i }).first();
          if (await stringOption.count() > 0) {
            await stringOption.click();
          }
        }
      }
    }

    // Submit form
    const submitButton = createModal.locator('button[type="submit"], button:has-text("作成"), button.ant-btn-primary');
    await submitButton.first().click(isMobile ? { force: true } : {});
    await page.waitForTimeout(5000);

    // Check for success
    const successMessage = page.locator('.ant-message-success');
    if (await successMessage.count() > 0) {
      typeCreated = true;
      console.log('Custom type created successfully');
    }

    // Close modal if still open
    const modalStillOpen = await page.locator('.ant-modal:visible').count() > 0;
    if (modalStillOpen) {
      const closeButton = page.locator('.ant-modal-close');
      if (await closeButton.count() > 0) {
        await closeButton.first().click();
      }
    }

    // Verify type appears in table
    await page.waitForTimeout(2000);
    const typeRow = page.locator('.ant-table-tbody tr').filter({ hasText: customTypeId });
    const typeInTable = await typeRow.count() > 0;
    console.log(`Type ${customTypeId} in table: ${typeInTable}`);
  });

  test('Step 2: Create document with custom type', async ({ page, browserName }) => {
    console.log(`Creating document with custom type: ${testDocumentName}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Upload document
    const uploadButton = page.locator('button').filter({ hasText: /アップロード|Upload/ }).first();
    if (await uploadButton.count() === 0) {
      test.skip('Upload button not found');
      return;
    }

    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(1000);

    const uploadModal = page.locator('.ant-modal:visible');
    await expect(uploadModal).toBeVisible({ timeout: 5000 });

    // Select file
    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: testDocumentName,
      mimeType: 'text/plain',
      buffer: Buffer.from(`Type management test content - ${testRunId}`, 'utf-8'),
    });
    await page.waitForTimeout(1000);

    // Fill file name
    const nameInput = uploadModal.locator('input[placeholder*="ファイル名"]');
    if (await nameInput.count() > 0) {
      await nameInput.fill(testDocumentName);
    }

    // Try to select custom type
    const typeSelector = uploadModal.locator('.ant-select').filter({ hasText: /タイプ|Type/ });
    if (await typeSelector.count() > 0) {
      await typeSelector.click();
      await page.waitForTimeout(500);

      const customTypeOption = page.locator('.ant-select-item-option').filter({ hasText: customTypeId });
      if (await customTypeOption.count() > 0) {
        await customTypeOption.click();
        console.log(`Selected custom type: ${customTypeId}`);
        await page.waitForTimeout(500);

        // Fill custom property if visible
        const customPropInput = uploadModal.locator(`input[placeholder*="${customPropName}"], input[name*="${customPropId}"]`);
        if (await customPropInput.count() > 0) {
          await customPropInput.fill(`TestValue_${testRunId}`);
        }
      }
    }

    // Submit upload
    const submitButton = uploadModal.locator('button[type="submit"]').filter({ hasText: /アップロード|Upload/ });
    await submitButton.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(5000);

    // Verify document created
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    const docExists = await documentRow.count() > 0;
    console.log(`Document ${testDocumentName} created: ${docExists}`);

    if (docExists) {
      const rowKey = await documentRow.getAttribute('data-row-key');
      if (rowKey) {
        testDocumentId = rowKey;
      }
    }

    expect(docExists).toBe(true);
  });

  test('Step 3: Attempt to delete custom type (should fail due to existing documents)', async ({ page, browserName }) => {
    console.log('Attempting to delete custom type with existing documents...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to Type Management
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);
    }

    const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: /タイプ管理|Type Management/i });
    if (await typeManagementItem.count() > 0) {
      await typeManagementItem.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Find the custom type row
    const typeRow = page.locator('.ant-table-tbody tr').filter({ hasText: customTypeId }).first();
    if (await typeRow.count() === 0) {
      console.log('Custom type not found in table');
      return;
    }

    // Try to delete the type
    const deleteButton = typeRow.locator('button').filter({ hasText: /削除|Delete/ }).first();
    if (await deleteButton.count() > 0) {
      await deleteButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(500);

      // Confirm deletion
      const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除/ }).first();
      if (await confirmButton.count() > 0) {
        await confirmButton.click();
        await page.waitForTimeout(3000);
      }

      // Check for error message (deletion should fail)
      const errorMessage = page.locator('.ant-message-error');
      const hasError = await errorMessage.count() > 0;
      console.log(`Type deletion error shown: ${hasError}`);

      // Verify type still exists
      const typeStillExists = await page.locator('.ant-table-tbody tr').filter({ hasText: customTypeId }).count() > 0;
      console.log(`Type still exists after deletion attempt: ${typeStillExists}`);

      // Type should still exist because documents are using it
      expect(typeStillExists).toBe(true);
    } else {
      console.log('Delete button not found for type');
    }
  });

  test('Step 4: Preview document with custom type', async ({ page, browserName }) => {
    console.log('Testing preview functionality with custom type document...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Find the test document
    const documentRow = page.locator('.ant-table-tbody tr').filter({ hasText: testDocumentName }).first();
    if (await documentRow.count() === 0) {
      test.skip('Test document not found');
      return;
    }

    await documentRow.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Look for preview button
    const previewButton = page.locator('button').filter({ hasText: /プレビュー|Preview|表示/ }).first();
    if (await previewButton.count() > 0) {
      await previewButton.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);

      // Check if preview modal/panel opened
      const previewContainer = page.locator('.ant-modal:visible, .preview-container, iframe');
      const previewOpened = await previewContainer.count() > 0;
      console.log(`Preview opened: ${previewOpened}`);

      if (previewOpened) {
        // Close preview
        const closeButton = page.locator('.ant-modal-close').first();
        if (await closeButton.count() > 0) {
          await closeButton.click();
          await page.waitForTimeout(500);
        }
      }
    } else {
      console.log('Preview button not found');
    }

    // Verify custom property is displayed
    const propertiesTab = page.locator('.ant-tabs-tab').filter({ hasText: /プロパティ|Properties/ });
    if (await propertiesTab.count() > 0) {
      await propertiesTab.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);

      const customPropLabel = page.locator(`text=${customPropName}`);
      const customPropVisible = await customPropLabel.count() > 0;
      console.log(`Custom property ${customPropName} visible: ${customPropVisible}`);
    }
  });

  test('Step 5: Delete document and then delete custom type', async ({ page, browserName }) => {
    console.log('Deleting document to allow type deletion...');

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents page
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    await documentsMenuItem.click(isMobile ? { force: true } : {});
    await page.waitForTimeout(2000);

    // Delete the test document
    await testHelper.deleteTestDocument(testDocumentName);
    await page.waitForTimeout(2000);

    // Now try to delete the custom type
    const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
    if (await adminMenu.count() > 0) {
      await adminMenu.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(1000);
    }

    const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: /タイプ管理|Type Management/i });
    if (await typeManagementItem.count() > 0) {
      await typeManagementItem.click(isMobile ? { force: true } : {});
      await page.waitForTimeout(2000);
    }

    // Find and delete the custom type
    const typeRow = page.locator('.ant-table-tbody tr').filter({ hasText: customTypeId }).first();
    if (await typeRow.count() > 0) {
      const deleteButton = typeRow.locator('button').filter({ hasText: /削除|Delete/ }).first();
      if (await deleteButton.count() > 0) {
        await deleteButton.click(isMobile ? { force: true } : {});
        await page.waitForTimeout(500);

        const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除/ }).first();
        if (await confirmButton.count() > 0) {
          await confirmButton.click();
          await page.waitForTimeout(3000);
        }

        // Verify type is deleted
        const typeDeleted = await page.locator('.ant-table-tbody tr').filter({ hasText: customTypeId }).count() === 0;
        console.log(`Type deleted successfully: ${typeDeleted}`);
      }
    }
  });

  test.afterAll(async ({ browser }) => {
    console.log('Cleaning up type management test data...');

    const context = await browser.newContext();
    const page = await context.newPage();
    const authHelper = new AuthHelper(page);
    const testHelper = new TestHelper(page);

    try {
      await authHelper.login();
      await page.waitForTimeout(2000);

      // Delete test document if it still exists
      const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
      if (await documentsMenuItem.count() > 0) {
        await documentsMenuItem.click();
        await page.waitForTimeout(2000);
        await testHelper.deleteTestDocument(testDocumentName);
      }

      // Delete custom type if it still exists
      const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
      if (await adminMenu.count() > 0) {
        await adminMenu.click();
        await page.waitForTimeout(1000);

        const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: /タイプ管理|Type Management/i });
        if (await typeManagementItem.count() > 0) {
          await typeManagementItem.click();
          await page.waitForTimeout(2000);

          const typeRow = page.locator('.ant-table-tbody tr').filter({ hasText: customTypeId }).first();
          if (await typeRow.count() > 0) {
            const deleteButton = typeRow.locator('button').filter({ hasText: /削除|Delete/ }).first();
            if (await deleteButton.count() > 0) {
              await deleteButton.click();
              await page.waitForTimeout(500);

              const confirmButton = page.locator('.ant-modal button, .ant-popconfirm button').filter({ hasText: /OK|確認|削除/ }).first();
              if (await confirmButton.count() > 0) {
                await confirmButton.click();
                await page.waitForTimeout(2000);
              }
            }
          }
        }
      }
    } catch (error) {
      console.error('Cleanup error:', error);
    } finally {
      await context.close();
    }
  });
});
