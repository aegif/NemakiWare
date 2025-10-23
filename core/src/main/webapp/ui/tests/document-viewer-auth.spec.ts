import { test, expect } from '@playwright/test';

/**
 * Test for document viewer authentication issue
 *
 * Issue: "Content detail screen requires re-authentication and then errors"
 *
 * This test verifies that accessing document details doesn't cause
 * authentication issues or repeated login prompts.
 */

test.describe('Document Viewer Authentication', () => {
  test('should access document details without authentication errors', async ({ page, browserName }) => {
    // Enable console logging
    page.on('console', msg => {
      console.log('BROWSER:', msg.type(), msg.text());
    });

    page.on('pageerror', error => {
      console.log('PAGE ERROR:', error.message);
    });

    // Navigate to login page
    await page.goto('http://localhost:8080/core/ui/dist/index.html');
    await page.waitForTimeout(1000);

    // Login as admin
    const repositorySelect = page.locator('.ant-select-selector').first();
    await repositorySelect.click();
    await page.waitForTimeout(500);
    await page.locator('.ant-select-item-option:has-text("bedroom")').click();
    await page.waitForTimeout(500);

    await page.locator('input[placeholder*="ユーザー名"]').fill('admin');
    await page.locator('input[placeholder*="パスワード"]').fill('admin');
    await page.locator('button[type="submit"]').click();

    // Wait for navigation and UI initialization
    await page.waitForTimeout(5000);

    // Verify login successful - check for documents page elements
    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 });
    console.log('✅ Login successful - documents page loaded');

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]').first();
      if (await menuToggle.count() > 0) {
        await menuToggle.click({ timeout: 3000 });
        await page.waitForTimeout(500);
        console.log('✅ Mobile sidebar closed');
      }
    }

    // Get current URL to verify we're on documents page
    const currentUrl = page.url();
    console.log('Current URL after login:', currentUrl);
    expect(currentUrl).toContain('documents');

    // Click on first document to access detail view
    const firstDocument = page.locator('.ant-table-tbody tr:has([aria-label="file"]) button').first();

    // Wait for document to be available
    await expect(firstDocument).toBeVisible({ timeout: 10000 });

    console.log('📍 Clicking on first document to access detail view...');
    await firstDocument.click({ force: true });

    // Wait for document detail page to load
    await page.waitForTimeout(3000);

    // Check for authentication errors
    const hasLoginForm = await page.locator('input[placeholder*="ユーザー名"]').count() > 0;
    const hasAuthError = await page.locator('.ant-message-error, .ant-notification-error').count() > 0;
    const hasDocumentDetails = await page.locator('.ant-descriptions').count() > 0;

    console.log('Has login form (re-auth required):', hasLoginForm);
    console.log('Has auth error message:', hasAuthError);
    console.log('Has document details:', hasDocumentDetails);

    // Verify no re-authentication required
    expect(hasLoginForm).toBe(false);

    // Verify no authentication errors
    if (hasAuthError) {
      const errorText = await page.locator('.ant-message-error, .ant-notification-error').first().textContent();
      console.log('❌ Authentication error found:', errorText);
      expect(hasAuthError).toBe(false);
    }

    // Verify document details loaded successfully
    if (hasDocumentDetails) {
      console.log('✅ Document details loaded successfully');

      // Verify key document information is displayed
      const hasObjectId = await page.locator('text=ID').count() > 0;
      const hasProperties = await page.locator('.ant-tabs-tab:has-text("プロパティ")').count() > 0;

      expect(hasObjectId).toBe(true);
      expect(hasProperties).toBe(true);
    } else {
      console.log('❌ Document details failed to load');
      expect(hasDocumentDetails).toBe(true);
    }

    // Verify back button works
    const backButton = page.locator('button:has-text("戻る")');
    if (await backButton.count() > 0) {
      await backButton.click({ force: true });
      await page.waitForTimeout(2000);

      // Should return to documents list
      const hasDocumentsList = await page.locator('.ant-table').count() > 0;
      expect(hasDocumentsList).toBe(true);
      console.log('✅ Back button works - returned to documents list');
    }
  });

  test('should handle multiple document detail accesses without session issues', async ({ page, browserName }) => {
    // Login
    await page.goto('http://localhost:8080/core/ui/dist/index.html');
    await page.waitForTimeout(1000);

    const repositorySelect = page.locator('.ant-select-selector').first();
    await repositorySelect.click();
    await page.waitForTimeout(500);
    await page.locator('.ant-select-item-option:has-text("bedroom")').click();
    await page.waitForTimeout(500);

    await page.locator('input[placeholder*="ユーザー名"]').fill('admin');
    await page.locator('input[placeholder*="パスワード"]').fill('admin');
    await page.locator('button[type="submit"]').click();
    await page.waitForTimeout(5000);

    await expect(page.locator('.ant-table')).toBeVisible({ timeout: 15000 });

    // Mobile sidebar handling
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobile) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]').first();
      if (await menuToggle.count() > 0) {
        await menuToggle.click({ timeout: 3000 });
        await page.waitForTimeout(500);
      }
    }

    // Access multiple documents to test session stability
    const documentButtons = page.locator('.ant-table-tbody tr:has([aria-label="file"]) button');
    const documentCount = await documentButtons.count();
    console.log(`Found ${documentCount} documents`);

    if (documentCount > 0) {
      const accessCount = Math.min(3, documentCount);

      for (let i = 0; i < accessCount; i++) {
        console.log(`\n📍 Accessing document ${i + 1}/${accessCount}...`);

        // Click document
        await documentButtons.nth(i).click({ force: true });
        await page.waitForTimeout(3000);
        
        // Wait for navigation to complete
        await page.waitForURL(/\/documents\/[a-f0-9-]+/, { timeout: 10000 });

        // Check for errors
        const hasLoginForm = await page.locator('input[placeholder*="ユーザー名"]').count() > 0;
        const hasDocumentDetails = await page.locator('.ant-descriptions').count() > 0;
        const hasErrorMessage = await page.locator('.ant-message-error, .ant-notification-error').count() > 0;

        // DEBUGGING: Log current state
        const currentUrl = page.url();
        console.log(`  Current URL: ${currentUrl}`);
        console.log(`  Has login form: ${hasLoginForm}`);
        console.log(`  Has document details: ${hasDocumentDetails}`);
        console.log(`  Has error message: ${hasErrorMessage}`);

        if (hasErrorMessage) {
          const errorText = await page.locator('.ant-message-error, .ant-notification-error').first().textContent();
          console.log(`  Error message: "${errorText}"`);
        }

        if (hasLoginForm) {
          console.log(`❌ Document ${i + 1}: Re-authentication required`);
          expect(hasLoginForm).toBe(false);
        }

        if (!hasDocumentDetails) {
          console.log(`❌ Document ${i + 1}: Failed to load details`);
          console.log(`POSSIBLE ISSUE: Document detail page not rendering properly after multiple accesses`);

          // Check if we're stuck on a loading state or if there's a UI error
          const hasSpinner = await page.locator('.ant-spin').count() > 0;
          const hasDrawer = await page.locator('.ant-drawer').count() > 0;
          console.log(`  Has spinner (loading): ${hasSpinner}`);
          console.log(`  Has drawer: ${hasDrawer}`);

          expect(hasDocumentDetails).toBe(true);
        } else {
          console.log(`✅ Document ${i + 1}: Loaded successfully`);
        }

        // Return to list
        const backButton = page.locator('button:has-text("戻る")');
        if (await backButton.count() > 0) {
          await backButton.click({ force: true });
          await page.waitForTimeout(1000);
        }
      }

      console.log(`\n✅ Successfully accessed ${accessCount} documents without authentication issues`);
    }
  });
});
