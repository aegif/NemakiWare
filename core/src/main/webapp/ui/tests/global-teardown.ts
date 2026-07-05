import { FullConfig, request } from '@playwright/test';
import fs from 'fs';

const BASE = 'http://localhost:8080/core';
const CMIS = `${BASE}/browser/bedroom`;
const AUTH = {
  Authorization: 'Basic ' + Buffer.from('admin:admin').toString('base64'),
  'X-Requested-With': 'XMLHttpRequest',
};

/**
 * Test-created object name prefixes that land in the repository ROOT. Specs
 * create documents/folders with these prefixes followed by a random suffix or
 * epoch-ms timestamp, so a `startsWith` match cannot collide with the handful
 * of seed objects (CMIS-v1.1-Specification-Sample.pdf, 日本語ドキュメント.pdf,
 * .system, Sites, Technical Documents). Most specs clean up after themselves;
 * this is a best-effort BACKSTOP for the few that leave root residue, so the
 * root folder does not grow unbounded across runs (accumulation slows the
 * Solr-backed document-list query most specs wait on after login).
 *
 * Runs once, AFTER the entire suite completes, so it can never disturb a
 * still-running spec.
 */
const ROOT_SWEEP_PREFIXES = [
  'test-',
  // ingest-pipeline-e2e imported docs (also swept in that spec's afterAll)
  'roundtrip-', 'hash-', 'meta-', 'dup-', 'nocont-', 'cloud-', 'cls-', 'skip-test-',
];

/** Best-effort sweep of test-created objects from the repository root. */
async function sweepRootTestObjects(): Promise<void> {
  const ctx = await request.newContext();
  try {
    const res = await ctx.get(`${CMIS}/root?cmisselector=children&maxItems=1000`, { headers: AUTH });
    if (!res.ok()) return;
    const body = await res.json();
    let deleted = 0;
    for (const o of body.objects ?? []) {
      const props = o.object?.properties ?? {};
      const name: string = props['cmis:name']?.value ?? '';
      const baseType: string = props['cmis:baseTypeId']?.value ?? '';
      const objectId = props['cmis:objectId']?.value;
      if (!objectId) continue;
      if (!ROOT_SWEEP_PREFIXES.some((p) => name.startsWith(p))) continue;
      // Folders may have children (e.g. lazy-parent trees) → deleteTree.
      const action = baseType === 'cmis:folder' ? 'deleteTree' : 'delete';
      const form: Record<string, string> =
        action === 'deleteTree'
          ? { cmisaction: 'deleteTree', objectId, allVersions: 'true', continueOnFailure: 'true', unfileObjects: 'delete' }
          : { cmisaction: 'delete', objectId, allVersions: 'true' };
      const del = await ctx.post(`${CMIS}`, { headers: AUTH, form }).catch(() => null);
      if (del && del.ok()) deleted++;
    }
    if (deleted > 0) console.log(`🗑️  Swept ${deleted} test object(s) from repository root`);
  } catch (e) {
    console.log(`[sweepRootTestObjects] best-effort error: ${e}`);
  } finally {
    await ctx.dispose().catch(() => {});
  }
}

/**
 * Global teardown for NemakiWare UI tests
 *
 * This teardown:
 * - Sweeps test-created objects from the repository root (best-effort backstop)
 * - Cleans up local test artifacts / temporary files
 */
async function globalTeardown(_config: FullConfig) {
  console.log('🧹 Starting NemakiWare UI Test Global Teardown');

  // Repository-side sweep (backstop for specs that leave root residue).
  await sweepRootTestObjects();

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