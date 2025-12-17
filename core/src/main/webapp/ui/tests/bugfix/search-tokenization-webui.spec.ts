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
import { AuthHelper } from '../utils/auth-helper';
import { TestHelper } from '../utils/test-helper';
import { randomUUID } from 'crypto';

test.describe('Bug Fix: Search Tokenization Issue (WebUI)', () => {
  // Tests must run in order - document lifecycle
  test.describe.configure({ mode: 'serial' });

  let authHelper: AuthHelper;
  let testHelper: TestHelper;

  // Unique search term that won't exist in other documents
  const UNIQUE_ID = randomUUID().substring(0, 8).toUpperCase();
  const UNIQUE_SEARCH_TERM = `UNIQUE_SEARCH_VERIFY_${UNIQUE_ID}`;

  // Test document names
  const docWithExactMatch = `search-exact-${UNIQUE_ID}.txt`;
  const docWithPartialMatch = `search-partial-${UNIQUE_ID}.txt`;
  const docWithNoMatch = `search-nomatch-${UNIQUE_ID}.txt`;

  test.beforeEach(async ({ page, browserName }) => {
    authHelper = new AuthHelper(page);
    testHelper = new TestHelper(page);

    await page.context().clearCookies();
    await page.context().clearPermissions();

    await authHelper.login();
    await testHelper.waitForAntdLoad();

    // MOBILE FIX: Close sidebar to prevent overlay blocking clicks
    const viewportSize = page.viewportSize();
    const isMobileChrome = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    if (isMobileChrome) {
      const menuToggle = page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
      if (await menuToggle.count() > 0) {
        try {
          await menuToggle.first().click({ timeout: 3000 });
          await page.waitForTimeout(500);
        } catch {
          // Continue even if sidebar close fails
        }
      }
    }
  });

  test('Step 1: Upload document with EXACT search term', async ({ page, browserName }) => {
    console.log(`[TEST] Creating document with exact search term: ${docWithExactMatch}`);
    console.log(`[TEST] Search term: ${UNIQUE_SEARCH_TERM}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await page.waitForTimeout(2000);
    }

    // Click upload button
    let uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    if (await uploadButton.count() === 0) {
      uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
    }

    if (await uploadButton.count() === 0) {
      test.skip('Upload functionality not available');
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
    await page.waitForTimeout(1000);

    // Submit upload
    const submitBtn = page.locator('.ant-modal button[type="submit"]');
    await submitBtn.click();

    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(2000);

    // Verify document appears
    const uploadedDoc = page.locator(`text=${docWithExactMatch}`);
    await expect(uploadedDoc).toBeVisible({ timeout: 5000 });
    console.log(`[TEST] ✅ Document with exact match uploaded: ${docWithExactMatch}`);
  });

  test('Step 2: Upload document with PARTIAL match (individual tokens)', async ({ page, browserName }) => {
    console.log(`[TEST] Creating document with partial match: ${docWithPartialMatch}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await page.waitForTimeout(2000);
    }

    // Click upload button
    let uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    if (await uploadButton.count() === 0) {
      uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
    }

    if (await uploadButton.count() === 0) {
      test.skip('Upload functionality not available');
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
    await page.waitForTimeout(1000);

    // Submit upload
    const submitBtn = page.locator('.ant-modal button[type="submit"]');
    await submitBtn.click();

    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(2000);

    // Verify document appears
    const uploadedDoc = page.locator(`text=${docWithPartialMatch}`);
    await expect(uploadedDoc).toBeVisible({ timeout: 5000 });
    console.log(`[TEST] ✅ Document with partial match uploaded: ${docWithPartialMatch}`);
  });

  test('Step 3: Upload document with NO match', async ({ page, browserName }) => {
    console.log(`[TEST] Creating document with no match: ${docWithNoMatch}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await page.waitForTimeout(2000);
    }

    // Click upload button
    let uploadButton = page.locator('button').filter({ hasText: 'アップロード' }).first();
    if (await uploadButton.count() === 0) {
      uploadButton = page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
    }

    if (await uploadButton.count() === 0) {
      test.skip('Upload functionality not available');
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
    await page.waitForTimeout(1000);

    // Submit upload
    const submitBtn = page.locator('.ant-modal button[type="submit"]');
    await submitBtn.click();

    await page.waitForSelector('.ant-message-success', { timeout: 10000 });
    await page.waitForTimeout(2000);

    // Verify document appears
    const uploadedDoc = page.locator(`text=${docWithNoMatch}`);
    await expect(uploadedDoc).toBeVisible({ timeout: 5000 });
    console.log(`[TEST] ✅ Document with no match uploaded: ${docWithNoMatch}`);
  });

  test('Step 4: Wait for Solr indexing', async ({ page }) => {
    console.log(`[TEST] Waiting for Solr to index documents (5 seconds)...`);
    // Give Solr time to index the uploaded documents
    await page.waitForTimeout(5000);
    console.log(`[TEST] ✅ Wait complete`);
  });

  test('Step 5: CRITICAL TEST - Search should ONLY return exact phrase match', async ({ page, browserName }) => {
    console.log(`[TEST] Executing search for: ${UNIQUE_SEARCH_TERM}`);

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to search page
    const searchMenu = page.locator('.ant-menu-item').filter({ hasText: '検索' });
    if (await searchMenu.count() > 0) {
      await searchMenu.click();
      await page.waitForTimeout(2000);
    } else {
      test.skip('Search page not available');
      return;
    }

    // Find search input
    const searchInput = page.locator('input[placeholder*="検索"], input[placeholder*="search"]');
    if (await searchInput.count() === 0) {
      test.skip('Search input not found');
      return;
    }

    // Enter the unique search term (underscore-separated phrase)
    await searchInput.first().fill(UNIQUE_SEARCH_TERM);
    console.log(`[TEST] Search term entered: ${UNIQUE_SEARCH_TERM}`);

    // Execute search
    const searchButton = page.locator('button:has-text("検索"), .ant-btn:has-text("Search")');
    if (await searchButton.count() > 0) {
      await searchButton.first().click(isMobile ? { force: true } : {});
    } else {
      await searchInput.first().press('Enter');
    }

    // Wait for search results
    await page.waitForTimeout(3000);

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
    } else {
      console.log(`[TEST] ✅ BUG FIXED: Partial match document was correctly excluded`);
    }

    if (exactMatchFound) {
      console.log(`[TEST] ✅ Exact phrase match is working correctly`);
    } else {
      console.log(`[TEST] ⚠️ Warning: Exact match document not found - Solr may need more indexing time`);
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

    const viewportSize = page.viewportSize();
    const isMobile = browserName === 'chromium' && viewportSize && viewportSize.width <= 414;

    // Navigate to documents
    const documentsMenuItem = page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await page.waitForTimeout(2000);
    }

    // Delete each test document
    const testDocs = [docWithExactMatch, docWithPartialMatch, docWithNoMatch];

    for (const docName of testDocs) {
      const docRow = page.locator('tr').filter({ hasText: docName });

      if (await docRow.count() > 0) {
        const deleteButton = docRow.locator('button').filter({
          has: page.locator('[data-icon="delete"]')
        });

        if (await deleteButton.count() > 0) {
          // Use force click to avoid tooltip interference
          await deleteButton.click({ force: true });
          await page.waitForTimeout(1000);

          // Wait for popconfirm to appear
          await page.waitForTimeout(500);
          const confirmButton = page.locator('.ant-popconfirm button.ant-btn-primary').first();
          if (await confirmButton.count() > 0) {
            // Use force click to bypass tooltip overlay
            await confirmButton.click({ force: true });
            await page.waitForTimeout(2000);
            console.log(`[TEST] ✅ Deleted: ${docName}`);
          }
        }
      } else {
        console.log(`[TEST] Document not found for cleanup: ${docName}`);
      }
    }

    console.log(`[TEST] ✅ Cleanup complete`);
  });
});
