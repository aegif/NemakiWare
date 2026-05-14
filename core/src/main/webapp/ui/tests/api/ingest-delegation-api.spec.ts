import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * Connector / Profile delegation — REST API security gates.
 *
 * Pinned by 3.1.1-RC3 design. The single locked boundary verified here:
 *
 *   "Non-admin who holds cmis:all on folder F may create / edit / delete
 *    manual-only delegated profiles bound to F, using only connectors an
 *    admin has expressly delegated. Admin-owned profiles and the
 *    scheduler are untouched."
 *
 * Each test is self-contained — the cmis:all ACE is granted on a fresh
 * subfolder created by admin, so failures don't pollute the bedroom root
 * for other suites.
 *
 * Non-admin user: api-e2e-testuser / testtest (set up by global-setup.ts).
 */

const BASE = 'http://localhost:8080/core/api';
const CMIS = 'http://localhost:8080/core/browser/bedroom';

const ADMIN_AUTH = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const USER_AUTH = 'Basic ' + Buffer.from('api-e2e-testuser:testtest').toString('base64');

// CSRF gate: Basic auth does NOT bypass CsrfValidator on /api/v1/*. Send
// X-Requested-With on every state-changing request — see CLAUDE.md.
const ADMIN_H = { Authorization: ADMIN_AUTH, 'X-Requested-With': 'XMLHttpRequest' };
const ADMIN_JSON = { ...ADMIN_H, 'Content-Type': 'application/json' };
const USER_H = { Authorization: USER_AUTH, 'X-Requested-With': 'XMLHttpRequest' };
const USER_JSON = { ...USER_H, 'Content-Type': 'application/json' };

const SUFFIX = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;

interface Created {
  connectorId: string;
  profileId: string;
  folderId: string;
}

/**
 * Read a property value from a CMIS Browser-binding response, handling
 * both the verbose and succinct forms. Some endpoints (e.g. createFolder
 * POST) reply only with succinctProperties.
 */
function cmisProp(body: Record<string, unknown>, propId: string): unknown {
  const succinct = body.succinctProperties as Record<string, unknown> | undefined;
  if (succinct && succinct[propId] !== undefined) return succinct[propId];
  const props = body.properties as Record<string, { value: unknown }> | undefined;
  return props?.[propId]?.value;
}

/** Resolve the root folder objectId for {@code bedroom}. */
async function getRootFolderId(request: APIRequestContext): Promise<string> {
  const res = await request.get(`${CMIS}/root?cmisselector=object`, { headers: ADMIN_H });
  expect(res.status()).toBe(200);
  const body = await res.json();
  return cmisProp(body, 'cmis:objectId') as string;
}

/** Create an admin-owned folder and grant api-e2e-testuser cmis:all on it. */
async function createOwnedFolder(request: APIRequestContext, name: string): Promise<string> {
  const rootId = await getRootFolderId(request);

  // Browser binding's createFolder needs an explicit folderId/objectId param;
  // POSTing to /root alone gives "folderId parameter is required".
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'createFolder');
  formData.append('folderId', rootId);
  formData.append('propertyId[0]', 'cmis:objectTypeId');
  formData.append('propertyValue[0]', 'cmis:folder');
  formData.append('propertyId[1]', 'cmis:name');
  formData.append('propertyValue[1]', name);

  const res = await request.post(`${CMIS}`, {
    headers: { Authorization: ADMIN_AUTH, 'Content-Type': 'application/x-www-form-urlencoded' },
    data: formData.toString(),
  });
  expect(res.status(), `createFolder failed: ${await res.text()}`).toBe(201);
  const body = await res.json();
  const folderId = cmisProp(body, 'cmis:objectId') as string;

  // Grant cmis:all to api-e2e-testuser on this folder
  const acl = new URLSearchParams();
  acl.append('cmisaction', 'applyACL');
  acl.append('ACLPropagation', 'repositorydetermined');
  acl.append('addACEPrincipal[0]', 'api-e2e-testuser');
  acl.append('addACEPermission[0][0]', 'cmis:all');
  const aclRes = await request.post(`${CMIS}`, {
    headers: { Authorization: ADMIN_AUTH, 'Content-Type': 'application/x-www-form-urlencoded' },
    data: `objectId=${folderId}&${acl.toString()}`,
  });
  expect(aclRes.status()).toBe(200);
  return folderId;
}

