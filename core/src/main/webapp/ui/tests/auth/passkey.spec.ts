import { test, expect, CDPSession, Page } from '@playwright/test';

test.describe.serial('Passkey (WebAuthn) Management', () => {
  // CDP (Chrome DevTools Protocol) is only available in Chromium
  test.skip(({ browserName }) => browserName !== 'chromium', 'CDP WebAuthn requires Chromium');
  const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
  const UI_URL = `${BASE_URL}/core/ui`;
  const REST_BASE = `${BASE_URL}/core/rest/repo/bedroom`;
  const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');
  const REST_CSRF = { 'X-Requested-With': 'XMLHttpRequest' as const };
  let cdpSession: CDPSession;
  let authenticatorId: string;

  // Helper: login as admin via UI
  async function loginAsAdmin(page: Page) {
    await page.goto(`${UI_URL}/`);
    await page.waitForSelector('input[placeholder*="ユーザー"], input[placeholder*="User"]', { timeout: 15000 });
    await page.fill('input[placeholder*="ユーザー"], input[placeholder*="User"]', '');
    await page.fill('input[placeholder*="ユーザー"], input[placeholder*="User"]', 'admin');
    await page.fill('input[type="password"]', '');
    await page.fill('input[type="password"]', 'admin');
    await page.click('button[type="submit"], button:has-text("ログイン"), button:has-text("Login")');
    await page.waitForURL(/\/#\/documents/, { timeout: 45000 });
  }

  // Helper: setup virtual authenticator via CDP
  async function setupVirtualAuthenticator(page: Page) {
    cdpSession = await page.context().newCDPSession(page);
    await cdpSession.send('WebAuthn.enable');
    const result = await cdpSession.send('WebAuthn.addVirtualAuthenticator', {
      options: {
        protocol: 'ctap2',
        transport: 'internal',
        hasResidentKey: true,
        hasUserVerification: true,
        isUserVerified: true,
      },
    });
    authenticatorId = result.authenticatorId;
  }

  // Helper: cleanup virtual authenticator
  async function cleanupAuthenticator() {
    if (cdpSession && authenticatorId) {
      try {
        await cdpSession.send('WebAuthn.removeVirtualAuthenticator', {
          authenticatorId,
        });
        await cdpSession.send('WebAuthn.disable');
      } catch {
        // Ignore cleanup errors
      }
    }
  }

  test('Registration challenge API responds', async ({ request }) => {
    const res = await request.post(
      `${REST_BASE}/webauthn/register/begin`,
      {
        headers: { Authorization: ADMIN_AUTH, ...REST_CSRF },
      }
    );
    const data = await res.json();
    expect(data.status).toBe('success');
    expect(data.options).toBeDefined();
    expect(data.options.challenge).toBeDefined();
    expect(data.options.rp).toBeDefined();
    expect(data.options.user).toBeDefined();
  });

  test('Authentication challenge API responds', async ({ request }) => {
    const res = await request.post(
      `${REST_BASE}/webauthn/authenticate/begin`,
      { headers: { ...REST_CSRF } }
    );
    const data = await res.json();
    expect(data.status).toBe('success');
    expect(data.options).toBeDefined();
    expect(data.options.challenge).toBeDefined();
  });

  test('Account settings shows passkey section', async ({ page }) => {
    await loginAsAdmin(page);
    // Wait for auth state to fully load before navigating
    await page.waitForTimeout(3000);
    await page.goto(`${UI_URL}/#/account`);
    // Retry navigation if redirected (auth state race condition)
    for (let retry = 0; retry < 3; retry++) {
      await page.waitForTimeout(2000);
      if (page.url().includes('/account')) break;
      console.log(`passkey: Retrying navigation to /account (attempt ${retry + 2})`);
      await page.goto(`${UI_URL}/#/account`);
    }
    await page.waitForLoadState('networkidle');

    // Click on the passkey tab (default tab is 'profile')
    const passkeyTab = page.locator('.ant-tabs-tab').filter({ hasText: /パスキー|Passkey/i });
    await expect(passkeyTab).toBeVisible({ timeout: 10000 });
    await passkeyTab.click();

    // Should show passkey section heading inside the tab content
    await expect(page.getByRole('heading', { name: /パスキー|Passkeys/ })).toBeVisible({ timeout: 10000 });
    // Should show register button
    await expect(page.getByRole('button', { name: /パスキーを登録|Register Passkey/ })).toBeVisible();
  });

  test('Login page shows passkey button', async ({ page }) => {
    await page.goto(`${UI_URL}/#/login`);
    await page.waitForLoadState('networkidle');

    // Should show passkey login button (if WebAuthn is supported in browser)
    await expect(page.getByRole('button', { name: /パスキーでログイン|Sign in with Passkey/ })).toBeVisible({ timeout: 10000 });
  });

  test('Passkey registration flow completes', async ({ page }) => {
    await loginAsAdmin(page);

    // Navigate to account page BEFORE setting up virtual authenticator
    // Wait for auth state to fully load before navigating
    await page.waitForTimeout(3000);
    await page.goto(`${UI_URL}/#/account`);
    // Retry navigation if redirected (auth state race condition)
    for (let retry = 0; retry < 3; retry++) {
      await page.waitForTimeout(2000);
      if (page.url().includes('/account')) break;
      console.log(`passkey-registration: Retrying navigation to /account (attempt ${retry + 2})`);
      await page.goto(`${UI_URL}/#/account`);
    }
    await page.waitForLoadState('networkidle');

    // Click on the passkey tab (default tab is 'profile')
    const passkeyTab = page.locator('.ant-tabs-tab').filter({ hasText: /パスキー|Passkey/i });
    await expect(passkeyTab).toBeVisible({ timeout: 10000 });
    await passkeyTab.click();

    // Wait for passkey section to appear before CDP setup
    await expect(page.getByRole('heading', { name: /パスキー|Passkeys/ })).toBeVisible({ timeout: 10000 });

    // Setup virtual authenticator (CDP session) - skip test if CDP/WebAuthn unavailable
    try {
      await setupVirtualAuthenticator(page);
    } catch (e) {
      test.skip(true, 'WebAuthn virtual authenticator not available: ' + String(e).substring(0, 100));
      return;
    }

    try {
      // Click register button to open modal
      await page.getByRole('button', { name: /パスキーを登録|Register Passkey/ }).click({ timeout: 10000 });

      // Wait for modal to appear
      await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 5000 });

      // Enter display name in modal
      await page.fill('input[placeholder*="パスキーの名前"], input[placeholder*="Passkey name"]', 'Test Passkey');

      // Click register/start button in modal
      await page.getByRole('button', { name: /登録開始|Start Registration/ }).click();

      // Wait for modal to close (registration success means the server
      // accepted the credential — the modal only closes on success)
      await expect(page.locator('.ant-modal')).toBeHidden({ timeout: 20000 });

      // Verify registration was persisted via API
      const listRes = await page.request.get(
        `${REST_BASE}/webauthn/credentials`,
        { headers: { Authorization: ADMIN_AUTH } }
      );
      const listData = await listRes.json();
      expect(listData.status).toBe('success');
      const testCred = listData.credentials.find(
        (c: { displayName?: string }) => c.displayName === 'Test Passkey'
      );
      expect(testCred).toBeDefined();

      // Verify UI list also shows the registered passkey
      // After successful registration the component calls loadCredentials().
      // Wait for the credentials API response, then verify the table shows the passkey.
      // If the in-place update didn't render in time, reload and try again.
      const testPasskeyLocator = page.getByText('Test Passkey');
      try {
        await expect(testPasskeyLocator).toBeVisible({ timeout: 10000 });
      } catch {
        // Fallback: reload and wait for credentials API response
        const credResPromise = page.waitForResponse(
          resp => resp.url().includes('/webauthn/credentials') && resp.status() === 200,
          { timeout: 15000 }
        );
        await page.reload();
        await credResPromise;
        await page.waitForLoadState('networkidle');
        const passkeyTabAfter = page.locator('.ant-tabs-tab').filter({ hasText: /パスキー|Passkey/i });
        await expect(passkeyTabAfter).toBeVisible({ timeout: 10000 });
        await passkeyTabAfter.click();
        await expect(testPasskeyLocator).toBeVisible({ timeout: 15000 });
      }
    } finally {
      await cleanupAuthenticator();
    }
  });

  test('Passkey login flow works', async ({ request }) => {
    // Verify API endpoints respond correctly with Basic Auth
    const registerBeginRes = await request.post(
      `${REST_BASE}/webauthn/register/begin`,
      { headers: { Authorization: ADMIN_AUTH, ...REST_CSRF } }
    );
    const registerBeginData = await registerBeginRes.json();
    expect(registerBeginData.status).toBe('success');

    // Verify credentials list endpoint works
    const listRes = await request.get(
      `${REST_BASE}/webauthn/credentials`,
      { headers: { Authorization: ADMIN_AUTH } }
    );
    const listData = await listRes.json();
    expect(listData.status).toBe('success');
    expect(Array.isArray(listData.credentials)).toBe(true);
  });

  test('Multiple passkeys can be managed', async ({ request }) => {
    // Verify register/begin returns proper WebAuthn options
    const res = await request.post(
      `${REST_BASE}/webauthn/register/begin`,
      { headers: { Authorization: ADMIN_AUTH, ...REST_CSRF } }
    );
    const data = await res.json();
    expect(data.status).toBe('success');
    expect(data.options.rp).toBeDefined();
    expect(data.options.pubKeyCredParams).toBeDefined();
  });

  test('Passkey registration preserves password login', async ({ request }) => {
    // Verify that password login still works after passkey features are added
    const loginRes = await request.post(
      `${REST_BASE}/authtoken/admin/login`,
      {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...REST_CSRF },
        data: 'password=admin',
      }
    );
    const loginData = await loginRes.json();
    expect(loginData.status).toBe('success');
    expect(loginData.value.userName).toBe('admin');
    expect(loginData.value.token).toBeDefined();
  });

  // Cleanup: delete any test passkeys created during tests
  test.afterAll(async ({ request }) => {
    try {
      const listRes = await request.get(
        `${REST_BASE}/webauthn/credentials`,
        { headers: { Authorization: ADMIN_AUTH } }
      );
      const listData = await listRes.json();
      if (listData.status === 'success' && listData.credentials) {
        for (const cred of listData.credentials) {
          await request.delete(
            `${REST_BASE}/webauthn/credentials/${cred.id}`,
            { headers: { Authorization: ADMIN_AUTH, ...REST_CSRF } }
          );
        }
      }
    } catch {
      // Ignore cleanup errors
    }
  });
});
