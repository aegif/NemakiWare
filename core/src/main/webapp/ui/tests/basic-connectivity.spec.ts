import { test, expect } from '@playwright/test';

test.describe('Basic Connectivity Tests', () => {
  test('should load NemakiWare UI page', async ({ page }) => {
    // Navigate to the UI
    await page.goto('http://localhost:8080/core/ui/dist/');

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
    const response = await page.request.get('http://localhost:8080/core/ui/dist/');
    expect(response.status()).toBe(200);
  });

  test('should check for required static assets', async ({ page }) => {
    // Check if main assets exist
    const assets = [
      '/core/ui/dist/assets/index-B81QkMzs.js',
      '/core/ui/dist/assets/index-D9wpoSK3.css',
    ];

    for (const asset of assets) {
      const response = await page.request.get(`http://localhost:8080${asset}`);
      console.log(`Asset ${asset}: ${response.status()}`);
      expect(response.status()).toBe(200);
    }
  });

  test('should wait for React app initialization', async ({ page }) => {
    await page.goto('http://localhost:8080/core/ui/dist/');

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