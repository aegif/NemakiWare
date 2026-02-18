/**
 * Directory Sync Service
 *
 * Provides API calls for directory synchronization management:
 * - Cloud providers (Google Workspace, Microsoft Entra ID): Delta sync, Full reconciliation
 * - LDAP / Active Directory: Full sync, Preview (Dry Run)
 * - Status monitoring (polling)
 * - Connection testing
 * - Sync cancellation
 */

import { AuthService } from './auth';

export interface CloudSyncStatus {
  syncId: string;
  status: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'ERROR' | 'CANCELLED';
  syncMode: 'DELTA' | 'FULL' | null;
  provider: string;
  repositoryId: string;
  startTime: string | null;
  endTime: string | null;
  usersCreated: number;
  usersUpdated: number;
  usersDeleted: number;
  usersSkipped: number;
  groupsCreated: number;
  groupsUpdated: number;
  groupsDeleted: number;
  groupsSkipped: number;
  currentPage: number;
  totalPages: number;
  errors: string[];
  warnings: string[];
}

// NemakiWare REST API returns flat JSON objects (makeResult merges status into the result object)
// e.g. {"syncId":"...","status":"success","syncStatus":"RUNNING","usersCreated":0,...}
// The "status" field from makeResult is "success" or "failure", while sync status is in "syncStatus".

function getBaseUrl(repositoryId: string): string {
  return `/core/rest/repo/${repositoryId}/cloud-sync`;
}

async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
  const authService = AuthService.getInstance();
  const headers = authService.getAuthHeaders();
  return fetch(url, {
    ...options,
    headers: {
      ...headers,
      'Accept': 'application/json',
      ...options.headers,
    },
  });
}

function parseResponse(json: Record<string, unknown>): CloudSyncStatus {
  if (json.status === 'failure') {
    // makeResult puts errors under the "error" key (ITEM_ERROR = "error" in ResourceBase)
    // Each element can be a plain string or an object like {"errorCode": "message"}
    const errArr = json.error;
    if (Array.isArray(errArr) && errArr.length > 0) {
      const messages = (errArr as unknown[]).map((e: unknown) =>
        typeof e === 'string' ? e : Object.values(e as Record<string, string>).join(': ')
      );
      throw new Error(messages.join('; '));
    }
    throw new Error('Cloud sync operation failed');
  }
  // Map syncStatus (actual sync state) to status field for CloudSyncStatus interface
  const syncStatus = (json.syncStatus as string) || 'IDLE';
  return { ...json, status: syncStatus } as unknown as CloudSyncStatus;
}

export async function startDeltaSync(repositoryId: string, provider: string): Promise<CloudSyncStatus> {
  const body = new URLSearchParams();
  body.append('provider', provider);
  const response = await fetchWithAuth(`${getBaseUrl(repositoryId)}/trigger`, {
    method: 'POST',
    body,
  });
  const json = await response.json();
  return parseResponse(json);
}

export async function startFullReconciliation(repositoryId: string, provider: string): Promise<CloudSyncStatus> {
  const body = new URLSearchParams();
  body.append('provider', provider);
  const response = await fetchWithAuth(`${getBaseUrl(repositoryId)}/full-reconciliation`, {
    method: 'POST',
    body,
  });
  const json = await response.json();
  return parseResponse(json);
}

export async function getSyncStatus(repositoryId: string, provider: string): Promise<CloudSyncStatus> {
  const response = await fetchWithAuth(
    `${getBaseUrl(repositoryId)}/status?provider=${encodeURIComponent(provider)}`
  );
  const json = await response.json();
  return parseResponse(json);
}

export async function cancelSync(repositoryId: string, provider: string): Promise<void> {
  const body = new URLSearchParams();
  body.append('provider', provider);
  await fetchWithAuth(`${getBaseUrl(repositoryId)}/cancel`, {
    method: 'POST',
    body,
  });
}

export async function testConnection(repositoryId: string, provider: string): Promise<boolean> {
  const response = await fetchWithAuth(
    `${getBaseUrl(repositoryId)}/test-connection?provider=${encodeURIComponent(provider)}`
  );
  const json = await response.json();
  return json.connected ?? false;
}

// ---- LDAP Directory Sync API ----
// Uses separate REST endpoints: /core/rest/repo/{repositoryId}/sync

export interface LdapConfig {
  enabled: boolean;
  ldapUrl: string;
  baseDn: string;
  userSearchBase: string;
  groupSearchBase: string;
}

