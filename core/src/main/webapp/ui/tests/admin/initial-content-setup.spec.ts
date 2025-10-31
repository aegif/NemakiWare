import { test, expect } from '@playwright/test';

/**
 * Initial Content Setup E2E Tests
 *
 * Critical backend integration tests for NemakiWare system initialization:
 * - Validates Patch_InitialContentSetup.java execution during system startup
 * - Verifies initial folders (Sites, Technical Documents) are created in root folder
 * - Validates multi-principal ACL configuration (admin, GROUP_EVERYONE, system)
 * - Prevents regression of ACL issues where folders were created with only system principal
 * - Uses CMIS Browser Binding API with CouchDB direct access for reliable validation
 *
 * IMPORTANT DESIGN DECISIONS:
 * 1. Backend-Focused Testing (No Browser Automation):
 *    - Pure API testing using fetch() for CMIS Browser Binding endpoints
 *    - No Playwright page automation or UI interaction
 *    - Direct CouchDB HTTP API access for ACL validation
 *    - Rationale: System initialization is backend operation, no UI involvement
 *    - Performance: Faster execution without browser overhead
 *
 * 2. CMIS API-First with CouchDB Fallback (Lines 80-140, 142-199):
 *    - Primary: CMIS Browser Binding API for folder discovery (cmisselector=children)
 *    - Fallback: Direct CouchDB access for reliable ACL validation
 *    - Rationale: CMIS ACL retrieval via AtomPub can be less reliable than CouchDB direct access
 *    - Implementation: Fetch folder ID via CMIS, then validate ACL via CouchDB HTTP API
 *    - CouchDB Credentials: admin:password (different from CMIS admin:admin)
 *
 * 3. Multi-Principal ACL Validation Strategy (Lines 115-139, 173-198):
 *    - Expected ACL entries: Exactly 3 principals (admin, GROUP_EVERYONE, system)
 *    - admin: cmis:all permissions (full control)
 *    - GROUP_EVERYONE: cmis:read permissions (read-only for all users)
 *    - system: cmis:all permissions (internal operations)
 *    - Rationale: Prevents regression where ACL=null resulted in only system principal
 *    - Critical Fix: PatchService.createInitialFolders() must set proper ACL at creation time
 *
 * 4. Regression Test Pattern (Lines 201-245):
 *    - Dedicated test specifically targeting the historical bug
 *    - Bug History: Folders created with ACL=null → Only system principal added by default
 *    - Detection: ACL entries.length > 1 AND hasAdmin=true AND hasEveryone=true
 *    - Value: Catches regression if PatchService changes break ACL initialization
 *    - Console Output: "✅ Regression test passed: Folders have proper multi-principal ACL"
 *
 * 5. BeforeAll Server Check (Lines 19-30):
 *    - Validates CMIS server accessibility before running any tests
 *    - Endpoint: Browser Binding root (http://localhost:8080/core/browser/bedroom)
 *    - Throws Error if server not accessible (prevents cascading test failures)
 *    - Rationale: Early failure detection saves time debugging individual test failures
 *
 * 6. Folder Discovery via Browser Binding (Lines 32-54, 56-78):
 *    - Uses cmisselector=children to list root folder contents
 *    - Filters by cmis:name property to find specific folders
 *    - Validates cmis:baseTypeId=cmis:folder (ensures correct object type)
 *    - Console Logging: Outputs folder objectId for debugging
 *    - Rationale: Browser Binding JSON format easier to parse than AtomPub XML
 *
 * 7. Direct CouchDB Access for ACL Validation (Lines 102-113, 161-172):
 *    - Endpoint: http://localhost:5984/{repositoryId}/{folderId}
 *    - Credentials: admin:password (CouchDB-specific, not CMIS credentials)
 *    - Returns complete document including ACL structure
 *    - Rationale: Most reliable way to validate ACL persistence in database layer
 *    - Alternative: CMIS AtomPub /acl endpoint less reliable for initial setup validation
 *
 * 8. Test Execution Order (5 tests):
 *    - Test 1: Sites folder existence (basic folder creation validation)
 *    - Test 2: Technical Documents folder existence (second folder validation)
 *    - Test 3: Sites folder ACL validation (3-principal ACL structure)
 *    - Test 4: Technical Documents folder ACL validation (ACL consistency check)
 *    - Test 5: Regression test (multi-principal ACL enforcement)
 *    - Rationale: Progressive validation from simple existence to complex ACL structure
 *
 * 9. Console Logging Strategy (Lines 53, 77, 139, 198, 244):
 *    - ✅ Checkmark prefix for visual clarity in test output
 *    - Outputs folder objectId for debugging and verification
 *    - Logs complete ACL entries for troubleshooting
 *    - Regression test success message for clarity
 *    - Rationale: Facilitates debugging when tests fail or ACL issues occur
 *
 * 10. Constants Configuration (Lines 13-15):
 *     - CMIS_BASE_URL: http://localhost:8080/core (standard Docker deployment)
 *     - REPOSITORY_ID: 'bedroom' (primary NemakiWare repository)
 *     - ADMIN_CREDENTIALS: 'admin:admin' (CMIS authentication)
 *     - Centralized configuration: Easy to modify for different environments
 *     - Rationale: Avoid hardcoded values scattered across test methods
 *
 * Test Coverage:
 * 1. ✅ Sites Folder Existence (CMIS Browser Binding children listing)
 * 2. ✅ Technical Documents Folder Existence (CMIS Browser Binding children listing)
 * 3. ✅ Sites Folder ACL Validation (3 principals: admin:all, GROUP_EVERYONE:read, system:all)
 * 4. ✅ Technical Documents Folder ACL Validation (3 principals with same permissions)
 * 5. ✅ Regression Test (Multi-principal ACL enforcement, prevents system-only ACL bug)
 *
 * System Initialization Architecture:
 * - **Patch System**: PatchService.applyPatchesOnStartup() called during Core startup
 * - **Patch Implementation**: Patch_InitialContentSetup.createInitialFolders()
 * - **ACL Creation**: Explicit ACL set during folder creation (admin, GROUP_EVERYONE, system)
 * - **Database Layer**: CouchDB stores ACL entries as part of folder document
 * - **CMIS Layer**: ObjectService.createFolder() with acl parameter
 *
 * Patch_InitialContentSetup.java Integration:
 * - **Method**: createInitialFolders(repositoryId, rootFolderId, callContext)
 * - **Folder Creation**: Uses ObjectService.createFolder() with proper ACL parameter
 * - **ACL Structure**: AccessControlListImpl with 3 AccessControlEntryImpl objects
 * - **Persistence**: ACL stored in CouchDB document.acl.entries array
 * - **Validation**: This test suite validates Patch execution and ACL persistence
 *
 * Expected Test Results:
 * - Sites folder exists in root folder with objectId logged
 * - Technical Documents folder exists in root folder with objectId logged
 * - Both folders have exactly 3 ACL entries (admin, GROUP_EVERYONE, system)
 * - admin ACL entry has cmis:all permissions
 * - GROUP_EVERYONE ACL entry has cmis:read permissions
 * - system ACL entry has cmis:all permissions
 * - Regression test confirms multi-principal ACL (not system-only)
 * - All tests pass with green ✅ console messages
 *
 * Known Limitations:
 * - Does not validate folder properties beyond name and baseTypeId
 * - Does not test folder hierarchy (only root-level folders)
 * - Does not validate other CMIS properties (createdBy, creationDate, etc.)
 * - Does not test folder deletion or modification
 * - ACL validation relies on CouchDB direct access (not pure CMIS API)
 * - Test assumes localhost deployment (not configurable for remote servers)
 *
 * Performance Optimizations:
 * - No browser automation overhead (pure API testing)
 * - Single beforeAll check prevents running tests if server unavailable
 * - Reuses folder ID lookup across tests (could cache but tests are independent)
 * - Direct CouchDB access faster than CMIS ACL retrieval
 * - Minimal network requests (2-3 per test)
 *
 * Debugging Features:
 * - Console logging with ✅ checkmarks for visual clarity
 * - Folder objectId output for CouchDB direct inspection
 * - Complete ACL entries logged for troubleshooting
 * - BeforeAll server check prevents cascading failures
 * - Regression test with clear success message
 * - Error messages show which principal or permission failed
 *
 * Relationship to Other Components:
 * - **Patch_InitialContentSetup.java**: Java class being validated by this test suite
 * - **PatchService.java**: Orchestrates patch execution during system startup
 * - **ObjectService.createFolder()**: CMIS service used by patch to create folders
 * - **CouchDB**: Database layer where ACL is persisted
 * - **Browser Binding**: CMIS API used for folder discovery
 * - **access-control.spec.ts**: Related test suite for runtime ACL manipulation
 * - **acl-management.spec.ts**: Related test suite for ACL CRUD operations
 *
 * Historical Context - ACL Regression Bug:
 * - **Original Issue**: PatchService.createInitialFolders() created folders with acl=null
 * - **Symptom**: Folders only had system principal in ACL (default CouchDB behavior)
 * - **Impact**: Admin and GROUP_EVERYONE principals missing, breaking access control
 * - **Fix**: Patch_InitialContentSetup now explicitly sets ACL during folder creation
 * - **Prevention**: This test suite catches regression if fix is removed or broken
 *
 * Credentials Reference:
 * - **CMIS Authentication**: admin:admin (used for Browser Binding API)
 * - **CouchDB Authentication**: admin:password (used for direct database access)
 * - **Repository**: bedroom (primary NemakiWare repository ID)
 * - **Base URL**: http://localhost:8080/core (standard Docker deployment)
 */

