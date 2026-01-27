import { Page, expect } from '@playwright/test';
import { ApiHelper, generateTestId, generateTestFolderName, generateTestDocumentName } from './api-helper';

// Re-export API helper utilities for convenience
export { ApiHelper, generateTestId, generateTestFolderName, generateTestDocumentName };

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
   * Check if the current browser is mobile viewport
   * Mobile detection: Chromium browser with viewport width ≤ 414px
   *
   * @param browserName The browser name from Playwright (chromium, firefox, webkit)
   * @returns true if mobile viewport, false otherwise
   */
  isMobile(browserName: string): boolean {
    const viewportSize = this.page.viewportSize();
    return browserName === 'chromium' && viewportSize !== null && viewportSize.width <= 414;
  }

  /**
   * Close mobile sidebar if on mobile viewport
   * In mobile mode, the sidebar may overlay content and block interactions.
   * This method detects mobile viewport and closes the sidebar menu.
   *
   * Usage in test.beforeEach:
   * ```typescript
   * test.beforeEach(async ({ page, browserName }) => {
   *   const testHelper = new TestHelper(page);
   *   await testHelper.closeMobileSidebar(browserName);
   * });
   * ```
   *
   * @param browserName The browser name from Playwright (chromium, firefox, webkit)
   */
  async closeMobileSidebar(browserName: string): Promise<void> {
    if (!this.isMobile(browserName)) {
      return;
    }

    const menuToggle = this.page.locator('button[aria-label="menu-fold"], button[aria-label="menu-unfold"]');
    if (await menuToggle.count() > 0) {
      try {
        await menuToggle.first().click({ timeout: 3000 });
        await this.page.waitForTimeout(500);
      } catch (e) {
        // Menu toggle may not be available or already closed
      }
    }
  }

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
   *
   * STABILIZATION FIX (2025-01-17): Added closeAllOverlays at the beginning
   * to ensure any leftover modals from failed tests are cleaned up before upload.
   *
   * @param fileName Name of the file to upload
   * @param content Content of the file
   * @param isMobile Whether the browser is in mobile mode
   * @returns true if upload succeeded, false otherwise
   */
  async uploadDocument(fileName: string, content: string, isMobile: boolean = false): Promise<boolean> {
    console.log(`TestHelper: Uploading ${fileName}`);

    // STABILIZATION FIX: Close any open overlays before attempting upload
    await this.closeAllOverlays();

    // CRITICAL FIX (2026-01-21): Double-check for Error Boundary after closeAllOverlays
    // The error boundary may still be visible if closeAllOverlays didn't detect it properly
    const errorBoundaryCheck = this.page.locator('button').filter({ hasText: '再読み込み' }).first();
    if (await errorBoundaryCheck.count() > 0) {
      console.log('TestHelper: Error boundary still visible after closeAllOverlays - forcing page reload');
      await this.page.reload({ waitUntil: 'networkidle' });
      // Wait for UI to be ready after reload (not just a timeout)
      await this.waitForAntdLoad();
      await this.page.waitForTimeout(1000); // Additional stabilization time
    }

    // Click upload button to open modal
    const uploadButton = this.page.locator('button').filter({ hasText: 'アップロード' }).first();
    await uploadButton.click(isMobile ? { force: true } : {});
    
    // Wait for upload modal to appear
    console.log('TestHelper: Waiting for upload modal...');
    await this.page.waitForSelector('.ant-modal:has-text("ファイルアップロード")', { timeout: 5000 });
    await this.page.waitForTimeout(500);

    console.log('TestHelper: Selecting file...');
    // Ant Design Upload.Dragger creates a hidden file input
    // CRITICAL: Playwright's setInputFiles needs special handling for Ant Design
    // The file input must be made visible or we need to use evaluate
    let fileInput = this.page.locator('.ant-modal .ant-upload input[type="file"]').first();
    if (await fileInput.count() === 0) {
      fileInput = this.page.locator('.ant-modal input[type="file"]').first();
    }

    try {
      // First, ensure the file input is ready
      await fileInput.waitFor({ state: 'attached', timeout: 5000 });

      // Set files using Playwright's built-in method
      await fileInput.setInputFiles({
        name: fileName,
        mimeType: 'text/plain',
        buffer: Buffer.from(content, 'utf-8'),
      });

      // CRITICAL: Dispatch change event to trigger Ant Design's Upload onChange handler
      await fileInput.dispatchEvent('change');
      console.log('TestHelper: Dispatched change event');

      // Wait for Ant Design to process the file selection
      await this.page.waitForTimeout(1500);

      // Verify file was attached by checking for file list item or upload list
      const fileListItem = this.page.locator('.ant-modal .ant-upload-list-item, .ant-modal .ant-upload-list');
      const fileAttached = await fileListItem.count() > 0;
      console.log(`TestHelper: File attached: ${fileAttached}`);

      // Also check if the name field was auto-filled (indicates successful file selection)
      const nameField = this.page.locator('.ant-modal input[placeholder*="ファイル名"]').first();
      if (await nameField.count() > 0) {
        const autoFilledName = await nameField.inputValue();
        console.log(`TestHelper: Auto-filled name: ${autoFilledName}`);
      }

      console.log('TestHelper: File selected successfully');
    } catch (e) {
      console.log('TestHelper: File selection error:', e);
      return false;
    }
    await this.page.waitForTimeout(1500);

    console.log('TestHelper: Filling file name field...');
    // Wait for any auto-fill from file selection onChange
    await this.page.waitForTimeout(500);

    // Try multiple selectors for the name input
    let nameInput = this.page.locator('.ant-modal input[placeholder*="ファイル名"]').first();
    if (await nameInput.count() === 0) {
      nameInput = this.page.locator('.ant-modal input#name').first();
    }
    if (await nameInput.count() === 0) {
      nameInput = this.page.locator('.ant-modal .ant-form-item input').first();
    }

    // Clear first, then fill (in case auto-fill happened)
    await nameInput.clear();
    await nameInput.fill(fileName);
    console.log(`TestHelper: Name input value: ${await nameInput.inputValue()}`);
    await this.page.waitForTimeout(500);

    // CRITICAL FIX: Ensure the type is set to cmis:document (not cmis:folder)
    // This fixes the issue where the form state persists from folder creation
    console.log('TestHelper: Checking type selector...');
    // Find the type selector by looking for Select element in the modal
    // The form structure is: Form.Item with label "タイプ" > Select
    const typeSelector = this.page.locator('.ant-modal .ant-select').first();
    if (await typeSelector.count() > 0) {
      // Check current type value from the selection item inside the Select
      const selectionItem = typeSelector.locator('.ant-select-selection-item');
      const currentType = await selectionItem.textContent().catch(() => '');
      console.log(`TestHelper: Current type: "${currentType}"`);

      // If type is not a document type, change it to cmis:document
      if (currentType && currentType.includes('folder')) {
        console.log('TestHelper: Type is folder - changing to cmis:document...');
        await typeSelector.click();
        await this.page.waitForTimeout(500);

        // Wait for dropdown to appear
        const dropdown = this.page.locator('.ant-select-dropdown:visible');
        await dropdown.waitFor({ state: 'visible', timeout: 3000 }).catch(() => {});

        // Select cmis:document from dropdown
        const documentOption = this.page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: 'cmis:document' }).first();
        if (await documentOption.count() > 0) {
          await documentOption.click();
          await this.page.waitForTimeout(500);
          console.log('TestHelper: Type changed to cmis:document');
        } else {
          // Try first option that contains "document"
          const anyDocOption = this.page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: /document/i }).first();
          if (await anyDocOption.count() > 0) {
            await anyDocOption.click();
            await this.page.waitForTimeout(500);
            console.log('TestHelper: Type changed to a document type');
          } else {
            console.log('TestHelper: WARNING - Could not find cmis:document option');
            // Press Escape to close dropdown
            await this.page.keyboard.press('Escape');
          }
        }
      } else {
        console.log('TestHelper: Type is already a document type');
      }
    } else {
      console.log('TestHelper: Type selector not found in modal');
    }

    console.log('TestHelper: Clicking upload button in modal...');
    // Try multiple selectors for the upload button
    let modalUploadButton = this.page.locator('.ant-modal button[type="submit"]').filter({ hasText: /アップロード|Upload/i }).first();
    if (await modalUploadButton.count() === 0) {
      modalUploadButton = this.page.locator('.ant-modal button.ant-btn-primary').first();
    }

    // Verify button is enabled before clicking
    const isDisabled = await modalUploadButton.isDisabled().catch(() => true);
    if (isDisabled) {
      console.log('TestHelper: Upload button is disabled - form may be invalid');
      // Take screenshot for debugging
      await this.page.screenshot({ path: 'test-results/screenshots/upload-button-disabled.png', fullPage: true });
    }

    await modalUploadButton.click(isMobile ? { force: true } : {});

    console.log('TestHelper: Waiting for upload to complete...');

    // Wait a moment to see if there's an error message
    await this.page.waitForTimeout(2000);

    // Check for form validation errors
    const formErrors = await this.page.locator('.ant-modal .ant-form-item-explain-error').allTextContents();
    if (formErrors.length > 0) {
      console.log('TestHelper: Form validation errors:', formErrors);
      // Take screenshot for debugging
      await this.page.screenshot({ path: 'test-results/screenshots/upload-form-errors.png', fullPage: true });
    }

    // Check for API error alerts
    const alertError = await this.page.locator('.ant-modal .ant-alert-error').textContent().catch(() => null);
    if (alertError) {
      console.log('TestHelper: Upload API error:', alertError);
    }

    // Check if upload button is loading (indicates upload in progress)
    const isLoading = await this.page.locator('.ant-modal button[type="submit"].ant-btn-loading').count() > 0;
    console.log(`TestHelper: Upload button is loading: ${isLoading}`);

    // Wait for modal to close (indicates upload request was sent)
    try {
      await this.page.waitForSelector('.ant-modal:has-text("ファイルアップロード")', { state: 'hidden', timeout: 20000 });
      console.log('TestHelper: Upload modal closed');
    } catch (e) {
      console.log('TestHelper: Upload modal did not close within 20s - trying to close manually');
      // Take screenshot to see modal state
      await this.page.screenshot({ path: 'test-results/screenshots/upload-modal-timeout.png', fullPage: true });
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

  /**
   * Ensure a test document exists, creating it if necessary
   * This method is idempotent - safe to call multiple times
   *
   * STABILIZATION FIX (2025-01-17): Added closeAllOverlays at the beginning
   * to ensure any leftover modals from failed tests are cleaned up.
   *
   * @param fileName Name of the file to ensure exists
   * @param content Content of the file (used only if creating)
   * @param isMobile Whether the browser is in mobile mode
   * @returns true if document exists (or was created), false if creation failed
   */
  async ensureTestDocument(fileName: string, content: string = 'Test content', isMobile: boolean = false): Promise<boolean> {
    console.log(`TestHelper: Ensuring test document ${fileName} exists...`);

    // STABILIZATION FIX: Close any open overlays before checking/creating document
    await this.closeAllOverlays();

    // First check if document already exists in table
    const existingDoc = this.page.locator('.ant-table-tbody tr').filter({ hasText: fileName }).first();
    if (await existingDoc.count() > 0) {
      console.log(`TestHelper: Document ${fileName} already exists`);
      return true;
    }

    // Document doesn't exist, try to create it
    console.log(`TestHelper: Document ${fileName} not found, creating...`);

    // Check if upload button is available
    let uploadButton = this.page.locator('button').filter({ hasText: 'アップロード' }).first();
    if (await uploadButton.count() === 0) {
      uploadButton = this.page.locator('button').filter({ hasText: 'ファイルアップロード' }).first();
    }
    if (await uploadButton.count() === 0) {
      uploadButton = this.page.locator('button').filter({ has: this.page.locator('[data-icon="upload"]') }).first();
    }

    if (await uploadButton.count() === 0) {
      console.log('TestHelper: Upload button not found - cannot create document');
      return false;
    }

    // Use the uploadDocument method
    return await this.uploadDocument(fileName, content, isMobile);
  }

  /**
   * Navigate to documents page if not already there
   */
  async navigateToDocuments(): Promise<void> {
    const currentUrl = this.page.url();
    if (currentUrl.includes('/documents')) {
      console.log('TestHelper: Already on documents page');
      return;
    }

    console.log('TestHelper: Navigating to documents page...');
    const documentsMenuItem = this.page.locator('.ant-menu-item').filter({ hasText: 'ドキュメント' });
    if (await documentsMenuItem.count() > 0) {
      await documentsMenuItem.click();
      await this.page.waitForTimeout(2000);
    }
  }

  /**
   * Navigate into a folder by clicking on its name link
   *
   * CRITICAL FIX (2025-01-17): DocumentList uses SINGLE CLICK on folder name link
   * to navigate, NOT double-click on row. The folder name is rendered as a clickable
   * link that calls setSelectedFolderId() on click.
   *
   * @param folderName Name of the folder to navigate into
   * @param isMobile Whether the browser is in mobile mode
   * @returns true if navigation was successful, false otherwise
   */
  async navigateIntoFolder(folderName: string, isMobile: boolean = false): Promise<boolean> {
    console.log(`TestHelper: Navigating into folder: ${folderName}`);

    // Find the folder row
    const folderRow = this.page.locator('.ant-table-tbody tr').filter({ hasText: folderName }).first();
    if (await folderRow.count() === 0) {
      console.log(`TestHelper: Folder ${folderName} not found in table`);
      return false;
    }

    // Get current URL to verify navigation later
    const urlBefore = this.page.url();
    console.log(`TestHelper: URL before navigation: ${urlBefore}`);

    // Get the folder ID for URL verification
    const folderId = await folderRow.getAttribute('data-row-key').catch(() => null);
    console.log(`TestHelper: Folder ID: ${folderId}`);

    // CRITICAL FIX: Click the folder NAME LINK, not double-click the row
    // The folder name is a clickable <a> or <span> that triggers navigation
    const folderNameLink = folderRow.locator('a, span.ant-typography').filter({ hasText: folderName }).first();
    if (await folderNameLink.count() > 0) {
      await folderNameLink.click(isMobile ? { force: true } : {});
      console.log('TestHelper: Clicked folder name link');
    } else {
      // Fallback: try clicking the name cell directly
      const nameCell = folderRow.locator('td').nth(1); // Name is usually the second column
      await nameCell.click(isMobile ? { force: true } : {});
      console.log('TestHelper: Clicked folder name cell (fallback)');
    }

    // Wait for navigation to complete
    await this.page.waitForTimeout(2000);

    // Verify navigation was successful by checking:
    // 1. URL change (should contain folder ID)
    // 2. Table content change (folder should not be in current view)

    const urlAfter = this.page.url();
    console.log(`TestHelper: URL after navigation: ${urlAfter}`);

    // Check if URL contains the folder ID (indicates navigation)
    const urlContainsFolderId = folderId ? urlAfter.includes(folderId) : false;
    const urlChanged = urlAfter !== urlBefore;

    // Check if the folder is no longer visible in the table (we're inside it now)
    // Note: The folder name might still appear in breadcrumb, but not in table
    await this.page.waitForTimeout(500); // Wait for table to update
    const folderStillInTable = await this.page.locator('.ant-table-tbody tr').filter({ hasText: folderName }).count() > 0;

    console.log(`TestHelper: Navigation check - URL changed: ${urlChanged}, URL has folderId: ${urlContainsFolderId}, Folder still in table: ${folderStillInTable}`);

    // Navigation is successful if:
    // - URL contains the folder ID, OR
    // - URL changed AND folder is no longer in table
    const navigationSuccessful = urlContainsFolderId || (urlChanged && !folderStillInTable);

    if (navigationSuccessful) {
      console.log(`TestHelper: Successfully navigated into folder: ${folderName}`);
    } else {
      console.log(`TestHelper: Navigation into folder ${folderName} may have failed`);
      // Take screenshot for debugging
      await this.page.screenshot({ path: 'test-results/screenshots/folder-navigation-failed.png', fullPage: true });
    }

    return navigationSuccessful;
  }

  /**
   * Wait for document list page to be fully loaded with action buttons
   * This method ensures all UI elements are ready before tests interact with them
   *
   * CRITICAL FIX (2025-12-27): Many tests were dynamically skipping because:
   * - "Upload button not visible" (31 skips)
   * - "Folder creation button not visible" (10 skips)
   * - "Search input not visible" (12 skips)
   *
   * Root cause: Tests weren't waiting long enough for DocumentList component to render
   * Solution: Comprehensive wait for multiple UI elements before proceeding
   *
   * @param options.timeout Maximum wait time in ms (default 30000)
   * @param options.requireUploadButton Whether to require upload button (default true)
   * @param options.requireFolderButton Whether to require folder button (default true)
   * @param options.requireSearchInput Whether to require search input (default false)
   * @returns Object with availability status of each element
   */
  async waitForDocumentListReady(options: {
    timeout?: number;
    requireUploadButton?: boolean;
    requireFolderButton?: boolean;
    requireSearchInput?: boolean;
  } = {}): Promise<{
    uploadButtonVisible: boolean;
    folderButtonVisible: boolean;
    searchInputVisible: boolean;
    tableVisible: boolean;
  }> {
    const timeout = options.timeout ?? 30000;
    const startTime = Date.now();

    console.log('TestHelper: Waiting for document list page to be ready...');

    // Wait for Ant Design layout to be present
    await this.waitForAntdLoad();

    const result = {
      uploadButtonVisible: false,
      folderButtonVisible: false,
      searchInputVisible: false,
      tableVisible: false,
    };

    // Wait for table to be visible (primary indicator that page is loaded)
    try {
      await this.page.waitForSelector('.ant-table', { timeout: Math.max(1000, timeout - (Date.now() - startTime)) });
      result.tableVisible = true;
      console.log('TestHelper: Table is visible');
    } catch (e) {
      console.log('TestHelper: Table not found within timeout');
    }

    // Additional wait for dynamic content to render
    await this.page.waitForTimeout(1000);

    // Check upload button - multiple selector patterns (Japanese + English)
    const uploadButtonSelectors = [
      'button:has-text("ファイルアップロード")',
      'button:has-text("アップロード")',
      'button:has-text("Upload")',
      'button:has-text("File Upload")',
      'button:has([data-icon="upload"])',
    ];

    for (const selector of uploadButtonSelectors) {
      try {
        const button = this.page.locator(selector).first();
        if (await button.isVisible({ timeout: 2000 })) {
          result.uploadButtonVisible = true;
          console.log(`TestHelper: Upload button visible (${selector})`);
          break;
        }
      } catch (e) {
        // Try next selector
      }
    }

    // Check folder creation button - multiple selector patterns (Japanese + English)
    const folderButtonSelectors = [
      'button:has-text("フォルダ作成")',
      'button:has-text("新規フォルダ")',
      'button:has-text("Create Folder")',
      'button:has-text("New Folder")',
      'button:has([data-icon="folder-add"])',
      'button:has([data-icon="plus"])',
    ];

    for (const selector of folderButtonSelectors) {
      try {
        const button = this.page.locator(selector).first();
        if (await button.isVisible({ timeout: 2000 })) {
          result.folderButtonVisible = true;
          console.log(`TestHelper: Folder button visible (${selector})`);
          break;
        }
      } catch (e) {
        // Try next selector
      }
    }

    // Check search input (Japanese + English)
    const searchSelectors = [
      'input.search-input',
      'input[placeholder*="検索"]',
      'input[placeholder*="Search"]',
      '.ant-input-search input',
    ];

    for (const selector of searchSelectors) {
      try {
        const input = this.page.locator(selector).first();
        if (await input.isVisible({ timeout: 2000 })) {
          result.searchInputVisible = true;
          console.log(`TestHelper: Search input visible (${selector})`);
          break;
        }
      } catch (e) {
        // Try next selector
      }
    }

    console.log('TestHelper: Document list ready state:', result);
    return result;
  }

  /**
   * Get upload button locator with fallback selectors (Japanese + English)
   * Use this instead of hardcoded selectors in tests
   */
  async getUploadButton(): Promise<any | null> {
    const selectors = [
      'button:has-text("ファイルアップロード")',
      'button:has-text("アップロード")',
      'button:has-text("Upload")',
      'button:has-text("File Upload")',
      'button:has([data-icon="upload"])',
    ];

    for (const selector of selectors) {
      const button = this.page.locator(selector).first();
      if (await button.count() > 0) {
        return button;
      }
    }
    return null;
  }

  /**
   * Get folder creation button locator with fallback selectors (Japanese + English)
   */
  async getFolderButton(): Promise<any | null> {
    const selectors = [
      'button:has-text("フォルダ作成")',
      'button:has-text("新規フォルダ")',
      'button:has-text("Create Folder")',
      'button:has-text("New Folder")',
      'button:has([data-icon="folder-add"])',
    ];

    for (const selector of selectors) {
      const button = this.page.locator(selector).first();
      if (await button.count() > 0) {
        return button;
      }
    }
    return null;
  }

  /**
   * Delete a test document if it exists
   *
   * STABILIZATION FIX (2025-01-17): Added closeAllOverlays at the beginning
   * to ensure any leftover modals from failed tests are cleaned up before deletion.
   *
   * @param fileName Name of the file to delete. Must not contain single quotes (used in CMIS query).
   * @param isMobile Whether the browser is in mobile mode
   * @returns true if deleted (or didn't exist), false if deletion failed
   */
  async deleteTestDocument(fileName: string, _isMobile: boolean = false): Promise<boolean> {
    console.log(`TestHelper: Deleting test document ${fileName} via API...`);

    try {
      const apiHelper = new ApiHelper(this.page);

      // Query for document by name
      const safeName = fileName.replace(/'/g, "''");
      const query = `SELECT cmis:objectId FROM cmis:document WHERE cmis:name = '${safeName}'`;
      const docIds = await apiHelper.queryObjects(query);

      if (docIds.length === 0) {
        console.log(`TestHelper: Document ${fileName} not found - already deleted or never existed`);
        return true;
      }

      // Delete each matching document
      let allDeleted = true;
      for (const docId of docIds) {
        const deleted = await apiHelper.deleteObject(docId);
        if (deleted) {
          console.log(`TestHelper: Document ${fileName} (${docId}) deleted successfully via API`);
        } else {
          console.log(`TestHelper: Failed to delete document ${fileName} (${docId})`);
          allDeleted = false;
        }
      }

      return allDeleted;
    } catch (error) {
      console.log(`TestHelper: Error deleting document ${fileName}:`, error);
      return false;
    }
  }

  /**
   * Create a folder with proper waiting and error handling
   *
   * CRITICAL FIX (2025-12-27): This method addresses common folder creation failures:
   * - Don't rely on success message (fades out in 3 seconds)
   * - Use modal closure as primary success indicator
   * - Use robust button detection with multiple selectors
   * - Proper visibility waits for modal content
   *
   * STABILIZATION FIX (2025-01-17): Added closeAllOverlays at the beginning
   * to ensure any leftover modals from failed tests are cleaned up before folder creation.
   *
   * @param folderName Name of the folder to create
   * @param isMobile Whether the browser is in mobile mode
   * @returns true if folder was created successfully, false otherwise
   */
  async createFolder(folderName: string, isMobile: boolean = false): Promise<boolean> {
    console.log(`TestHelper: Creating folder: ${folderName}`);

    // STABILIZATION FIX: Close any open overlays before attempting folder creation
    await this.closeAllOverlays();

    // Get folder creation button
    const folderButton = await this.getFolderButton();
    if (!folderButton) {
      console.log('TestHelper: ❌ Folder creation button not found');
      return false;
    }

    await folderButton.click(isMobile ? { force: true } : {});

    // Wait for modal to appear with extended timeout
    const modal = this.page.locator('.ant-modal:not(.ant-modal-hidden)');
    try {
      await modal.waitFor({ state: 'visible', timeout: 10000 });
      console.log('TestHelper: Modal appeared');
    } catch {
      console.log('TestHelper: ❌ Modal did not appear');
      return false;
    }

    // Wait for modal content to fully render
    await this.page.waitForTimeout(1000);

    // Find and fill the name input - try specific placeholder first
    let nameInput = modal.locator('input[placeholder*="フォルダ名"]').first();
    if (await nameInput.count() === 0) {
      // Fallback to first input in modal
      nameInput = modal.locator('input').first();
      console.log('TestHelper: Using fallback input selector');
    }
    await nameInput.fill(folderName);
    console.log(`TestHelper: Input value: ${await nameInput.inputValue()}`);

    // Click the submit button - try multiple selectors
    const submitButton = modal.locator('button[type="submit"]');
    const primaryButton = modal.locator('button.ant-btn-primary');

    let buttonClicked = false;

    if (await submitButton.count() > 0) {
      console.log('TestHelper: Found submit button by type="submit"');
      await submitButton.first().click();
      buttonClicked = true;
    } else if (await primaryButton.count() > 0) {
      console.log('TestHelper: Found primary button');
      await primaryButton.first().click();
      buttonClicked = true;
    } else {
      // Last resort: find button with exact text
      const textButton = modal.getByRole('button', { name: '作成' });
      if (await textButton.count() > 0) {
        console.log('TestHelper: Found button by role with name "作成"');
        await textButton.click();
        buttonClicked = true;
      }
    }

    if (!buttonClicked) {
      console.log('TestHelper: ❌ No submit button found');
      return false;
    }

    // Wait for modal to close (primary success indicator)
    // Don't rely on success message (fades out in 3 seconds)
    let success = false;

    try {
      await expect(modal).not.toBeVisible({ timeout: 15000 });
      console.log(`TestHelper: ✅ Folder created: ${folderName} (modal closed)`);
      success = true;
    } catch {
      // Check for error messages
      const errorMsg = await this.page.locator('.ant-message-error').textContent().catch(() => null);
      const formError = await modal.locator('.ant-form-item-explain-error').textContent().catch(() => null);

      if (errorMsg || formError) {
        console.log(`TestHelper: ❌ Folder creation failed: ${errorMsg || formError}`);
      } else {
        // Modal might have closed but expect failed
        const modalStillOpen = await modal.isVisible().catch(() => false);
        if (!modalStillOpen) {
          console.log(`TestHelper: ✅ Folder created: ${folderName} (modal no longer visible)`);
          success = true;
        } else {
          console.log('TestHelper: ❌ Folder creation failed: Modal still open');
        }
      }
    }

    if (!success) {
      return false;
    }

    // Wait for table to refresh with new folder
    const maxWaitTime = 10000;
    const startTime = Date.now();
    let folderInTable = false;

    while (Date.now() - startTime < maxWaitTime) {
      folderInTable = await this.page.locator('.ant-table-tbody tr').filter({ hasText: folderName }).isVisible().catch(() => false);
      if (folderInTable) {
        console.log(`TestHelper: Folder "${folderName}" visible in table (after ${Date.now() - startTime}ms)`);
        break;
      }
      await this.page.waitForTimeout(500);
    }

    if (!folderInTable) {
      console.log(`TestHelper: Folder "${folderName}" NOT visible in table after ${maxWaitTime}ms`);

      // CRITICAL FIX (2025-12-27): If folder not visible, try to force refresh by reloading the page
      console.log(`TestHelper: Attempting page reload to force refresh...`);
      await this.page.reload();
      await this.page.waitForSelector('.ant-table', { timeout: 10000 }).catch(() => null);
      await this.page.waitForTimeout(2000);

      // Check again after reload
      folderInTable = await this.page.locator('.ant-table-tbody tr').filter({ hasText: folderName }).isVisible().catch(() => false);
      if (folderInTable) {
        console.log(`TestHelper: Folder "${folderName}" visible after page reload`);
      } else {
        console.log(`TestHelper: Folder "${folderName}" still NOT visible after page reload`);
      }
    }

    return true;
  }

  /**
   * Close all open modals, drawers, and overlay elements
   *
   * CRITICAL FIX (2025-12-27): This method addresses the "ant-modal-wrap intercepts pointer events" issue
   * that occurs during test cleanup. Modals left open from failed tests can block subsequent interactions.
   *
   * STABILIZATION FIX (2025-01-17): Improved order of operations:
   * 1. FIRST remove blocking elements via JavaScript (to unblock pointer events)
   * 2. Press Escape multiple times
   * 3. Try clicking close buttons with force option
   * 4. Final JavaScript cleanup
   * 5. Verify all overlays are closed
   *
   * @returns Number of overlays closed
   */
  async closeAllOverlays(): Promise<number> {
    let closedCount = 0;

    // Step 0: Check for Error Boundary error and recover by reloading
    // This handles cases where React throws "Failed to execute 'removeChild' on 'Node'" errors
    const errorBoundaryReloadButton = this.page.locator('button').filter({ hasText: '再読み込み' }).first();
    if (await errorBoundaryReloadButton.count() > 0) {
      console.log('TestHelper: Error boundary detected - clicking reload button to recover');
      try {
        await errorBoundaryReloadButton.click({ timeout: 3000 });
        // Wait for page to reload and stabilize
        await this.page.waitForLoadState('networkidle', { timeout: 30000 });
        await this.page.waitForTimeout(2000);
        closedCount++;
        console.log('TestHelper: Page reloaded after error boundary recovery');
      } catch (e) {
        console.log('TestHelper: Error boundary reload failed, attempting page reload');
        await this.page.reload({ waitUntil: 'networkidle' });
        // Wait for UI to be ready after reload (not just a timeout)
        await this.waitForAntdLoad();
        closedCount++;
      }
    }

    // ===== UI Operations First (React-safe approach) =====
    // Try UI interactions before resorting to JavaScript DOM manipulation
    // This preserves React's internal state consistency

    // Step 1: Press Escape multiple times to close any modal/drawer/popover
    // This is the safest way to close overlays as it triggers proper React event handlers
    for (let i = 0; i < 3; i++) {
      await this.page.keyboard.press('Escape');
      await this.page.waitForTimeout(200);
    }

    // Step 2: Try clicking modal close buttons
    const modalCloseButtons = this.page.locator('.ant-modal:not(.ant-modal-hidden) .ant-modal-close');
    const modalCloseCount = await modalCloseButtons.count();
    for (let i = 0; i < modalCloseCount; i++) {
      try {
        await modalCloseButtons.nth(i).click({ force: true, timeout: 2000 });
        closedCount++;
        await this.page.waitForTimeout(300);
      } catch (e) {
        // Button might have already been closed
      }
    }

    // Step 3: Try clicking cancel buttons in modals
    const cancelButtons = this.page.locator('.ant-modal:not(.ant-modal-hidden) button').filter({ hasText: /キャンセル|Cancel|閉じる|Close/i });
    const cancelCount = await cancelButtons.count();
    for (let i = 0; i < cancelCount; i++) {
      try {
        await cancelButtons.nth(i).click({ force: true, timeout: 2000 });
        closedCount++;
        await this.page.waitForTimeout(300);
      } catch (e) {
        // Button might have already been closed
      }
    }

    // Step 4: Close drawers via UI
    const drawerCloseButtons = this.page.locator('.ant-drawer:not(.ant-drawer-hidden) .ant-drawer-close');
    const drawerCloseCount = await drawerCloseButtons.count();
    for (let i = 0; i < drawerCloseCount; i++) {
      try {
        await drawerCloseButtons.nth(i).click({ force: true, timeout: 2000 });
        closedCount++;
        await this.page.waitForTimeout(300);
      } catch (e) {
        // Drawer might have already closed
      }
    }

    // Step 5: Wait for any animations to complete after UI operations
    await this.page.waitForTimeout(500);

    // ===== JavaScript Cleanup (Last Resort) =====
    // Only use DOM manipulation if UI operations failed to close overlays
    // This risks React state inconsistency but is necessary for stuck overlays

    // Step 6: Check if overlays still remain
    const remainingModalMasks = await this.page.locator('.ant-modal-mask').count();
    const remainingModalWraps = await this.page.locator('.ant-modal-wrap').count();
    const remainingDrawerMasks = await this.page.locator('.ant-drawer-mask').count();

    if (remainingModalMasks > 0 || remainingModalWraps > 0 || remainingDrawerMasks > 0) {
      console.log(`TestHelper: UI operations did not fully close overlays (masks: ${remainingModalMasks}, wraps: ${remainingModalWraps}, drawers: ${remainingDrawerMasks}). Using JavaScript cleanup as fallback.`);

      // Step 7: JavaScript cleanup for stuck overlays
      const jsRemoved = await this.page.evaluate(() => {
        let removed = 0;

        // Remove modal masks (they block pointer events)
        document.querySelectorAll('.ant-modal-mask').forEach((el) => {
          if (el.parentNode) {
            el.parentNode.removeChild(el);
            removed++;
          }
        });

        // Remove modal wraps and roots
        document.querySelectorAll('.ant-modal-wrap, .ant-modal-root').forEach((el) => {
          if (el.parentNode) {
            el.parentNode.removeChild(el);
            removed++;
          }
        });

        // Remove drawer elements
        document.querySelectorAll('.ant-drawer, .ant-drawer-mask').forEach((el) => {
          if (el.parentNode) {
            el.parentNode.removeChild(el);
            removed++;
          }
        });

        // Remove popconfirms and popovers
        document.querySelectorAll('.ant-popconfirm, .ant-popover').forEach((el) => {
          if (el.parentNode) {
            el.parentNode.removeChild(el);
            removed++;
          }
        });

        // Reset body styles
        document.body.style.overflow = '';
        document.body.style.paddingRight = '';
        document.body.classList.remove('ant-scrolling-effect');

        return removed;
      });
      closedCount += jsRemoved;

      // Step 8: Final verification
      const stillRemaining = await this.page.locator('.ant-modal-wrap, .ant-modal-mask, .ant-drawer-mask').count();
      if (stillRemaining > 0) {
        console.log(`TestHelper: WARNING - ${stillRemaining} overlay elements still present after JavaScript cleanup`);
        // Absolute last resort - force remove
        await this.page.evaluate(() => {
          document.querySelectorAll('.ant-modal-wrap, .ant-modal-mask, .ant-drawer-mask').forEach((el) => {
            el.remove();
          });
        });
      }
    } else {
      // UI operations were sufficient - just reset body styles as a precaution
      await this.page.evaluate(() => {
        document.body.style.overflow = '';
        document.body.style.paddingRight = '';
        document.body.classList.remove('ant-scrolling-effect');
      });
    }

    if (closedCount > 0) {
      console.log(`TestHelper: Closed ${closedCount} overlay elements`);
    }

    return closedCount;
  }

  /**
   * Delete a test folder if it exists
   *
   * STABILIZATION FIX (2025-01-17): Added closeAllOverlays at the beginning
   * to ensure any leftover modals from failed tests are cleaned up before deletion.
   *
   * @param folderName Name of the folder to delete. Must not contain single quotes (used in CMIS query).
   * @param isMobile Whether the browser is in mobile mode
   * @returns true if deleted (or didn't exist), false if deletion failed
   */
  async deleteTestFolder(folderName: string, _isMobile: boolean = false): Promise<boolean> {
    console.log(`TestHelper: Deleting test folder ${folderName} via API...`);

    try {
      const apiHelper = new ApiHelper(this.page);

      // Query for folder by name
      const safeName = folderName.replace(/'/g, "''");
      const query = `SELECT cmis:objectId FROM cmis:folder WHERE cmis:name = '${safeName}'`;
      const folderIds = await apiHelper.queryObjects(query);

      if (folderIds.length === 0) {
        console.log(`TestHelper: Folder ${folderName} not found - already deleted or never existed`);
        return true;
      }

      // Delete each matching folder (including contents)
      let allDeleted = true;
      for (const folderId of folderIds) {
        const deleted = await apiHelper.deleteFolderTree(folderId);
        if (deleted) {
          console.log(`TestHelper: Folder ${folderName} (${folderId}) deleted successfully via API`);
        } else {
          console.log(`TestHelper: Failed to delete folder ${folderName} (${folderId})`);
          allDeleted = false;
        }
      }

      return allDeleted;
    } catch (error) {
      console.log(`TestHelper: Error deleting folder ${folderName}:`, error);
      return false;
    }
  }
}
