import { AuthService } from './auth';
import { parseJsonResponseBody } from './http/jsonFetch';

/**
 * Bulk correction of users, groups and memberships (design docs/design/principal-batch.md §8).
 * Preview writes nothing; execute confirms a preview's plan. Skipping unexpected rows is never
 * the default — it is its own call with onUnexpected=skip.
 */
export type BatchKind = 'users' | 'groups' | 'memberships';
export type BatchOperation = 'create' | 'update' | 'delete' | 'add' | 'remove' | 'replace';

/** The columns each (kind, operation) accepts — the server refuses any other (400). */
export const BATCH_COLUMNS: Record<BatchKind, Partial<Record<BatchOperation, string[]>>> = {
  users: {
    create: ['userId', 'name', 'firstName', 'lastName', 'email', 'password', 'admin', 'groups'],
    update: ['userId', 'name', 'firstName', 'lastName', 'email', 'password', 'admin', 'groups'],
    delete: ['userId'],
  },
  groups: {
    create: ['groupId', 'name', 'users', 'groups'],
    update: ['groupId', 'name', 'users', 'groups'],
    delete: ['groupId'],
  },
  memberships: {
    add: ['groupId', 'memberId', 'memberType'],
    remove: ['groupId', 'memberId', 'memberType'],
    replace: ['groupId', 'members'],
  },
};

export interface BatchRowVerdict {
  line: number;
  id: string;
  verdict: 'expected' | 'unexpected' | 'forbidden';
  reason?: string;
  message?: string;
}

export interface BatchPreview {
  planId: string;
  snapshotHash: string;
  expiresAt: string;
  kind: BatchKind;
  operation: BatchOperation;
  counts: { rows: number; expected: number; unexpected: number; forbidden: number; adminGrants: number };
  rows: BatchRowVerdict[];
  passwordPresent: boolean;
}

export interface BatchRowOutcome {
  line: number;
  id: string;
  outcome: 'applied' | 'skipped' | 'forbidden' | 'not_applied' | 'failed';
  reason?: string;
}

/** What execute answered, whatever the status: applied / partial / refused / error. */
export interface BatchExecution {
  httpStatus: number;
  status?: string;
  reason?: string;
  message?: string;
  incidentId?: string;
  stoppedAt?: number;
  counts?: Record<string, number>;
  rows?: Array<BatchRowOutcome | BatchRowVerdict>;
}

/** A refusal of the request itself (400 / 409 / 413 / 503), with the server's reason. */
export class BatchRequestRefused extends Error {
  constructor(message: string, public readonly httpStatus: number, public readonly reason?: string) {
    super(message);
  }
}

const base = (repositoryId: string) =>
  `/core/api/v1/cmis/repositories/${encodeURIComponent(repositoryId)}/principals/batch`;

function authHeaders(): Record<string, string> {
  return { Accept: 'application/json', ...AuthService.getInstance().getAuthHeaders() };
}

/** A CSV with just the header row for the (kind, operation) — the template the screen offers. */
export function templateCsv(kind: BatchKind, operation: BatchOperation): string {
  return `${(BATCH_COLUMNS[kind][operation] || []).join(',')}\n`;
}

export async function previewBatch(
  repositoryId: string,
  file: File,
  kind: BatchKind,
  operation: BatchOperation
): Promise<BatchPreview> {
  const form = new FormData();
  form.append('file', file);
  form.append('kind', kind);
  form.append('operation', operation);
  const response = await fetch(`${base(repositoryId)}/preview`, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  const data = await parseJsonResponseBody(response, 'previewBatch');
  if (!response.ok) {
    throw new BatchRequestRefused((data.message as string) || `HTTP ${response.status}`, response.status,
      data.reason as string | undefined);
  }
  return data as unknown as BatchPreview;
}

/**
 * Confirms a preview's plan. `onUnexpected` is 'abort' for "confirm and run" (a plan with an
 * unexpected or forbidden row then writes nothing — 409) and 'skip' only for the separate
 * "skip the unexpected rows" button. A plan with a password needs the same file again (the plan
 * keeps no password); any other plan is confirmed by its id alone.
 */
export async function executePlan(
  repositoryId: string,
  preview: BatchPreview,
  onUnexpected: 'abort' | 'skip',
  file: File | null
): Promise<BatchExecution> {
  let response: Response;
  if (preview.passwordPresent) {
    if (!file) {
      throw new BatchRequestRefused('this plan has rows with a password; the same file is required', 400);
    }
    const form = new FormData();
    form.append('file', file);
    form.append('kind', preview.kind);
    form.append('operation', preview.operation);
    form.append('planId', preview.planId);
    form.append('onUnexpected', onUnexpected);
    response = await fetch(`${base(repositoryId)}/execute`, { method: 'POST', headers: authHeaders(), body: form });
  } else {
    response = await fetch(`${base(repositoryId)}/execute`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ planId: preview.planId, onUnexpected }),
    });
  }
  const data = await parseJsonResponseBody(response, 'executePlan');
  return { httpStatus: response.status, ...(data as Omit<BatchExecution, 'httpStatus'>) };
}
