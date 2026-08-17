package jp.aegif.nemaki.acl;

/**
 * The TRI-STATE outcome of a by-id principal lookup (increment 5T).
 *
 * <p>Before 5T the principal DAO returned {@code null} for two materially different situations and
 * the reader-token projection could not tell them apart:
 *
 * <ul>
 *   <li>the principal genuinely does not exist (a deleted user/group) — dropping it is CORRECT;</li>
 *   <li>the query could not be served at all (a missing design document, view or database, which
 *       {@code CloudantClientWrapper.queryView} maps to {@code null}) — dropping it silently
 *       degrades EVERY principal to "absent", so the fail-closed fallback yields an ADMIN-ONLY
 *       reader set, the write SUCCEEDS, and a reconciliation task is deleted as if it had
 *       reconciled. Nothing else on the path raises a signal, so only a tri-state can observe it.</li>
 * </ul>
 *
 * <p>Note the third case that does NOT appear here: a TRANSIENT fault (connection reset, timeout,
 * 5xx) is not a lookup outcome at all — {@code queryView} rethrows it as a
 * {@code CmisRuntimeException} which propagates out of the resolver.
 *
 * <p><b>Rating.</b> Every failure mode shrinks the token set; no outcome can widen it. This is an
 * AVAILABILITY / index-health concern, not a confidentiality one.
 */
public enum PrincipalLookup {

    /** The principal exists. */
    FOUND,

    /** The query was served and the principal is genuinely absent. Dropping it is correct. */
    NOT_FOUND,

    /**
     * The query could NOT be served (missing design document / view / database). The caller must
     * not treat this as absence.
     */
    UNAVAILABLE
}
