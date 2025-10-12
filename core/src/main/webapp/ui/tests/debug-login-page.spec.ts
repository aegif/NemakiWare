import { test, expect } from '@playwright/test';

test('debug login page structure', async ({ page }) => {
  test.setTimeout(60000);

  // Listen for console messages
  page.on('console', msg => {
    console.log(`Browser console [${msg.type()}]:`, msg.text());
  });

  // Listen for page errors
  page.on('pageerror', error => {
    console.log('Page error:', error.message);
  });

  // Listen for failed requests
  page.on('requestfailed', request => {
    console.log('Failed request:', request.url(), request.failure()?.errorText);
  });

  console.log('Navigating to login page...');
  await page.goto('http://localhost:8080/core/ui/dist/index.html');

  console.log('Waiting for page to load...');
  await page.waitForTimeout(5000);

  console.log('Taking screenshot...');
  await page.screenshot({ path: 'login-page-debug.png', fullPage: true });

  console.log('\n=== Checking for input fields ===');
  const allInputs = await page.locator('input').all();
  console.log(`Found ${allInputs.length} input elements`);

  for (let i = 0; i < allInputs.length; i++) {
    const input = allInputs[i];
    const type = await input.getAttribute('type');
    const placeholder = await input.getAttribute('placeholder');
    const name = await input.getAttribute('name');
    const id = await input.getAttribute('id');
    const isVisible = await input.isVisible();
    console.log(`Input ${i}: type="${type}", placeholder="${placeholder}", name="${name}", id="${id}", visible=${isVisible}`);
  }

  console.log('\n=== Checking for specific selectors ===');
  const selectors = [
    'input[placeholder="ユーザー名"]',
    'input[name="username"]',
    'input[type="text"]',
    'input[placeholder="パスワード"]',
    'input[type="password"]',
    '.ant-input',
  ];

  for (const selector of selectors) {
    const count = await page.locator(selector).count();
    console.log(`Selector "${selector}": ${count} elements found`);
  }

  console.log('\n=== Page HTML (first 1000 chars) ===');
  const html = await page.content();
  console.log(html.substring(0, 1000));
});
