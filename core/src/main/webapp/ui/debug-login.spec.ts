import { test } from '@playwright/test';

test('debug login page HTML', async ({ page }) => {
  await page.goto('http://localhost:8080/core/ui/index.html');
  await page.waitForTimeout(2000);
  
  // Get HTML content
  const html = await page.content();
  console.log('=== PAGE HTML ===');
  console.log(html);
  
  // Get all input fields
  const inputs = await page.locator('input').all();
  console.log('\n=== ALL INPUT FIELDS ===');
  for (let i = 0; i < inputs.length; i++) {
    const input = inputs[i];
    const type = await input.getAttribute('type');
    const placeholder = await input.getAttribute('placeholder');
    const name = await input.getAttribute('name');
    const id = await input.getAttribute('id');
    console.log(`Input ${i}: type=${type}, placeholder=${placeholder}, name=${name}, id=${id}`);
  }
});