const CMIS_BASE_URL = 'http://localhost:8080/core';
const REPOSITORY_ID = 'bedroom';
const ADMIN_CREDENTIALS = 'admin:admin';

test.describe('Initial Content Setup - Folder Creation and ACL', () => {

  test.beforeAll(async () => {
    // Verify server is accessible
    const response = await fetch(`${CMIS_BASE_URL}/browser/${REPOSITORY_ID}`, {
      headers: {
        'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`
      }
    });

    if (!response.ok) {
      throw new Error(`Server not accessible: ${response.status} ${response.statusText}`);
    }
  });

  test('Sites folder should exist in root folder', async () => {
    const response = await fetch(
      `${CMIS_BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=children`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`
        }
      }
    );

    expect(response.ok).toBe(true);
    const data = await response.json();

    // Find Sites folder in children
    const sitesFolder = data.objects?.find(
      (obj: any) => obj.object.properties['cmis:name']?.value === 'Sites'
    );

    expect(sitesFolder).toBeDefined();
    expect(sitesFolder.object.properties['cmis:baseTypeId'].value).toBe('cmis:folder');

    console.log('✅ Sites folder found:', sitesFolder.object.properties['cmis:objectId'].value);
  });

  test('Technical Documents folder should exist in root folder', async () => {
    const response = await fetch(
      `${CMIS_BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=children`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`
        }
      }
    );

    expect(response.ok).toBe(true);
    const data = await response.json();

    // Find Technical Documents folder in children
    const techDocsFolder = data.objects?.find(
      (obj: any) => obj.object.properties['cmis:name']?.value === 'Technical Documents'
    );

    expect(techDocsFolder).toBeDefined();
    expect(techDocsFolder.object.properties['cmis:baseTypeId'].value).toBe('cmis:folder');

    console.log('✅ Technical Documents folder found:', techDocsFolder.object.properties['cmis:objectId'].value);
  });

  test('Sites folder should have correct ACL (admin:all, GROUP_EVERYONE:read, system:all)', async () => {
    // First, get folder ID
    const childrenResponse = await fetch(
      `${CMIS_BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=children`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`
        }
      }
    );

    const childrenData = await childrenResponse.json();
    const sitesFolder = childrenData.objects?.find(
      (obj: any) => obj.object.properties['cmis:name']?.value === 'Sites'
    );

    expect(sitesFolder).toBeDefined();
    const folderId = sitesFolder.object.properties['cmis:objectId'].value;

    // Note: AtomPub ACL retrieval via /atom/{repo}/acl endpoint
    // For this test, we focus on CouchDB validation which is more reliable

    // Also verify via direct CouchDB access
    const couchDbResponse = await fetch(
      `http://localhost:5984/${REPOSITORY_ID}/${folderId}`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:password').toString('base64')}`
        }
      }
    );

    expect(couchDbResponse.ok).toBe(true);
    const folderDoc = await couchDbResponse.json();

    expect(folderDoc.acl).toBeDefined();
    expect(folderDoc.acl.entries).toHaveLength(3);

    // Verify each ACL entry
    const principals = folderDoc.acl.entries.map((entry: any) => entry.principal);
    expect(principals).toContain('admin');
    expect(principals).toContain('GROUP_EVERYONE');
    expect(principals).toContain('system');

    // Verify admin has cmis:all
    const adminEntry = folderDoc.acl.entries.find((entry: any) => entry.principal === 'admin');
    expect(adminEntry).toBeDefined();
    expect(adminEntry.permissions).toContain('cmis:all');

    // Verify GROUP_EVERYONE has cmis:read
    const everyoneEntry = folderDoc.acl.entries.find((entry: any) => entry.principal === 'GROUP_EVERYONE');
    expect(everyoneEntry).toBeDefined();
    expect(everyoneEntry.permissions).toContain('cmis:read');

    // Verify system has cmis:all
    const systemEntry = folderDoc.acl.entries.find((entry: any) => entry.principal === 'system');
    expect(systemEntry).toBeDefined();
    expect(systemEntry.permissions).toContain('cmis:all');

    console.log('✅ Sites folder ACL entries validated:', folderDoc.acl.entries);
  });

  test('Technical Documents folder should have correct ACL (admin:all, GROUP_EVERYONE:read, system:all)', async () => {
    // First, get folder ID
    const childrenResponse = await fetch(
      `${CMIS_BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=children`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`
        }
      }
    );

    const childrenData = await childrenResponse.json();
    const techDocsFolder = childrenData.objects?.find(
      (obj: any) => obj.object.properties['cmis:name']?.value === 'Technical Documents'
    );

    expect(techDocsFolder).toBeDefined();
    const folderId = techDocsFolder.object.properties['cmis:objectId'].value;

    // Verify via direct CouchDB access
    const couchDbResponse = await fetch(
      `http://localhost:5984/${REPOSITORY_ID}/${folderId}`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:password').toString('base64')}`
        }
      }
    );

    expect(couchDbResponse.ok).toBe(true);
    const folderDoc = await couchDbResponse.json();

    expect(folderDoc.acl).toBeDefined();
    expect(folderDoc.acl.entries).toHaveLength(3);

    // Verify each ACL entry
    const principals = folderDoc.acl.entries.map((entry: any) => entry.principal);
    expect(principals).toContain('admin');
    expect(principals).toContain('GROUP_EVERYONE');
    expect(principals).toContain('system');

    // Verify admin has cmis:all
    const adminEntry = folderDoc.acl.entries.find((entry: any) => entry.principal === 'admin');
    expect(adminEntry).toBeDefined();
    expect(adminEntry.permissions).toContain('cmis:all');

    // Verify GROUP_EVERYONE has cmis:read
    const everyoneEntry = folderDoc.acl.entries.find((entry: any) => entry.principal === 'GROUP_EVERYONE');
    expect(everyoneEntry).toBeDefined();
    expect(everyoneEntry.permissions).toContain('cmis:read');

    // Verify system has cmis:all
    const systemEntry = folderDoc.acl.entries.find((entry: any) => entry.principal === 'system');
    expect(systemEntry).toBeDefined();
    expect(systemEntry.permissions).toContain('cmis:all');

    console.log('✅ Technical Documents folder ACL entries validated:', folderDoc.acl.entries);
  });

  test('Regression test: Folders should NOT have only system principal', async () => {
    // This test specifically catches the regression where PatchService.createInitialFolders()
    // was creating folders with ACL=null, resulting in only system principal

    const childrenResponse = await fetch(
      `${CMIS_BASE_URL}/browser/${REPOSITORY_ID}/root?cmisselector=children`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from(ADMIN_CREDENTIALS).toString('base64')}`
        }
      }
    );

    const childrenData = await childrenResponse.json();
    const sitesFolder = childrenData.objects?.find(
      (obj: any) => obj.object.properties['cmis:name']?.value === 'Sites'
    );

    expect(sitesFolder).toBeDefined();
    const folderId = sitesFolder.object.properties['cmis:objectId'].value;

    const couchDbResponse = await fetch(
      `http://localhost:5984/${REPOSITORY_ID}/${folderId}`,
      {
        headers: {
          'Authorization': `Basic ${Buffer.from('admin:password').toString('base64')}`
        }
      }
    );

    const folderDoc = await couchDbResponse.json();

    // CRITICAL: ACL must have more than 1 entry
    expect(folderDoc.acl.entries.length).toBeGreaterThan(1);

    // CRITICAL: Must have admin principal
    const hasAdmin = folderDoc.acl.entries.some((entry: any) => entry.principal === 'admin');
    expect(hasAdmin).toBe(true);

    // CRITICAL: Must have GROUP_EVERYONE principal
    const hasEveryone = folderDoc.acl.entries.some((entry: any) => entry.principal === 'GROUP_EVERYONE');
    expect(hasEveryone).toBe(true);

    console.log('✅ Regression test passed: Folders have proper multi-principal ACL');
  });
});
