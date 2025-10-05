import { test, expect } from '@playwright/test';

/**
 * Initial Content Setup Tests
 *
 * Verifies that initial folders (Sites, Technical Documents) are created with correct ACL
 * during system initialization. This prevents regression of ACL issues where folders
 * were created with only system principal instead of admin and GROUP_EVERYONE.
 *
 * Critical Test: Validates Patch_InitialContentSetup execution and ACL configuration
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
