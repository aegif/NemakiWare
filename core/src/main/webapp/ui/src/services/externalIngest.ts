import { AuthService } from './auth';
import { parseJsonResponseBody } from './http/jsonFetch';

const CONNECTOR_URL = '/core/api/v1/admin/connectors';
const PROFILE_URL = '/core/api/v1/admin/import-profiles';

async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();
  // Scope /v1/admin/* ingest calls to the repository the user logged into.
  // The server authenticates these endpoints against this header (falling back
  // to the default repository when absent), so per-repository admins only ever
  // act on their own repository. Harmless for a default-repository login (the
  // header simply names the default repository).
  const repositoryId = authService.getRepositoryId();
  const repoHeader: Record<string, string> = repositoryId
    ? { 'X-Nemaki-Repository': repositoryId }
    : {};
  return fetch(url, {
    ...options,
    headers: {
      'Accept': 'application/json',
      ...headers,
      ...repoHeader,
      ...options.headers,
    },
  });
}

/** Single body read + HTTP status check */
async function parseJsonOrThrow<T>(res: Response, context: string): Promise<T> {
  const data = (await parseJsonResponseBody(res, context)) as Record<string, unknown>;
  if (!res.ok) {
    const msg = typeof data.message === 'string' ? data.message : `HTTP ${res.status}`;
    // The delegation gates emit a stable DenialReason enum alongside the
    // free-form message. We surface it in the thrown Error so callers can
    // show it in a toast and admins quoting the error don't have to copy
    // possibly-translated English text. Format: "[REASON] message".
    const reason = typeof data.denialReason === 'string' ? data.denialReason : null;
    const tagged = reason ? `[${reason}] ${msg}` : msg;
    throw new Error(`${context}: ${tagged}`);
  }
  return data as T;
}

// ── Adapter Registry ──────────────────────────────────────────────

export interface AdapterDescriptor {
  sourceSystem: string;
  displayName: string;
  archetype: string;
  requiredParams: string[];
  optionalParams: string[];
  webhookScopeKeys: string[];
  apiCallsPerItem: number;
  paramsExample: string;
}

let adapterRegistryCache: AdapterDescriptor[] | null = null;

/** Fetch the adapter registry (cached after first successful call). */
export async function fetchAdapterRegistry(): Promise<AdapterDescriptor[]> {
  if (adapterRegistryCache) return adapterRegistryCache;
  const res = await fetchWithAuth('/core/api/v1/admin/connectors/adapter-registry');
  if (!res.ok) return []; // Don't cache failures
  const data = await res.json();
  adapterRegistryCache = data as AdapterDescriptor[];
  return adapterRegistryCache;
}

// ── Connector Types ────────────────────────────────────────────────

