import { Page, expect } from '@playwright/test';

/**
 * General Test Helper Utilities for NemakiWare Playwright E2E Tests
 *
 * Comprehensive testing utilities providing document upload, network verification, and UI interaction support:
 * - Complete upload workflow with modal handling and verification
 * - Network monitoring and error detection (CMIS API, HTTP errors)
 * - JavaScript error collection with event listeners
 * - Ant Design component wait strategies
 * - Element stability detection for dynamic UIs
 * - File upload with flexible selector support (string or Locator)
 * - Timestamped screenshot capture for debugging
 * - Mobile browser support with force click
 * - Comprehensive console logging for CI/CD troubleshooting
 *
 * Usage Examples:
 * ```typescript
 * const testHelper = new TestHelper(page);
 *
 * // Upload document with automatic verification
 * const success = await testHelper.uploadDocument('test.txt', 'content', false);
 *
 * // Wait for Ant Design components to load
 * await testHelper.waitForAntdLoad();
 *
 * // Check for JavaScript errors
 * const errors = await testHelper.checkForJSErrors(1000);
 * if (errors.length > 0) {
 *   console.error('JavaScript errors:', errors);
 * }
 *
 * // Wait for CMIS API response
 * await testHelper.waitForCMISResponse(/\/core\/browser/);
 *
 * // Verify no network errors occurred
 * await testHelper.verifyNoNetworkErrors(2000);
 *
 * // Upload file to input element
 * await testHelper.uploadTestFile('input[type="file"]', 'test.txt', 'content');
 *
 * // Wait for element to stabilize (stop moving/resizing)
 * await testHelper.waitForElementStable('.ant-modal', 3000);
 *
 * // Take debugging screenshot
 * await testHelper.takeTimestampedScreenshot('test-failure');
 * ```
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Ant Design Component Wait Strategy (Lines 20-29):
 *    - Waits for multiple Ant Design components: .ant-layout, .ant-menu, .ant-table, .ant-form, .ant-btn
 *    - Uses querySelectorAll() to check if any components present (length > 0)
 *    - Timeout: 30000ms (30 seconds) - increased from 15000ms per 2025-10-12 code review
 *    - waitForFunction() ensures browser-side check runs in page context
 *    - Rationale: Ant Design CSS must load before components render properly
 *    - Code review feedback: Slower mobile browsers need generous timeouts
 *    - Multiple selector fallback: Tests pass if ANY Ant Design component loads
 *    - Advantage: Works across different page types (document list, admin pages, forms)
 *
 * 2. JavaScript Error Collection Pattern (Lines 42-71):
 *    - Sets up two event listeners: 'console' (console.error) and 'pageerror' (uncaught exceptions)
 *    - Collects errors in array during specified wait period (default 1000ms)
 *    - Cleans up listeners with page.off() after collection period
 *    - consoleHandler checks msg.type() === 'error' to filter error-level console messages
 *    - pageErrorHandler captures error.message from uncaught exceptions
 *    - Returns string array of all error messages collected
 *    - Rationale: Proactive JavaScript error detection prevents silent failures
 *    - Implementation: Event listener pattern ensures all errors captured during wait period
 *    - Advantage: Tests can fail early when JavaScript errors occur instead of timeout
 *
 * 3. CMIS API Response Pattern Matching (Lines 73-85):
 *    - Flexible pattern: string (includes check) OR RegExp (test check)
 *    - Default pattern: /\/core\/(atom|browser|rest)/ matches all CMIS bindings
 *    - Waits for response matching pattern AND HTTP 200 status
 *    - Timeout: 15000ms (15 seconds)
 *    - waitForResponse() callback checks both url.includes() and response.status()
 *    - Rationale: CMIS operations may take time (especially Browser Binding POST requests)
 *    - Implementation: Single wait covers all common CMIS endpoints
 *    - Advantage: Tests can verify backend operations completed before proceeding
 *
 * 4. Network Error Verification with Listener Pattern (Lines 87-117):
 *    - Sets up response listener to collect HTTP status ≥ 400 errors
 *    - Waits for networkidle state (all network activity completed)
 *    - Additional wait period (default 2000ms) to catch late-arriving errors
 *    - Cleans up listener with page.off() after verification
 *    - Throws Error with detailed list if any HTTP errors detected
 *    - Error details format: "status: url" on each line
 *    - Rationale: Backend errors may not manifest as test failures without explicit check
 *    - Implementation: Listener captures all responses during test execution
 *    - Advantage: Tests fail with actionable error information (status codes + URLs)
 *
 * 5. Flexible File Input Handling (Lines 119-142):
 *    - Accepts string selector OR Playwright Locator object
 *    - typeof check: if string → page.setInputFiles(), else → locator.setInputFiles()
 *    - Creates Buffer.from() with utf8 encoding
 *    - Fixed mimeType: 'text/plain' for test files
 *    - Rationale: Tests may have Locator or need to use string selector
 *    - Implementation: Dual code paths for both selector types
 *    - Advantage: Reusable across different test scenarios (pre-located input vs selector)
 *
 * 6. Element Stability Detection (Lines 144-171):
 *    - Checks element is visible first with expect().toBeVisible()
 *    - Uses getBoundingClientRect() to get position/size at two points in time
 *    - Waits 100ms between checks to detect movement/resizing
 *    - Compares top, left, width, height for stability
 *    - Timeout: configurable, default 3000ms (3 seconds)
 *    - Returns true only when all 4 properties unchanged
 *    - Rationale: Ant Design modals/drawers have CSS transition animations
 *    - Implementation: waitForFunction with async browser-side check
 *    - Advantage: Tests can wait for animations to complete before interaction
 *
 * 7. Complete Upload Workflow with Retry (Lines 173-242):
 *    - Phase 1: Click upload button (with mobile force click support)
 *    - Phase 2: Wait for modal '.ant-modal:has-text("ファイルアップロード")' 5s timeout
 *    - Phase 3: Select file with setInputFiles() Buffer creation
 *    - Phase 4: Fill file name input with placeholder*="ファイル名"
 *    - Phase 5: Click modal submit button with force click if mobile
 *    - Phase 6: Wait for modal to close (primary) OR manual cancel click (fallback)
 *    - Phase 7: Wait 5s for backend processing and table refresh
 *    - Phase 8: Verify document appears in table row with hasText filter
 *    - Returns boolean: true if found, false if not found
 *    - Comprehensive console.log() at each phase for debugging
 *    - Rationale: Upload is multi-step operation with timing dependencies
 *    - Implementation: Sequential phases with waits between each step
 *    - Advantage: Self-contained upload operation tests can use as black box
 *
 * 8. Comprehensive Console Logging (Lines 180-240):
 *    - Each upload phase logged with descriptive message
 *    - Success: "Document {fileName} found in table"
 *    - Failure: "Document {fileName} not found in table" + total rows count
 *    - Modal timeout: "Upload modal did not close within 20s - trying to close manually"
 *    - Rationale: CI/CD failures difficult to diagnose without phase visibility
 *    - Implementation: console.log() before each operation and after verification
 *    - Advantage: Failed tests show exact phase where upload workflow broke
 *
 * 9. Modal Close Timeout Handling with Manual Fallback (Lines 212-223):
 *    - Primary: Wait for modal hidden state with 20000ms timeout
 *    - Fallback: try-catch around wait, manual cancel button click if timeout
 *    - Cancel button selector: '.ant-modal button' filter hasText('キャンセル')
 *    - Additional 1000ms wait after manual cancel
 *    - Rationale: Upload modal may not close automatically if backend slow or error
 *    - Implementation: Defensive programming with graceful fallback
 *    - Advantage: Tests don't hang indefinitely waiting for modal close
 *
 * 10. Upload Success Verification with Table Check (Lines 225-242):
 *     - Waits 5000ms for backend processing and table refresh
 *     - Searches table rows with filter hasText(fileName)
 *     - Checks documentRow.count() > 0 for existence
 *     - On failure: Logs total row count for debugging
 *     - Returns boolean instead of throwing error (caller decides handling)
 *     - Rationale: Backend may process upload but UI table may not refresh
 *     - Implementation: Explicit table row check after sufficient wait
 *     - Advantage: Tests can verify end-to-end upload success (backend + UI)
 *
 * Expected Results:
 * - waitForPageLoad(): Page networkidle state reached, all AJAX requests completed
 * - waitForAntdLoad(): At least one Ant Design component present in DOM
 * - takeTimestampedScreenshot(): Screenshot saved to test-results/screenshots/ with ISO timestamp
 * - checkForJSErrors(): Array of error messages (empty if no errors)
 * - waitForCMISResponse(): CMIS API response received with HTTP 200 status
 * - verifyNoNetworkErrors(): No HTTP ≥400 errors detected, throws if errors found
 * - uploadTestFile(): File input filled with buffer content
 * - waitForElementStable(): Element position/size unchanged for 100ms
 * - uploadDocument(): true if document appears in table, false if not found
 *
 * Performance Characteristics:
 * - waitForPageLoad(): ~2-5s typical, ~10s max timeout
 * - waitForAntdLoad(): ~1-3s typical, ~30s max timeout
 * - takeTimestampedScreenshot(): ~500ms-2s depending on page size
 * - checkForJSErrors(): ~1s default wait period (configurable)
 * - waitForCMISResponse(): ~2-8s typical CMIS operations, ~15s max timeout
 * - verifyNoNetworkErrors(): ~2-4s networkidle + wait period
 * - uploadTestFile(): ~100-500ms instant file selection
 * - waitForElementStable(): ~100ms-3s depending on animation duration
 * - uploadDocument(): ~10-20s full workflow (modal + upload + verify)
 *
 * Debugging Features:
 * - Comprehensive console logging each upload phase
 * - Error collection with both console and pageerror events
 * - Network error details with status codes and URLs
 * - Document verification with row count logging
 * - Screenshot capture with timestamped filenames
 * - Modal close timeout detection with manual fallback
 * - Element stability detection logs in browser context
 *
 * Known Limitations:
 * - uploadDocument() Japanese text hardcoded (アップロード, ファイルアップロード, ファイル名, キャンセル)
 * - uploadTestFile() fixed mimeType 'text/plain' (no binary file support)
 * - waitForAntdLoad() assumes at least one of 5 Ant Design component classes present
 * - CMIS response pattern hardcoded to /core/ prefix (may not work with custom deployments)
 * - uploadDocument() assumes Ant Design modal structure (may break with custom modals)
 * - Element stability 100ms check interval may miss very fast animations
 * - Network error verification only checks status ≥400 (doesn't catch 200 with error body)
 * - 5s upload verification wait may be too short for very large files or slow backends
 *
 * Relationships to Other Utilities:
 * - Complements AuthHelper: AuthHelper handles login/logout, TestHelper handles operations
 * - Used by document-management.spec.ts for upload operations
 * - Used by document-properties-edit.spec.ts for file creation
 * - Used by custom-type-creation.spec.ts for test document creation
 * - uploadDocument() method specifically designed for NemakiWare CMIS document upload UI
 * - All test files can use checkForJSErrors() for proactive error detection
 * - waitForAntdLoad() commonly used in beforeEach hooks after AuthHelper.login()
 *
 * Common Failure Scenarios:
 * - uploadDocument() fails: Modal not found (upload button not clicked or modal structure changed)
 * - uploadDocument() fails: File input not found (modal structure changed)
 * - uploadDocument() fails: Modal close timeout (backend processing error or network issue)
 * - uploadDocument() fails: Document not in table (backend processing failed or table refresh issue)
 * - waitForAntdLoad() timeout: No Ant Design components loaded (wrong page or CSS loading failure)
 * - checkForJSErrors() misses errors: Wait period too short or errors occur after check
 * - waitForCMISResponse() timeout: CMIS operation taking >15s or endpoint not matching pattern
 * - verifyNoNetworkErrors() false positive: Legitimate 404s for optional resources flagged as errors
 * - waitForElementStable() timeout: Element has continuous animation or resize events
 */
