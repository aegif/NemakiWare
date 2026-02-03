/**
 * Allowed Auth Methods Tests
 *
 * Tests for the nemaki:allowedAuthMethods attribute that controls
 * which authentication methods are permitted for each user.
 *
 * Values:
 * - null/empty: All methods allowed (backward compatible)
 * - "password": Password authentication only
 * - "cloud": Cloud/OIDC authentication only
 * - "password,cloud": Both methods allowed
 * - "disabled": No authentication allowed (account locked)
 */

import { test, expect } from '@playwright/test';

const BASE_URL = 'http://localhost:8080';
const REPO_ID = 'bedroom';
const ADMIN_AUTH = 'admin:admin';

// Test user for auth method tests
const TEST_USER_ID = 'auth-method-test-user';
const TEST_USER_PASSWORD = 'TestPassword123!';

/**
 * Helper function to create a user with Basic Auth
 */
async function createTestUser(request: any): Promise<void> {
  const response = await request.post(
    `${BASE_URL}/core/rest/repo/${REPO_ID}/user/create/${TEST_USER_ID}`,
    {
      headers: {
        'Authorization': `Basic ${Buffer.from(ADMIN_AUTH).toString('base64')}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      data: `name=Auth Method Test User&firstName=Auth&lastName=Test&email=${TEST_USER_ID}@example.com&password=${TEST_USER_PASSWORD}`,
    }
  );
  // User may already exist, that's OK
  if (response.status() !== 200) {
    const body = await response.text();
    if (!body.includes('already exists') && !body.includes('ERR_ALREADYEXISTS')) {
      console.log('Create user response:', body);
    }
  }
}

/**
 * Helper function to delete a user with Basic Auth
 */
async function deleteTestUser(request: any): Promise<void> {
  await request.delete(
    `${BASE_URL}/core/rest/repo/${REPO_ID}/user/delete/${TEST_USER_ID}`,
    {
      headers: {
        'Authorization': `Basic ${Buffer.from(ADMIN_AUTH).toString('base64')}`,
      },
    }
  );
}

/**
 * Helper function to update user's allowedAuthMethods
 */
async function setAllowedAuthMethods(request: any, value: string): Promise<void> {
  const response = await request.put(
    `${BASE_URL}/core/rest/repo/${REPO_ID}/user/update/${TEST_USER_ID}`,
    {
      headers: {
        'Authorization': `Basic ${Buffer.from(ADMIN_AUTH).toString('base64')}`,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      data: `allowedAuthMethods=${encodeURIComponent(value)}`,
    }
  );
  expect(response.status()).toBe(200);
  const body = await response.json();
  expect(body.status).toBe('success');
}

/**
 * Helper function to verify user's allowedAuthMethods value
 */
async function getAllowedAuthMethods(request: any): Promise<string | null> {
  const response = await request.get(
    `${BASE_URL}/core/rest/repo/${REPO_ID}/user/list?query=${TEST_USER_ID}`,
    {
      headers: {
        'Authorization': `Basic ${Buffer.from(ADMIN_AUTH).toString('base64')}`,
      },
    }
  );
  expect(response.status()).toBe(200);
  const body = await response.json();
  const user = body.users?.find((u: any) => u.userId === TEST_USER_ID);
  return user?.allowedAuthMethods ?? null;
}

/**
 * Helper function to attempt password authentication
 * Returns true if authentication succeeds, false otherwise
 */
async function attemptPasswordAuth(request: any): Promise<boolean> {
  const response = await request.get(
    `${BASE_URL}/core/atom/${REPO_ID}`,
    {
      headers: {
        'Authorization': `Basic ${Buffer.from(`${TEST_USER_ID}:${TEST_USER_PASSWORD}`).toString('base64')}`,
      },
    }
  );
  return response.status() === 200;
}

/**
 * Helper function to attempt authentication via auth token API (simulating OIDC flow)
 * Note: This tests the allowedAuthMethods check in the auth token creation flow
 */
async function attemptTokenAuth(request: any): Promise<{ success: boolean; error?: string }> {
  // First, get a valid auth token for the user via password login
  const loginResponse = await request.post(
    `${BASE_URL}/core/rest/repo/${REPO_ID}/authtoken/${TEST_USER_ID}/login`,
    {
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      data: `password=${encodeURIComponent(TEST_USER_PASSWORD)}`,
    }
  );

  if (loginResponse.status() !== 200) {
    const body = await loginResponse.text();
    return { success: false, error: body };
  }

  const body = await loginResponse.json();
  if (body.status === false || body.status === 'error' || body.status === 'failure') {
    return { success: false, error: JSON.stringify(body.errMsg || body.error || body) };
  }

  return { success: true };
}

test.describe('Allowed Auth Methods', () => {
  test.beforeAll(async ({ request }) => {
    // Create test user if not exists
    await createTestUser(request);
    // Reset to default (all methods allowed)
    await setAllowedAuthMethods(request, '');
  });

  test.afterAll(async ({ request }) => {
    // Clean up: reset to default and optionally delete user
    await setAllowedAuthMethods(request, '');
    // Uncomment to delete test user after tests
    // await deleteTestUser(request);
  });

  test('should allow all auth methods when allowedAuthMethods is empty/null', async ({ request }) => {
    // Set to empty (all methods allowed)
    await setAllowedAuthMethods(request, '');

    // Verify it's null/empty
    const value = await getAllowedAuthMethods(request);
    expect(value).toBeFalsy();

    // Password auth should work
    const passwordResult = await attemptPasswordAuth(request);
    expect(passwordResult).toBe(true);

    // Token auth should work
    const tokenResult = await attemptTokenAuth(request);
    expect(tokenResult.success).toBe(true);
  });

  test('should reject password auth when allowedAuthMethods is "cloud"', async ({ request }) => {
    // Set to cloud only
    await setAllowedAuthMethods(request, 'cloud');

    // Verify the value was set
    const value = await getAllowedAuthMethods(request);
    expect(value).toBe('cloud');

    // Password auth should fail
    const passwordResult = await attemptPasswordAuth(request);
    expect(passwordResult).toBe(false);
  });

  test('should allow password auth when allowedAuthMethods is "password"', async ({ request }) => {
    // Set to password only
    await setAllowedAuthMethods(request, 'password');

    // Verify the value was set
    const value = await getAllowedAuthMethods(request);
    expect(value).toBe('password');

    // Password auth should work
    const passwordResult = await attemptPasswordAuth(request);
    expect(passwordResult).toBe(true);
  });

  test('should allow password auth when allowedAuthMethods is "password,cloud"', async ({ request }) => {
    // Set to both methods
    await setAllowedAuthMethods(request, 'password,cloud');

    // Verify the value was set
    const value = await getAllowedAuthMethods(request);
    expect(value).toBe('password,cloud');

    // Password auth should work
    const passwordResult = await attemptPasswordAuth(request);
    expect(passwordResult).toBe(true);
  });

  test('should reject all auth when allowedAuthMethods is "disabled"', async ({ request }) => {
    // Set to disabled
    await setAllowedAuthMethods(request, 'disabled');

    // Verify the value was set
    const value = await getAllowedAuthMethods(request);
    expect(value).toBe('disabled');

    // Password auth should fail
    const passwordResult = await attemptPasswordAuth(request);
    expect(passwordResult).toBe(false);

    // Token auth should also fail (tests the token creation path)
    const tokenResult = await attemptTokenAuth(request);
    expect(tokenResult.success).toBe(false);
  });

  test('should allow auth when allowedAuthMethods contains the method (case insensitive)', async ({ request }) => {
    // Set to PASSWORD (uppercase)
    await setAllowedAuthMethods(request, 'PASSWORD');

    // Password auth should still work (case insensitive check)
    const passwordResult = await attemptPasswordAuth(request);
    expect(passwordResult).toBe(true);
  });

  test('should allow auth with spaces in comma-separated list', async ({ request }) => {
    // Set with spaces
    await setAllowedAuthMethods(request, 'password, cloud');

    // Password auth should work
    const passwordResult = await attemptPasswordAuth(request);
    expect(passwordResult).toBe(true);
  });

  test('should properly update allowedAuthMethods via API', async ({ request }) => {
    // Test multiple updates in sequence
    await setAllowedAuthMethods(request, 'password');
    let value = await getAllowedAuthMethods(request);
    expect(value).toBe('password');

    await setAllowedAuthMethods(request, 'cloud');
    value = await getAllowedAuthMethods(request);
    expect(value).toBe('cloud');

    await setAllowedAuthMethods(request, 'disabled');
    value = await getAllowedAuthMethods(request);
    expect(value).toBe('disabled');

    // Clear it
    await setAllowedAuthMethods(request, '');
    value = await getAllowedAuthMethods(request);
    expect(value).toBeFalsy();
  });
});