export interface ConnectorDefinition {
  connectorId: string;
  displayName?: string;
  sourceArchetype: string;
  sourceSystem: string;
  authType?: string;
  credentialRef?: string;
  endpoint?: string;
  tenantId?: string;
  adapterKind?: string;
  rateLimitRpm?: number;
  webhookSecret?: string;
  enabled: boolean;
  /** When false, only admins may reference this connector. Default false. */
  delegated?: boolean;
  /** When true, ignore allowedFolderIds and let any folder use this connector. */
  delegateAllFolders?: boolean;
  /** Folder IDs (and descendants) that may reference this connector from a delegated profile. */
  allowedFolderIds?: string[];
  /** Username and group IDs allowed to reference this connector. Empty = no principal restriction. */
  allowedPrincipalIds?: string[];
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Slim connector summary returned by /admin/connectors/summary for non-admins.
 * Has no secret / endpoint / scope material.
 */
export interface ConnectorSummary {
  connectorId: string;
  displayName?: string;
  sourceArchetype?: string;
  sourceSystem?: string;
  adapterKind?: string;
}

// V3 (RC5 ext): governance dashboard — "what connectors does principal X have access to?"
export interface ConnectorPrincipalMatch {
  connectorId: string;
  displayName?: string;
  sourceArchetype?: string;
  sourceSystem?: string;
  adapterKind?: string;
  delegated: boolean;
  enabled: boolean;
  matchedPrincipalIds: string[];
  /** "direct" | "group" | "direct+group" */
  matchType: 'direct' | 'group' | 'direct+group';
}

export interface ConnectorByPrincipalResponse {
  principalId: string;
  /** "USER" | "GROUP" | "UNKNOWN" — added by V2 (RC5 ext) */
  principalType: 'USER' | 'GROUP' | 'UNKNOWN';
  repositoryId: string;
  expand: boolean;
  expandedPrincipals: string[];
  matches: ConnectorPrincipalMatch[];
}

// ── Profile Types ──────────────────────────────────────────────────

export interface ImportProfileDefinition {
  profileId: string;
  displayName?: string;
  repositoryId: string;
  targetFolderId?: string;
  targetFolderPath?: string;
  defaultObjectTypeId?: string;
  secondaryTypeIds?: string[];
  allowedArchetypes?: string[];
  allowedConnectorIds?: string[];
  defaultConnectorId?: string;
  dedupePolicy?: string;
  dedupeMatchBy?: string;
  updatePolicy?: string;
  versioningPolicy?: string;
  relationshipPolicy?: string;
  retentionDays?: number;
  aclSyncPolicy?: string;
  enabled: boolean;
  defaultProfile?: boolean;
  schedulerEnabled?: boolean;
  schedulerParams?: Record<string, string>;
  preserveOriginalEml?: boolean;
  /** Username of the principal who created this profile. */
  createdByUserId?: string;
  /** True for profiles created by a non-admin via folder delegation. */
  delegated?: boolean;
  createdAt?: string;
  updatedAt?: string;
  /**
   * V1 (RC5 ext): set by the scheduler when it auto-disabled the
   * profile because the creator was inactive for N consecutive ticks.
   * Null on profiles disabled by an admin or never auto-disabled.
   * Cleared on the next admin re-enable.
   */
  lastAutoDisabledAt?: string;
  lastAutoDisabledReason?: string;
}

export interface ProfileResponse {
  profile: ImportProfileDefinition;
  warnings?: string[];
}

// ── Connector CRUD ─────────────────────────────────────────────────

export async function listConnectors(): Promise<ConnectorDefinition[]> {
  const res = await fetchWithAuth(CONNECTOR_URL);
  return parseJsonOrThrow<ConnectorDefinition[]>(res, 'listConnectors');
}

/**
 * Non-admin discovery — returns only connectors the caller is allowed to
 * reference for the given folder. The summary view never includes
 * credential / endpoint / scope fields. The caller must already hold
 * cmis:all on {@code targetFolderId}.
 */
export async function listConnectorSummary(
  repositoryId: string,
  targetFolderId: string,
): Promise<ConnectorSummary[]> {
  const url = `${CONNECTOR_URL}/summary?repositoryId=${encodeURIComponent(repositoryId)}&targetFolderId=${encodeURIComponent(targetFolderId)}`;
  const res = await fetchWithAuth(url);
  return parseJsonOrThrow<ConnectorSummary[]>(res, 'listConnectorSummary');
}

/**
 * V3 (RC5 ext): governance view — admin-only. Returns every delegated
 * connector whose {@code allowedPrincipalIds} contains
 * {@code principalId}. With {@code expand=true} also returns connectors
 * reachable via group expansion (only when the principal is a user;
 * see {@link ConnectorByPrincipalResponse#principalType}).
 */
export async function getConnectorsByPrincipal(
  principalId: string,
  repositoryId: string,
  expand: boolean,
): Promise<ConnectorByPrincipalResponse> {
  const url = `${CONNECTOR_URL}/by-principal/${encodeURIComponent(principalId)}`
    + `?repositoryId=${encodeURIComponent(repositoryId)}`
    + `&expand=${expand ? 'true' : 'false'}`;
  const res = await fetchWithAuth(url);
  return parseJsonOrThrow<ConnectorByPrincipalResponse>(res, 'getConnectorsByPrincipal');
}

// ── B3-2: group-membership governance ─────────────────────────────

export interface MemberImpact {
  userId: string;
  lostIfGroupRemoved: ConnectorPrincipalMatch[];
}

export interface ConnectorsByGroupResponse {
  groupId: string;
  groupType: 'GROUP' | 'UNKNOWN';
  groupName?: string | null;
  repositoryId: string;
  /** Untruncated group size — UI shows "N members, showing M". */
  memberCount: number;
  subGroupCount: number;
  /** Capped at memberLimit; full count in memberCount. */
  memberUserIds: string[];
  memberUserIdsTruncated: boolean;
  subGroupIds: string[];
  /** Echo of the effective memberLimit (server clamps to MAX_MEMBER_LIMIT). */
  memberLimit: number;
  /** Connectors that list groupId directly in allowedPrincipalIds. */
  directGrants: ConnectorPrincipalMatch[];
  /** Empty when includeMembers=false (the fast path). */
  perMemberImpact: MemberImpact[];
  perMemberImpactTruncated: boolean;
}

/**
 * RC6 B3-2: group-membership governance. Returns the group's direct
 * connector grants AND a per-member "what would each member lose if
 * the group were removed" view (sole-route detection per member).
 *
 * `includeMembers=false` is the fast path: skips per-member expansion
 * and returns `perMemberImpact: []`. Use it when the UI only needs
 * member count / direct grants.
 *
 * `memberLimit` caps both `memberUserIds` and `perMemberImpact` in
 * lock-step. The server clamps it to a hard ceiling (currently 1000);
 * the response echoes back the effective value so the UI can render
 * "N members, showing M".
 */
export async function getConnectorsByGroup(
  groupId: string,
  repositoryId: string,
  includeMembers: boolean,
  memberLimit: number,
): Promise<ConnectorsByGroupResponse> {
  const url = `${CONNECTOR_URL}/by-group/${encodeURIComponent(groupId)}`
    + `?repositoryId=${encodeURIComponent(repositoryId)}`
    + `&includeMembers=${includeMembers ? 'true' : 'false'}`
    + `&memberLimit=${Math.max(1, Math.floor(memberLimit))}`;
  const res = await fetchWithAuth(url);
  return parseJsonOrThrow<ConnectorsByGroupResponse>(res, 'getConnectorsByGroup');
}

/**
 * W2 (RC5.3): server-side simulate-remove. Same sole-route logic the
 * V5/V7 client computes, but invokable from CLI / scripts and used by
 * the UI when the match set is large enough that client-side
 * computation would be wasteful (the server already has the data).
 *
 * Returns a `{lost, kept}` partition of the matches that would exist
 * with the original expansion: lost = connectors where every
 * matched principal lies in `removePrincipalIds` (no alternate route
 * grants access); kept = everything else.
 *
 * Admin only on the server; this client method does not enforce that
 * but the endpoint will 403 non-admin callers.
 */
export interface SimulateRemoveResponse {
  principalId: string;
  principalType: 'USER' | 'GROUP' | 'UNKNOWN';
  repositoryId: string;
  expand: boolean;
  expandedPrincipals: string[];
  removePrincipalIds: string[];
  lost: ConnectorPrincipalMatch[];
  kept: ConnectorPrincipalMatch[];
}

export async function simulateRemovePrincipals(
  principalId: string,
  repositoryId: string,
  expand: boolean,
  removePrincipalIds: string[],
): Promise<SimulateRemoveResponse> {
  const url = `${CONNECTOR_URL}/by-principal/${encodeURIComponent(principalId)}/simulate-remove`;
  const res = await fetchWithAuth(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repositoryId, expand, removePrincipalIds }),
  });
  return parseJsonOrThrow<SimulateRemoveResponse>(res, 'simulateRemovePrincipals');
}

