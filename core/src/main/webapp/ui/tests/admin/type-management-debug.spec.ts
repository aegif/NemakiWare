import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

test.describe('Type Management Debug', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Navigate to type management
    await page.waitForTimeout(2000);
    const adminMenu = page.locator('.ant-menu-submenu:has-text("管理")');
    if (await adminMenu.count() > 0) {
      await adminMenu.click();
      await page.waitForTimeout(1000);
    }
    await page.locator('.ant-menu-item:has-text("タイプ管理")').click();
    await page.waitForTimeout(2000);
  });

  test('should debug type management data loading', async ({ page }) => {
    console.log('🔍 Debugging type management data loading...');

    // Wait for the type management page to load
    await page.waitForSelector('.ant-table', { timeout: 15000 });

    // Check for loading states
    const loadingElement = await page.locator('.ant-spin').count();
    console.log(`Loading spinner count: ${loadingElement}`);

    // Check table structure
    const tableRows = await page.locator('.ant-table tbody tr').count();
    console.log(`Table rows found: ${tableRows}`);

    // Check for empty state
    const emptyElement = await page.locator('.ant-empty').count();
    console.log(`Empty state elements: ${emptyElement}`);

    // Check for error messages
    const errorMessages = await page.locator('.ant-message-error').count();
    console.log(`Error messages: ${errorMessages}`);

    // Get browser console logs
    page.on('console', msg => {
      if (msg.type() === 'error') {
        console.log(`❌ Browser console error: ${msg.text()}`);
      } else if (msg.text().includes('CMIS DEBUG')) {
        console.log(`🐛 CMIS Debug: ${msg.text()}`);
      }
    });

    // Wait for potential async operations
    await page.waitForTimeout(5000);

    // Check if any types are displayed
    const typeRows = await page.locator('.ant-table tbody tr').all();
    if (typeRows.length > 0) {
      console.log(`✅ Found ${typeRows.length} type rows in table`);

      for (let i = 0; i < Math.min(typeRows.length, 3); i++) {
        const row = typeRows[i];
        const cells = await row.locator('td').all();
        const rowData = [];

        for (const cell of cells) {
          const text = await cell.textContent();
          rowData.push(text?.trim() || '');
        }

        console.log(`  Type ${i + 1}: ${rowData.slice(0, 4).join(' | ')}`);
      }
    } else {
      console.log('❌ No type rows found in table');

      // Check if there's specific content in the table
      const tableContent = await page.locator('.ant-table').textContent();
      console.log(`Table content: "${tableContent}"`);
    }

    // Try manual API call to verify server response
    const apiResponse = await page.evaluate(async () => {
      try {
        const response = await fetch('/core/atom/bedroom/types', {
          headers: {
            'Authorization': 'Basic ' + btoa('admin:admin'),
            'Accept': 'application/atom+xml'
          }
        });

        if (response.ok) {
          const text = await response.text();
          const parser = new DOMParser();
          const xmlDoc = parser.parseFromString(text, 'text/xml');
          const entries = xmlDoc.getElementsByTagName('entry');

          return {
            status: response.status,
            entriesCount: entries.length,
            sample: entries.length > 0 ? entries[0].querySelector('title')?.textContent : null
          };
        } else {
          return {
            status: response.status,
            error: await response.text()
          };
        }
      } catch (error) {
        return {
          error: error.toString()
        };
      }
    });

    console.log('📡 Manual API response:', apiResponse);

    // Take screenshot for debugging
    await page.screenshot({ path: 'test-results/screenshots/type_management_debug_state_analysis.png', fullPage: true });

    // Basic assertion
    expect(page.url()).toContain('/types');
  });

  test('should check for JavaScript errors on type management page', async ({ page }) => {
    console.log('🔍 Checking for JavaScript errors...');

    const jsErrors: string[] = [];

    page.on('pageerror', error => {
      jsErrors.push(error.message);
      console.log(`❌ Page error: ${error.message}`);
    });

    page.on('requestfailed', request => {
      console.log(`❌ Failed request: ${request.url()} - ${request.failure()?.errorText}`);
    });

    // Wait for page to fully load
    await page.waitForTimeout(10000);

    // Log any JavaScript errors
    if (jsErrors.length > 0) {
      console.log(`❌ JavaScript errors found: ${jsErrors.length}`);
      jsErrors.forEach((error, index) => {
        console.log(`  ${index + 1}. ${error}`);
      });
    } else {
      console.log('✅ No JavaScript errors found');
    }

    // Take final screenshot
    await page.screenshot({ path: 'test-results/screenshots/type_management_debug_error_check.png', fullPage: true });
  });

  test.afterEach(async ({ page }) => {
    // Cleanup handled automatically
  });
});