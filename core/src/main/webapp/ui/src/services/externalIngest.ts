import { AuthService } from './auth';

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
  createdAt?: string;
  updatedAt?: string;
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
  createdAt?: string;
  updatedAt?: string;
}

export interface ProfileResponse {
  profile: ImportProfileDefinition;
  warnings?: string[];
}

// ── Connector CRUD ─────────────────────────────────────────────────

export async function listConnectors(): Promise<ConnectorDefinition[]> {
  const res = await fetchWithAuth(CONNECTOR_URL);
  if (!res.ok) throw new Error(`Failed to list connectors: ${res.status}`);
  return res.json();
}

export async function getConnector(connectorId: string): Promise<ConnectorDefinition> {
  const res = await fetchWithAuth(`${CONNECTOR_URL}/${encodeURIComponent(connectorId)}`);
  if (!res.ok) throw new Error(`Failed to get connector: ${res.status}`);
  return res.json();
}

export async function createConnector(connector: ConnectorDefinition): Promise<{ status: string; connectorId: string }> {
  const res = await fetchWithAuth(CONNECTOR_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(connector),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || `Failed to create connector: ${res.status}`);
  }
  return res.json();
}

export async function updateConnector(connectorId: string, connector: Partial<ConnectorDefinition>): Promise<{ status: string }> {
  const res = await fetchWithAuth(`${CONNECTOR_URL}/${encodeURIComponent(connectorId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(connector),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || `Failed to update connector: ${res.status}`);
  }
  return res.json();
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
  if (!res.ok) throw new Error(`Failed to list profiles: ${res.status}`);
  return res.json();
}

export async function getProfile(profileId: string): Promise<ProfileResponse> {
  const res = await fetchWithAuth(`${PROFILE_URL}/${encodeURIComponent(profileId)}`);
  if (!res.ok) throw new Error(`Failed to get profile: ${res.status}`);
  return res.json();
}

export async function createProfile(
  profile: ImportProfileDefinition
): Promise<{ status: string; profileId: string; warnings?: string[] }> {
  const res = await fetchWithAuth(PROFILE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profile),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || `Failed to create profile: ${res.status}`);
  }
  return res.json();
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
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.message || `Failed to update profile: ${res.status}`);
  }
  return res.json();
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
  if (!res.ok) throw new Error(`Failed to list jobs: ${res.status}`);
  return res.json();
}

export async function listDlqEntries(limit = 100): Promise<{ count: number; entries: DlqEntry[] }> {
  const res = await fetchWithAuth(`${INGEST_URL}/dlq?limit=${limit}`);
  if (!res.ok) throw new Error(`Failed to list DLQ: ${res.status}`);
  return res.json();
}

export async function retryDlqEntry(dlqId: string): Promise<{ status: string; objectId?: string; errors?: string[] }> {
  const res = await fetchWithAuth(`${INGEST_URL}/dlq/${encodeURIComponent(dlqId)}/retry`, { method: 'POST' });
  return res.json();
}

export async function deleteDlqEntry(dlqId: string): Promise<void> {
  const res = await fetchWithAuth(`${INGEST_URL}/dlq/${encodeURIComponent(dlqId)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`Failed to delete DLQ entry: ${res.status}`);
}

// ── Scheduler ─────────────────────────────────────────────────────

export async function getSchedulerStatus(): Promise<{ scheduledProfiles: unknown[]; count: number; idleProfiles: string[] }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/status`);
  if (!res.ok) throw new Error(`Failed to get scheduler status: ${res.status}`);
  return res.json();
}

export async function getCheckpoints(profileId: string): Promise<{ profileId: string; checkpoints: Record<string, string> }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/checkpoint/${encodeURIComponent(profileId)}`);
  if (!res.ok) throw new Error(`Failed to get checkpoints: ${res.status}`);
  return res.json();
}

export async function resetCheckpoint(profileId: string, scope?: string): Promise<{ status: string; message: string }> {
  const url = scope
    ? `${SCHEDULER_URL}/checkpoint/${encodeURIComponent(profileId)}?scope=${encodeURIComponent(scope)}`
    : `${SCHEDULER_URL}/checkpoint/${encodeURIComponent(profileId)}`;
  const res = await fetchWithAuth(url, { method: 'DELETE' });
  return res.json();
}

export async function triggerProfile(profileId: string): Promise<Record<string, unknown>> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/trigger/${encodeURIComponent(profileId)}`, { method: 'POST' });
  return res.json();
}

export async function startIdleMonitoring(profileId: string): Promise<{ status: string; message: string }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/idle/start/${encodeURIComponent(profileId)}`, { method: 'POST' });
  return res.json();
}

export async function stopIdleMonitoring(profileId: string): Promise<{ status: string; message: string }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/idle/stop/${encodeURIComponent(profileId)}`, { method: 'POST' });
  return res.json();
}

export async function getIdleStatus(): Promise<{ idleProfiles: string[] }> {
  const res = await fetchWithAuth(`${SCHEDULER_URL}/idle/status`);
  if (!res.ok) throw new Error(`Failed to get IDLE status: ${res.status}`);
  return res.json();
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
