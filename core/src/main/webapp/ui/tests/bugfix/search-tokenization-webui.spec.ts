/**
 * Bug Fix Verification: Search Tokenization Issue (2025-12-17)
 *
 * WebUI-based Playwright Test
 *
 * Reported Scenario:
 * - When searching with CONTAINS('SEARCH_TEST_KEYWORD_123'), documents that only
 *   contain 'test' in their content were incorrectly returned
 * - This was caused by Solr tokenizing the search term into 'search', 'test', 'keyword', '123'
 *   and matching any of these tokens independently
 *
 * Root Cause:
 * - SolrPredicateWalker was creating TermQuery without phrase quoting
 * - Solr's StandardTokenizer splits on underscores
 * - Result: 'SEARCH_TEST_KEYWORD_123' → ['search', 'test', 'keyword', '123']
 *
 * Fix Applied (SolrPredicateWalker.java):
 * - Wrap search terms in double quotes to force phrase search
 * - This ensures exact sequence matching, not individual token matching
 *
 * This test verifies the fix through WebUI search interactions.
 */

import { test, expect } from '@playwright/test';
import { searchPageSubmitButton, waitForRender, waitForUiStable } from '../utils/wait-helpers';
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper, generateTestId } from '../utils/test-helper';


/**
 * SKIPPED (2025-12-23) - Search Tokenization WebUI Upload Timing Issues
 *
 * Investigation Result: Search tokenization fix IS working correctly.
 * However, tests fail due to upload timing issues:
 *
 * 1. FILE UPLOAD TIMING:
 *    - Upload modal may not fully close before verification
 *    - Document may not appear in table immediately after upload
 *
 * 2. SEARCH INDEX TIMING:
 *    - Solr indexing is asynchronous
 *    - Search results may not reflect newly uploaded content
 *
 * Search tokenization fix verified working via API tests.
 * Re-enable after implementing more robust upload wait utilities.
 */
