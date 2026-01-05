/**
 * Concurrent Load Test for NemakiWare
 *
 * Tests system stability under concurrent access:
 * - Multiple UI sessions simultaneously
 * - Concurrent API calls
 * - Mixed UI and API load
 *
 * Focus: UI-heavy testing with API mix
 */

import { test, expect } from '@playwright/test';
import { AuthHelper } from '../utils/auth-helper';

const BASE_URL = 'http://localhost:8080';
const REPOSITORY_ID = 'bedroom';

// Test configuration
const CONCURRENT_UI_SESSIONS = 5;
const CONCURRENT_API_CALLS = 10;
const API_ITERATIONS = 3;

test.describe('Concurrent Load Test - UI Heavy', () => {
  test.setTimeout(180000); // 3 minutes timeout for load tests

  test('should handle multiple concurrent UI logins', async ({ browser }) => {
    console.log(`\n=== Concurrent UI Login Test (${CONCURRENT_UI_SESSIONS} sessions) ===`);
    const startTime = Date.now();
    const results: { session: number; success: boolean; time: number; error?: string }[] = [];

    // Create multiple browser contexts for concurrent sessions
    const contextPromises = Array.from({ length: CONCURRENT_UI_SESSIONS }, async (_, i) => {
      const sessionStart = Date.now();
      try {
        const context = await browser.newContext();
        const page = await context.newPage();
        const authHelper = new AuthHelper(page);

        // Login
        await authHelper.login();
        await page.waitForTimeout(2000);

        // Verify logged in
        const userInfo = page.locator('.ant-avatar, [data-testid="user-info"]');
        const isLoggedIn = await userInfo.count() > 0;

        await context.close();

        results.push({
          session: i + 1,
          success: isLoggedIn,
          time: Date.now() - sessionStart
        });
      } catch (error) {
        results.push({
          session: i + 1,
          success: false,
          time: Date.now() - sessionStart,
          error: error instanceof Error ? error.message : String(error)
        });
      }
    });

    await Promise.all(contextPromises);

    const totalTime = Date.now() - startTime;
    const successCount = results.filter(r => r.success).length;
    const avgTime = results.reduce((sum, r) => sum + r.time, 0) / results.length;

    console.log('\nResults:');
    results.forEach(r => {
      console.log(`  Session ${r.session}: ${r.success ? '✅ OK' : '❌ FAIL'} (${r.time}ms)${r.error ? ` - ${r.error}` : ''}`);
    });
    console.log(`\nSummary: ${successCount}/${CONCURRENT_UI_SESSIONS} succeeded`);
    console.log(`Total time: ${totalTime}ms, Avg per session: ${Math.round(avgTime)}ms`);

    expect(successCount).toBeGreaterThanOrEqual(Math.floor(CONCURRENT_UI_SESSIONS * 0.8)); // 80% success rate
  });

  test('should handle concurrent folder browsing', async ({ browser }) => {
    console.log(`\n=== Concurrent Folder Browsing Test (${CONCURRENT_UI_SESSIONS} sessions) ===`);
    const startTime = Date.now();
    const results: { session: number; success: boolean; time: number; folderCount?: number }[] = [];

    const contextPromises = Array.from({ length: CONCURRENT_UI_SESSIONS }, async (_, i) => {
      const sessionStart = Date.now();
      try {
        const context = await browser.newContext();
        const page = await context.newPage();
        const authHelper = new AuthHelper(page);

        await authHelper.login();
        await page.waitForTimeout(2000);

        // Navigate to folder view
        const folderMenu = page.locator('.ant-menu-item').filter({ hasText: /フォルダ|Folder|ブラウズ/i });
        if (await folderMenu.count() > 0) {
          await folderMenu.first().click();
          await page.waitForTimeout(2000);
        }

        // Count visible folders/files
        const tableRows = await page.locator('.ant-table tbody tr').count();

        await context.close();

        results.push({
          session: i + 1,
          success: true,
          time: Date.now() - sessionStart,
          folderCount: tableRows
        });
      } catch (error) {
        results.push({
          session: i + 1,
          success: false,
          time: Date.now() - sessionStart
        });
      }
    });

    await Promise.all(contextPromises);

    const totalTime = Date.now() - startTime;
    const successCount = results.filter(r => r.success).length;

    console.log('\nResults:');
    results.forEach(r => {
      console.log(`  Session ${r.session}: ${r.success ? '✅' : '❌'} (${r.time}ms) - ${r.folderCount || 0} items`);
    });
    console.log(`\nSummary: ${successCount}/${CONCURRENT_UI_SESSIONS} succeeded in ${totalTime}ms`);

    expect(successCount).toBeGreaterThanOrEqual(Math.floor(CONCURRENT_UI_SESSIONS * 0.8));
  });

  test('should handle concurrent search operations', async ({ browser }) => {
    console.log(`\n=== Concurrent Search Test (${CONCURRENT_UI_SESSIONS} sessions) ===`);
    const searchTerms = ['CMIS', 'test', 'Sites', 'document', 'folder'];
    const startTime = Date.now();
    const results: { session: number; searchTerm: string; success: boolean; time: number; resultCount?: number }[] = [];

    const contextPromises = Array.from({ length: CONCURRENT_UI_SESSIONS }, async (_, i) => {
      const sessionStart = Date.now();
      const searchTerm = searchTerms[i % searchTerms.length];

      try {
        const context = await browser.newContext();
        const page = await context.newPage();
        const authHelper = new AuthHelper(page);

        await authHelper.login();
        await page.waitForTimeout(2000);

        // Navigate to search
        const searchMenu = page.locator('.ant-menu-item').filter({ hasText: /検索|Search/i });
        if (await searchMenu.count() > 0) {
          await searchMenu.first().click();
          await page.waitForTimeout(1500);
        } else {
          await page.goto(`${BASE_URL}/core/ui/#/search`);
          await page.waitForTimeout(1500);
        }

        // Perform search
        const searchInput = page.locator('input[placeholder*="検索"]').first();
        if (await searchInput.count() > 0) {
          await searchInput.fill(searchTerm);
        }

        const searchButton = page.locator('button.search-button').first();
        if (await searchButton.count() > 0) {
          await searchButton.click();
          await page.waitForTimeout(3000);
        }

        // Get result count
        const resultRows = await page.locator('.ant-table tbody tr').count();

        await context.close();

        results.push({
          session: i + 1,
          searchTerm,
          success: true,
          time: Date.now() - sessionStart,
          resultCount: resultRows
        });
      } catch (error) {
        results.push({
          session: i + 1,
          searchTerm,
          success: false,
          time: Date.now() - sessionStart
        });
      }
    });

    await Promise.all(contextPromises);

    const totalTime = Date.now() - startTime;
    const successCount = results.filter(r => r.success).length;

    console.log('\nResults:');
    results.forEach(r => {
      console.log(`  Session ${r.session} ("${r.searchTerm}"): ${r.success ? '✅' : '❌'} (${r.time}ms) - ${r.resultCount || 0} results`);
    });
    console.log(`\nSummary: ${successCount}/${CONCURRENT_UI_SESSIONS} succeeded in ${totalTime}ms`);

    expect(successCount).toBeGreaterThanOrEqual(Math.floor(CONCURRENT_UI_SESSIONS * 0.8));
  });

  test('should handle concurrent type management access', async ({ browser }) => {
    console.log(`\n=== Concurrent Type Management Test (${CONCURRENT_UI_SESSIONS} sessions) ===`);
    const startTime = Date.now();
    const results: { session: number; success: boolean; time: number; typeCount?: number }[] = [];

    const contextPromises = Array.from({ length: CONCURRENT_UI_SESSIONS }, async (_, i) => {
      const sessionStart = Date.now();

      try {
        const context = await browser.newContext();
        const page = await context.newPage();
        const authHelper = new AuthHelper(page);

        await authHelper.login();
        await page.waitForTimeout(2000);

        // Navigate to admin > type management
        const adminMenu = page.locator('.ant-menu-submenu').filter({ hasText: /管理|Admin/i });
        if (await adminMenu.count() > 0) {
          await adminMenu.click();
          await page.waitForTimeout(1000);
        }

        const typeManagementItem = page.locator('.ant-menu-item').filter({ hasText: /タイプ管理|Type Management/i });
        if (await typeManagementItem.count() > 0) {
          await typeManagementItem.click();
          await page.waitForTimeout(2000);
        }

        // Count types in table
        const typeRows = await page.locator('.ant-table tbody tr').count();

        await context.close();

        results.push({
          session: i + 1,
          success: typeRows > 0,
          time: Date.now() - sessionStart,
          typeCount: typeRows
        });
      } catch (error) {
        results.push({
          session: i + 1,
          success: false,
          time: Date.now() - sessionStart
        });
      }
    });

    await Promise.all(contextPromises);

    const totalTime = Date.now() - startTime;
    const successCount = results.filter(r => r.success).length;

    console.log('\nResults:');
    results.forEach(r => {
      console.log(`  Session ${r.session}: ${r.success ? '✅' : '❌'} (${r.time}ms) - ${r.typeCount || 0} types`);
    });
    console.log(`\nSummary: ${successCount}/${CONCURRENT_UI_SESSIONS} succeeded in ${totalTime}ms`);

    expect(successCount).toBeGreaterThanOrEqual(Math.floor(CONCURRENT_UI_SESSIONS * 0.8));
  });
});

