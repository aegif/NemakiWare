package jp.aegif.nemaki.acl;

/**
 * Raised when reader-token projection cannot resolve a principal because the lookup COULD NOT BE
 * SERVED ({@link PrincipalLookup#UNAVAILABLE}), as opposed to the principal being absent.
 *
 * <p>Deliberately thrown on BOTH the strict and the ordinary path, because the two callers need the
 * same fact and only differ in what they do with it:
 *
 * <ul>
 *   <li>the strict / reconciliation path lets it propagate, so the task is counted and retried
 *       instead of being completed against an admin-only reader set;</li>
 *   <li>the ordinary index path ({@code SolrUtil.createSolrDocument}) catches it, KEEPS the
 *       fail-closed empty {@code readers} for visibility, and ENQUEUES the object for
 *       reconciliation so the deny is retried durably rather than surviving until the next ACL
 *       change or a full reindex.</li>
 * </ul>
 *
 * <p>Swallowing it on only one of those paths would reproduce the asymmetry that increment 3b had
 * to fix: closed under strict, open on the path that actually runs on every create and update.
 */
public class PrincipalUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PrincipalUnavailableException(String message) {
        super(message);
    }
}
