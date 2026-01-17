/**
 * Test Constants and Utilities for Complex Scenario Tests
 *
 * This file provides:
 * - Configurable timeout constants for different operations
 * - Solr indexing wait strategies with polling/retry
 * - Cleanup utilities with failure logging
 * - i18n-aware selector patterns
 */

import { Page, expect } from '@playwright/test';

// ============================================================================
// Timeout Constants (in milliseconds)
// ============================================================================

export const TIMEOUTS = {
  /** Short wait for UI animations and transitions */
  UI_ANIMATION: 500,

  /** Medium wait for page navigation and component loading */
  PAGE_LOAD: 2000,

  /** Wait for Solr indexing to complete (initial) */
  SOLR_INDEX_INITIAL: 3000,

  /** Maximum wait for Solr indexing with retry */
  SOLR_INDEX_MAX: 15000,

  /** Wait for modal to appear */
  MODAL_APPEAR: 5000,

  /** Wait for modal to close */
  MODAL_CLOSE: 20000,

  /** Wait for table to load */
  TABLE_LOAD: 15000,

  /** Wait for CMIS API response */
  CMIS_RESPONSE: 15000,

  /** Wait for Ant Design components to load */
  ANTD_LOAD: 30000,
} as const;

// ============================================================================
// Retry Configuration
// ============================================================================

export const RETRY_CONFIG = {
  /** Intervals for Solr polling (in ms) */
  SOLR_POLL_INTERVALS: [1000, 2000, 3000, 5000] as const,

  /** Maximum retries for search operations */
  SEARCH_MAX_RETRIES: 5,

  /** Interval between search retries (in ms) */
  SEARCH_RETRY_INTERVAL: 2000,
} as const;

// ============================================================================
// i18n Selector Patterns
// ============================================================================

/**
 * Regex patterns for i18n-aware text matching (Japanese/English)
 */
export const I18N_PATTERNS = {
  /** Admin menu */
  ADMIN: /管理|Admin/i,

  /** Type Management menu item */
  TYPE_MANAGEMENT: /タイプ管理|Type Management/i,

  /** Documents menu item */
  DOCUMENTS: /ドキュメント|Documents/i,

  /** Search menu item */
  SEARCH: /検索|Search/i,

  /** Archive/Trash menu item */
  ARCHIVE: /アーカイブ|Archive|ゴミ箱|Trash/i,

  /** Create/New button */
  CREATE: /新規|作成|Create|New/i,

  /** Delete button */
  DELETE: /削除|Delete/i,

  /** Save button */
  SAVE: /保存|Save/i,

  /** Cancel button */
  CANCEL: /キャンセル|Cancel/i,

  /** OK/Confirm button */
  CONFIRM: /OK|確認|はい|Yes/i,

  /** Upload button */
  UPLOAD: /アップロード|Upload/i,

  /** Properties tab */
  PROPERTIES: /プロパティ|Properties/i,

  /** Version History */
  VERSION_HISTORY: /バージョン履歴|Version History|履歴/i,

  /** Permissions/ACL */
  PERMISSIONS: /権限|ACL|Permissions/i,

  /** Restore button */
  RESTORE: /復元|Restore|元に戻す/i,

  /** Move button */
  MOVE: /移動|Move/i,

  /** Folder creation */
  CREATE_FOLDER: /フォルダ作成|新規フォルダ|Create Folder/i,

  /** Preview button */
  PREVIEW: /プレビュー|Preview|表示/i,

  /** Secondary type/Aspect */
  SECONDARY_TYPE: /アスペクト|セカンダリ|Secondary|Aspect/i,

  /** Break inheritance */
  BREAK_INHERITANCE: /継承を解除|Break Inheritance|独自の権限/i,

  /** Permanent delete */
  PERMANENT_DELETE: /完全削除|Permanent Delete|完全に削除/i,
} as const;

// ============================================================================
// Solr Wait Utilities
// ============================================================================

/**
 * Wait for Solr indexing with polling/retry strategy
 *
 * @param page - Playwright page object
 * @param searchAction - Function that performs the search
 * @param verifyAction - Function that verifies the expected result
 * @param options - Configuration options
 * @returns true if verification passed, false otherwise
 */
