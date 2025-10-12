import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright Configuration for NemakiWare React UI
 *
 * This configuration supports testing the NemakiWare CMIS UI with:
 * - Multiple browser engines (Chromium, Firefox, WebKit)
 * - Different viewport sizes for responsive testing
 * - Authentication scenarios with admin credentials
 * - Docker containerized backend integration
 */
export default defineConfig({
  // Test directory
  testDir: './tests',

  // Output directory for test results
  outputDir: './test-results',

  // Global timeout for each test (extended for cleanup operations)
  timeout: 60 * 1000, // 60 seconds

  // Global timeout for expect() assertions
  expect: {
    timeout: 5000,
  },

  // Run tests in files in parallel
  fullyParallel: true,

  // Fail the build on CI if you accidentally left test.only in the source code
  forbidOnly: !!process.env.CI,

  // Retry on CI only
  retries: process.env.CI ? 2 : 0,

  // Opt out of parallel tests on CI
  workers: process.env.CI ? 1 : undefined,

  // Reporter configuration
  reporter: [
    ['html', { outputFolder: './playwright-report' }],
    ['json', { outputFile: './test-results.json' }],
    ['list'], // Console output
  ],

  // Shared settings for all projects
  use: {
    // Base URL for tests
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:8080',

    // Force headless mode for Docker/CI environments
    // This prevents GTK/GStreamer dependency issues in containers
    headless: process.env.CI || process.env.DOCKER_ENV ? true : undefined,

    // Collect trace when retrying the failed test
    trace: 'on-first-retry',

    // Capture screenshot on failure
    screenshot: 'only-on-failure',

    // Record video on failure
    video: 'retain-on-failure',

    // Default timeout for actions
    actionTimeout: 10000,

    // Default timeout for navigation
    navigationTimeout: 30000,
  },

  // Configure projects for major browsers
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // NemakiWare UI viewport settings
        viewport: { width: 1280, height: 720 },
      },
    },

    {
      name: 'firefox',
      use: {
        ...devices['Desktop Firefox'],
        viewport: { width: 1280, height: 720 },
      },
    },

    {
      name: 'webkit',
      use: {
        ...devices['Desktop Safari'],
        viewport: { width: 1280, height: 720 },
      },
    },

    // Mobile testing
    {
      name: 'Mobile Chrome',
      use: {
        ...devices['Pixel 5'],
      },
    },
    {
      name: 'Mobile Safari',
      use: {
        ...devices['iPhone 12'],
      },
    },

    // Tablet testing for document management interface
    {
      name: 'Tablet',
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

  // Global setup and teardown (temporarily disabled for initial testing)
  // globalSetup: require.resolve('./tests/global-setup.ts'),
  // globalTeardown: require.resolve('./tests/global-teardown.ts'),
});