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

export interface LineageEventSummary {
  eventId: string;
  eventKey: string;
  repositoryId: string;
  processType: string | null;
  occurredAt: string;
  inputs: string[];
  outputs: string[];
  publishStatusByTarget: Record<string, string>;
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

export async function getEvent(eventId: string): Promise<LineageEventSummary> {
  const res = await fetchWithAuth(`${BASE_URL}/events/${encodeURIComponent(eventId)}`);
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
