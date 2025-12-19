import { FullConfig } from '@playwright/test';
import { exec } from 'child_process';
import { promisify } from 'util';
import * as path from 'path';

const execAsync = promisify(exec);

/**
 * Global setup for NemakiWare UI tests
 *
 * This setup:
 * - Ensures Keycloak is running for OIDC/SAML tests
 * - Verifies backend availability via HTTP check
 *
 * CRITICAL (2025-12-14): Keycloak is REQUIRED for external authentication tests.
 * This setup will start Keycloak if not running.
 *
 * Note: Login verification is handled by individual tests with their own
 * authentication helpers (AuthHelper) for better isolation and reliability.
 */

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8088';

/**
 * Get the docker directory path
 * Directory structure:
 *   <project-root>/
 *     core/src/main/webapp/ui/tests/  <- tests directory (__dirname)
 *     docker/                         <- docker directory
 *
 * From tests/, we need to go up 6 levels to project root:
 * tests/ -> ui/ -> webapp/ -> main/ -> src/ -> core/ -> project-root/
 */
function getDockerDir(): string {
  // Use environment variable if set, otherwise calculate relative path
  if (process.env.DOCKER_DIR) {
    return process.env.DOCKER_DIR;
  }

  // Navigate from tests directory to project root
  // __dirname is: <project-root>/core/src/main/webapp/ui/tests
  // We need: <project-root>/docker
  const projectRoot = path.resolve(__dirname, '..', '..', '..', '..', '..', '..'); // 6 levels up
  return path.join(projectRoot, 'docker');
}

/**
 * Check if Keycloak is running and healthy
 */
async function isKeycloakRunning(): Promise<boolean> {
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

  try {
    // Check if testuser already exists
    const checkResponse = await fetch(`${couchdbUrl}/bedroom/_find`, {
      method: 'POST',
      headers: {
        'Authorization': couchdbAuth,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        selector: { type: 'user', userId: 'testuser' },
        limit: 1
      }),
      signal: AbortSignal.timeout(10000)
    });

    if (!checkResponse.ok) {
      console.log('⚠️ Could not query CouchDB for testuser');
      return;
    }

    const result = await checkResponse.json();

    if (result.docs && result.docs.length > 0) {
      const existingUser = result.docs[0];
      // Check if password is already BCrypt hashed
      if (existingUser.password && existingUser.password.startsWith('$2')) {
        console.log('✅ testuser already exists with BCrypt password');
        return;
      }

      // Update existing user with BCrypt password
      console.log('🔐 Updating testuser with BCrypt password...');
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
        console.log('✅ testuser password updated to BCrypt');
      } else {
        console.log('⚠️ Failed to update testuser password');
      }
    } else {
      // Create new testuser
      console.log('👤 Creating testuser...');
      const newUser = {
        type: 'user',
        userId: 'testuser',
        name: 'Test User',
        firstName: 'Test',
        lastName: 'User',
        email: 'testuser@example.com',
        password: '$2a$12$WOlW7Yk7vFYz7kjFCz/GpeJ7B4kzWhnSMXH2UcN/iMAuiMcYC/Cie', // BCrypt hash of 'test'
        admin: false,
        created: new Date().toISOString(),
        creator: 'admin'
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
        console.log('✅ testuser created successfully');
      } else {
        console.log('⚠️ Failed to create testuser:', await createResponse.text());
      }
    }
  } catch (error) {
    console.log('⚠️ Could not ensure testuser exists:', error);
  }
}

/**
 * Start Keycloak using docker-compose
 */
async function startKeycloak(): Promise<void> {
  console.log('🔐 Starting Keycloak...');

  const dockerDir = getDockerDir();
  console.log(`📁 Docker directory: ${dockerDir}`);

  try {
    await execAsync(`cd "${dockerDir}" && docker compose -f docker-compose.keycloak.yml up -d`);
    console.log('⏳ Waiting for Keycloak to become healthy (up to 90 seconds)...');

    // Wait for Keycloak to be ready
    const maxWait = 90;
    for (let i = 0; i < maxWait; i++) {
      if (await isKeycloakRunning()) {
        console.log(`✅ Keycloak is ready after ${i + 1} seconds`);
        return;
      }
      await new Promise(resolve => setTimeout(resolve, 1000));
    }

    throw new Error(`Keycloak did not become healthy within ${maxWait} seconds`);
  } catch (error) {
    console.error('❌ Failed to start Keycloak:', error);
    throw error;
  }
}

async function globalSetup(config: FullConfig) {
  console.log('🚀 Starting NemakiWare UI Test Global Setup');
  console.log('');

  // Step 1: Check and start Keycloak for external authentication tests
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
  console.log('Step 1: Keycloak (External Authentication)');
  console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');

  if (await isKeycloakRunning()) {
    console.log('✅ Keycloak is already running at ' + KEYCLOAK_URL);
  } else {
    console.log('⚠️ Keycloak is not running, starting...');
    await startKeycloak();
  }

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
  console.log(`   Keycloak: ${KEYCLOAK_URL}`);
  console.log(`   Backend:  ${baseURL}`);
  console.log('');
}

export default globalSetup;
