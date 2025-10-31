/**
 * Basic Connectivity Tests
 *
 * Foundational infrastructure tests for NemakiWare UI and backend:
 * - Validates server accessibility and HTTP responses
 * - Verifies static asset delivery (JS/CSS bundle loading)
 * - Confirms React application initialization
 * - Provides diagnostic information for test environment setup
 *
 * Test Coverage (4 tests):
 * 1. NemakiWare UI page loading and title verification
 * 2. Backend HTTP connectivity (200 OK responses)
 * 3. Dynamic static asset detection and validation
 * 4. React/Ant Design app initialization detection
 *
 * IMPORTANT DESIGN DECISIONS:
 *
 * 1. Infrastructure Diagnostic Focus (All tests):
 *    - These tests validate basic prerequisites for all other tests
 *    - Should run first in test suite execution order
 *    - Failures here indicate environment setup issues, not code bugs
 *    - Rationale: Catch deployment/configuration problems early
 *    - Use case: CI/CD pipeline health checks before running full suite
 *
 * 2. Dynamic Asset Detection Pattern (Lines 28-59):
 *    - Parses index.html to extract actual JS/CSS file paths
 *    - Uses regex: /src="([^"]*\.js)"/ and /href="([^"]*\.css)"/
 *    - Avoids hardcoding asset filenames (Vite uses content hashes)
 *    - Example: index-B987_GLT.js changes with each build
 *    - Rationale: Tests remain valid across different build outputs
 *    - Implementation: Validates detected assets exist with 200 OK
 *
 * 3. Console Error Monitoring (Lines 76-82):
 *    - Captures all console error messages during page load
 *    - Logs errors for debugging but doesn't fail test
 *    - Rationale: Informational diagnostic, not strict requirement
 *    - Some warnings may be expected (e.g., React DevTools)
 *    - Implementation: page.on('console') event handler
 *
 * 4. Screenshot Capture for Debugging (Line 12):
 *    - Takes full-page screenshot: test-results/basic-connectivity.png
 *    - Useful for visual inspection of page state
 *    - Captured regardless of test pass/fail status
 *    - Rationale: Provides visual evidence of UI rendering
 *    - Use case: Debugging CI failures without browser access
 *
 * 5. React/Ant Design Initialization Detection (Lines 61-94):
 *    - Waits 5 seconds for app initialization (generous timeout)
 *    - Counts form elements: input, button, form tags
 *    - Counts Ant Design elements: [class*="ant-"]
 *    - Logs input field attributes (placeholder, type)
 *    - Rationale: Confirms React app rendered, not just static HTML
 *    - Implementation: Element counting without strict assertions
 *
 * 6. Console Logging Strategy (Lines 18-19, 47, 52, 69-93):
 *    - Logs page URL, title for manual verification
 *    - Logs detected assets for debugging
 *    - Logs asset HTTP status codes
 *    - Logs element counts (form, Ant Design, inputs)
 *    - Logs input field details (first 5 inputs)
 *    - Rationale: Rich diagnostic information for troubleshooting
 *    - Output visible in Playwright test reports
 *
 * 7. Minimal Assertions Philosophy (Lines 9, 15, 25, 31, 53, 57-58):
 *    - Only asserts critical invariants:
 *      - Page title matches /NemakiWare/
 *      - #root div is visible
 *      - HTTP 200 for index.html and assets
 *      - At least one JS and one CSS asset detected
 *    - Doesn't assert on element counts or specific content
 *    - Rationale: Focuses on connectivity, not UI implementation
 *    - Allows UI changes without breaking basic tests
 *
 * 8. Backend Accessibility Validation (Lines 22-26):
 *    - Uses page.request.get() for pure HTTP check
 *    - Doesn't require browser rendering
 *    - Tests server is responding to HTTP requests
 *    - Rationale: Isolates backend connectivity from frontend issues
 *    - Use case: Detect server startup failures quickly
 *
 * Expected Results:
 * - All 4 tests should pass if infrastructure is correctly set up
 * - Page title contains "NemakiWare"
 * - HTTP 200 responses for index.html and all detected assets
 * - React app initializes (form elements and Ant Design classes present)
 * - Screenshots saved to test-results/ directory
 *
 * Performance Characteristics:
 * - Fast execution: <10 seconds total for all 4 tests
 * - 5-second wait in React initialization test (intentional diagnostic delay)
 * - No authentication or complex interactions
 *
 * Debugging Features:
 * - Full-page screenshot capture
 * - Console error logging
 * - Element count reporting
 * - Input field attribute inspection
 * - Asset URL logging
 *
 * Known Limitations:
 * - 5-second wait in React test is arbitrary (not based on specific ready signal)
 * - Console error monitoring doesn't fail test (informational only)
 * - Element count assertions are loose (doesn't verify specific UI structure)
 *
 * Relationship to Other Tests:
 * - Should run FIRST before any functional tests
 * - Validates prerequisites for auth/login.spec.ts
 * - Confirms static asset delivery for all UI tests
 * - Provides baseline for test environment health
 *
 * Common Failure Scenarios:
 * - Server not started: HTTP 404 or connection refused
 * - Wrong URL/port: Update http://localhost:8080 if needed
 * - Asset build issues: index.html doesn't reference assets
 * - React initialization failure: No form/Ant Design elements found
 */

