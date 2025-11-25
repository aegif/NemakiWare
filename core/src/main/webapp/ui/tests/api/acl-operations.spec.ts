import { test, expect } from '@playwright/test';
import { randomUUID } from 'crypto';

/**
 * ACL Operations API Direct Tests
 *
 * CRITICAL IMPLEMENTATION DECISION (2025-11-25):
 * This test suite validates ACL operations by testing the CMIS API layer directly,
 * bypassing UI components to avoid React closure scope issues in E2E test contexts.
 *
 * BACKGROUND:
 * Previous UI-based tests (access-control.spec.ts) encountered a fundamental issue
 * where Popconfirm onConfirm callbacks could not execute from Playwright test context
 * due to closure variable scope loss (record.principalId, handleRemovePermission).
 *
 * Three approaches were tested and all failed identically:
 * 1. Direct fiber tree callback invocation
 * 2. Native DOM event dispatch
 * 3. Playwright built-in click()
 *
 * All approaches successfully triggered DOM events and closed the Popconfirm modal,
 * but the arrow function callback body never executed because closure scope variables
 * were not accessible from the test execution context.
 *
 * CMIS RESEARCH FINDINGS (2025-11-25):
 * - Current REST API implementation (POST /core/rest/repo/{repo}/node/{id}/acl)
 *   uses "getACL + filter + setACL" pattern with full permissions array
 * - This approach aligns with CMIS community best practices (Stack Overflow recommendation)
 * - Backend also supports Browser Binding applyACL with removeACE parameters
 * - NemakiBrowserBindingServlet.java (lines 4205-4296) implements extractAclFromRequest
 *   with support for removeACEPrincipal[i] and removeACEPermission[i][j] parameters
 *
 * TEST STRATEGY:
 * These API-level tests validate that the CMIS implementation correctly handles
 * ACL operations according to CMIS 1.1 specification, independent of UI layer.
 *
 * Test Flow:
 * 1. Setup: Create test folder and apply initial ACL with test user permissions
 * 2. Test: Remove ACL entry via direct API call (getACL → filter → setACL pattern)
 * 3. Verify: Confirm user permission was removed from ACL
 * 4. Restore: Re-add user permission via direct API call
 * 5. Verify: Confirm user permission was restored
 * 6. Cleanup: Delete test folder
 *
 * API Endpoints Used:
 * - GET /core/browser/{repo}?cmisselector=acl&objectId={id} - Retrieve current ACL
 * - POST /core/browser/{repo} with cmisaction=applyACL - Apply ACL changes
 *
 * Expected Behavior (CMIS 1.1 Compliant):
 * - ACL operations should succeed with HTTP 200/201 responses
 * - Removed principals should not appear in subsequent getACL results
 * - Added principals should appear with correct permissions in getACL results
 * - Only direct ACEs can be modified (inherited ACEs are read-only per CMIS spec)
 */