async function deleteFolderTree(request: APIRequestContext, folderId: string): Promise<void> {
  const formData = new URLSearchParams();
  formData.append('cmisaction', 'deleteTree');
  formData.append('objectId', folderId);
  formData.append('allVersions', 'true');
  formData.append('continueOnFailure', 'true');
  await request.post(`${CMIS}`, {
    headers: { Authorization: ADMIN_AUTH, 'Content-Type': 'application/x-www-form-urlencoded' },
    data: formData.toString(),
  }).catch(() => {});
}

/**
 * Create a delegated connector + admin-owned folder + delegated profile
 * scoped to that folder for {@code api-e2e-testuser}. Returns the IDs.
 */
async function setupDelegated(request: APIRequestContext, label: string): Promise<Created> {
  const folderId = await createOwnedFolder(request, `delg-${label}-${SUFFIX}`);

  const connectorId = `delg-conn-${label}-${SUFFIX}`;
  const cRes = await request.post(`${BASE}/v1/admin/connectors`, {
    headers: ADMIN_JSON,
    data: {
      connectorId,
      sourceArchetype: 'FILE_SHARE',
      sourceSystem: 'box',
      authType: 'none',
      enabled: true,
      delegated: true,
      allowedFolderIds: [folderId],
      allowedPrincipalIds: ['api-e2e-testuser'],
    },
  });
  expect(cRes.status()).toBe(201);

  const profileId = `delg-prof-${label}-${SUFFIX}`;
  const pRes = await request.post(`${BASE}/v1/admin/import-profiles`, {
    headers: USER_JSON, // user creates it via delegation
    data: {
      profileId,
      repositoryId: 'bedroom',
      targetFolderId: folderId,
      defaultConnectorId: connectorId,
      allowedConnectorIds: [connectorId],
      defaultObjectTypeId: 'cmis:document',
      dedupePolicy: 'create_new_version',
      versioningPolicy: 'major',
      enabled: true,
    },
  });
  expect(pRes.status(), `delegated profile create should succeed (got ${pRes.status()}: ${await pRes.text()})`).toBe(201);

  return { connectorId, profileId, folderId };
}

async function teardown(request: APIRequestContext, c: Created): Promise<void> {
  await request.delete(`${BASE}/v1/admin/import-profiles/${c.profileId}`, { headers: ADMIN_H }).catch(() => {});
  await request.delete(`${BASE}/v1/admin/connectors/${c.connectorId}`, { headers: ADMIN_H }).catch(() => {});
  await deleteFolderTree(request, c.folderId);
}

