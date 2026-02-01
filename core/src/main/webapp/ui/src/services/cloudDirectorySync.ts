/**
 * Cloud Directory Sync Service
 *
 * Provides API calls for cloud directory synchronization management:
 * - Delta sync (incremental changes)
 * - Full reconciliation
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

interface RestApiResponse {
  status: boolean;
  result: CloudSyncStatus;
  errMsg: string[];
}

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

function parseResponse(json: RestApiResponse): CloudSyncStatus {
  if (!json.status && json.errMsg && json.errMsg.length > 0) {
    throw new Error(json.errMsg.join('; '));
  }
  return json.result;
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
  return json.result?.connected ?? false;
}