export async function getConnector(connectorId: string): Promise<ConnectorDefinition> {
  const res = await fetchWithAuth(`${CONNECTOR_URL}/${encodeURIComponent(connectorId)}`);
  return parseJsonOrThrow<ConnectorDefinition>(res, 'getConnector');
}

export async function createConnector(connector: ConnectorDefinition): Promise<{ status: string; connectorId: string }> {
  const res = await fetchWithAuth(CONNECTOR_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(connector),
  });
  return parseJsonOrThrow<{ status: string; connectorId: string }>(res, 'createConnector');
}

export async function updateConnector(connectorId: string, connector: Partial<ConnectorDefinition>): Promise<{ status: string }> {
  const res = await fetchWithAuth(`${CONNECTOR_URL}/${encodeURIComponent(connectorId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(connector),
  });
  return parseJsonOrThrow<{ status: string }>(res, 'updateConnector');
}

export async function deleteConnector(connectorId: string): Promise<void> {
  const res = await fetchWithAuth(`${CONNECTOR_URL}/${encodeURIComponent(connectorId)}`, {
    method: 'DELETE',
  });
  if (!res.ok) throw new Error(`Failed to delete connector: ${res.status}`);
}

// ── Profile CRUD ───────────────────────────────────────────────────

/**
 * W1 (RC5.3) / R4 (RC5.4): optional `autoDisabledSince` is an
 * ISO-8601 instant. When set, the server returns only profiles
 * whose `lastAutoDisabledAt` is &gt;= that cutoff. Used by V6's
 * window filter to push the work server-side for large profile
 * lists.
 *
 * Server input handling (RC5.4 R4 strict):
 * - Empty / undefined / `null` → pass-through (no filter applied).
 * - Valid ISO-8601 → filter applied normally.
 * - Non-empty malformed string → server returns **HTTP 400**.
 *   The shipped UI flow only ever sends `Date.toISOString()` so
 *   this never trips in practice; CLI / scripting callers should
 *   validate the cutoff client-side before sending to avoid the
 *   400.
 */
export interface ListProfilesOptions {
  repositoryId?: string;
  autoDisabledSince?: string;
}

export async function listProfiles(
  repositoryIdOrOptions?: string | ListProfilesOptions,
): Promise<ImportProfileDefinition[]> {
  // Back-compat: accept a bare repositoryId string OR an options object.
  // Existing callers pass `listProfiles('bedroom')` unchanged.
  const opts: ListProfilesOptions = typeof repositoryIdOrOptions === 'string'
    ? { repositoryId: repositoryIdOrOptions }
    : (repositoryIdOrOptions ?? {});
  const params = new URLSearchParams();
  if (opts.repositoryId) params.set('repositoryId', opts.repositoryId);
  if (opts.autoDisabledSince) params.set('autoDisabledSince', opts.autoDisabledSince);
  const qs = params.toString();
  const url = qs ? `${PROFILE_URL}?${qs}` : PROFILE_URL;
  const res = await fetchWithAuth(url);
  return parseJsonOrThrow<ImportProfileDefinition[]>(res, 'listProfiles');
}

export async function getProfile(profileId: string): Promise<ProfileResponse> {
  const res = await fetchWithAuth(`${PROFILE_URL}/${encodeURIComponent(profileId)}`);
  return parseJsonOrThrow<ProfileResponse>(res, 'getProfile');
}

export async function createProfile(
  profile: ImportProfileDefinition
): Promise<{ status: string; profileId: string; warnings?: string[] }> {
  const res = await fetchWithAuth(PROFILE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profile),
  });
  return parseJsonOrThrow<{ status: string; profileId: string; warnings?: string[] }>(res, 'createProfile');
}

export async function updateProfile(
  profileId: string,
  profile: Partial<ImportProfileDefinition>
): Promise<{ status: string; warnings?: string[] }> {
  const res = await fetchWithAuth(`${PROFILE_URL}/${encodeURIComponent(profileId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profile),
  });
  return parseJsonOrThrow<{ status: string; warnings?: string[] }>(res, 'updateProfile');
}