export class TestHelper {
  constructor(private page: Page) {}

  /**
   * Wait for page to fully load including all AJAX requests
   */
  async waitForPageLoad(timeout: number = 10000): Promise<void> {
    await this.page.waitForLoadState('networkidle', { timeout });
  }

  /**
   * Wait for Ant Design components to fully render
   * Increased timeout for mobile browser compatibility
   */
  async waitForAntdLoad(): Promise<void> {
    // Wait for Ant Design CSS to load
    await this.page.waitForFunction(
      () => {
        const antdElements = document.querySelectorAll('.ant-layout, .ant-menu, .ant-table, .ant-form, .ant-btn');
        return antdElements.length > 0;
      },
      { timeout: 30000 }
    );
  }

  /**
   * Take a screenshot with timestamp
   */
  async takeTimestampedScreenshot(name: string): Promise<void> {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    await this.page.screenshot({
      path: `test-results/screenshots/${name}-${timestamp}.png`,
      fullPage: true,
    });
  }

  /**
   * Check for JavaScript errors on the page
   * Sets up listeners and waits for a period to collect errors
   */
  async checkForJSErrors(waitTimeMs: number = 1000): Promise<string[]> {
    const errors: string[] = [];

    const consoleHandler = (msg: any) => {
      if (msg.type() === 'error') {
        errors.push(msg.text());
      }
    };

    const pageErrorHandler = (error: any) => {
      errors.push(error.message);
    };

    // Set up listeners
    this.page.on('console', consoleHandler);
    this.page.on('pageerror', pageErrorHandler);

    // Wait for specified time to collect errors
    await this.page.waitForTimeout(waitTimeMs);

    // Clean up listeners
    this.page.off('console', consoleHandler);
    this.page.off('pageerror', pageErrorHandler);

    return errors;
  }

