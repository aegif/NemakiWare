import { Browser } from '@playwright/test';
import { ApiHelper } from './api-helper';

/**
 * Sweep test-created objects from the repository so they do not accumulate in
 * the root folder. Accumulated objects slow the Solr-backed document-list query
 * that most specs wait on after login, which is the main source of flaky
 * `.ant-table` timeouts in long sequential runs.
 *
 * Use from a spec's `test.afterAll`, e.g.:
 *
 *   test.afterAll(({ browser }) =>
 *     cleanupTestData(browser, { documents: ['version-history-%', 'checkin-test-%'] }));
 *
 * Patterns are CMIS `LIKE` patterns (use `%` wildcards). Failures are logged but
 * never thrown — cleanup must not fail the suite.
 */
export async function cleanupTestData(
  browser: Browser,
  patterns: { documents?: string[]; folders?: string[] }
): Promise<void> {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    const api = new ApiHelper(page);
    for (const p of patterns.folders ?? []) {
      await api.cleanupTestFolders(p);
    }
    for (const p of patterns.documents ?? []) {
      await api.cleanupTestDocuments(p);
    }
  } catch (e) {
    console.log(`[cleanupTestData] error: ${e}`);
  } finally {
    await context.close();
  }
}
