/**
 * Shared wait helpers for Playwright E2E tests.
 *
 * Replace `page.waitForTimeout(N)` with these deterministic alternatives
 * that wait for specific signals rather than elapsed time.
 *
 * Migration guide:
 *   waitForTimeout(500)  → waitForRender(page)
 *   waitForTimeout(1000) → waitForUiStable(page) or waitForRender(page)
 *   waitForTimeout(2000) → waitForApiResponse(page, urlPattern)
 *   waitForTimeout(3000) → waitForSolrIndex(page, docName)
 *   waitForTimeout(5000) → waitForUiStable(page, { timeout: 5000 })
 */

import { Page, expect } from '@playwright/test';

/**
 * Wait for React render cycle to settle.
 * Replaces: waitForTimeout(300–500)
 *
 * Waits until no network requests are in-flight and the page has
 * finished layout reflow.  More reliable than a fixed 500ms timer.
 */
export async function waitForRender(page: Page, options?: { timeout?: number }): Promise<void> {
  const timeout = options?.timeout ?? 5000;
  try {
    await page.waitForLoadState('networkidle', { timeout });
  } catch {
    // networkidle may not fire if long-polling is active; that's OK
  }
}

/**
 * Wait for the UI to stabilize after a navigation or modal open.
 * Replaces: waitForTimeout(1000–2000)
 *
 * Waits for the Ant Design loading spinner to disappear (if present)
 * and for network activity to settle.
 */
export async function waitForUiStable(page: Page, options?: { timeout?: number }): Promise<void> {
  const timeout = options?.timeout ?? 10000;
  // Wait for any Ant Design Spin component to disappear
  const spinner = page.locator('.ant-spin-spinning');
  try {
    if (await spinner.count() > 0) {
      await spinner.first().waitFor({ state: 'hidden', timeout });
    }
  } catch {
    // spinner may never have appeared
  }
  await waitForRender(page, { timeout: Math.min(timeout, 3000) });
}

/**
 * Wait for a specific API response pattern.
 * Replaces: waitForTimeout(2000) after a POST/PUT/DELETE
 *
 * @param urlPattern - substring that the response URL must contain
 * @param action     - async function that triggers the request
 * @returns the Response object
 */
export async function waitForApiResponse(
  page: Page,
  urlPattern: string,
  action: () => Promise<void>,
  options?: { timeout?: number },
) {
  const timeout = options?.timeout ?? 15000;
  const [response] = await Promise.all([
    page.waitForResponse(
      (res) => res.url().includes(urlPattern) && res.status() < 500,
      { timeout },
    ),
    action(),
  ]);
  return response;
}

/**
 * Wait for Solr to index a document by polling the search API.
 * Replaces: waitForTimeout(3000–5000) after document creation
 *
 * @param page    - Playwright page
 * @param query   - CMIS SQL or search term to verify
 * @param options - timeout (default 30s), pollInterval (default 2s)
 */
export async function waitForSolrIndex(
  page: Page,
  query: string,
  options?: { timeout?: number; pollInterval?: number },
): Promise<boolean> {
  const timeout = options?.timeout ?? 30000;
  const pollInterval = options?.pollInterval ?? 2000;
  const deadline = Date.now() + timeout;

  while (Date.now() < deadline) {
    try {
      const response = await page.request.get(
        `http://localhost:8080/core/browser/bedroom/root?cmisselector=query&q=${encodeURIComponent(query)}&maxItems=1`,
        { headers: { Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64') } },
      );
      if (response.ok()) {
        const body = await response.json();
        if (body.numItems && body.numItems > 0) return true;
      }
    } catch {
      // query failed, will retry
    }
    await page.waitForTimeout(pollInterval); // intentional: polling interval
  }
  return false;
}

/**
 * Wait for a specific locator to become visible.
 * Replaces: waitForTimeout(N) + expect(locator).toBeVisible()
 */
export async function waitForVisible(
  page: Page,
  selector: string,
  options?: { timeout?: number },
): Promise<void> {
  const timeout = options?.timeout ?? 10000;
  await page.locator(selector).first().waitFor({ state: 'visible', timeout });
}
