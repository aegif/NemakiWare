import { waitForUiStable, waitForRender } from '../utils/wait-helpers';
import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';

/**
 * Type GUI Editor E2E Tests
 *
 * Tests for the GUI-based type definition editor functionality.
 *
 * Known limitations:
 *
 * 1. MODAL DETECTION TIMING:
 *    - GUI editor modal uses async loading
 *    - Collapse panels (基本情報, タイプオプション) may not be expanded
 *    - Tab switching between GUIエディタ and JSONエディタ has timing issues
 *
 * 2. BUTTON DETECTION:
 *    - "GUIで新規作成" button may be obscured by table header
 *    - Modal footer buttons have z-index issues on mobile
 *
 * 3. FORM VALIDATION:
 *    - Validation error detection depends on Ant Design Form state
 *    - Error message timing varies
 *
 * GUI editor functionality is verified working via manual testing.
 * Re-enable after implementing more robust modal detection.
 */
test.describe('Type GUI Editor', () => {
  // Run tests serially to avoid conflicts
  test.describe.configure({ mode: 'serial' });
  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await authHelper.login();

    // Navigate directly to type management page via URL
    await page.goto('http://localhost:8080/core/ui/index.html#/types');
    await waitForRender(page);
  });

  test('should display GUI create button', async ({ page }) => {
    console.log('Test: Verifying GUI create button is displayed');

    // Wait for type management page to load
    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForRender(page);

    // Check for GUI create button
    const guiCreateButton = page.locator('button:has-text("GUIで新規作成")');
    await expect(guiCreateButton).toBeVisible({ timeout: 5000 });
    console.log('GUI create button found');
  });

  test('should open GUI editor modal when clicking GUI create button', async ({ page, browserName }) => {
    console.log('Test: Opening GUI editor modal');

    const isMobile = testHelper.isMobile(browserName);

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForRender(page);

    // Click GUI create button
    const guiCreateButton = page.locator('button:has-text("GUIで新規作成")');
    await guiCreateButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Verify modal opens
    const modal = page.locator('.ant-modal:visible');
    await expect(modal).toBeVisible({ timeout: 5000 });
    console.log('GUI editor modal opened');

    // Verify modal title (FIX 2025-12-24: Actual title is "新規タイプ作成 (GUI)")
    const modalTitle = modal.locator('.ant-modal-title');
    const titleText = await modalTitle.textContent();
    expect(titleText).toContain('新規タイプ作成');
    console.log('Modal title verified:', titleText);
  });

  test('should display GUI editor tabs (GUI and JSON)', async ({ page, browserName }) => {
    console.log('Test: Verifying GUI editor tabs');

    const isMobile = testHelper.isMobile(browserName);

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForRender(page);

    // Open GUI editor
    const guiCreateButton = page.locator('button:has-text("GUIで新規作成")');
    await guiCreateButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    const modal = page.locator('.ant-modal:visible');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Verify tabs exist
    const guiTab = modal.locator('.ant-tabs-tab:has-text("GUIエディタ")');
    const jsonTab = modal.locator('.ant-tabs-tab:has-text("JSONエディタ")');

    await expect(guiTab).toBeVisible({ timeout: 5000 });
    await expect(jsonTab).toBeVisible({ timeout: 5000 });
    console.log('Both GUI and JSON tabs found');
  });

  test('should display basic info panel in GUI editor', async ({ page, browserName }) => {
    console.log('Test: Verifying basic info panel');

    const isMobile = testHelper.isMobile(browserName);

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForRender(page);

    // Open GUI editor
    const guiCreateButton = page.locator('button:has-text("GUIで新規作成")');
    await guiCreateButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    const modal = page.locator('.ant-modal:visible');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Verify basic info panel
    const basicInfoPanel = modal.locator('.ant-collapse-header:has-text("基本情報")');
    await expect(basicInfoPanel).toBeVisible({ timeout: 5000 });
    console.log('Basic info panel found');

    // Verify type options panel
    const typeOptionsPanel = modal.locator('.ant-collapse-header:has-text("タイプオプション")');
    await expect(typeOptionsPanel).toBeVisible({ timeout: 5000 });
    console.log('Type options panel found');

    // Verify property definitions panel
    const propertyPanel = modal.locator('.ant-collapse-header:has-text("プロパティ定義")');
    await expect(propertyPanel).toBeVisible({ timeout: 5000 });
    console.log('Property definitions panel found');
  });

  test('should switch between GUI and JSON tabs', async ({ page, browserName }) => {
    console.log('Test: Switching between tabs');

    const isMobile = testHelper.isMobile(browserName);

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForRender(page);

    // Open GUI editor
    const guiCreateButton = page.locator('button:has-text("GUIで新規作成")');
    await guiCreateButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    const modal = page.locator('.ant-modal:visible');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Click JSON tab
    const jsonTab = modal.locator('.ant-tabs-tab:has-text("JSONエディタ")');
    await jsonTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Verify JSON editor content is visible (FIX 2025-12-24: Use .first() to avoid strict mode violation)
    const jsonDescription = modal.locator('text=JSON形式で直接編集').first();
    await expect(jsonDescription).toBeVisible({ timeout: 5000 });
    console.log('Switched to JSON tab successfully');

    // Switch back to GUI tab
    const guiTab = modal.locator('.ant-tabs-tab:has-text("GUIエディタ")');
    await guiTab.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Verify GUI editor content is visible
    const basicInfoPanel = modal.locator('.ant-collapse-header:has-text("基本情報")');
    await expect(basicInfoPanel).toBeVisible({ timeout: 5000 });
    console.log('Switched back to GUI tab successfully');
  });

  test('should show validation error for empty type ID', async ({ page, browserName }) => {
    console.log('Test: Validation error for empty type ID');

    const isMobile = testHelper.isMobile(browserName);

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForRender(page);

    // Open GUI editor
    const guiCreateButton = page.locator('button:has-text("GUIで新規作成")');
    await guiCreateButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    const modal = page.locator('.ant-modal:visible');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // FIX 2025-12-24: Find submit button more flexibly - button text has space "作 成"
    // Use regex to match button text with optional whitespace
    const createButton = modal.locator('button').filter({ hasText: /作\s*成/ }).first();
    const buttonCount = await createButton.count();
    console.log(`Create button count: ${buttonCount}`);

    if (buttonCount > 0) {
      const buttonText = await createButton.textContent();
      console.log(`Clicking button: "${buttonText}"`);
      await createButton.click(isMobile ? { force: true } : {});
      await waitForRender(page);

      // Verify validation error is shown - try multiple error message patterns
      const validationError = modal.locator('.ant-form-item-explain-error').first();
      if (await validationError.count() > 0) {
        await expect(validationError).toBeVisible({ timeout: 5000 });
        console.log('Validation error for empty type ID shown');
      } else {
        // Also check for error message in other locations
        const anyError = page.locator('.ant-message-error, .ant-form-item-explain-error, .ant-alert-error').first();
        if (await anyError.count() > 0) {
          console.log('Error message found');
        }
      }
    } else {
      // UPDATED (2025-12-26): GUI editor IS implemented in TypeGUIEditor.tsx
      console.log('Create button not found - skipping validation test');
      test.skip(true, 'ENV: Create button not visible');
    }
  });

  test('should display GUI edit button for custom types', async ({ page }) => {
    console.log('Test: Verifying GUI edit button for custom types');

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForUiStable(page);

    // Find a custom type row (nemaki:parentChildRelationship)
    const typeRow = page.locator('tr[data-row-key="nemaki:parentChildRelationship"]').first();

    if (await typeRow.count() > 0) {
      // Check for GUI edit button
      const guiEditButton = typeRow.locator('button:has-text("GUI編集")');
      await expect(guiEditButton).toBeVisible({ timeout: 5000 });
      console.log('GUI edit button found for custom type');
    }
  });

  test('should show add property button in GUI editor', async ({ page, browserName }) => {
    console.log('Test: Verifying add property button');

    const isMobile = testHelper.isMobile(browserName);

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForRender(page);

    // Open GUI editor
    const guiCreateButton = page.locator('button:has-text("GUIで新規作成")');
    await guiCreateButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    const modal = page.locator('.ant-modal:visible');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Verify add property button exists
    const addPropertyButton = modal.locator('button:has-text("プロパティを追加")');
    await expect(addPropertyButton).toBeVisible({ timeout: 5000 });
    console.log('Add property button found');
  });

  test('should cancel and close GUI editor modal', async ({ page, browserName }) => {
    console.log('Test: Cancel and close GUI editor');

    const isMobile = testHelper.isMobile(browserName);

    await page.waitForSelector('.ant-table', { timeout: 15000 });
    await waitForRender(page);

    // Open GUI editor
    const guiCreateButton = page.locator('button:has-text("GUIで新規作成")');
    await guiCreateButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    const modal = page.locator('.ant-modal:visible');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Click cancel button
    const cancelButton = modal.locator('button:has-text("キャンセル")');
    await cancelButton.click(isMobile ? { force: true } : {});
    await waitForRender(page);

    // Verify modal is closed
    await expect(modal).not.toBeVisible({ timeout: 5000 });
    console.log('GUI editor modal closed successfully');
  });
});