  /**
   * Wait for CMIS API call to complete
   */
  async waitForCMISResponse(urlPattern: string | RegExp = /\/core\/(atom|browser|rest)/): Promise<void> {
    await this.page.waitForResponse(
      (response) => {
        const url = response.url();
        const matches = typeof urlPattern === 'string' ? url.includes(urlPattern) : urlPattern.test(url);
        return matches && response.status() === 200;
      },
      { timeout: 15000 }
    );
  }

  /**
   * Verify no network errors occurred
   * Sets up listeners and waits for network activity to complete
   */
  async verifyNoNetworkErrors(waitTimeMs: number = 2000): Promise<void> {
    const responses: Array<{ url: string; status: number }> = [];

    const responseHandler = (response: any) => {
      if (response.status() >= 400) {
        responses.push({
          url: response.url(),
          status: response.status(),
        });
      }
    };

    // Set up listener
    this.page.on('response', responseHandler);

    // Wait for network activity to complete
    await this.page.waitForLoadState('networkidle');
    await this.page.waitForTimeout(waitTimeMs);

    // Clean up listener
    this.page.off('response', responseHandler);

    if (responses.length > 0) {
      const errorDetails = responses.map(r => `${r.status}: ${r.url}`).join('\n');
      throw new Error(`Network errors detected:\n${errorDetails}`);
    }
  }