test.describe('Concurrent Load Test - API Direct', () => {
  test.setTimeout(120000);

  test('should handle concurrent API type list requests', async ({ request }) => {
    console.log(`\n=== Concurrent API Type List Test (${CONCURRENT_API_CALLS} calls x ${API_ITERATIONS} iterations) ===`);
    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
    const allResults: { call: number; iteration: number; status: number; time: number }[] = [];

    for (let iter = 0; iter < API_ITERATIONS; iter++) {
      const iterStart = Date.now();
      const promises = Array.from({ length: CONCURRENT_API_CALLS }, async (_, i) => {
        const callStart = Date.now();
        try {
          const response = await request.get(`${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/type/list`, {
            headers: {
              'Authorization': authHeader,
              'Accept': 'application/json'
            }
          });
          return {
            call: i + 1,
            iteration: iter + 1,
            status: response.status(),
            time: Date.now() - callStart
          };
        } catch (error) {
          return {
            call: i + 1,
            iteration: iter + 1,
            status: 0,
            time: Date.now() - callStart
          };
        }
      });

      const results = await Promise.all(promises);
      allResults.push(...results);

      const successCount = results.filter(r => r.status === 200).length;
      const avgTime = results.reduce((sum, r) => sum + r.time, 0) / results.length;
      console.log(`  Iteration ${iter + 1}: ${successCount}/${CONCURRENT_API_CALLS} OK, avg ${Math.round(avgTime)}ms (total ${Date.now() - iterStart}ms)`);
    }

    const totalSuccess = allResults.filter(r => r.status === 200).length;
    const totalCalls = CONCURRENT_API_CALLS * API_ITERATIONS;
    console.log(`\nTotal: ${totalSuccess}/${totalCalls} succeeded`);

    expect(totalSuccess).toBeGreaterThanOrEqual(totalCalls * 0.9); // 90% success rate
  });

  test('should handle concurrent CMIS query requests', async ({ request }) => {
    console.log(`\n=== Concurrent CMIS Query Test (${CONCURRENT_API_CALLS} calls x ${API_ITERATIONS} iterations) ===`);
    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
    const queries = [
      'SELECT cmis:objectId, cmis:name FROM cmis:document',
      'SELECT cmis:objectId, cmis:name FROM cmis:folder',
      "SELECT cmis:objectId, cmis:name FROM cmis:document WHERE cmis:name LIKE '%CMIS%'",
    ];
    const allResults: { call: number; iteration: number; status: number; time: number; resultCount?: number }[] = [];

    for (let iter = 0; iter < API_ITERATIONS; iter++) {
      const iterStart = Date.now();
      const promises = Array.from({ length: CONCURRENT_API_CALLS }, async (_, i) => {
        const callStart = Date.now();
        const query = queries[i % queries.length];
        try {
          const response = await request.get(
            `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=query&q=${encodeURIComponent(query)}&maxItems=10`,
            {
              headers: {
                'Authorization': authHeader,
                'Accept': 'application/json'
              }
            }
          );
          let resultCount = 0;
          if (response.status() === 200) {
            const data = await response.json();
            resultCount = data.results?.length || 0;
          }
          return {
            call: i + 1,
            iteration: iter + 1,
            status: response.status(),
            time: Date.now() - callStart,
            resultCount
          };
        } catch (error) {
          return {
            call: i + 1,
            iteration: iter + 1,
            status: 0,
            time: Date.now() - callStart
          };
        }
      });

      const results = await Promise.all(promises);
      allResults.push(...results);

      const successCount = results.filter(r => r.status === 200).length;
      const avgTime = results.reduce((sum, r) => sum + r.time, 0) / results.length;
      console.log(`  Iteration ${iter + 1}: ${successCount}/${CONCURRENT_API_CALLS} OK, avg ${Math.round(avgTime)}ms`);
    }

    const totalSuccess = allResults.filter(r => r.status === 200).length;
    const totalCalls = CONCURRENT_API_CALLS * API_ITERATIONS;
    console.log(`\nTotal: ${totalSuccess}/${totalCalls} succeeded`);

    expect(totalSuccess).toBeGreaterThanOrEqual(totalCalls * 0.9);
  });

  test('should handle concurrent folder children requests', async ({ request }) => {
    console.log(`\n=== Concurrent Folder Children Test (${CONCURRENT_API_CALLS} calls x ${API_ITERATIONS} iterations) ===`);
    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
    const allResults: { call: number; iteration: number; status: number; time: number }[] = [];

    for (let iter = 0; iter < API_ITERATIONS; iter++) {
      const iterStart = Date.now();
      const promises = Array.from({ length: CONCURRENT_API_CALLS }, async (_, i) => {
        const callStart = Date.now();
        try {
          const response = await request.get(
            `${BASE_URL}/core/browser/${REPOSITORY_ID}/root?cmisselector=children&maxItems=20`,
            {
              headers: {
                'Authorization': authHeader,
                'Accept': 'application/json'
              }
            }
          );
          return {
            call: i + 1,
            iteration: iter + 1,
            status: response.status(),
            time: Date.now() - callStart
          };
        } catch (error) {
          return {
            call: i + 1,
            iteration: iter + 1,
            status: 0,
            time: Date.now() - callStart
          };
        }
      });

      const results = await Promise.all(promises);
      allResults.push(...results);

      const successCount = results.filter(r => r.status === 200).length;
      const avgTime = results.reduce((sum, r) => sum + r.time, 0) / results.length;
      console.log(`  Iteration ${iter + 1}: ${successCount}/${CONCURRENT_API_CALLS} OK, avg ${Math.round(avgTime)}ms`);
    }

    const totalSuccess = allResults.filter(r => r.status === 200).length;
    const totalCalls = CONCURRENT_API_CALLS * API_ITERATIONS;
    console.log(`\nTotal: ${totalSuccess}/${totalCalls} succeeded`);

    expect(totalSuccess).toBeGreaterThanOrEqual(totalCalls * 0.9);
  });
});