export async function deleteProfile(profileId: string): Promise<void> {
  const res = await fetchWithAuth(`${PROFILE_URL}/${encodeURIComponent(profileId)}`, {
    method: 'DELETE',
  });
  if (!res.ok) throw new Error(`Failed to delete profile: ${res.status}`);
}

// ── Ingest Jobs & DLQ ─────────────────────────────────────────────

const INGEST_URL = '/core/api/v1/admin/ingest';
const SCHEDULER_URL = '/core/api/v1/admin/ingest-scheduler';

export async function listIngestJobs(limit = 50): Promise<IngestJobRecord[]> {
  const res = await fetchWithAuth(`${INGEST_URL}/jobs?limit=${limit}`);
  return parseJsonOrThrow<IngestJobRecord[]>(res, 'listIngestJobs');
}

export async function listDlqEntries(limit = 100): Promise<{ count: number; entries: DlqEntry[] }> {
  const res = await fetchWithAuth(`${INGEST_URL}/dlq?limit=${limit}`);
  return parseJsonOrThrow<{ count: number; entries: DlqEntry[] }>(res, 'listDlqEntries');
}

export async function retryDlqEntry(dlqId: string): Promise<{ status: string; objectId?: string; errors?: string[] }> {
  const res = await fetchWithAuth(`${INGEST_URL}/dlq/${encodeURIComponent(dlqId)}/retry`, { method: 'POST' });
  return parseJsonOrThrow<{ status: string; objectId?: string; errors?: string[] }>(res, 'retryDlqEntry');
}

