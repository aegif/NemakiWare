/**
 * A search answer the ACL scan limit cut short (R344, 3.4.2).
 *
 * While the ACL propagation has not converged, the server answers a large query with what its scan window could confirm and
 * marks the answer with the extension `truncatedByAclScanLimit=true`: its count is a lower bound, its order is the window's
 * only, and it ends at the window. The CMIS Browser binding writes a top-level extension of the query answer as a top-level
 * key of the JSON with its value as a string; two elements of the same name become an array of their values (OpenCMIS
 * `JSONConverter.convertExtension` — the fixture `__fixtures__/browserQueryCutShort.json` is written by that serializer).
 * Such an answer cannot be shown as the result of the search: not its rows, not its count, not "more results".
 */
export const CUT_SHORT_MARK = 'truncatedByAclScanLimit';

/** Thrown by `CMISService.search` in place of a cut-short answer. Carries no row, id or count. */
export class SearchCutShortError extends Error {
  constructor() {
    super('The search answer is cut short by the ACL scan limit; narrow the search and try again');
    this.name = 'SearchCutShortError';
  }
}

/** Whether a Browser binding query answer carries the mark at its top level, as the string `true`. */
export function isCutShortByAclScanLimit(answer: unknown): boolean {
  if (answer === null || typeof answer !== 'object' || Array.isArray(answer)) {
    return false;
  }
  if (!Object.prototype.hasOwnProperty.call(answer, CUT_SHORT_MARK)) {
    return false;
  }
  const value = (answer as Record<string, unknown>)[CUT_SHORT_MARK];
  if (value === 'true') {
    return true;
  }
  return Array.isArray(value) && value.some((element) => element === 'true');
}