test.describe('Mixed Load Test - UI and API', () => {
  test.setTimeout(180000);

  test('should handle mixed UI and API concurrent access', async ({ browser, request }) => {
    console.log(`\n=== Mixed UI and API Load Test ===`);
    console.log(`  UI Sessions: ${CONCURRENT_UI_SESSIONS}`);
    console.log(`  API Calls: ${CONCURRENT_API_CALLS}`);

    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
    const startTime = Date.now();

    // UI tasks
    const uiPromises = Array.from({ length: CONCURRENT_UI_SESSIONS }, async (_, i) => {
      const sessionStart = Date.now();
      try {
        const context = await browser.newContext();
        const page = await context.newPage();
        const authHelper = new AuthHelper(page);

        await authHelper.login();
        await page.waitForTimeout(2000);

        // Navigate to search and perform search
        await page.goto(`${BASE_URL}/core/ui/#/search`);
        await page.waitForTimeout(1500);

        const searchInput = page.locator('input[placeholder*="検索"]').first();
        if (await searchInput.count() > 0) {
          await searchInput.fill('test');
        }

        const searchButton = page.locator('button.search-button').first();
        if (await searchButton.count() > 0) {
          await searchButton.click();
          await page.waitForTimeout(3000);
        }

        await context.close();

        return {
          type: 'UI',
          session: i + 1,
          success: true,
          time: Date.now() - sessionStart
        };
      } catch (error) {
        return {
          type: 'UI',
          session: i + 1,
          success: false,
          time: Date.now() - sessionStart
        };
      }
    });

    // API tasks
    const apiPromises = Array.from({ length: CONCURRENT_API_CALLS }, async (_, i) => {
      const callStart = Date.now();
      try {
        const response = await request.get(
          `${BASE_URL}/core/browser/${REPOSITORY_ID}?cmisselector=typeChildren`,
          {
            headers: {
              'Authorization': authHeader,
              'Accept': 'application/json'
            }
          }
        );
        return {
          type: 'API',
          call: i + 1,
          success: response.status() === 200,
          time: Date.now() - callStart
        };
      } catch (error) {
        return {
          type: 'API',
          call: i + 1,
          success: false,
          time: Date.now() - callStart
        };
      }
    });

    // Run all concurrently
    const allResults = await Promise.all([...uiPromises, ...apiPromises]);

    const totalTime = Date.now() - startTime;
    const uiResults = allResults.filter(r => r.type === 'UI');
    const apiResults = allResults.filter(r => r.type === 'API');
    const uiSuccess = uiResults.filter(r => r.success).length;
    const apiSuccess = apiResults.filter(r => r.success).length;

    console.log('\nUI Results:');
    uiResults.forEach((r: any) => {
      console.log(`  Session ${r.session}: ${r.success ? '✅' : '❌'} (${r.time}ms)`);
    });

    console.log('\nAPI Results:');
    const apiSuccessRate = (apiSuccess / apiResults.length * 100).toFixed(1);
    const avgApiTime = apiResults.reduce((sum, r) => sum + r.time, 0) / apiResults.length;
    console.log(`  ${apiSuccess}/${apiResults.length} succeeded (${apiSuccessRate}%), avg ${Math.round(avgApiTime)}ms`);

    console.log(`\n=== Summary ===`);
    console.log(`  Total time: ${totalTime}ms`);
    console.log(`  UI: ${uiSuccess}/${uiResults.length} succeeded`);
    console.log(`  API: ${apiSuccess}/${apiResults.length} succeeded`);

    const totalSuccess = uiSuccess + apiSuccess;
    const totalCalls = CONCURRENT_UI_SESSIONS + CONCURRENT_API_CALLS;
    console.log(`  Overall: ${totalSuccess}/${totalCalls} succeeded (${(totalSuccess/totalCalls*100).toFixed(1)}%)`);

    expect(uiSuccess).toBeGreaterThanOrEqual(Math.floor(CONCURRENT_UI_SESSIONS * 0.7));
    expect(apiSuccess).toBeGreaterThanOrEqual(Math.floor(CONCURRENT_API_CALLS * 0.8));
  });

  test('should handle sustained load over multiple rounds', async ({ browser, request }) => {
    console.log(`\n=== Sustained Load Test (3 rounds) ===`);
    const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
    const rounds = 3;
    const roundResults: { round: number; uiSuccess: number; apiSuccess: number; time: number }[] = [];

    for (let round = 1; round <= rounds; round++) {
      const roundStart = Date.now();

      // UI sessions (3 per round)
      const uiPromises = Array.from({ length: 3 }, async (_, i) => {
        try {
          const context = await browser.newContext();
          const page = await context.newPage();
          const authHelper = new AuthHelper(page);
          await authHelper.login();
          await page.waitForTimeout(1500);
          await context.close();
          return true;
        } catch {
          return false;
        }
      });

      // API calls (5 per round)
      const apiPromises = Array.from({ length: 5 }, async () => {
        try {
          const response = await request.get(
            `${BASE_URL}/core/rest/repo/${REPOSITORY_ID}/type/list`,
            {
              headers: { 'Authorization': authHeader, 'Accept': 'application/json' }
            }
          );
          return response.status() === 200;
        } catch {
          return false;
        }
      });

      const [uiResults, apiResults] = await Promise.all([
        Promise.all(uiPromises),
        Promise.all(apiPromises)
      ]);

      const roundTime = Date.now() - roundStart;
      const uiSuccess = uiResults.filter(r => r).length;
      const apiSuccess = apiResults.filter(r => r).length;

      roundResults.push({ round, uiSuccess, apiSuccess, time: roundTime });
      console.log(`  Round ${round}: UI ${uiSuccess}/3, API ${apiSuccess}/5 (${roundTime}ms)`);

      // Brief pause between rounds
      if (round < rounds) {
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
    }

    const totalUiSuccess = roundResults.reduce((sum, r) => sum + r.uiSuccess, 0);
    const totalApiSuccess = roundResults.reduce((sum, r) => sum + r.apiSuccess, 0);
    console.log(`\nTotal: UI ${totalUiSuccess}/${rounds * 3}, API ${totalApiSuccess}/${rounds * 5}`);

    expect(totalUiSuccess).toBeGreaterThanOrEqual(Math.floor(rounds * 3 * 0.7));
    expect(totalApiSuccess).toBeGreaterThanOrEqual(Math.floor(rounds * 5 * 0.8));
  });
});