function getLdapBaseUrl(repositoryId: string): string {
  return `/core/rest/repo/${repositoryId}/sync`;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function parseLdapResponse(json: Record<string, any>): CloudSyncStatus {
  // makeResult puts errors under the "error" key (ITEM_ERROR = "error" in ResourceBase)
  if (json.status === 'failure') {
    const errArr = json.error;
    if (Array.isArray(errArr) && errArr.length > 0) {
      const messages = (errArr as unknown[]).map((e: unknown) =>
        typeof e === 'string' ? e : Object.values(e as Record<string, string>).join(': ')
      );
      throw new Error(messages.join('; '));
    }
    throw new Error('LDAP sync operation failed');
  }
  // Map LDAP API field names to CloudSyncStatus
  // triggerSync puts the result under "syncResult", getStatus puts it under "lastSyncResult"
  const syncResult = json.lastSyncResult ?? json.syncResult ?? json;
  const errors: string[] = Array.isArray(syncResult.errors)
    ? syncResult.errors.map((e: { message?: string } | string) =>
        typeof e === 'string' ? e : (e.message ?? JSON.stringify(e)))
    : [];
  const warnings: string[] = Array.isArray(syncResult.warnings)
    ? syncResult.warnings.map((w: { message?: string } | string) =>
        typeof w === 'string' ? w : (w.message ?? JSON.stringify(w)))
    : [];
  return {
    syncId: syncResult.syncId ?? '',
    // syncResult.status contains the real sync status (e.g. SUCCESS, FAILED, RUNNING)
    // This is NOT the makeResult wrapper status ("success"/"failure") because we
    // extract syncResult from json.syncResult ?? json, and the real status is inside syncResult.
    status: mapLdapStatus(syncResult.status),
    syncMode: syncResult.dryRun ? 'DELTA' : 'FULL', // Use DELTA for dry-run display, FULL for real sync
    provider: 'ldap',
    repositoryId: syncResult.repositoryId ?? '',
    startTime: syncResult.startTime ? new Date(syncResult.startTime).toISOString() : null,
    endTime: syncResult.endTime ? new Date(syncResult.endTime).toISOString() : null,
    usersCreated: syncResult.usersAdded ?? syncResult.usersCreated ?? 0,
    usersUpdated: syncResult.usersUpdated ?? 0,
    usersDeleted: syncResult.usersRemoved ?? syncResult.usersDeleted ?? 0,
    usersSkipped: syncResult.usersSkipped ?? 0,
    groupsCreated: syncResult.groupsCreated ?? 0,
    groupsUpdated: syncResult.groupsUpdated ?? 0,
    groupsDeleted: syncResult.groupsDeleted ?? 0,
    groupsSkipped: syncResult.groupsSkipped ?? 0,
    currentPage: 0,
    totalPages: 0,
    errors,
    warnings,
  };
}

function mapLdapStatus(status: string | undefined): CloudSyncStatus['status'] {
  if (!status) return 'IDLE';
  switch (status.toUpperCase()) {
    case 'SUCCESS': return 'COMPLETED';
    case 'PARTIAL': return 'COMPLETED';
    case 'FAILED': return 'ERROR';
    case 'FAILURE': return 'ERROR';  // makeResult wrapper status ("failure") if syncResult is missing
    case 'ERROR': return 'ERROR';
    case 'RUNNING': return 'RUNNING';
    case 'IDLE': return 'IDLE';
    case 'CANCELLED': return 'CANCELLED';
    default: return 'IDLE';
  }
}

export async function startLdapSync(repositoryId: string, dryRun: boolean = false): Promise<CloudSyncStatus> {
  const response = await fetchWithAuth(
    `${getLdapBaseUrl(repositoryId)}/trigger?dryRun=${dryRun}`,
    { method: 'POST' }
  );
  const json = await response.json();
  return parseLdapResponse(json);
}

export async function getLdapSyncStatus(repositoryId: string): Promise<CloudSyncStatus> {
  const response = await fetchWithAuth(`${getLdapBaseUrl(repositoryId)}/status`);
  const json = await response.json();
  return parseLdapResponse(json);
}

export async function testLdapConnection(repositoryId: string): Promise<boolean> {
  const response = await fetchWithAuth(`${getLdapBaseUrl(repositoryId)}/test-connection`);
  const json = await response.json();
  return json.status === 'success';
}

export async function getLdapConfig(repositoryId: string): Promise<LdapConfig> {
  const response = await fetchWithAuth(`${getLdapBaseUrl(repositoryId)}/config`);
  const json = await response.json();
  const config = json.config ?? json;
  return {
    enabled: config.enabled ?? false,
    ldapUrl: config.ldapUrl ?? '',
    baseDn: config.baseDn ?? '',
    userSearchBase: config.userSearchBase ?? '',
    groupSearchBase: config.groupSearchBase ?? '',
  };
}
