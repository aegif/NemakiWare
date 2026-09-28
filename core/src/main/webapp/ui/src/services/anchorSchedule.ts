import { AuthService } from './auth';
import { parseJsonResponseBody } from './http/jsonFetch';

/**
 * The anchoring schedule of one ledger domain (a repository, or record-content) — design
 * docs/design/anchor-scheduler.md §5. The settings are the anchor.schedule.* keys; where anchors
 * are SENT is shown in `destinations` and cannot be changed from here.
 */
export const SCHEDULE_KEYS = {
  enabled: 'anchor.schedule.enabled',
  intervalMinutes: 'anchor.schedule.interval-minutes',
  maxUnanchoredEntries: 'anchor.schedule.max-unanchored-entries',
  minIntervalMinutes: 'anchor.schedule.min-interval-minutes',
  upgradeIntervalMinutes: 'anchor.schedule.upgrade-interval-minutes',
  retryUnsettledIntervalMinutes: 'anchor.schedule.retry-unsettled-interval-minutes',
} as const;

/** The one domain every repository's record-content statements are chained in. */
export const RECORD_CONTENT_DOMAIN = 'record-content';

export interface ScheduleRung {
  kind: string;
  configured: boolean;
}

export interface ScheduleDestinations {
  tsaUrl: string | null;
  policyOid: string | null;
  trustAnchorConfigured: boolean;
  otsSidecarUrl: string | null;
}

export interface ScheduleEffective {
  enabled: boolean;
  intervalMinutes: number | null;
  maxUnanchoredEntries: number | null;
  minIntervalMinutes: number;
  upgradeIntervalMinutes: number;
  retryUnsettledIntervalMinutes: number | null;
}

export interface ScheduleRuntime {
  lastTickAt: string | null;
  lastOutcome: string | null;
  lastReason: string | null;
  lastOutcomeAt: string | null;
  lastSealAttemptAt: string | null;
  nextEligibleAt: string | null;
  leader: boolean | null;
  lastUpgradeAt: string | null;
  lastUpgradeOutcome: string | null;
  lastRetryAt: string | null;
  lastRetryOutcome: string | null;
  retryHeldForCheckpoint: number | null;
  leaderElectionEnabled: boolean;
  nodeId: string | null;
}

/** OK carries counts; UNAVAILABLE carries a reason and deliberately no count. */
export interface ScheduleUnanchored {
  status: 'OK' | 'UNAVAILABLE';
  reason?: string;
  ledgerHighestSequence?: number;
  sealedThrough?: number | null;
  unsealedCount?: number;
  anchoredUpTo?: number | null;
  count?: number;
  countIsLowerBound?: boolean;
  oldestAt?: string | null;
  oldestAtUnreadable?: string;
  lastSealAt?: string | null;
}

export interface AnchorSchedule {
  status: string;
  repositoryId: string;
  limits: string;
  scheduleLimits: string;
  settings: Record<string, string | null> | null;
  settingsUnavailable?: string;
  effective?: ScheduleEffective;
  errors?: Record<string, string>;
  rungs: ScheduleRung[];
  destinations: ScheduleDestinations;
  runtime: ScheduleRuntime;
  unanchored: ScheduleUnanchored;
}

/** A refusal from the endpoint: which keys, and why. */
export class ScheduleRefused extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly errors: Record<string, string> = {},
    public readonly refusedKeys: string[] = []
  ) {
    super(message);
  }
}

const base = '/core/api/v1/admin/anchor';

function headers(json = false): Record<string, string> {
  return {
    Accept: 'application/json',
    ...(json ? { 'Content-Type': 'application/json' } : {}),
    ...AuthService.getInstance().getAuthHeaders(),
  };
}

export async function getAnchorSchedule(domain: string): Promise<AnchorSchedule> {
  const response = await fetch(`${base}/schedule?repositoryId=${encodeURIComponent(domain)}`, {
    method: 'GET',
    headers: headers(),
  });
  const data = await parseJsonResponseBody(response, 'getAnchorSchedule');
  if (!response.ok) {
    throw new ScheduleRefused((data.message as string) || `HTTP ${response.status}`, response.status);
  }
  return data as unknown as AnchorSchedule;
}

/** Saves the given keys ('' clears a key). One invalid value saves nothing (400). */
export async function updateAnchorSchedule(
  domain: string,
  settings: Record<string, string>
): Promise<AnchorSchedule> {
  const response = await fetch(`${base}/schedule?repositoryId=${encodeURIComponent(domain)}`, {
    method: 'PUT',
    headers: headers(true),
    body: JSON.stringify(settings),
  });
  const data = await parseJsonResponseBody(response, 'updateAnchorSchedule');
  if (!response.ok) {
    throw new ScheduleRefused(
      (data.message as string) || `HTTP ${response.status}`,
      response.status,
      (data.errors as Record<string, string>) || {},
      (data.refusedKeys as string[]) || []
    );
  }
  return data as unknown as AnchorSchedule;
}

/** The existing manual verb: seal a checkpoint now and send it. The answer is shown as it came. */
export async function checkpointAndAnchorNow(
  domain: string
): Promise<{ httpStatus: number; body: Record<string, unknown> }> {
  const response = await fetch(`${base}/checkpoint-and-anchor?repositoryId=${encodeURIComponent(domain)}`, {
    method: 'POST',
    headers: headers(),
  });
  const body = await parseJsonResponseBody(response, 'checkpointAndAnchorNow');
  return { httpStatus: response.status, body };
}

/** The existing /status: what the latest checkpoint's anchoring amounts to. */
export async function getAnchorStatus(domain: string): Promise<Record<string, unknown>> {
  const response = await fetch(`${base}/status?repositoryId=${encodeURIComponent(domain)}`, {
    method: 'GET',
    headers: headers(),
  });
  return parseJsonResponseBody(response, 'getAnchorStatus');
}
