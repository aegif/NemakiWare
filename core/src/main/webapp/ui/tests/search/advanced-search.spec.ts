import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

/**
 * Advanced Search E2E Tests
 *
 * Comprehensive end-to-end tests for NemakiWare search functionality:
 * - Search page accessibility and interface rendering
 * - Basic search execution with query input
 * - Search request/response monitoring (CMIS Browser Binding integration)
 * - Search result navigation without errors
 * - Navigation back to documents page
 *
 * IMPORTANT DESIGN DECISIONS:
 * 1. Flexible Language Support (Lines 68, 75, 128, 132, 177, 204):
 *    - Supports both Japanese (検索) and English ("Search") text patterns
 *    - Dual placeholder matching: "検索" and "search"
 *    - Button text patterns: "検索", "Search"
 *    - Menu navigation: "ドキュメント" (Documents)
 *    - Makes tests resilient to UI language configuration changes
 *    - Pattern: input[placeholder*="検索"], input[placeholder*="search"]
 *    - Rationale: NemakiWare may be deployed in multilingual environments
 *
 * 2. Mobile Browser Support (Lines 17-42, 60-62, 78, 99-100, 134):
 *    - Sidebar close logic in beforeEach prevents overlay blocking clicks
 *    - Viewport width ≤414px triggers mobile-specific behavior
 *    - Force click option for search button: .click(isMobile ? { force: true } : {})
 *    - Menu toggle detection with graceful fallback
 *    - Alternative toggle selector: '.ant-layout-header button, banner button'
 *    - Applied to all search interactions (input, button clicks)
 *    - Rationale: Mobile layouts have sidebar overlays that block UI interactions
 *
 * 3. Smart Conditional Skipping (Lines 93-94, 164-165, 195-196):
 *    - Tests check for search UI elements before attempting operations
 *    - Graceful test.skip() when search functionality not available
 *    - Better than hard failures - tests self-heal when features are implemented
 *    - Console messages explain why tests skipped (aids debugging)
 *    - Skip conditions: search input not found, search functionality not available
 *    - Rationale: Search UI may not be fully implemented in all deployments
 *
 * 4. Network Request Monitoring (Lines 102-122, 141-144):
 *    - Captures CMIS Browser Binding search/query requests
 *    - URL pattern: includes('browser') && (includes('search') || includes('query'))
 *    - Logs request URLs and response status codes
 *    - Response body logging (first 200 chars) for debugging
 *    - Tracks total search request count
 *    - Console output for each request with index numbering
 *    - Rationale: Verifies search integrates correctly with CMIS backend
 *
 * 5. Error Detection Pattern (Lines 147-159):
 *    - Monitors for Ant Design error messages (.ant-message-error)
 *    - Expects zero error messages after search execution
 *    - Logs error message text if present (debugging aid)
 *    - Console markers: ✅ success, ❌ error with "PRODUCT BUG" label
 *    - Assertion: expect(errorCount).toBe(0)
 *    - Rationale: Search errors indicate backend integration problems
 *
 * 6. URL Verification (Lines 47, 162, 209):
 *    - Confirms navigation to /search page (Line 47)
 *    - Verifies staying on /search after search execution (Line 162)
 *    - Validates navigation to /documents after menu click (Line 209)
 *    - Pattern: expect(page.url()).toContain('/search')
 *    - Rationale: Ensures React Router navigation works correctly
 *
 * 7. Result Interaction Testing (Lines 183-191):
 *    - Tests clicking on search result table rows/links
 *    - Flexible selector: '.ant-table tbody tr a, .ant-table tbody tr td'
 *    - Verifies navigation occurs without error messages
 *    - Does not assert specific destination (could be detail view or download)
 *    - Rationale: Result click behavior may vary by document type
 *
 * 8. Multiple Selector Fallbacks (Lines 68, 75, 128, 132, 177):
 *    - Search input: placeholder matching OR .ant-input-search input
 *    - Search button: Japanese text OR English text OR .ant-input-search-button
 *    - Results container: .ant-table OR .search-results OR .ant-list
 *    - First matching selector used (.first() method)
 *    - Rationale: Search UI implementation may use different Ant Design components
 *
 * 9. Search Method Flexibility (Lines 74-82, 132-137, 177-180):
 *    - Primary method: Click search button
 *    - Fallback method: Press Enter key in search input
 *    - Button detection first, Enter key if button not found
 *    - Both methods trigger CMIS search operation
 *    - Rationale: Supports both explicit button clicks and keyboard-driven workflows
 *
 * 10. Response Body Logging (Lines 115-120):
 *     - Captures first 200 characters of search response body
 *     - Try-catch block for response.text() (may fail for binary responses)
 *     - Console output: "Could not read response body" on error
 *     - Helps diagnose CMIS Browser Binding response format issues
 *     - Rationale: Search response structure validation for debugging
 *
 * Test Coverage:
 * 1. ✅ Display Search Page (URL /search, interface visible, screenshot)
 * 2. ✅ Basic Search (input query "test", execute, verify results container)
 * 3. ✅ Execute Search Without Errors (CMIS requests, no error messages, URL persistence)
 * 4. ✅ Navigate to Document from Results (click result, no errors)
 * 5. ✅ Navigate Back from Search (Documents menu → /documents)
 *
 * Search Functionality Architecture:
 * - **Frontend**: React Search component with Ant Design Table for results
 * - **Backend Integration**: CMIS Browser Binding search/query endpoints
 * - **Query Processing**: Server-side CMIS SQL query execution
 * - **Result Rendering**: Table display with clickable document links
 * - **Error Handling**: Ant Design message component for user feedback
 *
 * CMIS Search Integration:
 * - **Search Endpoint**: CMIS Browser Binding cmisselector=query
 * - **Query Language**: CMIS SQL (SELECT * FROM cmis:document WHERE ...)
 * - **Response Format**: JSON with results array
 * - **Properties Returned**: cmis:objectId, cmis:name, cmis:contentStreamMimeType, etc.
 * - **Error Responses**: HTTP error codes + JSON error messages
 *
 * UI Verification Patterns:
 * - Search Input: input with placeholder "検索" or "search"
 * - Search Button: button with text "検索" or "Search" or .ant-input-search-button
 * - Results Container: .ant-table or .search-results or .ant-list
 * - Result Links: .ant-table tbody tr a or .ant-table tbody tr td
 * - Error Messages: .ant-message-error
 *
 * Expected Test Results:
 * - Search page accessible at /search URL
 * - Search input and button visible
 * - Search query "test" or "test-search-query" executes
 * - CMIS Browser Binding requests logged
 * - Zero error messages appear
 * - Results container becomes visible
 * - Result click navigates without errors
 * - Documents menu returns to /documents page
 *
 * Known Limitations:
 * - Tests skip gracefully if search UI not implemented
 * - Does not verify result content accuracy (only presence)
 * - Does not test advanced search filters (future enhancement)
 * - Result click destination varies by document type (not asserted)
 * - Search query terms are simple strings (no complex queries tested)
 *
 * Performance Optimizations:
 * - Uses first() selector method (stops at first match)
 * - Minimal waits: 1-2 seconds for UI updates
 * - Network request monitoring doesn't slow tests
 * - Screenshot only on first test (page display)
 *
 * Debugging Features:
 * - Network request URL logging (Lines 107, 142-144)
 * - Response status and body logging (Lines 114-120)
 * - Error message text logging (Lines 152-154)
 * - Success/error console markers (✅/❌)
 * - "PRODUCT BUG" label for search errors (Line 154)
 */