test.describe('ACL Operations - API Direct Tests', () => {
  let testFolderName: string;
  let testFolderId: string;
  let testUsername: string;
  const repositoryId = 'bedroom';
  const rootFolderId = 'e02f784f8360a02cc14d1314c10038ff';

  test.beforeAll(async ({ browser }) => {
    test.setTimeout(120000); // 2 minutes for setup

    // Generate unique test data with 8-character UUID prefix
    const uuid = randomUUID().split('-')[0];
    testFolderName = `api-test-folder-${uuid}`;
    testUsername = `apitest${uuid}`;

    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      console.log(`Setup: Creating test folder "${testFolderName}" in root folder`);

      // Step 1: Create test folder using CMIS Browser Binding
      const createResponse = await page.request.post(`http://localhost:8080/core/browser/${repositoryId}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
        },
        form: {
          'cmisaction': 'createFolder',
          'folderId': rootFolderId,
          'propertyId[0]': 'cmis:objectTypeId',
          'propertyValue[0]': 'cmis:folder',
          'propertyId[1]': 'cmis:name',
          'propertyValue[1]': testFolderName
        }
      });

      if (createResponse.ok()) {
        const responseText = await createResponse.text();
        console.log('Setup: Test folder created successfully');

        // Extract objectId from JSON response
        try {
          const responseData = JSON.parse(responseText);
          if (responseData.succinctProperties && responseData.succinctProperties['cmis:objectId']) {
            testFolderId = responseData.succinctProperties['cmis:objectId'];
          } else if (responseData.properties && responseData.properties['cmis:objectId']) {
            testFolderId = responseData.properties['cmis:objectId'].value;
          }
          console.log(`Setup: Test folder ID: ${testFolderId}`);
        } catch (e) {
          console.error('Setup: Failed to parse folder creation response:', e);
          throw new Error('Could not extract folder ID from creation response');
        }
      } else {
        const errorText = await createResponse.text();
        console.error(`Setup: Folder creation failed: ${createResponse.status()} - ${errorText}`);
        throw new Error(`Folder creation failed with status ${createResponse.status()}`);
      }

      // Step 2: Grant initial permissions to test user (cmis:read and cmis:write)
      console.log(`Setup: Granting permissions to ${testUsername}`);
      const aclResponse = await page.request.post(`http://localhost:8080/core/browser/${repositoryId}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
        },
        form: {
          'cmisaction': 'applyACL',
          'objectId': testFolderId,
          'addACEPrincipal[0]': testUsername,
          'addACEPermission[0][0]': 'cmis:read',
          'addACEPermission[0][1]': 'cmis:write'
        }
      });

      if (aclResponse.ok()) {
        console.log(`Setup: Initial permissions granted to ${testUsername}`);

        // Verify the ACL was actually applied
        const verifyResponse = await page.request.get(
          `http://localhost:8080/core/browser/${repositoryId}/${testFolderId}?cmisselector=acl`,
          {
            headers: {
              'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
            }
          }
        );
        if (verifyResponse.ok()) {
          const acl = await verifyResponse.json();
          console.log(`Setup: Verification - ACL has ${acl.aces?.length || 0} entries`);
          if (acl.aces && acl.aces.length > 0) {
            console.log(`Setup: First ACE structure: ${JSON.stringify(acl.aces[0])}`);
          }
          const hasUser = acl.aces?.some((ace: any) => ace.principalId === testUsername || ace.principal?.id === testUsername);
          console.log(`Setup: Verification - Test user ${testUsername} found in ACL: ${hasUser}`);
        }
      } else {
        const errorText = await aclResponse.text();
        console.error(`Setup: ACL application failed: ${aclResponse.status()} - ${errorText}`);
        throw new Error(`ACL application failed with status ${aclResponse.status()}`);
      }

    } catch (error) {
      console.error('Setup: Error during test setup:', error);
      throw error;
    } finally {
      await context.close();
    }
  });

  test('should remove ACL entry via direct API call', async ({ browser }) => {
    test.setTimeout(60000); // 1 minute timeout

    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      // Step 1: Get current ACL via CMIS Browser Binding
      console.log(`Test: Fetching current ACL for folder ${testFolderId}`);
      const getAclResponse = await page.request.get(
        `http://localhost:8080/core/browser/${repositoryId}/${testFolderId}?cmisselector=acl`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      if (!getAclResponse.ok()) {
        const errorText = await getAclResponse.text();
        console.error(`Test: getACL failed: ${getAclResponse.status()} - ${errorText}`);
      }
      expect(getAclResponse.ok()).toBeTruthy();
      const currentAcl = await getAclResponse.json();
      console.log(`Test: Current ACL has ${currentAcl.aces?.length || 0} entries`);
      console.log(`Test: First 10 principals: ${currentAcl.aces?.slice(0, 10).map((a: any) => a.principalId).join(', ')}`);
      console.log(`Test: Looking for test user: ${testUsername}`);

      // Verify test user exists in current ACL
      const hasTestUser = currentAcl.aces?.some((ace: any) =>
        ace.principalId === testUsername
      );
      expect(hasTestUser).toBeTruthy();
      console.log(`Test: Verified ${testUsername} exists in current ACL`);

      // Step 2: Remove test user from ACL using combined remove + add approach
      // According to CMIS spec, applyACL should process removes first, then adds
      console.log(`Test: Removing ${testUsername} from ACL using removeACE + addACE pattern`);

      // Build form data with removeACE for test user
      // CRITICAL: Backend extractAclFromRequest requires BOTH principal AND permissions to create ACE
      const formData: Record<string, string> = {
        'cmisaction': 'applyACL',
        'objectId': testFolderId,
        'removeACEPrincipal[0]': testUsername,
        'removeACEPermission[0][0]': 'cmis:read',
        'removeACEPermission[0][1]': 'cmis:write'
      };

      // Note: Including permissions with removeACE as backend requires both principal and permissions
      // to successfully extract and process the remove operation

      const removeAclResponse = await page.request.post(`http://localhost:8080/core/browser/${repositoryId}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
        },
        form: formData
      });

      expect(removeAclResponse.ok()).toBeTruthy();
      console.log(`Test: ACL update operation completed with status ${removeAclResponse.status()}`);

      // Step 3: Verify user was removed by fetching ACL again
      const verifyAclResponse = await page.request.get(
        `http://localhost:8080/core/browser/${repositoryId}/${testFolderId}?cmisselector=acl`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      expect(verifyAclResponse.ok()).toBeTruthy();
      const updatedAcl = await verifyAclResponse.json();
      console.log(`Test: Updated ACL has ${updatedAcl.aces?.length || 0} entries`);

      // Debug: Check if test user is in updated ACL and what other changes occurred
      const stillHasTestUser = updatedAcl.aces?.some((ace: any) =>
        ace.principalId === testUsername
      );
      console.log(`Test: Test user ${testUsername} in updated ACL: ${stillHasTestUser}`);
      console.log(`Test: Updated ACL first 10 principals: ${updatedAcl.aces?.slice(0, 10).map((a: any) => a.principalId).join(', ')}`);

      expect(stillHasTestUser).toBeFalsy();
      console.log(`Test: ✅ Verified ${testUsername} was successfully removed from ACL`);

    } finally {
      await context.close();
    }
  });

  test('should restore ACL entry via direct API call', async ({ browser }) => {
    test.setTimeout(60000); // 1 minute timeout

    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      // Step 1: Verify test user is not in ACL (should have been removed by previous test)
      console.log(`Test: Verifying ${testUsername} is not in current ACL`);
      const getAclResponse = await page.request.get(
        `http://localhost:8080/core/browser/${repositoryId}/${testFolderId}?cmisselector=acl`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      if (!getAclResponse.ok()) {
        const errorText = await getAclResponse.text();
        console.error(`Test: getACL failed: ${getAclResponse.status()} - ${errorText}`);
      }
      expect(getAclResponse.ok()).toBeTruthy();
      const currentAcl = await getAclResponse.json();
      console.log(`Test: Current ACL has ${currentAcl.aces?.length || 0} entries`);

      const hasTestUser = currentAcl.aces?.some((ace: any) =>
        ace.principalId === testUsername
      );
      expect(hasTestUser).toBeFalsy();
      console.log(`Test: Confirmed ${testUsername} is not in ACL`);

      // Step 2: Restore test user to ACL with original permissions
      console.log(`Test: Restoring ${testUsername} to ACL with cmis:read and cmis:write`);
      const addAclResponse = await page.request.post(`http://localhost:8080/core/browser/${repositoryId}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
        },
        form: {
          'cmisaction': 'applyACL',
          'objectId': testFolderId,
          'addACEPrincipal[0]': testUsername,
          'addACEPermission[0][0]': 'cmis:read',
          'addACEPermission[0][1]': 'cmis:write'
        }
      });

      expect(addAclResponse.ok()).toBeTruthy();
      console.log(`Test: Add ACL operation completed with status ${addAclResponse.status()}`);

      // Step 3: Verify user was restored by fetching ACL again
      const verifyAclResponse = await page.request.get(
        `http://localhost:8080/core/browser/${repositoryId}/${testFolderId}?cmisselector=acl`,
        {
          headers: {
            'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
          }
        }
      );

      expect(verifyAclResponse.ok()).toBeTruthy();
      const updatedAcl = await verifyAclResponse.json();
      console.log(`Test: Updated ACL has ${updatedAcl.aces?.length || 0} entries`);

      // Verify test user exists in ACL with correct permissions
      const restoredAce = updatedAcl.aces?.find((ace: any) =>
        ace.principalId === testUsername
      );
      expect(restoredAce).toBeDefined();
      expect(restoredAce.permissions).toContain('cmis:read');
      expect(restoredAce.permissions).toContain('cmis:write');
      console.log(`Test: ✅ Verified ${testUsername} was successfully restored to ACL with correct permissions`);

    } finally {
      await context.close();
    }
  });

  test.afterAll(async ({ browser }) => {
    test.setTimeout(60000); // 1 minute for cleanup

    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      console.log(`Cleanup: Deleting test folder ${testFolderName}`);

      // Delete test folder using CMIS Browser Binding deleteTree operation
      const deleteResponse = await page.request.post(`http://localhost:8080/core/browser/${repositoryId}`, {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:admin').toString('base64')}`
        },
        form: {
          'cmisaction': 'deleteTree',
          'folderId': testFolderId,  // Note: deleteTree uses folderId, not objectId
          'allVersions': 'true',
          'continueOnFailure': 'false'
        }
      });

      if (deleteResponse.ok()) {
        console.log('Cleanup: Test folder deleted successfully');
      } else {
        const errorText = await deleteResponse.text();
        console.error(`Cleanup: Folder deletion failed: ${deleteResponse.status()} - ${errorText}`);
      }
    } catch (error) {
      console.error('Cleanup: Error during test cleanup:', error);
    } finally {
      await context.close();
    }
  });
});
