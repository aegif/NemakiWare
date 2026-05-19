import { AuthService } from './auth';
import { parseJsonResponseBody } from './http/jsonFetch';

const CONNECTOR_URL = '/core/api/v1/admin/connectors';
const PROFILE_URL = '/core/api/v1/admin/import-profiles';

async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();
  return fetch(url, {
    ...options,
    headers: {
      'Accept': 'application/json',
      ...headers,
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

export async function listProfiles(repositoryId?: string): Promise<ImportProfileDefinition[]> {
  const url = repositoryId
    ? `${PROFILE_URL}?repositoryId=${encodeURIComponent(repositoryId)}`
    : PROFILE_URL;
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
