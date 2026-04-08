/**
 * Shared JSON parsing for fetch() responses.
 * Avoids opaque SyntaxError when the server returns HTML (proxy 502, WAF, etc.).
 */

/**
 * Read response body as JSON (single read). Throws with context if body is not valid JSON.
 */
export async function parseJsonResponseBody(
  response: Response,
  context: string
): Promise<Record<string, unknown>> {
  const text = await response.text();
  try {
    return text ? (JSON.parse(text) as Record<string, unknown>) : {};
  } catch {
    throw new Error(`${context}: invalid JSON in response (HTTP ${response.status})`);
  }
}

/**
 * Same as {@link parseJsonResponseBody} but returns null on parse failure (caller decides fallback).
 */
export async function tryParseJsonResponseBody(
  response: Response
): Promise<Record<string, unknown> | null> {
  const text = await response.text();
  try {
    return text ? (JSON.parse(text) as Record<string, unknown>) : {};
  } catch {
    return null;
  }
}
