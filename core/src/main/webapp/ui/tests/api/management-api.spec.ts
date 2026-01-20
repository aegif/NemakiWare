import { test, expect, Page, APIRequestContext } from '@playwright/test';
import { randomUUID } from 'crypto';

/**
 * Management API E2E Tests
 *
 * Comprehensive test suite for NemakiWare REST Management APIs:
 * - User Management API (/api/v1/cmis/repositories/{repositoryId}/users)
 * - Group Management API (/api/v1/cmis/repositories/{repositoryId}/groups)
 * - Authentication API (/api/v1/cmis/auth/repositories/{repositoryId})
 * - Archive Management API (/api/v1/cmis/repositories/{repositoryId}/archives)
 * - Cache Management API (/api/v1/cmis/repositories/{repositoryId}/cache)
 * - Search Engine Management API (/api/v1/cmis/repositories/{repositoryId}/search-engine)
 * - Item Management API (/api/v1/cmis/repositories/{repositoryId}/items)
 *
 * Test Strategy:
 * - Direct API calls using Playwright's request context
 * - Basic authentication with admin credentials
 * - UUID-based unique test data for parallel execution safety
 * - Cleanup after each test to maintain test isolation
 *
 * Prerequisites:
 * - NemakiWare server running on localhost:8080
 * - Admin user with credentials admin:admin
 * - Repository 'bedroom' available
 */

const BASE_URL = 'http://localhost:8080';
const REPOSITORY_ID = 'bedroom';

/** Generate Basic Authentication header */
function getAuthHeader(): string {
  return `Basic ${Buffer.from('admin:admin').toString('base64')}`;
}

/** Helper to make authenticated API requests */
async function apiRequest(
  request: APIRequestContext,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: any
): Promise<{ status: number; data: any; headers: { [key: string]: string } }> {
  const url = `${BASE_URL}${path}`;
  const options: any = {
    headers: {
      'Authorization': getAuthHeader(),
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    },
  };

  if (body) {
    options.data = body;
  }

  let response;
  switch (method) {
    case 'GET':
      response = await request.get(url, options);
      break;
    case 'POST':
      response = await request.post(url, options);
      break;
    case 'PUT':
      response = await request.put(url, options);
      break;
    case 'DELETE':
      response = await request.delete(url, options);
      break;
  }

  let data;
  try {
    data = await response.json();
  } catch {
    data = await response.text();
  }

  const headers: { [key: string]: string } = {};
  response.headers().forEach((value, key) => {
    headers[key] = value;
  });

  return { status: response.status(), data, headers };
}

/**
 * User Management API Tests
 */
test.describe('User Management API', () => {
  const uuid = randomUUID().substring(0, 8);
  const testUserId = `apitest_user_${uuid}`;
  const testUserEmail = `${testUserId}@test.local`;

  test('should list users', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('users');
    expect(Array.isArray(data.users)).toBe(true);
    expect(data).toHaveProperty('_links');
    console.log(`Listed ${data.users.length} users`);
  });

  test('should create, get, update, and delete user', async ({ request }) => {
    // Create user
    const createResponse = await apiRequest(
      request,
      'POST',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users`,
      {
        userId: testUserId,
        name: `Test User ${uuid}`,
        email: testUserEmail,
        firstName: 'Test',
        lastName: 'User',
        password: 'TestPassword123!'
      }
    );

    expect(createResponse.status).toBe(201);
    expect(createResponse.data).toHaveProperty('userId', testUserId);
    console.log(`Created user: ${testUserId}`);

    // Get user
    const getResponse = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users/${testUserId}`
    );

    expect(getResponse.status).toBe(200);
    expect(getResponse.data).toHaveProperty('userId', testUserId);
    expect(getResponse.data).toHaveProperty('email', testUserEmail);
    console.log(`Retrieved user: ${testUserId}`);

    // Update user
    const updateResponse = await apiRequest(
      request,
      'PUT',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users/${testUserId}`,
      {
        email: `updated_${testUserEmail}`,
        firstName: 'Updated'
      }
    );

    expect(updateResponse.status).toBe(200);
    expect(updateResponse.data).toHaveProperty('email', `updated_${testUserEmail}`);
    console.log(`Updated user: ${testUserId}`);

    // Delete user
    const deleteResponse = await apiRequest(
      request,
      'DELETE',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users/${testUserId}`
    );

    expect(deleteResponse.status).toBe(204);
    console.log(`Deleted user: ${testUserId}`);

    // Verify deletion
    const verifyResponse = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users/${testUserId}`
    );

    expect(verifyResponse.status).toBe(404);
    console.log(`Verified user deletion: ${testUserId}`);
  });

  test('should search users', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users/search?query=admin`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('users');
    console.log(`Search returned ${data.users.length} users`);
  });
});

