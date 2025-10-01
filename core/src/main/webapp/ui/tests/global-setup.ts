import { chromium, FullConfig } from '@playwright/test';

/**
 * Global setup for NemakiWare UI tests
 *
 * This setup:
 * - Verifies backend availability
 * - Prepares authentication state
 * - Sets up test data if needed
 */
async function globalSetup(config: FullConfig) {
  console.log('🚀 Starting NemakiWare UI Test Global Setup');

  const browser = await chromium.launch();
  const page = await browser.newPage();

  try {
    // Check if NemakiWare backend is available
    const baseURL = config.projects[0].use.baseURL || 'http://localhost:8080';

    console.log(`📡 Checking NemakiWare backend at ${baseURL}`);

    // Test basic connectivity to CMIS repository
    const response = await page.goto(`${baseURL}/core/atom/bedroom`, {
      waitUntil: 'networkidle',
      timeout: 30000,
    });

    if (!response || response.status() !== 200) {
      throw new Error(`❌ NemakiWare backend not available at ${baseURL}. Status: ${response?.status()}`);
    }

    console.log('✅ NemakiWare backend is available');

    // Check UI development server (if running)
    try {
      const uiResponse = await page.goto('http://localhost:5173', {
        waitUntil: 'networkidle',
        timeout: 10000,
      });

      if (uiResponse && uiResponse.status() === 200) {
        console.log('✅ Vite development server is running');
      }
    } catch (error) {
      console.log('ℹ️  Vite development server not running (using production build)');
    }

    // Perform a test login to verify authentication system
    console.log('🔐 Testing authentication system');

    await page.goto(`${baseURL}/core/ui/dist/`);

    // Wait for login form to load
    await page.waitForSelector('input[placeholder*="admin"]', { timeout: 10000 });

    // Test login functionality
    await page.fill('input[placeholder*="admin"]', 'admin');
    await page.fill('input[type="password"]', 'admin');

    // Select bedroom repository if dropdown exists
    const repositorySelect = page.locator('select, .ant-select');
    if (await repositorySelect.count() > 0) {
      await repositorySelect.click();
      await page.getByText('bedroom').click();
    }

    // Click login button
    await page.getByRole('button', { name: /login|ログイン/i }).click();

    // Wait for successful login (URL change or specific element)
    await page.waitForURL('**/ui/dist/**', { timeout: 15000 });

    console.log('✅ Authentication system is working');

    // Save authentication state for tests
    await page.context().storageState({ path: 'tests/fixtures/auth-state.json' });

    console.log('💾 Authentication state saved for tests');

  } catch (error) {
    console.error('❌ Global setup failed:', error);
    throw error;
  } finally {
    await browser.close();
  }

  console.log('🎉 Global setup completed successfully');
}

export default globalSetup;