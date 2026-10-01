import { defineConfig } from '@playwright/test';

/**
 * Runs only the locale-keys lint (tests/i18n/locale-keys.spec.ts). The lint reads files and opens
 * no browser, so this config has no global setup: the main config's global setup needs a running
 * backend, and the CI job that runs this config has none (.github/workflows/ui-unit.yml, which runs
 * vitest in the same job).
 */
export default defineConfig({
  testDir: './tests/i18n',
  testMatch: 'locale-keys.spec.ts',
  reporter: [['list']],
  workers: 1,
});
