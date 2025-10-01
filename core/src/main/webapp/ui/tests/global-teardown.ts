import { FullConfig } from '@playwright/test';
import fs from 'fs';

/**
 * Global teardown for NemakiWare UI tests
 *
 * This teardown:
 * - Cleans up test artifacts
 * - Removes temporary files
 * - Logs test completion summary
 */
async function globalTeardown(config: FullConfig) {
  console.log('🧹 Starting NemakiWare UI Test Global Teardown');

  try {
    // Clean up authentication state file
    const authStatePath = 'tests/fixtures/auth-state.json';
    if (fs.existsSync(authStatePath)) {
      fs.unlinkSync(authStatePath);
      console.log('🗑️  Cleaned up authentication state file');
    }

    // Clean up any temporary test files
    const tempFiles = [
      'test-results.json',
    ];

    tempFiles.forEach(file => {
      if (fs.existsSync(file)) {
        fs.unlinkSync(file);
        console.log(`🗑️  Cleaned up ${file}`);
      }
    });

    console.log('✅ Global teardown completed successfully');

  } catch (error) {
    console.error('❌ Global teardown failed:', error);
    // Don't throw error in teardown to avoid masking test failures
  }
}

export default globalTeardown;