import { AuthService } from './auth';
import { parseJsonResponseBody } from './http/jsonFetch';

const BASE_URL = '/core/api/v1/admin/lineage-journal';

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

function ensureOk(res: Response, context: string): void {
  if (!res.ok) {
    throw new Error(`${context}: HTTP ${res.status}`);
  }
}

/** How much the server could establish about one end of a lineage relation. */
export type LineageAssetResolution = 'TYPED' | 'LEGACY_NAME' | 'UNRESOLVED';

export interface LineageAsset {
  qualifiedName: string;
  resolution: LineageAssetResolution;
  /** Present only when resolution is TYPED. */
  kind: string | null;
  atlasTypeName: string | null;
  attributes: Record<string, unknown>;
  /** Present only when resolution is UNRESOLVED. */
  unresolvedReason?: string;
}

export interface LineageEventSummary {
  eventId: string;
  /**
   * The v1 name for processIdentity. On a v2 record this holds the processKey, so prefer
   * processIdentity where it is present.
   */
  eventKey: string;
  /** Added in A-2 Slice 2b; absent on responses from an older server. */
  processIdentity?: string;
  /** The journal document identity: eventId on v1, deliveryId on v2. */
  recordId?: string | null;
  schemaVersion?: number;
  idempotencyKeyVersion?: number;
  repositoryId: string;
  processType: string | null;
  occurredAt: string;
  inputs: string[];
  outputs: string[];
  /** Structured form of inputs/outputs; carries the kind and any unresolved reason. */
  inputAssets?: LineageAsset[];
  outputAssets?: LineageAsset[];
  publishStatusByTarget: Record<string, string>;
  /** Set when the server could not project the stored row; the other fields are then partial. */
  unprojectable?: boolean;
  unprojectableReason?: string;
}

/** The identity to display: the version-neutral one where the server supplies it. */
export function displayProcessIdentity(event: LineageEventSummary): string {
  return event.processIdentity ?? event.eventKey;
}

export interface DeadLetterRecord {
  eventId: string;
  eventKey: string;
  repositoryId: string;
  processType: string | null;
  reason: string;
  recordedAt: string;
  replayed: boolean;
  replayedAt?: string;
}

export interface LineageMetricsData {
  eventsPublished: number;
  eventsFailed: number;
  eventsDiscarded: number;
  deadLetterCount: number;
  pollCount: number;
  lastPollTime: string | null;
  lastPollEventCount: number;
  byTarget: Record<string, { published: number; failed: number }>;
  backlog: Record<string, { nonTerminal: number; maxDocs: number; estimatedSizeBytes: number }>;
}

export interface LineageStatsData {
  mode: string;
  totalEvents: number;
  nonTerminalByTarget: Record<string, number>;
  byProcessType: Record<string, number>;
  storeActive: boolean;
  targets: string[];
  hasRepositoryOverrides?: boolean;
}

// ==================== Events ====================

export async function getEvents(params: {
  limit?: number;
  offset?: number;
  repositoryId?: string;
  processType?: string;
} = {}): Promise<{ events: LineageEventSummary[]; total: number }> {
  const query = new URLSearchParams();
  if (params.limit) query.set('limit', String(params.limit));
  if (params.offset != null) query.set('offset', String(params.offset));
  if (params.repositoryId) query.set('repositoryId', params.repositoryId);
  if (params.processType) query.set('processType', params.processType);

  const res = await fetchWithAuth(`${BASE_URL}/events?${query}`);
  ensureOk(res, 'getEvents');
  return (await parseJsonResponseBody(res, 'getEvents')) as unknown as { events: LineageEventSummary[]; total: number };
}

/**
 * Fetches one journal row. The parameter is a RECORD id — v1's eventId, v2's deliveryId; use
 * recordId from a list row where present (they are the same value for every v1 row).
 */
export async function getEvent(recordId: string): Promise<LineageEventSummary> {
  const res = await fetchWithAuth(`${BASE_URL}/events/${encodeURIComponent(recordId)}`);
  ensureOk(res, 'getEvent');
  return (await parseJsonResponseBody(res, 'getEvent')) as unknown as LineageEventSummary;
}

export async function getStats(): Promise<LineageStatsData> {
  const res = await fetchWithAuth(`${BASE_URL}/stats`);
  ensureOk(res, 'getStats');
  return (await parseJsonResponseBody(res, 'getStats')) as unknown as LineageStatsData;
}

export async function getMetrics(): Promise<LineageMetricsData> {
  const res = await fetchWithAuth(`${BASE_URL}/metrics`);
  ensureOk(res, 'getMetrics');
  return (await parseJsonResponseBody(res, 'getMetrics')) as unknown as LineageMetricsData;
}

// ==================== Dead-letter ====================

export async function getDeadLetters(params: {
  limit?: number;
  offset?: number;
  replayed?: boolean;
} = {}): Promise<{ deadLetters: DeadLetterRecord[]; total: number }> {
  const query = new URLSearchParams();
  if (params.limit) query.set('limit', String(params.limit));
  if (params.offset != null) query.set('offset', String(params.offset));
  if (params.replayed !== undefined) query.set('replayed', String(params.replayed));

  const res = await fetchWithAuth(`${BASE_URL}/dead-letters?${query}`);
  ensureOk(res, 'getDeadLetters');
  return (await parseJsonResponseBody(res, 'getDeadLetters')) as unknown as { deadLetters: DeadLetterRecord[]; total: number };
}

export async function getDeadLetter(eventId: string): Promise<DeadLetterRecord> {
  const res = await fetchWithAuth(`${BASE_URL}/dead-letters/${encodeURIComponent(eventId)}`);
  ensureOk(res, 'getDeadLetter');
  return (await parseJsonResponseBody(res, 'getDeadLetter')) as unknown as DeadLetterRecord;
}

export async function replayDeadLetter(eventId: string): Promise<{ status: string; message: string }> {
  const res = await fetchWithAuth(`${BASE_URL}/dead-letters/${encodeURIComponent(eventId)}/replay`, {
    method: 'POST',
  });
  ensureOk(res, 'replayDeadLetter');
  return (await parseJsonResponseBody(res, 'replayDeadLetter')) as unknown as { status: string; message: string };
}

export async function replayAllDeadLetters(): Promise<{ status: string; replayed: number }> {
  const res = await fetchWithAuth(`${BASE_URL}/dead-letters/replay-all`, {
    method: 'POST',
  });
  ensureOk(res, 'replayAllDeadLetters');
  return (await parseJsonResponseBody(res, 'replayAllDeadLetters')) as unknown as { status: string; replayed: number };
}

export async function getDeadLetterCount(replayed?: boolean): Promise<{ count: number }> {
  const query = new URLSearchParams();
  if (replayed !== undefined) query.set('replayed', String(replayed));

  const res = await fetchWithAuth(`${BASE_URL}/dead-letters/count?${query}`);
  ensureOk(res, 'getDeadLetterCount');
  return (await parseJsonResponseBody(res, 'getDeadLetterCount')) as unknown as { count: number };
}