  /**
   * Upload a test file
   */
  async uploadTestFile(fileInputSelector: string | any, fileName: string, content: string): Promise<void> {
    // Create a temporary file
    const buffer = Buffer.from(content, 'utf8');

    // Handle both string selectors and Locator objects
    if (typeof fileInputSelector === 'string') {
      // Set the file input using string selector
      await this.page.setInputFiles(fileInputSelector, {
        name: fileName,
        mimeType: 'text/plain',
        buffer: buffer,
      });
    } else {
      // Assume it's a Locator object
      await fileInputSelector.setInputFiles({
        name: fileName,
        mimeType: 'text/plain',
        buffer: buffer,
      });
    }
  }

  /**
   * Wait for element to be stable (not moving/changing)
   */
  async waitForElementStable(selector: string, timeout: number = 3000): Promise<void> {
    const element = this.page.locator(selector);
    await expect(element).toBeVisible();

    // Wait for element to stop moving/changing
    await this.page.waitForFunction(
      async (sel) => {
        const el = document.querySelector(sel);
        if (!el) return false;

        const rect1 = el.getBoundingClientRect();

        // Wait a bit and check again
        await new Promise(resolve => setTimeout(resolve, 100));

        const rect2 = el.getBoundingClientRect();
        return rect1.top === rect2.top &&
               rect1.left === rect2.left &&
               rect1.width === rect2.width &&
               rect1.height === rect2.height;
      },
      selector,
      { timeout }
    );
  }