/**
 * Group Management API Tests
 */
test.describe('Group Management API', () => {
  const uuid = randomUUID().substring(0, 8);
  const testGroupId = `apitest_group_${uuid}`;

  test('should list groups', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('groups');
    expect(Array.isArray(data.groups)).toBe(true);
    expect(data).toHaveProperty('_links');
    console.log(`Listed ${data.groups.length} groups`);
  });

  test('should create, get, update, and delete group', async ({ request }) => {
    // Create group
    const createResponse = await apiRequest(
      request,
      'POST',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups`,
      {
        groupId: testGroupId,
        name: `Test Group ${uuid}`,
        description: 'Test group for API testing'
      }
    );

    expect(createResponse.status).toBe(201);
    expect(createResponse.data).toHaveProperty('groupId', testGroupId);
    console.log(`Created group: ${testGroupId}`);

    // Get group
    const getResponse = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups/${testGroupId}`
    );

    expect(getResponse.status).toBe(200);
    expect(getResponse.data).toHaveProperty('groupId', testGroupId);
    console.log(`Retrieved group: ${testGroupId}`);

    // Update group
    const updateResponse = await apiRequest(
      request,
      'PUT',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups/${testGroupId}`,
      {
        description: 'Updated description for API testing'
      }
    );

    expect(updateResponse.status).toBe(200);
    expect(updateResponse.data).toHaveProperty('description', 'Updated description for API testing');
    console.log(`Updated group: ${testGroupId}`);

    // Delete group
    const deleteResponse = await apiRequest(
      request,
      'DELETE',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups/${testGroupId}`
    );

    expect(deleteResponse.status).toBe(204);
    console.log(`Deleted group: ${testGroupId}`);

    // Verify deletion
    const verifyResponse = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups/${testGroupId}`
    );

    expect(verifyResponse.status).toBe(404);
    console.log(`Verified group deletion: ${testGroupId}`);
  });

  test('should search groups', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups/search?query=admin`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('groups');
    console.log(`Search returned ${data.groups.length} groups`);
  });
});

/**
 * Authentication API Tests
 */
test.describe('Authentication API', () => {
  test('should login with valid credentials', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'POST',
      `/core/api/v1/cmis/auth/repositories/${REPOSITORY_ID}/login`,
      {
        userId: 'admin',
        password: 'admin'
      }
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('token');
    expect(data).toHaveProperty('userId', 'admin');
    console.log('Login successful, token received');
  });

  test('should reject invalid credentials', async ({ request }) => {
    const { status } = await apiRequest(
      request,
      'POST',
      `/core/api/v1/cmis/auth/repositories/${REPOSITORY_ID}/login`,
      {
        userId: 'invalid',
        password: 'invalid'
      }
    );

    expect(status).toBe(401);
    console.log('Invalid credentials correctly rejected');
  });

  test('should get current user', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/auth/repositories/${REPOSITORY_ID}/me`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('userId');
    console.log(`Current user: ${data.userId}`);
  });
});

/**
 * Archive Management API Tests
 */
test.describe('Archive Management API', () => {
  test('should list archives (requires admin)', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/archives`
    );

    // Should succeed with admin credentials
    expect([200, 403]).toContain(status);
    if (status === 200) {
      expect(data).toHaveProperty('archives');
      console.log(`Listed ${data.archives?.length || 0} archives`);
    } else {
      console.log('Archive listing requires admin privileges');
    }
  });
});

/**
 * Cache Management API Tests
 */
test.describe('Cache Management API', () => {
  test('should invalidate object cache (requires admin)', async ({ request }) => {
    const { status } = await apiRequest(
      request,
      'POST',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/cache/invalidate/object`,
      { objectId: 'test-object-id' }
    );

    // Should succeed with admin credentials or return 403 if not admin
    expect([200, 204, 403, 404]).toContain(status);
    console.log(`Cache invalidation status: ${status}`);
  });

  test('should invalidate type definition cache (requires admin)', async ({ request }) => {
    const { status } = await apiRequest(
      request,
      'POST',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/cache/invalidate/type-definition`
    );

    // Should succeed with admin credentials or return 403 if not admin
    expect([200, 204, 403]).toContain(status);
    console.log(`Type definition cache invalidation status: ${status}`);
  });
});

