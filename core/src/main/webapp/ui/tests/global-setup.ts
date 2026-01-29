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
 * - KEYCLOAK_URL: Keycloak server URL (default: http://localhost:8088)
 * - SKIP_KEYCLOAK: Set to 'true' to skip Keycloak checks entirely
 *
 * Test Categories:
 * - Standard tests: Run without Keycloak (basic auth, file operations, admin features)
 * - External auth tests: Require Keycloak (OIDC, SAML, LDAP integration)
 */

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8088';
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
async function ensureTestUserExists(): Promise<void> {
  const couchdbUrl = process.env.COUCHDB_URL || 'http://localhost:5984';
  const couchdbUser = process.env.COUCHDB_USER || 'admin';
  const couchdbPass = process.env.COUCHDB_PASS || 'password';
  const couchdbAuth = 'Basic ' + Buffer.from(`${couchdbUser}:${couchdbPass}`).toString('base64');

  // Use unique test user name to avoid conflict with Keycloak SSO users
  const testUserId = 'api-e2e-testuser';

  try {
    // Check if test user already exists
    const checkResponse = await fetch(`${couchdbUrl}/bedroom/_find`, {
      method: 'POST',
      headers: {
        'Authorization': couchdbAuth,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        selector: { type: 'user', userId: testUserId },
        limit: 1
      }),
      signal: AbortSignal.timeout(10000)
    });

    if (!checkResponse.ok) {
      console.log(`⚠️ Could not query CouchDB for ${testUserId}`);
      return;
    }

    const result = await checkResponse.json();

    if (result.docs && result.docs.length > 0) {
      const existingUser = result.docs[0];
      // Check if password is already BCrypt hashed
      if (existingUser.password && existingUser.password.startsWith('$2')) {
        console.log(`✅ ${testUserId} already exists with BCrypt password`);
        return;
      }

      // Update existing user with BCrypt password
      console.log(`🔐 Updating ${testUserId} with BCrypt password...`);
      existingUser.password = '$2a$12$WOlW7Yk7vFYz7kjFCz/GpeJ7B4kzWhnSMXH2UcN/iMAuiMcYC/Cie'; // BCrypt hash of 'test'

      const updateResponse = await fetch(`${couchdbUrl}/bedroom/${existingUser._id}`, {
        method: 'PUT',
        headers: {
          'Authorization': couchdbAuth,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(existingUser),
        signal: AbortSignal.timeout(10000)
      });

      if (updateResponse.ok) {
        console.log(`✅ ${testUserId} password updated to BCrypt`);
      } else {
        console.log(`⚠️ Failed to update ${testUserId} password`);
      }
    } else {
      // Create new test user (unique name to avoid conflict with SSO users)
      // Document format must match NemakiWare's nemaki:user structure
      console.log(`👤 Creating ${testUserId}...`);
      const newUser = {
        type: 'cmis:item',
        objectType: 'nemaki:user',
        userId: testUserId,
        password: '$2a$12$WOlW7Yk7vFYz7kjFCz/GpeJ7B4kzWhnSMXH2UcN/iMAuiMcYC/Cie', // BCrypt hash of 'test'
        admin: false,
        creator: 'system',
        modifier: 'system',
        document: false,
        content: false,
        folder: false,
        attachment: false,
        relationship: false,
        policy: false,
        aspects: [],
        acl: { entries: [] },
        secondaryIds: [],
        subTypeProperties: []
      };

      const createResponse = await fetch(`${couchdbUrl}/bedroom`, {
        method: 'POST',
        headers: {
          'Authorization': couchdbAuth,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(newUser),
        signal: AbortSignal.timeout(10000)
      });

      if (createResponse.ok) {
        console.log(`✅ ${testUserId} created successfully`);
      } else {
        console.log(`⚠️ Failed to create ${testUserId}:`, await createResponse.text());
      }
    }
  } catch (error) {
    console.log(`⚠️ Could not ensure ${testUserId} exists:`, error);
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
async function ensureTestPdfExists(baseURL: string): Promise<void> {
  const authHeader = 'Basic ' + Buffer.from('admin:admin').toString('base64');
  const pdfName = 'CMIS-v1.1-Specification-Sample.pdf';

  try {
    // Get root folder ID first (needed for both check and upload)
    const repoInfoResponse = await fetch(
      `${baseURL}/core/browser/bedroom?cmisselector=repositoryInfo`,
      {
        headers: { 'Authorization': authHeader },
        signal: AbortSignal.timeout(10000)
      }
    );
    const repoInfo = await repoInfoResponse.json();
    const rootFolderId = repoInfo.rootFolderId;

    // Check if PDF already exists by searching children of root folder
    const childrenResponse = await fetch(
      `${baseURL}/core/browser/bedroom/root?cmisselector=children`,
      {
        headers: { 'Authorization': authHeader },
        signal: AbortSignal.timeout(10000)
      }
    );

    if (childrenResponse.ok) {
      const childrenData = await childrenResponse.json();
      const objects = childrenData.objects || [];
      const existingPdf = objects.find((obj: any) =>
        obj.object?.properties?.['cmis:name']?.value === pdfName
      );
      if (existingPdf) {
        console.log(`✅ ${pdfName} already exists in repository`);
        return;
      }
    }

    // Create PDF content
    const pdfBuffer = createTestPdf();

    // Upload via application/x-www-form-urlencoded (without file content first, then setContent)
    // Use URLSearchParams for reliable encoding
    const formData = new URLSearchParams();
    formData.append('cmisaction', 'createDocument');
    formData.append('propertyId[0]', 'cmis:objectTypeId');
    formData.append('propertyValue[0]', 'cmis:document');
    formData.append('propertyId[1]', 'cmis:name');
    formData.append('propertyValue[1]', pdfName);
    formData.append('objectId', rootFolderId);

    console.log(`📄 Creating ${pdfName} for search tests...`);
    const createResponse = await fetch(
      `${baseURL}/core/browser/bedroom`,
      {
        method: 'POST',
        headers: {
          'Authorization': authHeader,
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: formData.toString(),
        signal: AbortSignal.timeout(30000)
      }
    );

    if (!createResponse.ok) {
      const errorText = await createResponse.text();
      if (errorText.includes('already exists') || errorText.includes('AlreadyExists')) {
        console.log(`✅ ${pdfName} already exists`);
        return;
      }
      console.log(`⚠️ Could not create ${pdfName}: ${createResponse.status} ${errorText.substring(0, 200)}`);
      return;
    }

    const createData = await createResponse.json();
    const docId = createData.succinctProperties?.['cmis:objectId'] || createData.properties?.['cmis:objectId']?.value;
    console.log(`✅ ${pdfName} created (ID: ${docId})`);

    // Now upload PDF content via setContentStream using multipart
    const boundary = '----FormBoundary' + Math.random().toString(36).substring(2);
    const parts: Buffer[] = [];

    const addField = (name: string, value: string) => {
      parts.push(Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`
      ));
    };

    addField('cmisaction', 'setContent');
    addField('objectId', docId);

    // Add file part
    parts.push(Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="content"; filename="${pdfName}"\r\nContent-Type: application/pdf\r\n\r\n`
    ));
    parts.push(pdfBuffer);
    parts.push(Buffer.from('\r\n'));
    parts.push(Buffer.from(`--${boundary}--\r\n`));

    const body = Buffer.concat(parts);

    const setContentResponse = await fetch(
      `${baseURL}/core/browser/bedroom`,
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

    if (setContentResponse.ok) {
      console.log(`✅ PDF content uploaded successfully`);
    } else {
      const errorText = await setContentResponse.text();
      console.log(`⚠️ Could not set PDF content: ${setContentResponse.status} ${errorText.substring(0, 200)}`);
    }

    // Also create Japanese-named PDF for multilingual search test
    const jpPdfName = '日本語ドキュメント.pdf';
    const jpChildrenData = childrenResponse.ok ? await (async () => {
      // Re-check children (we already fetched above but need to check for JP PDF)
      const resp = await fetch(
        `${baseURL}/core/browser/bedroom/root?cmisselector=children`,
        { headers: { 'Authorization': authHeader }, signal: AbortSignal.timeout(10000) }
      );
      return resp.ok ? await resp.json() : { objects: [] };
    })() : { objects: [] };

    const existingJpPdf = (jpChildrenData.objects || []).find((obj: any) =>
      obj.object?.properties?.['cmis:name']?.value === jpPdfName
    );

    if (!existingJpPdf) {
      const jpFormData = new URLSearchParams();
      jpFormData.append('cmisaction', 'createDocument');
      jpFormData.append('propertyId[0]', 'cmis:objectTypeId');
      jpFormData.append('propertyValue[0]', 'cmis:document');
      jpFormData.append('propertyId[1]', 'cmis:name');
      jpFormData.append('propertyValue[1]', jpPdfName);
      jpFormData.append('objectId', rootFolderId);

      console.log(`📄 Creating ${jpPdfName} for Japanese search tests...`);
      const jpCreateResponse = await fetch(
        `${baseURL}/core/browser/bedroom`,
        {
          method: 'POST',
          headers: {
            'Authorization': authHeader,
            'Content-Type': 'application/x-www-form-urlencoded',
          },
          body: jpFormData.toString(),
          signal: AbortSignal.timeout(30000)
        }
      );

      if (jpCreateResponse.ok) {
        const jpCreateData = await jpCreateResponse.json();
        const jpDocId = jpCreateData.succinctProperties?.['cmis:objectId'] || jpCreateData.properties?.['cmis:objectId']?.value;
        console.log(`✅ ${jpPdfName} created (ID: ${jpDocId})`);

        // Set content with Japanese text for full-text search
        const jpPdfBuffer = createTestPdf();
        const jpBoundary = '----FormBoundary' + Math.random().toString(36).substring(2);
        const jpParts: Buffer[] = [];
        const addJpField = (name: string, value: string) => {
          jpParts.push(Buffer.from(
            `--${jpBoundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`
          ));
        };
        addJpField('cmisaction', 'setContent');
        addJpField('objectId', jpDocId);
        jpParts.push(Buffer.from(
          `--${jpBoundary}\r\nContent-Disposition: form-data; name="content"; filename="${jpPdfName}"\r\nContent-Type: application/pdf\r\n\r\n`
        ));
        jpParts.push(jpPdfBuffer);
        jpParts.push(Buffer.from('\r\n'));
        jpParts.push(Buffer.from(`--${jpBoundary}--\r\n`));

        await fetch(`${baseURL}/core/browser/bedroom`, {
          method: 'POST',
          headers: {
            'Authorization': authHeader,
            'Content-Type': `multipart/form-data; boundary=${jpBoundary}`,
          },
          body: Buffer.concat(jpParts),
          signal: AbortSignal.timeout(30000)
        });
        console.log(`✅ ${jpPdfName} content uploaded`);
      } else {
        const jpErrorText = await jpCreateResponse.text();
        if (jpErrorText.includes('already exists') || jpErrorText.includes('AlreadyExists')) {
          console.log(`✅ ${jpPdfName} already exists`);
        } else {
          console.log(`⚠️ Could not create ${jpPdfName}: ${jpCreateResponse.status}`);
        }
      }
    } else {
      console.log(`✅ ${jpPdfName} already exists`);
    }

    console.log('⏳ Waiting for Solr indexing...');
  } catch (error) {
    console.log(`⚠️ Could not ensure test PDF exists:`, error);
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

      const rootIdResponse = await fetch(
        `${baseURL}/core/browser/bedroom?cmisselector=repositoryInfo`,
        {
          headers: { 'Authorization': authHeader },
          signal: AbortSignal.timeout(10000)
        }
      );
      const repoInfo = await rootIdResponse.json();
      const rootFolderId = repoInfo.rootFolderId;

      formData.append('objectId', rootFolderId);

      const createResponse = await fetch(
        `${baseURL}/core/browser/bedroom`,
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
  await ensureTestUserExists();

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