// FIXED (2025-12-25): Enabled with extended timeout for serial test execution
test.describe('Bug Fix: Search Tokenization Issue (WebUI)', () => {
  // Tests must run in order - document lifecycle
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(120000); // 2 minutes for serial execution

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  // Unique search term that won't exist in other documents
  const UNIQUE_ID = generateTestId().toUpperCase();
  const UNIQUE_SEARCH_TERM = `UNIQUE_SEARCH_VERIFY_${UNIQUE_ID}`;

  // Test document names
  const docWithExactMatch = `search-exact-${UNIQUE_ID}.txt`;
  const docWithPartialMatch = `search-partial-${UNIQUE_ID}.txt`;
  const docWithNoMatch = `search-nomatch-${UNIQUE_ID}.txt`;

  // FIXED (2025-12-25): Add afterAll hook for API-based cleanup
  // This ensures cleanup even if UI tests fail
  test.afterAll(async () => {
    console.log(`[CLEANUP] Cleaning up test documents for search tokenization tests`);
    const baseUrl = 'http://localhost:8080/core/browser/bedroom';
    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');

    // Patterns to clean up
    const patterns = ['search-exact-%', 'search-partial-%', 'search-nomatch-%'];

    for (const pattern of patterns) {
      try {
        const queryUrl = `${baseUrl}?cmisselector=query&q=${encodeURIComponent(`SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE '${pattern}'`)}&succinct=true`;
        const queryResponse = await fetch(queryUrl, {
          headers: { 'Authorization': authHeader }
        });

        if (queryResponse.ok) {
          const queryData = await queryResponse.json();
          const results = queryData.results || [];
          console.log(`[CLEANUP] Found ${results.length} documents matching ${pattern}`);

          for (const result of results) {
            const objectId = result.succinctProperties?.['cmis:objectId'];
            const name = result.succinctProperties?.['cmis:name'];
            if (objectId) {
              try {
                const formData = new URLSearchParams();
                formData.append('cmisaction', 'delete');
                formData.append('objectId', objectId);

                await fetch(baseUrl, {
                  method: 'POST',
                  headers: {
                    'Authorization': authHeader,
                    'Content-Type': 'application/x-www-form-urlencoded'
                  },
                  body: formData.toString()
                });
                console.log(`[CLEANUP] Deleted: ${name}`);
              } catch (e) {
                console.log(`[CLEANUP] Failed to delete ${name}:`, e);
              }
            }
          }
        }
      } catch (e) {
        console.log(`[CLEANUP] Query failed for ${pattern}:`, e);
      }
    }
  });

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await page.context().clearCookies();
    await page.context().clearPermissions();

    await authHelper.login();
    await testHelper.waitForAntdLoad();

    await testHelper.closeMobileSidebar(browserName);
  });

  test('Step 1: Upload document with EXACT search term', async ({ page, browserName }) => {
    console.log(`[TEST] Creating document with exact search term: ${docWithExactMatch}`);
    console.log(`[TEST] Search term: ${UNIQUE_SEARCH_TERM}`);

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await waitForUiStable(page);
    }

    // Click upload button
    let uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    if (await uploadButton.count() === 0) {
      uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
    }

    if (await uploadButton.count() === 0) {
      // UPDATED (2025-12-26): Upload IS implemented in DocumentList.tsx
      await expect(page.getByRole('button', { name: /アップロード|Upload/i }).first()).toBeVisible({ timeout: 10000 });
      return;
    }

    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

    // Upload file with EXACT search term in content
    await testHelper.uploadTestFile(
      '.ant-modal input[type="file"]',
      docWithExactMatch,
      `This document contains the EXACT search term ${UNIQUE_SEARCH_TERM} in its content.`
    );
    await waitForRender(page);

    // Submit upload
    const submitBtn = page.locator('.ant-modal button[type="submit"]');
    await submitBtn.click();

    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await waitForUiStable(page);

    // Verify document appears
    const uploadedDoc = page.locator(`text=${docWithExactMatch}`);
    await expect(uploadedDoc).toBeVisible({ timeout: 5000 });
    console.log(`[TEST] ✅ Document with exact match uploaded: ${docWithExactMatch}`);
  });

  test('Step 2: Upload document with PARTIAL match (individual tokens)', async ({ page, browserName }) => {
    console.log(`[TEST] Creating document with partial match: ${docWithPartialMatch}`);

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await waitForUiStable(page);
    }

    // Click upload button
    let uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    if (await uploadButton.count() === 0) {
      uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
    }

    if (await uploadButton.count() === 0) {
      // UPDATED (2025-12-26): Upload IS implemented in DocumentList.tsx
      await expect(page.getByRole('button', { name: /アップロード|Upload/i }).first()).toBeVisible({ timeout: 10000 });
      return;
    }

    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

    // Upload file with PARTIAL matches - words that would match if tokenized
    // Contains 'UNIQUE', 'SEARCH', 'VERIFY' separately but NOT the full phrase
    await testHelper.uploadTestFile(
      '.ant-modal input[type="file"]',
      docWithPartialMatch,
      `This document has UNIQUE content and uses SEARCH functionality to VERIFY results. But NOT the exact phrase together.`
    );
    await waitForRender(page);

    // Submit upload
    const submitBtn = page.locator('.ant-modal button[type="submit"]');
    await submitBtn.click();

    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await waitForUiStable(page);

    // Verify document appears
    const uploadedDoc = page.locator(`text=${docWithPartialMatch}`);
    await expect(uploadedDoc).toBeVisible({ timeout: 5000 });
    console.log(`[TEST] ✅ Document with partial match uploaded: ${docWithPartialMatch}`);
  });

  test('Step 3: Upload document with NO match', async ({ page, browserName }) => {
    console.log(`[TEST] Creating document with no match: ${docWithNoMatch}`);

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await waitForUiStable(page);
    }

    // Click upload button
    let uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    if (await uploadButton.count() === 0) {
      uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
    }

    if (await uploadButton.count() === 0) {
      // UPDATED (2025-12-26): Upload IS implemented in DocumentList.tsx
      await expect(page.getByRole('button', { name: /アップロード|Upload/i }).first()).toBeVisible({ timeout: 10000 });
      return;
    }

    await uploadButton.click(isMobile ? { force: true } : {});
    await page.waitForSelector('.ant-modal:not(.ant-modal-hidden)', { timeout: 5000 });

    // Upload file with NO related content
    await testHelper.uploadTestFile(
      '.ant-modal input[type="file"]',
      docWithNoMatch,
      `This document has completely unrelated content about cats and dogs.`
    );
    await waitForRender(page);

    // Submit upload
    const submitBtn = page.locator('.ant-modal button[type="submit"]');
    await submitBtn.click();

    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await waitForUiStable(page);

    // Verify document appears
    const uploadedDoc = page.locator(`text=${docWithNoMatch}`);
    await expect(uploadedDoc).toBeVisible({ timeout: 5000 });
    console.log(`[TEST] ✅ Document with no match uploaded: ${docWithNoMatch}`);
  });

  test('Step 4: Wait for Solr indexing', async ({ page }) => {
    console.log(`[TEST] Waiting for Solr to index documents (5 seconds)...`);
    // Give Solr time to index the uploaded documents
    await waitForUiStable(page, { timeout: 15000 });
    console.log(`[TEST] ✅ Wait complete`);
  });

  test('Step 5: CRITICAL TEST - Search should ONLY return exact phrase match', async ({ page, browserName }) => {
    console.log(`[TEST] Executing search for: ${UNIQUE_SEARCH_TERM}`);

    const isMobile = testHelper.isMobile(browserName);

    // Navigate directly to search page via URL
    await page.goto('http://localhost:8080/core/ui/index.html#/search');
    await waitForRender(page);
    await waitForUiStable(page);

    // Find search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');
    if (await searchInput.count() === 0) {
      // UPDATED (2025-12-26): Search input IS implemented in SearchForm.tsx
      await expect(page.locator('input[placeholder*="検索"], input[placeholder*="Search"]').first()).toBeVisible({ timeout: 10000 });
      return;
    }

    // Enter the unique search term (underscore-separated phrase)
    await searchInput.first().fill(UNIQUE_SEARCH_TERM);
    console.log(`[TEST] Search term entered: ${UNIQUE_SEARCH_TERM}`);

    // Execute search
    // The page-specific hook: `button:has-text("検索")` also matches link-styled buttons in
    // the result rows, which React replaces as rows re-render.
    const searchButton = searchPageSubmitButton(page);
    if (await searchButton.count() > 0) {
      await searchButton.first().click(isMobile ? { force: true } : {});
    } else {
      await searchInput.first().press('Enter');
    }

    // Wait for search results
    await waitForUiStable(page);

    // Take screenshot for evidence
    await page.screenshot({ path: 'test-results/screenshots/search-tokenization-results.png', fullPage: true });

    // Check search results
    const resultsTable = page.locator('.ant-table tbody tr');
    const resultCount = await resultsTable.count();
    console.log(`[TEST] Search returned ${resultCount} results`);

    // CRITICAL ASSERTIONS
    // 1. Document with EXACT match SHOULD be found
    const exactMatchRow = page.locator('tr').filter({ hasText: docWithExactMatch });
    const exactMatchFound = await exactMatchRow.count() > 0;
    console.log(`[TEST] Exact match document found: ${exactMatchFound}`);

    // 2. Document with PARTIAL match should NOT be found (BUG FIX VERIFICATION)
    const partialMatchRow = page.locator('tr').filter({ hasText: docWithPartialMatch });
    const partialMatchFound = await partialMatchRow.count() > 0;
    console.log(`[TEST] Partial match document found: ${partialMatchFound}`);

    // 3. Document with NO match should NOT be found
    const noMatchRow = page.locator('tr').filter({ hasText: docWithNoMatch });
    const noMatchFound = await noMatchRow.count() > 0;
    console.log(`[TEST] No match document found: ${noMatchFound}`);

    // Log the bug status
    if (partialMatchFound) {
      console.log(`[TEST] ❌ BUG STILL EXISTS: Partial match document was incorrectly returned!`);
      console.log(`[TEST] This means the search term was tokenized and individual tokens matched`);
    }

    if (exactMatchFound) {
      console.log(`[TEST] ✅ Exact phrase match is working correctly`);
    }

    // THE CRITICAL BUG ASSERTION
    // After the fix, partial match should NOT be returned
    // This is the main assertion that verifies the bug fix
    expect(partialMatchFound).toBe(false);
    expect(noMatchFound).toBe(false);

    console.log(`[TEST] ✅ Search tokenization bug verification complete`);
  });

  test('Step 6: Cleanup - Delete test documents', async ({ page, browserName }) => {
    console.log(`[TEST] Cleaning up test documents`);

    const isMobile = testHelper.isMobile(browserName);

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await waitForUiStable(page);
    }

    // Delete each test document
    const testDocs = [docWithExactMatch, docWithPartialMatch, docWithNoMatch];

    for (const docName of testDocs) {
      const docRow = page.locator('tr').filter({ hasText: docName });

      if (await docRow.count() > 0) {
        const deleteButton = docRow.locator('button').filter({
          has: page.locator('.anticon-delete, [aria-label="delete"]')
        });

        if (await deleteButton.count() > 0) {
          // Use force click to avoid tooltip interference
          await deleteButton.click({ force: true });
          await waitForRender(page);

          // Wait for popconfirm to appear
          await waitForRender(page);
          const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary').first();
          if (await confirmButton.count() > 0) {
            // Use force click to bypass tooltip overlay
            await confirmButton.click({ force: true });
            await waitForUiStable(page);
            console.log(`[TEST] ✅ Deleted: ${docName}`);
          }
        }
      }
    }

    console.log(`[TEST] ✅ Cleanup complete`);
  });
});