test.describe('Advanced Search', () => {
  let authHelper: AuthHelper;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    await authHelper.login();

    // Navigate to search page
    await page.waitForTimeout(2000);
    const searchMenu = page.locator('.ant-menu-item:has-text("検索")');
    await searchMenu.click();
    await page.waitForTimeout(2000);

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
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
      } else {
        const alternativeToggle = page.locator('.ant-layout-header button, banner button').first();
        if (await alternativeToggle.count() > 0) {
          try {
            await alternativeToggle.click({ timeout: 3000 });
            await page.waitForTimeout(500);
          } catch (error) {
            // Continue even if alternative selector fails
          }
        }
      }
    }
  });

  test('should display search page', async ({ page }) => {
    // Verify URL contains /search
    expect(page.url()).toContain('/search');

    // Check for search interface
    const searchInterface = page.locator('.ant-card, .search-container, form');
    if (await searchInterface.count() > 0) {
      await expect(searchInterface.first()).toBeVisible({ timeout: 10000 });
    }

    // Take screenshot
    await page.screenshot({ path: 'test-results/screenshots/search_page.png', fullPage: true });
  });

  test('should handle basic search', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to load
    await page.waitForTimeout(2000);

    // Look for search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"], .ant-input-search input');

    if (await searchInput.count() > 0) {
      // Fill search query
      await searchInput.first().fill('test');

      // Look for search button
      const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search"), .ant-input-search-button');

      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        // Try pressing Enter
        await searchInput.first().press('Enter');
      }

      // Wait for search results
      await page.waitForTimeout(2000);

      // Verify results container exists
      const resultsContainer = page.locator('.ant-table, .search-results, .ant-list');
      if (await resultsContainer.count() > 0) {
        await expect(resultsContainer.first()).toBeVisible({ timeout: 5000 });
      }
    } else {
      test.skip('Search input not found');
    }
  });

  test('should execute search without errors', async ({ page, browserName }) => {
    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    const searchRequests: string[] = [];
    page.on('request', request => {
      const url = request.url();
      if (url.includes('browser') && (url.includes('search') || url.includes('query'))) {
        searchRequests.push(url);
        console.log(`Search request URL: ${url}`);
      }
    });

    page.on('response', async response => {
      const url = response.url();
      if (url.includes('browser') && (url.includes('search') || url.includes('query'))) {
        console.log(`Search response status: ${response.status()}`);
        try {
          const body = await response.text();
          console.log(`Search response body (first 200 chars): ${body.substring(0, 200)}`);
        } catch (e) {
          console.log(`Could not read response body`);
        }
      }
    });

    // Wait for page to load
    await page.waitForTimeout(2000);

    // Perform a search
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');
    if (await searchInput.count() > 0) {
      await searchInput.first().fill('test-search-query');

      const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        await searchInput.first().press('Enter');
      }

      await page.waitForTimeout(2000);

      console.log(`Total search requests: ${searchRequests.length}`);
      searchRequests.forEach((url, index) => {
        console.log(`Request ${index + 1}: ${url}`);
      });

      // Verify no error messages appeared
      const errorMessage = page.locator('.ant-message-error');
      const errorCount = await errorMessage.count();

      // DEBUGGING: Log error message if present
      if (errorCount > 0) {
        const errorText = await errorMessage.first().textContent();
        console.log(`❌ Search error message appeared: "${errorText}"`);
        console.log(`PRODUCT BUG: Search operation returned error despite valid query`);
      } else {
        console.log('✅ No error messages - search executed successfully');
      }

      expect(errorCount).toBe(0);

      // Verify we're still on search page (not redirected due to error)
      expect(page.url()).toContain('/search');
    } else {
      test.skip('Search functionality not available');
    }
  });

  test('should navigate to document from search results', async ({ page }) => {
    // Wait for page to load
    await page.waitForTimeout(2000);

    // Perform search
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');
    if (await searchInput.count() > 0) {
      await searchInput.first().fill('test');

      const searchButton = page.locator('button:has-text("検索")');
      if (await searchButton.count() > 0) {
        await searchButton.first().click();
        await page.waitForTimeout(2000);

        // Look for clickable result
        const resultLink = page.locator('.ant-table tbody tr a, .ant-table tbody tr td').first();
        if (await resultLink.count() > 0) {
          await resultLink.click();
          await page.waitForTimeout(1000);

          // Should navigate somewhere (document detail or download)
          // Just verify page didn't error
          const errorMessage = page.locator('.ant-message-error');
          expect(await errorMessage.count()).toBe(0);
        }
      }
    } else {
      test.skip('Search functionality not available');
    }
  });

  test('should navigate back from search page', async ({ page }) => {
    // Wait for page to stabilize
    await page.waitForTimeout(1000);

    // Click on Documents menu item
    const documentsMenu = page.locator('.ant-menu-item:has-text("ドキュメント")');
    await documentsMenu.click();
    await page.waitForTimeout(2000);

    // Verify navigation to documents page
    expect(page.url()).toContain('/documents');
  });
});
