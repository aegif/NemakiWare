/**
 * NemakiWare REST envelopes from ResourceBase.makeResult:
 * status "success" | "failure", failure details in `error` (array of objects).
 */

export function isResourceBaseSuccess(result: Record<string, unknown>): boolean {
  const s = result.status;
  return s === 'success' || s === true;
}

/**
 * Human-readable message from `error` or legacy `errMsg` arrays.
 */
export function getResourceBaseErrorMessage(
  result: Record<string, unknown>,
  fallback = 'Request failed'
): string {
  const raw = result.error ?? result.errMsg;
  if (!Array.isArray(raw) || raw.length === 0) {
    return fallback;
  }
  const parts: string[] = [];
  for (const item of raw) {
    if (item == null) continue;
    if (typeof item === 'string') {
      parts.push(item);
    } else if (typeof item === 'object' && !Array.isArray(item)) {
      const o = item as Record<string, unknown>;
      for (const [k, v] of Object.entries(o)) {
        parts.push(`${k}: ${formatErrValue(v)}`);
      }
    }
  }
  return parts.length > 0 ? parts.join('; ') : fallback;
}

function formatErrValue(v: unknown): string {
  if (v == null) return String(v);
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
