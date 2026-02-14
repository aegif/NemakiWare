import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright Configuration for NemakiWare React UI
 *
 * This configuration supports testing the NemakiWare CMIS UI with:
 * - Multiple browser engines (Chromium, Firefox, WebKit)
 * - Different viewport sizes for responsive testing
 * - Authentication scenarios with admin credentials
 * - Docker containerized backend integration
 *
 * REFACTORING NOTES (2026-01-26):
 * - Reduced default timeout from 120s to 60s for faster failure detection
 * - Added test categorization via grep patterns for selective test runs
 * - Optimized action/navigation timeouts for better balance
 * - Added testIgnore patterns for load tests (run separately)
 */
export default defineConfig({
  // Test directory
  testDir: './tests',

  // Output directory for test results
  outputDir: './test-results',

  // Global timeout for each test
  // Reduced from 120s to 60s - tests should fail faster if something is wrong
  // Individual tests can override with test.setTimeout() if needed
  timeout: 60 * 1000, // 60 seconds

  // Global timeout for expect() assertions
  expect: {
    timeout: 10000, // Increased from 5s to 10s for slow UI updates
  },

  // Run tests in files in parallel
  fullyParallel: true,

  // Fail the build on CI if you accidentally left test.only in the source code
  forbidOnly: !!process.env.CI,

  // Retry on CI only - reduced from 2 to 1 for faster feedback
  retries: process.env.CI ? 1 : 0,

  // Use single worker to prevent authentication race conditions
  // Multiple concurrent logins can cause session conflicts in NemakiWare
  // NOTE: Consider increasing workers when tests are properly isolated
  workers: 1,

  // Ignore load tests by default - run separately with --grep @load
  testIgnore: process.env.RUN_LOAD_TESTS ? [] : ['**/load/**'],

  // Reporter configuration
  reporter: [
    ['html', { outputFolder: './playwright-report', open: 'never' }],
    ['json', { outputFile: './playwright-report/results.json' }],
    ['junit', { outputFile: './playwright-report/results.xml' }],
    ['list'], // Console output
  ],

  // Shared settings for all projects
  use: {
    // Base URL for tests
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:8080',

    // HTTP Basic Authentication credentials
    httpCredentials: {
      username: process.env.PW_BASIC_USER || 'admin',
      password: process.env.PW_BASIC_PASS || 'admin',
    },

    extraHTTPHeaders: {
      Authorization: 'Basic ' + Buffer.from(`${process.env.PW_BASIC_USER || 'admin'}:${process.env.PW_BASIC_PASS || 'admin'}`).toString('base64'),
    },

    // Force headless mode for Docker/CI environments
    // This prevents GTK/GStreamer dependency issues in containers
    headless: process.env.CI || process.env.DOCKER_ENV ? true : undefined,

    // Collect trace when retrying the failed test
    trace: 'on-first-retry',

    // Capture screenshot on failure
    screenshot: 'only-on-failure',

    // Record video on failure
    video: 'retain-on-failure',

    // Default timeout for actions - reduced from 60s to 30s
    // Most UI actions should complete within 30 seconds
    actionTimeout: 30000,

    // Default timeout for navigation - reduced from 60s to 30s
    navigationTimeout: 30000,
  },

  // Configure projects for major browsers
  // OPTIMIZATION: Run only chromium by default, use --project flag for others
  projects: [
    {
      name: 'chromium',
      timeout: 120000, // Increased from 90s to 120s for login + table load + test operations
      use: {
        ...devices['Desktop Chrome'],
        // NemakiWare UI viewport settings
        viewport: { width: 1280, height: 720 },
      },
    },

    {
      name: 'firefox',
      timeout: 120000,
      use: {
        ...devices['Desktop Firefox'],
        viewport: { width: 1280, height: 720 },
      },
    },

    {
      name: 'webkit',
      timeout: 120000,
      use: {
        ...devices['Desktop Safari'],
        viewport: { width: 1280, height: 720 },
      },
    },

    // Mobile testing
    {
      name: 'Mobile Chrome',
      timeout: 120000,
      use: {
        ...devices['Pixel 5'],
      },
    },
    {
      name: 'Mobile Safari',
      timeout: 120000,
      use: {
        ...devices['iPhone 12'],
      },
    },

    // Tablet testing for document management interface
    {
      name: 'Tablet',
      timeout: 120000,
      use: {
        ...devices['iPad Pro'],
        viewport: { width: 1024, height: 768 },
      },
    },
  ],

  // Web server configuration for NemakiWare tests
  // Tests connect to the actual NemakiWare server on port 8080
  // The Vite dev server is not needed for E2E tests as we test the production build
  // webServer: [
  //   {
  //     command: 'npm run dev',
  //     port: 5173,
  //     reuseExistingServer: !process.env.CI,
  //     stdout: 'ignore',
  //     stderr: 'pipe',
  //   },
  // ],

  // Global setup ensures Keycloak is running for OIDC/SAML tests
  // CRITICAL (2025-12-14): This setup will start Keycloak if not running
  globalSetup: require.resolve('./tests/global-setup.ts'),
  // globalTeardown: require.resolve('./tests/global-teardown.ts'),
});