export async function waitForSolrIndexWithRetry(
  page: Page,
  searchAction: () => Promise<void>,
  verifyAction: () => Promise<boolean>,
  options: {
    maxTimeout?: number;
    intervals?: readonly number[];
    description?: string;
  } = {}
): Promise<boolean> {
  const {
    maxTimeout = TIMEOUTS.SOLR_INDEX_MAX,
    intervals = RETRY_CONFIG.SOLR_POLL_INTERVALS,
    description = 'Solr indexing',
  } = options;

  const startTime = Date.now();
  let attempt = 0;

  console.log(`[Solr Wait] Starting ${description} with max timeout ${maxTimeout}ms`);

  // Initial wait for indexing
  await page.waitForTimeout(TIMEOUTS.SOLR_INDEX_INITIAL);

  while (Date.now() - startTime < maxTimeout) {
    attempt++;
    console.log(`[Solr Wait] Attempt ${attempt} for ${description}`);

    try {
      await searchAction();
      const result = await verifyAction();

      if (result) {
        console.log(`[Solr Wait] ${description} succeeded on attempt ${attempt}`);
        return true;
      }
    } catch (error) {
      console.log(`[Solr Wait] Attempt ${attempt} failed: ${error}`);
    }

    // Wait before next retry
    const intervalIndex = Math.min(attempt - 1, intervals.length - 1);
    const waitTime = intervals[intervalIndex];
    console.log(`[Solr Wait] Waiting ${waitTime}ms before retry`);
    await page.waitForTimeout(waitTime);
  }

  console.log(`[Solr Wait] ${description} failed after ${attempt} attempts`);
  return false;
}

// ============================================================================
// Cleanup Utilities
// ============================================================================

/**
 * Cleanup result tracking
 */
export interface CleanupResult {
  success: boolean;
  failedItems: string[];
}

/**
 * Execute cleanup with failure logging
 *
 * @param items - Array of items to clean up with their cleanup functions
 * @returns CleanupResult with success status and failed items
 */
export async function executeCleanupWithLogging(
  items: Array<{
    id: string;
    type: 'document' | 'folder' | 'type';
    cleanup: () => Promise<void>;
  }>
): Promise<CleanupResult> {
  const failedItems: string[] = [];

  // Sort items by dependency order: documents -> folders -> types
  const sortedItems = [...items].sort((a, b) => {
    const order = { document: 0, folder: 1, type: 2 };
    return order[a.type] - order[b.type];
  });

  for (const item of sortedItems) {
    try {
      console.log(`[Cleanup] Deleting ${item.type}: ${item.id}`);
      await item.cleanup();
      console.log(`[Cleanup] Successfully deleted ${item.type}: ${item.id}`);
    } catch (error) {
      const errorMsg = `${item.type}: ${item.id}`;
      failedItems.push(errorMsg);
      console.error(`[Cleanup] Failed to delete ${errorMsg}`, error);
    }
  }

  if (failedItems.length > 0) {
    console.warn('=== CLEANUP FAILURES - Manual cleanup required ===');
    console.warn(failedItems.join('\n'));
    console.warn('================================================');
  }

  return {
    success: failedItems.length === 0,
    failedItems,
  };
}

// ============================================================================
// Mobile Detection Utility
// ============================================================================

/**
 * Check if the current browser is a mobile browser
 *
 * @param page - Playwright page object
 * @param browserName - Browser name from test context
 * @returns true if mobile browser, false otherwise
 */
export function isMobileBrowser(page: Page, browserName: string): boolean {
  const viewportSize = page.viewportSize();
  return browserName === 'chromium' && viewportSize !== null && viewportSize.width <= 414;
}

/**
 * Get click options based on mobile detection
 *
 * @param isMobile - Whether the browser is mobile
 * @returns Click options object
 */
export function getClickOptions(isMobile: boolean): { force?: boolean } {
  return isMobile ? { force: true } : {};
}

// ============================================================================
// Element Interaction Utilities
// ============================================================================

/**
 * Click an element with i18n-aware text matching
 *
 * @param page - Playwright page object
 * @param selector - Base selector (e.g., '.ant-menu-item')
 * @param textPattern - Regex pattern for text matching
 * @param isMobile - Whether to use force click
 * @returns true if element was clicked, false if not found
 */
export async function clickElementWithI18n(
  page: Page,
  selector: string,
  textPattern: RegExp,
  isMobile: boolean
): Promise<boolean> {
  const element = page.locator(selector).filter({ hasText: textPattern }).first();

  if ((await element.count()) > 0) {
    await element.click(getClickOptions(isMobile));
    return true;
  }

  return false;
}

/**
 * Wait for and click a button with i18n-aware text matching
 *
 * @param page - Playwright page object
 * @param textPattern - Regex pattern for button text
 * @param isMobile - Whether to use force click
 * @param timeout - Maximum wait time
 * @returns true if button was clicked, false if not found
 */
export async function clickButtonWithI18n(
  page: Page,
  textPattern: RegExp,
  isMobile: boolean,
  timeout: number = TIMEOUTS.MODAL_APPEAR
): Promise<boolean> {
  const button = page.locator('button').filter({ hasText: textPattern }).first();

  try {
    await button.waitFor({ state: 'visible', timeout });
    await button.click(getClickOptions(isMobile));
    return true;
  } catch {
    return false;
  }
}
