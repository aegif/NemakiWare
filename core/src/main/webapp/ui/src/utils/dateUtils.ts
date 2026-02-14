import i18n from '../i18n';

/**
 * Normalize a server-side datetime string for cross-browser Date parsing.
 *
 * The backend uses SimpleDateFormat with the Z pattern, producing timezone
 * offsets like "+0900" (no colon). This is not reliably parsed by all JS
 * engines — Safari/WebKit may return Invalid Date.
 *
 * This function inserts a colon into the offset ("+0900" → "+09:00") so
 * the string conforms to ISO 8601 and is safe for `new Date()` everywhere.
 */
export function parseServerDate(dateStr: string | undefined | null): Date | null {
  if (!dateStr) return null;

  // If the string ends with a 4-digit offset like +0900 or -0530 (no colon),
  // insert a colon before the last two digits.
  const normalized = dateStr.replace(
    /([+-]\d{2})(\d{2})$/,
    '$1:$2'
  );

  const d = new Date(normalized);
  return isNaN(d.getTime()) ? null : d;
}

/**
 * Format a server datetime string to locale string, handling the +0900 offset.
 * Uses the app's current i18n language as the default locale.
 */
export function formatServerDate(dateStr: string | undefined | null, locale?: string): string {
  const d = parseServerDate(dateStr);
  return d ? d.toLocaleString(locale || i18n.language || navigator.language) : '-';
}