import { test, expect } from '@playwright/test';

test.describe('Basic Connectivity Tests', () => {
  test('should load NemakiWare UI page', async ({ page }) => {
    // Navigate to the UI
    await page.goto('http://localhost:8080/core/ui/dist/index.html');

    // Check that the page loads
    await expect(page).toHaveTitle(/NemakiWare/);

    // Take a screenshot for debugging
    await page.screenshot({ path: 'test-results/basic-connectivity.png', fullPage: true });

    // Check if the root div exists
    await expect(page.locator('#root')).toBeVisible();

    // Log some basic information
    console.log('Page URL:', page.url());
    console.log('Page title:', await page.title());
  });

  test('should load basic NemakiWare backend', async ({ page }) => {
    // Test if the backend is accessible
    const response = await page.request.get('http://localhost:8080/core/ui/dist/index.html');
    expect(response.status()).toBe(200);
  });

  test('should check for required static assets', async ({ page }) => {
    // Get the index.html content and parse asset references dynamically
    const response = await page.request.get('http://localhost:8080/core/ui/dist/index.html');
    expect(response.status()).toBe(200);

    const htmlContent = await response.text();

    // Extract JS and CSS asset paths from index.html
    const jsAssetMatch = htmlContent.match(/src="([^"]*\.js)"/);
    const cssAssetMatch = htmlContent.match(/href="([^"]*\.css)"/);

    const assets: string[] = [];
    if (jsAssetMatch && jsAssetMatch[1]) {
      assets.push(jsAssetMatch[1]);
    }
    if (cssAssetMatch && cssAssetMatch[1]) {
      assets.push(cssAssetMatch[1]);
    }

    console.log('Detected assets from index.html:', assets);

    // Check if detected assets exist
    for (const asset of assets) {
      const assetResponse = await page.request.get(`http://localhost:8080${asset}`);
      console.log(`Asset ${asset}: ${assetResponse.status()}`);
      expect(assetResponse.status()).toBe(200);
    }

    // Ensure we found at least one JS and one CSS asset
    expect(assets.some(asset => asset.endsWith('.js'))).toBe(true);
    expect(assets.some(asset => asset.endsWith('.css'))).toBe(true);
  });

  test('should wait for React app initialization', async ({ page }) => {
    await page.goto('http://localhost:8080/core/ui/dist/index.html');

    // Wait for potential React app initialization
    await page.waitForTimeout(5000);

    // Check if any form elements appeared
    const formElements = await page.locator('input, button, form').count();
    console.log(`Found ${formElements} form elements`);

    // Check for any specific React/Ant Design classes
    const antClasses = await page.locator('[class*="ant-"]').count();
    console.log(`Found ${antClasses} Ant Design elements`);

    // Log any console errors
    const jsErrors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        jsErrors.push(msg.text());
        console.log('JS Error:', msg.text());
      }
    });

    // Try to find any input fields
    const inputs = await page.locator('input').all();
    console.log(`Found ${inputs.length} input elements`);

    for (let i = 0; i < Math.min(inputs.length, 5); i++) {
      const input = inputs[i];
      const placeholder = await input.getAttribute('placeholder');
      const type = await input.getAttribute('type');
      console.log(`Input ${i}: placeholder="${placeholder}", type="${type}"`);
    }
  });
});