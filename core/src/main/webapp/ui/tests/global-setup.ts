import { FullConfig } from '@playwright/test';
import { promisify } from 'util';
import * as fs from 'fs';

/**
 * Global setup for NemakiWare UI tests
 *
 * This setup:
 * - Checks if Keycloak is available (optional, for OIDC/SAML tests)
 * - Verifies backend availability via HTTP check
 * - Creates test user for non-admin tests
 *
 * Environment Variables:
 * - KEYCLOAK_URL: Keycloak server URL (default: http://localhost:8180)
 * - SKIP_KEYCLOAK: Set to 'true' to skip Keycloak checks entirely
 *
 * Test Categories:
 * - Standard tests: Run without Keycloak (basic auth, file operations, admin features)
 * - External auth tests: Require Keycloak (OIDC, SAML, LDAP integration)
 */

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8180';
const SKIP_KEYCLOAK = process.env.SKIP_KEYCLOAK === 'true';

// Global state file to share Keycloak availability with tests
const STATE_FILE = '/tmp/nemakiware-test-state.json';

/**
 * Check if Keycloak is running and healthy
 */
async function isKeycloakRunning(): Promise<boolean> {
  if (SKIP_KEYCLOAK) {
    return false;
  }
  try {
    const response = await fetch(`${KEYCLOAK_URL}/realms/nemakiware/.well-known/openid-configuration`, {
      signal: AbortSignal.timeout(5000)
    });
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Check if NemakiWare backend is running
 */
async function isBackendRunning(baseURL: string): Promise<boolean> {
  try {
    const response = await fetch(`${baseURL}/core/atom/bedroom`, {
      signal: AbortSignal.timeout(10000),
      headers: {
        'Authorization': 'Basic ' + Buffer.from('admin:admin').toString('base64')
      }
    });
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Ensure testuser exists in CouchDB with BCrypt password
 * This is required for non-admin user tests
 */
/**
 * Ensure a test user exists via NemakiWare REST API.
 * Uses the REST API instead of direct CouchDB manipulation to avoid
 * cache inconsistencies and revision conflicts.
 */
async function ensureTestUser(baseURL: string, userId: string, password: string): Promise<void> {
  const adminAuth = 'Basic ' + Buffer.from('admin:admin').toString('base64');
  const restBase = `${baseURL}/core/rest/repo/bedroom`;

  try {
    // Check if user exists by trying to show it
    const checkRes = await fetch(`${restBase}/user/show/${userId}`, {
      headers: { 'Authorization': adminAuth },
      signal: AbortSignal.timeout(10000),
    });

    if (checkRes.ok) {
      const checkData = await checkRes.json();
      if (checkData.status === 'success' && checkData.user) {
        // User exists - reset password via REST API (admin can bypass old password)
        console.log(`🔐 Resetting ${userId} password...`);
        const resetRes = await fetch(`${restBase}/user/changePassword/${userId}`, {
          method: 'PUT',
          headers: {
            'Authorization': adminAuth,
            'Content-Type': 'application/x-www-form-urlencoded',
          },
          body: new URLSearchParams({ newPassword: password }).toString(),
          signal: AbortSignal.timeout(10000),
        });
        const resetData = await resetRes.json();
        if (resetData.status === 'success') {
          console.log(`✅ ${userId} password reset to default`);
        } else {
          throw new Error(`Failed to reset password for required test user ${userId}: ${JSON.stringify(resetData)}`);
        }
        return;
      }
    }

    // User doesn't exist - create via REST API
    console.log(`👤 Creating ${userId}...`);
    const createRes = await fetch(`${restBase}/user/create/${userId}`, {
      method: 'POST',
      headers: {
        'Authorization': adminAuth,
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({ name: userId, password }).toString(),
      signal: AbortSignal.timeout(10000),
    });

    const createData = await createRes.json();
    if (createData.status === 'success') {
      console.log(`✅ ${userId} created successfully`);
    } else {
      throw new Error(`Failed to create required test user ${userId}: ${JSON.stringify(createData)}`);
    }
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('Failed to create required test user')) {
      throw error; // Re-throw provisioning failures to stop the test run early
    }
    throw new Error(`Could not ensure required test user ${userId} exists: ${error}`);
  }
}

/**
 * Save test state to file for tests to read
 */
function saveTestState(state: { keycloakAvailable: boolean; keycloakUrl: string }) {
  try {
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
  } catch (error) {
    console.log('⚠️ Could not save test state:', error);
  }
}

/**
 * Create a minimal valid PDF containing searchable text keywords.
 * The PDF contains "repository" and "content stream" for Solr full-text search tests.
 */
function createTestPdf(): Buffer {
  // Minimal valid PDF with searchable text containing CMIS specification keywords
  const textContent = 'CMIS Content Management Interoperability Services Specification v1.1\n' +
    'This document describes the repository services and content stream handling.\n' +
    'A repository is a collection of objects that can be accessed via CMIS.\n' +
    'Content stream represents the binary content of a document object.\n';

  const stream = `1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n` +
    `2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n` +
    `3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n` +
    `5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n`;

  // Build content stream with text
  const lines = textContent.split('\n').filter(l => l.length > 0);
  let contentStreamText = 'BT\n/F1 12 Tf\n';
  let y = 750;
  for (const line of lines) {
    contentStreamText += `1 0 0 1 50 ${y} Tm\n(${line}) Tj\n`;
    y -= 20;
  }
  contentStreamText += 'ET\n';

  const contentStreamObj = `4 0 obj\n<< /Length ${contentStreamText.length} >>\nstream\n${contentStreamText}endstream\nendobj\n`;

  const body = `%PDF-1.4\n${stream}${contentStreamObj}`;

  // Calculate xref
  const xrefOffset = body.length;
  const xref = `xref\n0 6\n0000000000 65535 f \n` +
    `0000000009 00000 n \n` +
    `0000000058 00000 n \n` +
    `0000000115 00000 n \n` +
    `${String(body.indexOf('4 0 obj')).padStart(10, '0')} 00000 n \n` +
    `${String(body.indexOf('5 0 obj')).padStart(10, '0')} 00000 n \n`;

  const trailer = `trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;

  return Buffer.from(body + xref + trailer);
}

/**
 * Ensure CMIS-v1.1-Specification-Sample.pdf exists in the repository.
 * This PDF is required by advanced-search.spec.ts tests for full-text search verification.
 */
/**
 * Helper: Create a document with content in a single multipart createDocument request.
 * This avoids the setContent two-step approach which requires changeToken and can fail silently.
 */
async function createDocumentWithContent(
  baseURL: string,
  authHeader: string,
  docName: string,
  pdfBuffer: Buffer
): Promise<string | null> {
  const boundary = '----FormBoundary' + Math.random().toString(36).substring(2);
  const parts: Buffer[] = [];

  const addField = (name: string, value: string) => {
    parts.push(Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`
    ));
  };

  addField('cmisaction', 'createDocument');
  addField('propertyId[0]', 'cmis:objectTypeId');
  addField('propertyValue[0]', 'cmis:document');
  addField('propertyId[1]', 'cmis:name');
  addField('propertyValue[1]', docName);

  // Include file content in the same createDocument request
  parts.push(Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="content"; filename="${docName}"\r\nContent-Type: application/pdf\r\n\r\n`
  ));
  parts.push(pdfBuffer);
  parts.push(Buffer.from('\r\n'));
  parts.push(Buffer.from(`--${boundary}--\r\n`));

  const body = Buffer.concat(parts);

  const response = await fetch(
    `${baseURL}/core/browser/bedroom/root`,
    {
      method: 'POST',
      headers: {
        'Authorization': authHeader,
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
      },
      body: body,
      signal: AbortSignal.timeout(30000)
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    if (errorText.includes('already exists') || errorText.includes('AlreadyExists')) {
      return null; // Already exists
    }
    throw new Error(`Failed to create ${docName}: ${response.status} ${errorText.substring(0, 200)}`);
  }

  const data = await response.json();
  const docId = data.succinctProperties?.['cmis:objectId'] || data.properties?.['cmis:objectId']?.value;
  return docId;
}

/**
 * Helper: Upload content to an existing document that has no content stream.
 * Fetches the changeToken first, then calls setContent.
 */
async function setContentForExistingDoc(
  baseURL: string,
  authHeader: string,
  objectId: string,
  fileName: string,
  pdfBuffer: Buffer
): Promise<boolean> {
  // Get current changeToken
  const objResponse = await fetch(
    `${baseURL}/core/browser/bedroom?cmisselector=object&objectId=${objectId}`,
    { headers: { 'Authorization': authHeader }, signal: AbortSignal.timeout(10000) }
  );
  if (!objResponse.ok) throw new Error(`Failed to get object ${objectId}: ${objResponse.status}`);
  const objData = await objResponse.json();
  const changeToken = objData.succinctProperties?.['cmis:changeToken'] || objData.properties?.['cmis:changeToken']?.value;

  const boundary = '----FormBoundary' + Math.random().toString(36).substring(2);
  const parts: Buffer[] = [];
  const addField = (name: string, value: string) => {
    parts.push(Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`
    ));
  };

  addField('cmisaction', 'setContent');
  addField('objectId', objectId);
  if (changeToken) {
    addField('changeToken', changeToken);
  }
  parts.push(Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="content"; filename="${fileName}"\r\nContent-Type: application/pdf\r\n\r\n`
  ));
  parts.push(pdfBuffer);
  parts.push(Buffer.from('\r\n'));
  parts.push(Buffer.from(`--${boundary}--\r\n`));

  const response = await fetch(
    `${baseURL}/core/browser/bedroom`,
    {
      method: 'POST',
      headers: {
        'Authorization': authHeader,
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
      },
      body: Buffer.concat(parts),
      signal: AbortSignal.timeout(30000)
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`setContent failed for ${fileName}: ${response.status} ${errorText.substring(0, 200)}`);
  }
  return true;
}

async function ensureTestPdfExists(baseURL: string): Promise<void> {
  const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
  const pdfName = 'CMIS-v1.1-Specification-Sample.pdf';
  const jpPdfName = '日本語ドキュメント.pdf';

  try {
    // Check existing children of root folder
    const childrenResponse = await fetch(
      `${baseURL}/core/browser/bedroom/root?cmisselector=children`,
      {
        headers: { 'Authorization': authHeader },
        signal: AbortSignal.timeout(10000)
      }
    );

    let objects: any[] = [];
    if (childrenResponse.ok) {
      const childrenData = await childrenResponse.json();
      objects = childrenData.objects || [];
    }

    const pdfBuffer = createTestPdf();

    // Process each PDF: check existence + content, create or repair as needed
    for (const name of [pdfName, jpPdfName]) {
      const existing = objects.find((obj: any) =>
        obj.object?.properties?.['cmis:name']?.value === name
      );

      if (existing) {
        // Check if content stream exists (contentStreamMimeType should be set for valid content)
        const mimeType = existing.object?.properties?.['cmis:contentStreamMimeType']?.value;
        const contentLength = existing.object?.properties?.['cmis:contentStreamLength']?.value;

        if (mimeType && contentLength > 0) {
          console.log(`✅ ${name} already exists in repository`);
          continue;
        }

        // Document exists but has no content - repair by uploading content
        const objectId = existing.object?.properties?.['cmis:objectId']?.value;
        console.log(`🔧 ${name} exists but has no content (mime=${mimeType}, length=${contentLength}), uploading content...`);
        const success = await setContentForExistingDoc(baseURL, authHeader, objectId, name, pdfBuffer);
        if (success) {
          console.log(`✅ ${name} content repaired successfully`);
        }
      } else {
        // Create new document with content in single request
        console.log(`📄 Creating ${name} with content...`);
        const docId = await createDocumentWithContent(baseURL, authHeader, name, pdfBuffer);
        if (docId) {
          console.log(`✅ ${name} created with content (ID: ${docId})`);
        }
      }
    }

    console.log('⏳ Waiting for Solr indexing...');
  } catch (error) {
    throw new Error(`Required test PDF setup failed: ${error instanceof Error ? error.message : String(error)}`);
  }
}

async function ensureTestDataExists(baseURL: string): Promise<void> {
  const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');

  try {
    // Check if root folder has any children (folders)
    const childrenResponse = await fetch(
      `${baseURL}/core/browser/bedroom/root?cmisselector=children`,
      {
        headers: { 'Authorization': authHeader },
        signal: AbortSignal.timeout(10000)
      }
    );

    if (!childrenResponse.ok) {
      console.log('⚠️ Could not check root folder children');
      return;
    }

    const childrenData = await childrenResponse.json();
    const objects = childrenData.objects || [];
    const hasFolders = objects.some((obj: any) =>
      obj.object?.properties?.['cmis:baseTypeId']?.value === 'cmis:folder'
    );

    if (hasFolders) {
      console.log('✅ Root folder has existing folders');
    } else {
      // Create a Sites folder for tests that need folder navigation
      console.log('📁 Creating Sites folder for navigation tests...');
      const formData = new URLSearchParams();
      formData.append('cmisaction', 'createFolder');
      formData.append('propertyId[0]', 'cmis:objectTypeId');
      formData.append('propertyValue[0]', 'cmis:folder');
      formData.append('propertyId[1]', 'cmis:name');
      formData.append('propertyValue[1]', 'Sites');

      // POST to root folder URL for createFolder
      const createResponse = await fetch(
        `${baseURL}/core/browser/bedroom/root`,
        {
          method: 'POST',
          headers: {
            'Authorization': authHeader,
            'Content-Type': 'application/x-www-form-urlencoded',
          },
          body: formData.toString(),
          signal: AbortSignal.timeout(10000)
        }
      );

      if (createResponse.ok) {
        console.log('✅ Sites folder created');
      } else {
        const errorText = await createResponse.text();
        if (errorText.includes('already exists') || errorText.includes('nameConstraintViolation')) {
          console.log('✅ Sites folder already exists');
        } else {
          console.log(`⚠️ Could not create Sites folder: ${createResponse.status} ${errorText.substring(0, 200)}`);
        }
      }
    }
  } catch (error) {
    console.log('⚠️ Could not ensure test data exists:', error);
  }
}

/**
 * Ensure custom types exist for custom-property-input E2E tests.
 * Creates test:customFolderForE2E and test:customRelForE2E if missing.
 */
async function ensureCustomTypesExist(baseURL: string): Promise<void> {
  const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
  const typeApiBase = `${baseURL}/core/rest/repo/bedroom/type`;

  const typesToCreate = [
    {
      id: 'test:customFolderForE2E',
      displayName: 'E2E Test Custom Folder',
      baseId: 'cmis:folder',
      parentId: 'cmis:folder',
      propertyDefinitions: [{
        id: 'test:folderCustomProp',
        localName: 'test:folderCustomProp',
        displayName: 'Custom Property',
        propertyType: 'string',
        cardinality: 'single',
        updatability: 'readwrite',
        required: false,
        queryable: true,
      }],
    },
    {
      id: 'test:customRelForE2E',
      displayName: 'E2E Test Custom Relationship',
      baseId: 'cmis:relationship',
      parentId: 'cmis:relationship',
      propertyDefinitions: [{
        id: 'test:relCustomProp',
        localName: 'test:relCustomProp',
        displayName: 'Custom Property',
        propertyType: 'string',
        cardinality: 'single',
        updatability: 'readwrite',
        required: false,
        queryable: true,
      }],
    },
  ];

  try {
    // Fetch existing types
    const listResp = await fetch(`${typeApiBase}/list`, {
      headers: { 'Authorization': authHeader },
      signal: AbortSignal.timeout(10000),
    });
    const existingTypes: string[] = [];
    if (listResp.ok) {
      const listData = await listResp.json();
      const types = Array.isArray(listData) ? listData : (listData.types || listData.value || []);
      for (const t of types) {
        const tid = typeof t === 'string' ? t : (t.id || t.typeId);
        if (tid) existingTypes.push(tid);
      }
    }

    for (const typeDef of typesToCreate) {
      if (existingTypes.includes(typeDef.id)) {
        console.log(`✅ ${typeDef.id} already exists`);
        continue;
      }

      console.log(`📝 Creating type ${typeDef.id}...`);
      const createResp = await fetch(`${typeApiBase}/create`, {
        method: 'POST',
        headers: {
          'Authorization': authHeader,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          id: typeDef.id,
          localName: typeDef.id,
          displayName: typeDef.displayName,
          baseId: typeDef.baseId,
          parentId: typeDef.parentId,
          propertyDefinitions: typeDef.propertyDefinitions,
        }),
        signal: AbortSignal.timeout(15000),
      });

      if (createResp.ok) {
        console.log(`✅ ${typeDef.id} created successfully`);
      } else {
        const errText = await createResp.text();
        if (errText.includes('already') || errText.includes('Already')) {
          console.log(`✅ ${typeDef.id} already exists`);
        } else {
          console.log(`⚠️ Could not create ${typeDef.id}: ${createResp.status} ${errText.substring(0, 200)}`);
        }
      }
    }
  } catch (error) {
    console.log('⚠️ Could not ensure custom types exist:', error);
  }
}

async function globalSetup(config: FullConfig) {
  console.log('🚀 Starting NemakiWare UI Test Global Setup');
  console.log('');

  // Step 1: Check Keycloak availability (optional)
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('Step 1: Keycloak (External Authentication)');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');

  let keycloakAvailable = false;

  if (SKIP_KEYCLOAK) {
    console.log('⏭️ Keycloak check skipped (SKIP_KEYCLOAK=true)');
    console.log('   OIDC/SAML/LDAP tests will be skipped');
  } else {
    keycloakAvailable = await isKeycloakRunning();
    if (keycloakAvailable) {
      console.log('✅ Keycloak is available at ' + KEYCLOAK_URL);
    } else {
      console.log('ℹ️ Keycloak is not running');
      console.log('   OIDC/SAML/LDAP tests will be skipped');
      console.log('   To run external auth tests, start Keycloak:');
      console.log('   cd docker && docker compose -f docker-compose-ldap-keycloak-test.yml up -d');
    }
  }

  // Save state for tests to read
  saveTestState({ keycloakAvailable, keycloakUrl: KEYCLOAK_URL });

  // Step 2: Check NemakiWare backend availability
  console.log('');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('Step 2: NemakiWare Backend');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');

  const baseURL = config.projects[0]?.use?.baseURL || 'http://localhost:8080';
  console.log(`📡 Checking NemakiWare backend at ${baseURL}`);

  if (await isBackendRunning(baseURL)) {
    console.log('✅ NemakiWare backend is available');
  } else {
    const errorMsg = `❌ NemakiWare backend not available at ${baseURL}. Please start the Docker containers.`;
    console.error(errorMsg);
    throw new Error(errorMsg);
  }

  // Step 3: Ensure testuser exists for non-admin tests
  console.log('');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('Step 3: Test User Setup');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  await ensureTestUser(baseURL, 'api-e2e-testuser', 'testtest');
  await ensureTestUser(baseURL, 'api-e2e-admintest', 'testtest');

  // Step 4: Ensure test data exists (folders for navigation tests)
  console.log('');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('Step 4: Test Data Setup');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  await ensureTestDataExists(baseURL);

  // Step 5: Ensure test PDF exists for search tests
  console.log('');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('Step 5: PDF Test Data Setup');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  await ensureTestPdfExists(baseURL);

  // Step 6: Ensure custom types exist for property input tests
  console.log('');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('Step 6: Custom Type Setup');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  await ensureCustomTypesExist(baseURL);

  // Summary
  console.log('');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('🎉 Global Setup Complete');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  if (keycloakAvailable) {
    console.log(`   Keycloak: ${KEYCLOAK_URL} ✅`);
  } else {
    console.log('   Keycloak: Not available (external auth tests skipped)');
  }
  console.log(`   Backend:  ${baseURL}`);
  console.log('');
}

export default globalSetup;
