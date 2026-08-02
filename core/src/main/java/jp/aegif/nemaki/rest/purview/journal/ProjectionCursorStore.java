package jp.aegif.nemaki.rest.purview.journal;

import java.util.List;

/**
 * Persistence interface for projection cursors.
 *
 * <p>Each cursor tracks the last processed sequence number for a
 * (target, repositoryId) pair, enabling in-order event processing.
 */
public interface ProjectionCursorStore {

    /**
     * Returns the cursor for the given target and repository, or null if none exists.
     */
    ProjectionCursor getCursor(String target, String repositoryId);

    /**
     * Updates (or creates) the cursor for the given target and repository.
     *
     * <p>v1 semantics: last writer wins, failures are swallowed. Preserved byte-identical for
     * the legacy path (v2.3.18 ⑧); the D-rest ordered walk uses
     * {@link #advanceCursorMonotonic(ProjectionCursor)} instead.
     */
    void updateCursor(ProjectionCursor cursor);

    /**
     * §8-c: monotonic CAS cursor advance. Never writes a value smaller than the stored one
     * (max() semantics — a stored position at or past the incoming one is success without a
     * write). CAS on {@code _rev}; a lost race is retried with ONE reread and succeeds iff the
     * stored position already covers the incoming one. Malformed stored positions
     * (non-integral, negative) are refused loudly — never coerced to zero. Infrastructure
     * failures return {@code false} after an ERROR log.
     *
     * @return {@code true} iff the cursor durably covers {@code cursor.lastProcessedSequence()};
     *         on {@code false} the caller must halt the repository (zero-means-stop)
     */
    boolean advanceCursorMonotonic(ProjectionCursor cursor);

    /**
     * Returns all cursors across all targets and repositories.
     */
    List<ProjectionCursor> getAllCursors();

    /**
     * Whether the cursor store is active (backing DB available).
     */
    boolean isActive();
}