  /**
   * Upload a document and wait for success
   * @param fileName Name of the file to upload
   * @param content Content of the file
   * @param isMobile Whether the browser is in mobile mode
   * @returns true if upload succeeded, false otherwise
   */
  async uploadDocument(fileName: string, content: string, isMobile: boolean = false): Promise<boolean> {
    console.log(`TestHelper: Uploading ${fileName}`);
    
    // Click upload button to open modal
    const uploadButton = this.page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});
    
    // Wait for upload modal to appear
    console.log('TestHelper: Waiting for upload modal...');
    await this.page.waitForSelector('.ant-modal:has-text("ファイルアップロード")', { timeout: 5000 });
    await this.page.waitForTimeout(500);

    console.log('TestHelper: Selecting file...');
    const fileInput = this.page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: fileName,
      mimeType: 'text/plain',
      buffer: Buffer.from(content, 'utf-8'),
    });
    await this.page.waitForTimeout(1000);

    console.log('TestHelper: Filling file name field...');
    const nameInput = this.page.locator('.ant-modal input[placeholder*="ファイル名"]');
    await nameInput.fill(fileName);
    await this.page.waitForTimeout(500);

    console.log('TestHelper: Clicking upload button in modal...');
    const modalUploadButton = this.page.locator('.ant-modal button[type="submit"]').filter({ hasText: 'アップロード' });
    await modalUploadButton.click(isMobile ? { force: true } : {});

    console.log('TestHelper: Waiting for upload to complete...');
    
    // Wait for modal to close (indicates upload request was sent)
    try {
      await this.page.waitForSelector('.ant-modal:has-text("ファイルアップロード")', { state: 'hidden', timeout: 20000 });
      console.log('TestHelper: Upload modal closed');
    } catch (e) {
      console.log('TestHelper: Upload modal did not close within 20s - trying to close manually');
      const closeButton = this.page.locator('.ant-modal button').filter({ hasText: 'キャンセル' }).first();
      if (await closeButton.count() > 0) {
        await closeButton.click();
        await this.page.waitForTimeout(1000);
      }
    }
    
    // Wait for document to appear in table
    console.log(`TestHelper: Waiting for document ${fileName} to appear in table...`);
    await this.page.waitForTimeout(5000);
    
    const documentRow = this.page.locator('.ant-table-tbody tr').filter({ hasText: fileName }).first();
    const docExists = await documentRow.count() > 0;
    
    if (docExists) {
      console.log(`TestHelper: Document ${fileName} found in table`);
      return true;
    }
    
    console.log(`TestHelper: Document ${fileName} not found in table`);
    const allRows = await this.page.locator('.ant-table-tbody tr').count();
    console.log(`TestHelper: Total rows in table: ${allRows}`);
    
    return false;
  }
}
