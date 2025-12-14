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
