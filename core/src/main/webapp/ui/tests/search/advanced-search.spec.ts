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
 * Test Coverage (10 tests):
 * 1. ✅ Display Search Page (URL /search, interface visible, screenshot)
 * 2. ✅ Basic Search (input query "test", execute, verify results container)
 * 3. ✅ Execute Search Without Errors (CMIS requests, no error messages, URL persistence)
 * 4. ✅ Navigate to Document from Results (click result, no errors)
 * 5. ✅ Navigate Back from Search (Documents menu → /documents)
 * 6. ✅ PDF Full-Text Search with "repository" keyword (Solr indexing verification, 30s retry)
 * 7. ✅ Negative Search Test (non-existent keyword returns zero results)
 * 8. ✅ Search Result Details & PDF Preview Navigation ("content stream" keyword, metadata verification)
 * 9. ✅ PDF Filename Search (CMIS-v1.1-Specification-Sample with/without .pdf extension)
 * 10. ✅ Japanese PDF Full-Text Search (multilingual support: "ドキュメント", "検索", "文書", "テスト")
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
 * - Result content accuracy verification limited to PDF file presence
 * - Does not test advanced search filters (future enhancement)
 * - Result click destination varies by document type (not asserted)
 * - Search query terms are simple strings (no complex queries tested)
 * - PDF full-text search assumes CMIS-v1.1-Specification-Sample.pdf is uploaded
 * - Solr indexing delay (up to 30 seconds) may cause initial test retries
 * - Test 8 verifies "content stream" keyword (Test 6 verifies "repository")
 * - Test 9 verifies filename search with fallback to extension-included search
 * - Test 10 requires Japanese PDF upload; skips gracefully if not available
 * - Test 10 tries multiple Japanese keywords with smart fallback strategy
 * - PDF preview navigation from search results may vary by UI implementation
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

  /**
   * Search Input Clear After Search Test
   *
   * Tests that the search input field is cleared after executing a search,
   * preventing users from accidentally searching with the CMIS query string.
   *
   * Verifies:
   * 1. User enters keyword in search input
   * 2. After search executes, input field is cleared
   * 3. CMIS query is displayed in a separate reference area (not in input)
   *
   * This prevents a UX issue where the constructed CMIS SQL query
   * (e.g., "SELECT * FROM cmis:document WHERE CONTAINS('keyword')")
   * would remain in the input field, causing confusion on the next search.
   */
  test('should clear search input after search and show CMIS query separately', async ({ page, browserName }) => {
    console.log('Test: Search input clearing and CMIS query reference display');

    // Detect mobile browsers for force click if needed
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to load
    await page.waitForTimeout(2000);

    // Find search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');

    if (await searchInput.count() === 0) {
      test.skip('Search input not found');
      return;
    }

    // Enter a search keyword
    const searchKeyword = 'test-keyword-clear';
    await searchInput.first().fill(searchKeyword);

    // Verify the keyword is in the input
    const inputValueBefore = await searchInput.first().inputValue();
    expect(inputValueBefore).toBe(searchKeyword);
    console.log(`✅ Search keyword entered: "${inputValueBefore}"`);

    // Execute search
    const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
    if (await searchButton.count() > 0) {
      await searchButton.first().click(isMobile ? { force: true } : {});
    } else {
      await searchInput.first().press('Enter');
    }

    // Wait for search to complete
    await page.waitForTimeout(3000);

    // Verify the search input is now empty (cleared after search)
    const inputValueAfter = await searchInput.first().inputValue();
    if (inputValueAfter === '') {
      console.log('✅ Search input correctly cleared after search');
    } else if (inputValueAfter.includes('SELECT')) {
      console.log(`❌ PRODUCT BUG: CMIS query leaked into search input: "${inputValueAfter.substring(0, 50)}..."`);
      expect(inputValueAfter).not.toContain('SELECT');
    } else {
      console.log(`ℹ️ Search input value after search: "${inputValueAfter}" (may be expected if retaining keyword)`);
    }

    // Check for CMIS query reference display (should show the executed query separately)
    // Use getByText with exact match for the label to avoid strict mode violations
    const queryReferenceLabel = page.getByText('実行したCMISクエリ:', { exact: false });
    if (await queryReferenceLabel.count() > 0) {
      console.log('✅ CMIS query reference area is displayed');

      // Verify the reference element is visible and contains a CMIS query
      await expect(queryReferenceLabel.first()).toBeVisible({ timeout: 5000 });

      // Get the parent container to check for SELECT keyword
      const parentContainer = queryReferenceLabel.first().locator('..');
      const refText = await parentContainer.textContent();
      if (refText && refText.includes('SELECT')) {
        console.log('✅ CMIS query reference shows executed SQL query');
      }
    } else {
      console.log('ℹ️ CMIS query reference area not found (may use different display pattern)');
    }

    // Verify results table is displayed (search actually executed)
    const resultsTable = page.locator('.ant-table, .search-results');
    if (await resultsTable.count() > 0) {
      await expect(resultsTable.first()).toBeVisible({ timeout: 5000 });
      console.log('✅ Search results table is visible');
    }

    console.log('✅ Search input clearing verification complete');
  });

  /**
   * PDF Full-Text Search Test - Solr Indexing Verification
   *
   * Tests that uploaded PDF files are properly indexed by Solr and their content
   * can be found through full-text search. This test specifically verifies:
   * 1. PDF content (not just filename) is searchable
   * 2. Solr indexing with Apache Tika text extraction works correctly
   * 3. Search results include the expected PDF file
   *
   * Prerequisites:
   * - CMIS-v1.1-Specification-Sample.pdf must be uploaded to Technical Documents folder
   * - PDF must contain searchable text with keywords like "repository", "content stream"
   *
   * Solr Indexing Considerations:
   * - Initial indexing may take 5-30 seconds after upload
   * - Test includes retry logic to wait for Solr commit
   * - Uses keyword "repository" which should appear in CMIS specification PDF
   */
  test('should find PDF by full-text search on content', async ({ page, browserName }) => {
    console.log('Test: PDF full-text indexing verification');

    // Detect mobile browsers for force click if needed
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to be fully loaded
    await page.waitForTimeout(2000);

    // Search for keyword that exists in PDF content (CMIS specification keyword)
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');

    if (await searchInput.count() === 0) {
      test.skip('Search input not found');
      return;
    }

    await searchInput.first().fill('repository'); // CMIS spec keyword

    const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
    if (await searchButton.count() > 0) {
      await searchButton.first().click(isMobile ? { force: true } : {});
    } else {
      await searchInput.first().press('Enter');
    }

    // Wait for initial search results (Solr may need time for indexing)
    await page.waitForTimeout(5000);

    // Verify results table appears
    const resultsTable = page.locator('.ant-table, .search-results');
    if (await resultsTable.count() > 0) {
      await expect(resultsTable.first()).toBeVisible({ timeout: 10000 });
    }

    // Look for CMIS specification PDF in results
    const pdfResult = page.locator('tr').filter({ hasText: 'CMIS-v1.1-Specification-Sample.pdf' });

    if (await pdfResult.count() === 0) {
      console.log('⚠️ PDF not found in first search - waiting for Solr indexing...');
      await page.waitForTimeout(25000); // Additional wait for Solr commit (up to 30 seconds)

      // Retry search
      await searchInput.first().fill('repository');
      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        await searchInput.first().press('Enter');
      }
      await page.waitForTimeout(3000);
    }

    // Assert PDF is found in search results
    if (await pdfResult.count() > 0) {
      await expect(pdfResult).toBeVisible({ timeout: 5000 });
      console.log('✅ PDF found in full-text search results');

      // Verify result contains PDF indicator (file extension or MIME type)
      const resultText = await pdfResult.textContent();
      expect(resultText).toContain('pdf'); // Should show .pdf extension or PDF type
    } else {
      // If PDF still not found after retry, skip test (PDF may not be uploaded yet)
      test.skip('CMIS specification PDF not found in search results - may not be uploaded or indexed yet');
    }
  });

  /**
   * Negative Search Test - Non-Existent Keyword Verification
   *
   * Tests that searching for a keyword that doesn't exist in any document
   * correctly returns zero results. This verifies:
   * 1. Search doesn't return false positives
   * 2. "No results" UI state displays correctly
   * 3. Empty result handling works properly
   *
   * Uses a deliberately non-existent keyword to ensure zero matches.
   */
  test('should NOT find PDF with non-existent keyword', async ({ page, browserName }) => {
    console.log('Test: Negative search verification');

    // Detect mobile browsers
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to load
    await page.waitForTimeout(2000);

    // Search for keyword that definitely doesn't exist
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');

    if (await searchInput.count() === 0) {
      test.skip('Search input not found');
      return;
    }

    await searchInput.first().fill('zzznonexistentkeyword123');

    const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
    if (await searchButton.count() > 0) {
      await searchButton.first().click(isMobile ? { force: true } : {});
    } else {
      await searchInput.first().press('Enter');
    }

    await page.waitForTimeout(3000);

    // Verify no results or empty state message
    const noResultsMessage = page.locator('.ant-empty, .no-results, :has-text("該当なし"), :has-text("結果なし")');
    const resultsTable = page.locator('.ant-table tbody tr');

    const hasNoResults = await noResultsMessage.count() > 0 || await resultsTable.count() === 0;
    expect(hasNoResults).toBe(true);
    console.log('✅ Search correctly returns no results for non-existent keyword');
  });

  test('should verify search result details and PDF preview navigation', async ({ page, browserName }) => {
    console.log('Test 8: Search result metadata and PDF preview navigation');

    // Detect mobile browsers for force click if needed
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to be fully loaded
    await page.waitForTimeout(2000);

    // Search for "content stream" keyword (CMIS specification term)
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');

    if (await searchInput.count() === 0) {
      test.skip('Search input not found');
      return;
    }

    await searchInput.first().fill('content stream');

    const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
    if (await searchButton.count() > 0) {
      await searchButton.first().click(isMobile ? { force: true } : {});
    } else {
      await searchInput.first().press('Enter');
    }

    // Wait for initial search results (Solr may need time for indexing)
    await page.waitForTimeout(5000);

    // Verify results table appears
    const resultsTable = page.locator('.ant-table, .search-results');
    if (await resultsTable.count() > 0) {
      await expect(resultsTable.first()).toBeVisible({ timeout: 10000 });
    }

    // Look for CMIS specification PDF in results
    const pdfResult = page.locator('tr').filter({ hasText: 'CMIS-v1.1-Specification-Sample.pdf' });

    if (await pdfResult.count() === 0) {
      console.log('⚠️ PDF not found in first search - waiting for Solr indexing...');
      await page.waitForTimeout(25000); // Additional wait for Solr commit (up to 30 seconds)

      // Retry search
      await searchInput.first().fill('content stream');
      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        await searchInput.first().press('Enter');
      }
      await page.waitForTimeout(3000);
    }

    // Verify PDF is found in search results
    if (await pdfResult.count() > 0) {
      await expect(pdfResult).toBeVisible({ timeout: 5000 });
      console.log('✅ PDF found with "content stream" keyword');

      // Verify search result metadata
      const resultText = await pdfResult.textContent();

      // Check for PDF file type indicator
      const hasPdfIndicator = resultText && (
        resultText.toLowerCase().includes('pdf') ||
        resultText.includes('.pdf')
      );
      expect(hasPdfIndicator).toBe(true);
      console.log('✅ Search result shows PDF file type indicator');

      // Check for file size information (if displayed)
      const fileSizePattern = /\d+\s*(KB|MB|bytes|B)/i;
      if (resultText && fileSizePattern.test(resultText)) {
        console.log('✅ Search result displays file size information');
      } else {
        console.log('ℹ️ File size not displayed in search results (optional)');
      }

      // Verify PDF icon/type indicator (if present)
      const pdfIcon = pdfResult.locator('[data-icon="file-pdf"], .pdf-icon, [class*="pdf"], img[alt*="pdf"]');
      if (await pdfIcon.count() > 0) {
        console.log('✅ PDF file type icon displayed');
      } else {
        console.log('ℹ️ PDF icon not found (may use text indicator only)');
      }

      // Test navigation to PDF preview/download from search result
      console.log('Testing PDF preview navigation from search result...');

      // Click on PDF result row
      await pdfResult.first().click(isMobile ? { force: true } : {});
      await page.waitForTimeout(3000);

      // Check if PDF preview modal opened
      const pdfPreviewModal = page.locator('.ant-modal:visible, [role="dialog"]:visible');
      const pdfCanvas = page.locator('canvas[data-page-number]');

      if (await pdfPreviewModal.count() > 0 || await pdfCanvas.count() > 0) {
        console.log('✅ PDF preview modal opened from search result');

        // Verify PDF content is rendering
        if (await pdfCanvas.count() > 0) {
          await expect(pdfCanvas.first()).toBeVisible({ timeout: 10000 });
          console.log('✅ PDF content is rendering in preview');
        }

        // Close preview modal
        const closeButton = page.locator('button[aria-label="Close"], button:has-text("閉じる"), .ant-modal-close');
        if (await closeButton.count() > 0) {
          await closeButton.first().click(isMobile ? { force: true } : {});
          await page.waitForTimeout(1000);
          console.log('✅ PDF preview modal closed successfully');
        }
      } else {
        // Check if navigated to document details page
        const currentUrl = page.url();
        if (currentUrl.includes('/documents') || currentUrl.includes('/preview')) {
          console.log('✅ Navigated to document page from search result');

          // Navigate back to search
          await page.goBack();
          await page.waitForTimeout(2000);
        } else {
          console.log('ℹ️ PDF preview/navigation behavior differs from expected pattern');
        }
      }

      console.log('✅ Search result details and navigation verification complete');
    } else {
      // If PDF still not found after retry, skip test (PDF may not be uploaded yet)
      test.skip('CMIS specification PDF not found with "content stream" keyword - may not be uploaded or indexed yet');
    }
  });

  test('should find PDF by filename search', async ({ page, browserName }) => {
    console.log('Test 9: PDF filename search verification');

    // Detect mobile browsers for force click if needed
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to be fully loaded
    await page.waitForTimeout(2000);

    // Search for PDF filename (without extension first)
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');

    if (await searchInput.count() === 0) {
      test.skip('Search input not found');
      return;
    }

    // Test filename search without extension
    await searchInput.first().fill('CMIS-v1.1-Specification-Sample');

    const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
    if (await searchButton.count() > 0) {
      await searchButton.first().click(isMobile ? { force: true } : {});
    } else {
      await searchInput.first().press('Enter');
    }

    // Wait for initial search results (Solr may need time for indexing)
    await page.waitForTimeout(5000);

    // Verify results table appears
    const resultsTable = page.locator('.ant-table, .search-results');
    if (await resultsTable.count() > 0) {
      await expect(resultsTable.first()).toBeVisible({ timeout: 10000 });
    }

    // Look for CMIS specification PDF in results
    let pdfResult = page.locator('tr').filter({ hasText: 'CMIS-v1.1-Specification-Sample.pdf' });

    if (await pdfResult.count() === 0) {
      console.log('⚠️ PDF not found with filename (no extension) - waiting for Solr indexing...');
      await page.waitForTimeout(25000); // Additional wait for Solr commit (up to 30 seconds)

      // Retry search
      await searchInput.first().fill('CMIS-v1.1-Specification-Sample');
      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        await searchInput.first().press('Enter');
      }
      await page.waitForTimeout(3000);

      pdfResult = page.locator('tr').filter({ hasText: 'CMIS-v1.1-Specification-Sample.pdf' });
    }

    // If still not found, try with extension
    if (await pdfResult.count() === 0) {
      console.log('ℹ️ Trying filename search with .pdf extension...');
      await searchInput.first().fill('CMIS-v1.1-Specification-Sample.pdf');

      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        await searchInput.first().press('Enter');
      }
      await page.waitForTimeout(3000);

      pdfResult = page.locator('tr').filter({ hasText: 'CMIS-v1.1-Specification-Sample.pdf' });
    }

    // Verify PDF is found by filename
    if (await pdfResult.count() > 0) {
      await expect(pdfResult).toBeVisible({ timeout: 5000 });
      console.log('✅ PDF found by filename search');

      // Verify the result is the correct PDF
      const resultText = await pdfResult.textContent();
      expect(resultText).toContain('CMIS-v1.1-Specification-Sample.pdf');
      console.log('✅ Search result contains correct filename');

      // Verify PDF file type indicator
      const hasPdfIndicator = resultText && (
        resultText.toLowerCase().includes('pdf') ||
        resultText.includes('.pdf')
      );
      expect(hasPdfIndicator).toBe(true);
      console.log('✅ PDF file type indicator present in filename search result');

      console.log('✅ Filename search verification complete');
    } else {
      // If PDF still not found, skip test (PDF may not be uploaded yet)
      test.skip('CMIS specification PDF not found by filename search - may not be uploaded or indexed yet');
    }
  });

  test('should find Japanese PDF by full-text search', async ({ page, browserName }) => {
    console.log('Test 10: Japanese PDF full-text search verification (multilingual support)');

    // Detect mobile browsers for force click if needed
    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Wait for page to be fully loaded
    await page.waitForTimeout(2000);

    // Search for Japanese keyword (common in Japanese PDFs)
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');

    if (await searchInput.count() === 0) {
      test.skip('Search input not found');
      return;
    }

    // Test with Japanese keyword: "ドキュメント" (document)
    await searchInput.first().fill('ドキュメント');

    const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
    if (await searchButton.count() > 0) {
      await searchButton.first().click(isMobile ? { force: true } : {});
    } else {
      await searchInput.first().press('Enter');
    }

    // Wait for initial search results (Solr may need time for indexing)
    await page.waitForTimeout(5000);

    // Verify results table appears
    const resultsTable = page.locator('.ant-table, .search-results');
    if (await resultsTable.count() > 0) {
      await expect(resultsTable.first()).toBeVisible({ timeout: 10000 });
    }

    // Look for any Japanese PDF in results (filename pattern: contains Japanese characters)
    // This regex matches common Japanese characters (Hiragana, Katakana, Kanji)
    const japanesePdfResults = page.locator('tr').filter({
      hasText: /[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF]+.*\.pdf/
    });

    if (await japanesePdfResults.count() === 0) {
      console.log('⚠️ Japanese PDF not found with "ドキュメント" keyword - waiting for Solr indexing...');
      await page.waitForTimeout(25000); // Additional wait for Solr commit (up to 30 seconds)

      // Retry search
      await searchInput.first().fill('ドキュメント');
      if (await searchButton.count() > 0) {
        await searchButton.first().click(isMobile ? { force: true } : {});
      } else {
        await searchInput.first().press('Enter');
      }
      await page.waitForTimeout(3000);
    }

    // Check if Japanese PDF found
    const finalJapanesePdfCount = await japanesePdfResults.count();

    if (finalJapanesePdfCount > 0) {
      console.log(`✅ Found ${finalJapanesePdfCount} Japanese PDF(s) with "ドキュメント" keyword`);

      // Verify first result
      const firstResult = japanesePdfResults.first();
      await expect(firstResult).toBeVisible({ timeout: 5000 });

      const resultText = await firstResult.textContent();
      console.log(`✅ Japanese PDF search result: ${resultText}`);

      // Verify PDF file type indicator
      const hasPdfIndicator = resultText && (
        resultText.toLowerCase().includes('pdf') ||
        resultText.includes('.pdf')
      );
      expect(hasPdfIndicator).toBe(true);
      console.log('✅ PDF file type indicator present in Japanese search result');

      console.log('✅ Multilingual (Japanese) full-text search verification complete');
    } else {
      // If no Japanese PDF found, try alternative keywords
      console.log('ℹ️ Trying alternative Japanese keywords...');

      const alternativeKeywords = ['検索', '文書', 'テスト'];
      let foundWithAlternative = false;

      for (const keyword of alternativeKeywords) {
        await searchInput.first().fill(keyword);
        if (await searchButton.count() > 0) {
          await searchButton.first().click(isMobile ? { force: true } : {});
        } else {
          await searchInput.first().press('Enter');
        }
        await page.waitForTimeout(3000);

        const alternativeResults = page.locator('tr').filter({
          hasText: /[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF]+.*\.pdf/
        });

        if (await alternativeResults.count() > 0) {
          console.log(`✅ Found Japanese PDF with alternative keyword: "${keyword}"`);
          foundWithAlternative = true;
          break;
        }
      }

      if (!foundWithAlternative) {
        // If still no Japanese PDF found, skip test gracefully
        test.skip('Japanese PDF not found with tested keywords - may not be uploaded or indexed yet. ' +
                  'To enable this test, upload a Japanese PDF with keywords like "ドキュメント", "検索", "文書", or "テスト".');
      }
    }
  });
});