test.describe('Ingest delegation — REST API gates', () => {

  // ── Connector CRUD remains admin-only ──────────────────────────

  test('non-admin GET /connectors → 403', async ({ request }) => {
    const res = await request.get(`${BASE}/v1/admin/connectors`, { headers: USER_H });
    expect(res.status()).toBe(403);
  });

  test('non-admin POST /connectors → 403', async ({ request }) => {
    const res = await request.post(`${BASE}/v1/admin/connectors`, {
      headers: USER_JSON,
      data: { connectorId: `unauth-${SUFFIX}`, sourceArchetype: 'FILE_SHARE',
              sourceSystem: 'box', authType: 'none', enabled: true },
    });
    expect(res.status()).toBe(403);
  });

  // ── ConnectorDefinition validation invariants ──────────────────

  test('admin POST /connectors with delegated=true + empty scope → 400', async ({ request }) => {
    const id = `bad-scope-${SUFFIX}`;
    const res = await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: {
        connectorId: id,
        sourceArchetype: 'FILE_SHARE',
        sourceSystem: 'box',
        authType: 'none',
        enabled: true,
        delegated: true,
        // empty allowedFolderIds AND delegateAllFolders=false → no delegation
      },
    });
    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(String(body.message)).toContain('delegated=true');
    // Cleanup any partially-created record (validation happens before save anyway)
    await request.delete(`${BASE}/v1/admin/connectors/${id}`, { headers: ADMIN_H }).catch(() => {});
  });

  test('admin POST /connectors with delegated=false + scope fields → 400', async ({ request }) => {
    const id = `bad-stale-${SUFFIX}`;
    const res = await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: {
        connectorId: id,
        sourceArchetype: 'FILE_SHARE',
        sourceSystem: 'box',
        authType: 'none',
        enabled: true,
        delegated: false,
        allowedFolderIds: ['some-folder'], // stale
      },
    });
    expect(res.status()).toBe(400);
    await request.delete(`${BASE}/v1/admin/connectors/${id}`, { headers: ADMIN_H }).catch(() => {});
  });

  test('admin POST /connectors with delegateAllFolders + allowedFolderIds → 400', async ({ request }) => {
    const id = `bad-mutex-${SUFFIX}`;
    const res = await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: {
        connectorId: id,
        sourceArchetype: 'FILE_SHARE',
        sourceSystem: 'box',
        authType: 'none',
        enabled: true,
        delegated: true,
        delegateAllFolders: true,
        allowedFolderIds: ['x'], // mutually exclusive with delegateAllFolders
      },
    });
    expect(res.status()).toBe(400);
    await request.delete(`${BASE}/v1/admin/connectors/${id}`, { headers: ADMIN_H }).catch(() => {});
  });

  // ── /connectors/summary endpoint ────────────────────────────────

  test('summary endpoint requires cmis:all on target folder', async ({ request }) => {
    const c = await setupDelegated(request, 'summary-ok');
    try {
      // User HAS cmis:all on c.folderId — summary call succeeds
      const res = await request.get(
        `${BASE}/v1/admin/connectors/summary?repositoryId=bedroom&targetFolderId=${encodeURIComponent(c.folderId)}`,
        { headers: USER_H },
      );
      expect(res.status()).toBe(200);
      const body = await res.json();
      const found = body.find((x: { connectorId: string }) => x.connectorId === c.connectorId);
      expect(found).toBeTruthy();
      // Slim DTO: NO secret / endpoint / scope leakage
      expect(found).not.toHaveProperty('credentialRef');
      expect(found).not.toHaveProperty('webhookSecret');
      expect(found).not.toHaveProperty('endpoint');
      expect(found).not.toHaveProperty('allowedFolderIds');
      expect(found).not.toHaveProperty('allowedPrincipalIds');
    } finally {
      await teardown(request, c);
    }
  });

  test('summary endpoint denies caller without cmis:all on folder', async ({ request }) => {
    // Folder where api-e2e-testuser does NOT have cmis:all (root folder)
    const rootId = await getRootFolderId(request);
    const res = await request.get(
      `${BASE}/v1/admin/connectors/summary?repositoryId=bedroom&targetFolderId=${encodeURIComponent(rootId)}`,
      { headers: USER_H },
    );
    expect(res.status()).toBe(403);
  });

  // ── Profile create gating ──────────────────────────────────────

  test('non-admin profile create rejects schedulerEnabled=true', async ({ request }) => {
    const folderId = await createOwnedFolder(request, `sch-${SUFFIX}`);
    const connectorId = `sch-conn-${SUFFIX}`;
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: {
        connectorId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'box',
        authType: 'none', enabled: true, delegated: true, allowedFolderIds: [folderId],
      },
    });
    try {
      const res = await request.post(`${BASE}/v1/admin/import-profiles`, {
        headers: USER_JSON,
        data: {
          profileId: `sch-prof-${SUFFIX}`, repositoryId: 'bedroom',
          targetFolderId: folderId, defaultConnectorId: connectorId,
          allowedConnectorIds: [connectorId], schedulerEnabled: true, // ← forbidden
          enabled: true,
        },
      });
      expect(res.status()).toBe(403);
      const body = await res.json();
      expect(String(body.message)).toContain('Scheduled');
    } finally {
      await request.delete(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: ADMIN_H }).catch(() => {});
      await deleteFolderTree(request, folderId);
    }
  });

  test('non-admin profile create rejects defaultProfile=true', async ({ request }) => {
    const folderId = await createOwnedFolder(request, `dp-${SUFFIX}`);
    const connectorId = `dp-conn-${SUFFIX}`;
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: {
        connectorId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'box',
        authType: 'none', enabled: true, delegated: true, allowedFolderIds: [folderId],
      },
    });
    try {
      const res = await request.post(`${BASE}/v1/admin/import-profiles`, {
        headers: USER_JSON,
        data: {
          profileId: `dp-prof-${SUFFIX}`, repositoryId: 'bedroom',
          targetFolderId: folderId, defaultConnectorId: connectorId,
          allowedConnectorIds: [connectorId], defaultProfile: true, // ← forbidden
          enabled: true,
        },
      });
      expect(res.status()).toBe(403);
    } finally {
      await request.delete(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: ADMIN_H }).catch(() => {});
      await deleteFolderTree(request, folderId);
    }
  });

  test('non-admin profile create rejects undelegated connector', async ({ request }) => {
    const folderId = await createOwnedFolder(request, `und-${SUFFIX}`);
    const connectorId = `und-conn-${SUFFIX}`;
    // Connector NOT delegated
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: {
        connectorId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'box',
        authType: 'none', enabled: true, // delegated: false (default)
      },
    });
    try {
      const res = await request.post(`${BASE}/v1/admin/import-profiles`, {
        headers: USER_JSON,
        data: {
          profileId: `und-prof-${SUFFIX}`, repositoryId: 'bedroom',
          targetFolderId: folderId, defaultConnectorId: connectorId,
          allowedConnectorIds: [connectorId], enabled: true,
        },
      });
      expect(res.status()).toBe(403);
      const body = await res.json();
      expect(String(body.message)).toContain('Connector not delegated');
    } finally {
      await request.delete(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: ADMIN_H }).catch(() => {});
      await deleteFolderTree(request, folderId);
    }
  });

  test('non-admin profile create rejects empty allowedConnectorIds', async ({ request }) => {
    const folderId = await createOwnedFolder(request, `ec-${SUFFIX}`);
    try {
      const res = await request.post(`${BASE}/v1/admin/import-profiles`, {
        headers: USER_JSON,
        data: {
          profileId: `ec-prof-${SUFFIX}`, repositoryId: 'bedroom',
          targetFolderId: folderId,
          allowedConnectorIds: [], // ← empty rejected (no implicit "any connector")
          enabled: true,
        },
      });
      expect(res.status()).toBe(400);
    } finally {
      await deleteFolderTree(request, folderId);
    }
  });

  test('non-admin profile create rejects targetFolder where user lacks cmis:all', async ({ request }) => {
    // Create folder owned by admin only — no ACE for testuser
    const rootId = await getRootFolderId(request);
    const formData = new URLSearchParams();
    formData.append('cmisaction', 'createFolder');
    formData.append('folderId', rootId);
    formData.append('propertyId[0]', 'cmis:objectTypeId');
    formData.append('propertyValue[0]', 'cmis:folder');
    formData.append('propertyId[1]', 'cmis:name');
    formData.append('propertyValue[1]', `priv-${SUFFIX}`);
    const fr = await request.post(`${CMIS}`, {
      headers: { Authorization: ADMIN_AUTH, 'Content-Type': 'application/x-www-form-urlencoded' },
      data: formData.toString(),
    });
    const folderId = cmisProp(await fr.json(), 'cmis:objectId') as string;

    const connectorId = `priv-conn-${SUFFIX}`;
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: {
        connectorId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'box',
        authType: 'none', enabled: true, delegated: true, delegateAllFolders: true,
      },
    });
    try {
      const res = await request.post(`${BASE}/v1/admin/import-profiles`, {
        headers: USER_JSON,
        data: {
          profileId: `priv-prof-${SUFFIX}`, repositoryId: 'bedroom',
          targetFolderId: folderId, defaultConnectorId: connectorId,
          allowedConnectorIds: [connectorId], enabled: true,
        },
      });
      expect(res.status()).toBe(403);
      const body = await res.json();
      expect(String(body.message)).toContain('cmis:all');
    } finally {
      await request.delete(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: ADMIN_H }).catch(() => {});
      await deleteFolderTree(request, folderId);
    }
  });

  // ── Delegated profile is force-stamped with safe defaults ───────

  test('delegated profile is persisted with delegated=true / scheduler off / defaultProfile off', async ({ request }) => {
    const c = await setupDelegated(request, 'stamp');
    try {
      const res = await request.get(`${BASE}/v1/admin/import-profiles/${c.profileId}`, { headers: ADMIN_H });
      expect(res.status()).toBe(200);
      const { profile } = await res.json();
      expect(profile.delegated).toBe(true);
      expect(profile.schedulerEnabled).toBe(false);
      expect(profile.defaultProfile).toBe(false);
      expect(profile.createdByUserId).toBe('api-e2e-testuser');
    } finally {
      await teardown(request, c);
    }
  });

  // ── Admin-owned profiles are not editable by non-admin ──────────

  test('non-admin cannot PUT admin-owned profile', async ({ request }) => {
    const folderId = await createOwnedFolder(request, `aopu-${SUFFIX}`);
    const connectorId = `aopu-conn-${SUFFIX}`;
    const profileId = `aopu-prof-${SUFFIX}`;
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: { connectorId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'box',
              authType: 'none', enabled: true },
    });
    // Admin creates profile (delegated=false by default)
    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: ADMIN_JSON,
      data: { profileId, repositoryId: 'bedroom', targetFolderId: folderId,
              defaultConnectorId: connectorId, enabled: true },
    });
    try {
      // Non-admin tries to PUT — must be 403 even though they have cmis:all
      // (admin-owned profile gate)
      const res = await request.put(`${BASE}/v1/admin/import-profiles/${profileId}`, {
        headers: USER_JSON,
        data: { profileId, repositoryId: 'bedroom', targetFolderId: folderId,
                allowedConnectorIds: [connectorId], enabled: true },
      });
      expect(res.status()).toBe(403);
    } finally {
      await request.delete(`${BASE}/v1/admin/import-profiles/${profileId}`, { headers: ADMIN_H }).catch(() => {});
      await request.delete(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: ADMIN_H }).catch(() => {});
      await deleteFolderTree(request, folderId);
    }
  });

  test('non-admin cannot DELETE admin-owned profile', async ({ request }) => {
    const folderId = await createOwnedFolder(request, `aodel-${SUFFIX}`);
    const connectorId = `aodel-conn-${SUFFIX}`;
    const profileId = `aodel-prof-${SUFFIX}`;
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: { connectorId, sourceArchetype: 'FILE_SHARE', sourceSystem: 'box',
              authType: 'none', enabled: true },
    });
    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: ADMIN_JSON,
      data: { profileId, repositoryId: 'bedroom', targetFolderId: folderId,
              defaultConnectorId: connectorId, enabled: true },
    });
    try {
      const res = await request.delete(`${BASE}/v1/admin/import-profiles/${profileId}`, { headers: USER_H });
      expect(res.status()).toBe(403);
    } finally {
      await request.delete(`${BASE}/v1/admin/import-profiles/${profileId}`, { headers: ADMIN_H }).catch(() => {});
      await request.delete(`${BASE}/v1/admin/connectors/${connectorId}`, { headers: ADMIN_H }).catch(() => {});
      await deleteFolderTree(request, folderId);
    }
  });

  // ── List filtering ──────────────────────────────────────────────

  test('non-admin list shows delegated profiles only', async ({ request }) => {
    const c = await setupDelegated(request, 'list');
    // Also create an admin-owned profile that the user must NOT see
    const adminProfileId = `list-admin-${SUFFIX}`;
    await request.post(`${BASE}/v1/admin/import-profiles`, {
      headers: ADMIN_JSON,
      data: { profileId: adminProfileId, repositoryId: 'bedroom',
              targetFolderId: c.folderId, // even on the same folder!
              defaultConnectorId: c.connectorId, enabled: true },
    });
    try {
      const res = await request.get(`${BASE}/v1/admin/import-profiles?repositoryId=bedroom`, { headers: USER_H });
      expect(res.status()).toBe(200);
      const body: { profileId: string; delegated?: boolean }[] = await res.json();
      const ids = body.map(p => p.profileId);
      expect(ids).toContain(c.profileId);
      expect(ids).not.toContain(adminProfileId);
      // Defence in depth — even if filter regressed, ALL returned items
      // should at least be delegated
      for (const p of body) expect(p.delegated).toBe(true);
    } finally {
      await request.delete(`${BASE}/v1/admin/import-profiles/${adminProfileId}`, { headers: ADMIN_H }).catch(() => {});
      await teardown(request, c);
    }
  });

  // ── Runtime ingest gates ────────────────────────────────────────

  test('non-admin ingest rejects targetFolderOverride', async ({ request }) => {
    const c = await setupDelegated(request, 'ovr');
    try {
      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: USER_JSON,
        data: {
          profileId: c.profileId, connectorId: c.connectorId,
          sourceObjectId: 'src-1', sourceObjectType: 'file',
          targetFolderOverride: c.folderId, // ← forbidden in v1 even when same folder
        },
      });
      expect(res.status()).toBe(403);
    } finally {
      await teardown(request, c);
    }
  });

  test('non-admin ingest rejects missing profileId', async ({ request }) => {
    const c = await setupDelegated(request, 'noprof');
    try {
      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: USER_JSON,
        data: { connectorId: c.connectorId, sourceObjectId: 'src-1', sourceObjectType: 'file' },
      });
      expect(res.status()).toBe(403);
    } finally {
      await teardown(request, c);
    }
  });

  test('non-admin ingest rejects connector not in profile.allowedConnectorIds', async ({ request }) => {
    const c = await setupDelegated(request, 'mismatch');
    // A second delegated connector that the profile does NOT list
    const otherConn = `other-conn-${SUFFIX}`;
    await request.post(`${BASE}/v1/admin/connectors`, {
      headers: ADMIN_JSON,
      data: {
        connectorId: otherConn, sourceArchetype: 'FILE_SHARE', sourceSystem: 'box',
        authType: 'none', enabled: true, delegated: true, allowedFolderIds: [c.folderId],
      },
    });
    try {
      const res = await request.post(`${BASE}/v1/repo/bedroom/ingest`, {
        headers: USER_JSON,
        data: {
          profileId: c.profileId, connectorId: otherConn,
          sourceObjectId: 'src-1', sourceObjectType: 'file',
        },
      });
      expect(res.status()).toBe(403);
    } finally {
      await request.delete(`${BASE}/v1/admin/connectors/${otherConn}`, { headers: ADMIN_H }).catch(() => {});
      await teardown(request, c);
    }
  });
});