export async function deleteDlqEntry(dlqId: string): Promise<void> {
  const res = await fetchWithAuth(`${INGEST_URL}/dlq/${encodeURIComponent(dlqId)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`Failed to delete DLQ entry: ${res.status}`);
}

// ── Scheduler ─────────────────────────────────────────────────────

export async function getSchedulerStatus(): Promise<{ scheduledProfiles: unknown[]; count: number; idleProfiles: string[] }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/status`);
  return parseJsonOrThrow<{ scheduledProfiles: unknown[]; count: number; idleProfiles: string[] }>(
    res,
    'getSchedulerStatus'
  );
}

export async function getCheckpoints(profileId: string): Promise<{ profileId: string; checkpoints: Record<string, string> }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/checkpoint/${encodeURIComponent(profileId)}`);
  return parseJsonOrThrow<{ profileId: string; checkpoints: Record<string, string> }>(res, 'getCheckpoints');
}

export async function resetCheckpoint(profileId: string, scope?: string): Promise<{ status: string; message: string }> {
  const url = scope
    ? `${SCHEDULER_URL}/checkpoint/${encodeURIComponent(profileId)}?scope=${encodeURIComponent(scope)}`
    : `${SCHEDULER_URL}/checkpoint/${encodeURIComponent(profileId)}`;
  const res = await fetchWithAuth(url, { method: 'DELETE' });
  return parseJsonOrThrow<{ status: string; message: string }>(res, 'resetCheckpoint');
}

export async function triggerProfile(profileId: string): Promise<Record<string, unknown>> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/trigger/${encodeURIComponent(profileId)}`, { method: 'POST' });
  return parseJsonOrThrow<Record<string, unknown>>(res, 'triggerProfile');
}

export async function startIdleMonitoring(profileId: string): Promise<{ status: string; message: string }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/idle/start/${encodeURIComponent(profileId)}`, { method: 'POST' });
  return parseJsonOrThrow<{ status: string; message: string }>(res, 'startIdleMonitoring');
}

export async function stopIdleMonitoring(profileId: string): Promise<{ status: string; message: string }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/idle/stop/${encodeURIComponent(profileId)}`, { method: 'POST' });
  return parseJsonOrThrow<{ status: string; message: string }>(res, 'stopIdleMonitoring');
}

export async function getIdleStatus(): Promise<{ idleProfiles: string[] }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/idle/status`);
  return parseJsonOrThrow<{ idleProfiles: string[] }>(res, 'getIdleStatus');
}

// ── Types ─────────────────────────────────────────────────────────

export interface IngestJobRecord {
  jobId: string;
  profileId: string;
  connectorId: string;
  repositoryId: string;
  startedAt: string;
  completedAt?: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'PARTIAL';
  fetched: number;
  imported: number;
  skipped: number;
  failed: number;
  errors?: string[];
}

export interface DlqEntry {
  dlqId: string;
  profileId: string;
  connectorId: string;
  repositoryId: string;
  sourceObjectId: string;
  sourceObjectType: string;
  fileName?: string;
  failedAt: string;
  errorMessage: string;
  retryCount: number;
  lastRetryAt?: string;
  hasContent: boolean;
}
