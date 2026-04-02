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
  schedulerEnabled?: boolean;
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
