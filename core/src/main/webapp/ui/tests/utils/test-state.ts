/**
 * Test State Utilities
 *
 * Provides access to global test state set by global-setup.ts
 * Used by tests to conditionally skip tests based on environment availability.
 */

import * as fs from 'fs';

const STATE_FILE = '/tmp/nemakiware-test-state.json';

interface TestState {
  keycloakAvailable: boolean;
  keycloakUrl: string;
}

/**
 * Read the test state from the global setup
 */
export function getTestState(): TestState {
  try {
    if (fs.existsSync(STATE_FILE)) {
      const content = fs.readFileSync(STATE_FILE, 'utf-8');
      return JSON.parse(content);
    }
  } catch (error) {
    console.warn('Could not read test state:', error);
  }

  // Default state if file doesn't exist
  return {
    keycloakAvailable: false,
    keycloakUrl: 'http://localhost:8088'
  };
}

/**
 * Check if Keycloak is available for external auth tests
 */
export function isKeycloakAvailable(): boolean {
  return getTestState().keycloakAvailable;
}

/**
 * Get Keycloak URL
 */
export function getKeycloakUrl(): string {
  return getTestState().keycloakUrl;
}

/**
 * Skip message for tests that require Keycloak
 */
export const KEYCLOAK_SKIP_MESSAGE = 'Keycloak not available - skipping external auth test';
