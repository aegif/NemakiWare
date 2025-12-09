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

    // Test basic connectivity to NemakiWare UI endpoint (public access)
    const response = await page.goto(`${baseURL}/core/ui/`, {
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

    await page.goto(`${baseURL}/core/ui/`);

    // Wait for React app to load by waiting for content in the root div
    console.log('⏳ Waiting for React app to load...');
    await page.waitForFunction(() => {
      const root = document.getElementById('root');
      return root && root.innerHTML.length > 0;
    }, { timeout: 30000 });

    console.log('⏳ Waiting for React app to stabilize...');
    await page.waitForTimeout(2000); // Additional time for React components to render

    // Take a screenshot for debugging
    await page.screenshot({ path: 'debug-login-page.png', fullPage: true });
    console.log('📸 Screenshot saved as debug-login-page.png');

    // Log page content for debugging
    const pageContent = await page.content();
    console.log('📄 Page title:', await page.title());
    console.log('📄 Page URL:', page.url());
    console.log('📄 Root div content length:', pageContent.includes('<div id="root">') ? 'Found' : 'Not found');

    // Check for JavaScript errors
    const jsErrors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        jsErrors.push(msg.text());
      }
    });

    // Wait for login form to load with more specific debugging
    try {
      await page.waitForSelector('input[placeholder="ユーザー名"]', { timeout: 10000 });
    } catch (error) {
      console.log('❌ Username field not found, checking available inputs...');
      const inputs = await page.locator('input').all();
      console.log(`Found ${inputs.length} input elements`);
      for (let i = 0; i < inputs.length; i++) {
        const input = inputs[i];
        const placeholder = await input.getAttribute('placeholder');
        const type = await input.getAttribute('type');
        const name = await input.getAttribute('name');
        console.log(`Input ${i}: placeholder="${placeholder}", type="${type}", name="${name}"`);
      }

      if (jsErrors.length > 0) {
        console.log('JavaScript errors detected:');
        jsErrors.forEach(err => console.log('  ', err));
      }

      throw error;
    }

    // Test login functionality
    await page.fill('input[placeholder="ユーザー名"]', 'admin');
    await page.fill('input[placeholder="パスワード"]', 'admin');

    // Select bedroom repository if dropdown exists
    const repositorySelect = page.locator('.ant-select');
    if (await repositorySelect.count() > 0) {
      await repositorySelect.click();
      await page.getByText('bedroom').click();
    }

    // Click login button
    await page.getByRole('button', { name: 'ログイン' }).click();

    // Wait for successful login (URL change or specific element)
    await page.waitForURL('**/ui/**', { timeout: 15000 });

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