/**
 * Search Engine Management API Tests
 */
test.describe('Search Engine Management API', () => {
  test('should get Solr URL (requires admin)', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/search-engine/url`
    );

    // Should succeed with admin credentials or return 403 if not admin
    expect([200, 403]).toContain(status);
    if (status === 200) {
      expect(data).toHaveProperty('url');
      console.log(`Solr URL: ${data.url}`);
    } else {
      console.log('Solr URL requires admin privileges');
    }
  });

  test('should get reindex status (requires admin)', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/search-engine/status`
    );

    // Should succeed with admin credentials or return 403 if not admin
    expect([200, 403]).toContain(status);
    if (status === 200) {
      expect(data).toHaveProperty('status');
      console.log(`Reindex status: ${data.status}`);
    } else {
      console.log('Reindex status requires admin privileges');
    }
  });

  test('should check index health (requires admin)', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/search-engine/health`
    );

    // Should succeed with admin credentials or return 403 if not admin
    expect([200, 403]).toContain(status);
    if (status === 200) {
      expect(data).toHaveProperty('healthy');
      console.log(`Index healthy: ${data.healthy}`);
    } else {
      console.log('Index health check requires admin privileges');
    }
  });
});

/**
 * Rendition Management API Tests
 */
test.describe('Rendition Management API', () => {
  test('should get supported MIME types', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/renditions/supported-types`
    );

    expect([200, 403]).toContain(status);
    if (status === 200) {
      expect(data).toHaveProperty('mimeTypes');
      expect(Array.isArray(data.mimeTypes)).toBe(true);
      console.log(`Supported MIME types: ${data.mimeTypes?.length || 0}`);
    } else {
      console.log('Supported types requires admin privileges');
    }
  });
});

/**
 * Error Handling Tests
 */
test.describe('API Error Handling', () => {
  test('should return 404 for non-existent user', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users/non-existent-user-12345`
    );

    expect(status).toBe(404);
    expect(data).toHaveProperty('type');
    expect(data).toHaveProperty('title');
    console.log('Non-existent user correctly returns 404');
  });

  test('should return 404 for non-existent group', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups/non-existent-group-12345`
    );

    expect(status).toBe(404);
    expect(data).toHaveProperty('type');
    expect(data).toHaveProperty('title');
    console.log('Non-existent group correctly returns 404');
  });

  test('should return 400 for invalid request body', async ({ request }) => {
    const { status } = await apiRequest(
      request,
      'POST',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users`,
      {} // Empty body - missing required fields
    );

    expect(status).toBe(400);
    console.log('Invalid request body correctly returns 400');
  });
});

/**
 * HATEOAS Links Tests
 */
test.describe('HATEOAS Links', () => {
  test('should include _links in user list response', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('_links');
    expect(data._links).toHaveProperty('self');
    console.log('User list includes HATEOAS links');
  });

  test('should include _links in group list response', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('_links');
    expect(data._links).toHaveProperty('self');
    console.log('Group list includes HATEOAS links');
  });
});

/**
 * Pagination Tests
 */
test.describe('Pagination', () => {
  test('should support pagination in user list', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/users?skipCount=0&maxItems=5`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('users');
    expect(data).toHaveProperty('hasMoreItems');
    expect(data).toHaveProperty('numItems');
    console.log(`Pagination: ${data.users.length} users, hasMore: ${data.hasMoreItems}`);
  });

  test('should support pagination in group list', async ({ request }) => {
    const { status, data } = await apiRequest(
      request,
      'GET',
      `/core/api/v1/cmis/repositories/${REPOSITORY_ID}/groups?skipCount=0&maxItems=5`
    );

    expect(status).toBe(200);
    expect(data).toHaveProperty('groups');
    expect(data).toHaveProperty('hasMoreItems');
    expect(data).toHaveProperty('numItems');
    console.log(`Pagination: ${data.groups.length} groups, hasMore: ${data.hasMoreItems}`);
  });
});
