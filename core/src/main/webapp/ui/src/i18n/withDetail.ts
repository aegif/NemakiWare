// The default i18next instance, which src/i18n/index.ts initialises (not that module itself: tests
// that replace react-i18next cannot load it).
import i18n from 'i18next';

/**
 * The text an error or a server answer carries, for showing after a translated lead: the
 * message of an Error, or of a plain object that carries one as a string (CMISService's
 * handleHttpError answers `{ status, statusText, url, message }`, not an Error, and
 * changePassword throws it as it is), a non-empty string as it is, nothing for anything else.
 * The server's diagnostics stay in English (they are not translated); the lead is what the UI
 * language says.
 */
export function detailOf(value: unknown): string | undefined {
  const text = value instanceof Error
    ? value.message
    : typeof value === 'string'
      ? value
      : typeof value === 'object' && value !== null && typeof (value as { message?: unknown }).message === 'string'
        ? (value as { message: string }).message
        : undefined;
  return text && text.trim() ? text.trim() : undefined;
}

/**
 * A translated lead with the server's (or an exception's) own text as a detail after it — the
 * envelope for every message that shows text the UI did not write. With no such text, the lead
 * alone.
 */
export function withDetail(lead: string, detail: unknown): string {
  const text = detailOf(detail);
  return text ? i18n.t('common.errors.withDetail', { message: lead, detail: text }) : lead;
}
