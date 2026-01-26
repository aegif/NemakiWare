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
  const couchdbAuth = 'Basic ' + Buffer.from('admin:password').toString('base64');

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
