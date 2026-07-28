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

/**
 * Navigate to the full-text search page and wait until it OWNS the DOM.
 *
 * The two pages both offer a "search" control, and for a few dozen milliseconds
 * after the route changes BOTH are mounted: React has rendered `SearchResults`
 * while the outgoing `DocumentList` has not been torn down yet. A selector such as
 * `button.search-button, button:has-text("検索")` matches the document list's button
 * during that window, so `.click()` lands on an element React is about to remove and
 * fails with "element was detached from the DOM, retrying" until the test times out.
 * The button never comes back, so retrying cannot help.
 *
 * Waiting for the search page's OWN hook (`.search-submit-button`, which the document
 * list does not have) makes the handover observable instead of assumed.
 */
export async function gotoSearchPage(page: Page, options?: { timeout?: number }): Promise<void> {
  const timeout = options?.timeout ?? 30000;

  // A modal left open by the previous step swallows the menu click: Ant Design's mask
  // covers the sider, the SPA never navigates, and every later wait fails on a page that
  // is still the document list. Clear it first rather than discover it 30 seconds later.
  for (let i = 0; i < 3; i++) {
    if (await page.locator('.ant-modal-wrap:visible, .ant-drawer-open').count() === 0) break;
    await page.keyboard.press('Escape');
    await waitForRender(page, { timeout: 1000 });
  }

  if (!page.url().includes('#/search')) {
    const menuItem = page.locator('.ant-menu-item').filter({ hasText: '検索' });
    if (await menuItem.count() === 1) {
      await menuItem.click().catch(() => undefined);
    }
    // In-app hash navigation as the fallback. A full page.goto() reload would drop the
    // in-memory auth token and land on the login screen, where nothing this function
    // waits for can ever appear — a 30s timeout that says nothing about the search page.
    if (!page.url().includes('#/search')) {
      await page.evaluate(() => { window.location.hash = '#/search'; });
    }
  }

  await page.waitForFunction(() => window.location.hash.includes('/search'), undefined, { timeout });
  await page.locator('button.search-submit-button').waitFor({ state: 'visible', timeout });
  // The document list's own button must be GONE, not merely overlapped.
  await page.locator('button.search-button').waitFor({ state: 'detached', timeout }).catch(() => undefined);
  await waitForUiStable(page);
}

/** The search page's submit control. Never matches the document list's inline search. */
export function searchPageSubmitButton(page: Page) {
  return page.locator('button.search-submit-button');
}
