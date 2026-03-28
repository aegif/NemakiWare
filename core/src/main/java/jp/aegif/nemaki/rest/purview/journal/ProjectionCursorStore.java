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
     */
    void updateCursor(ProjectionCursor cursor);

    /**
     * Returns all cursors across all targets and repositories.
     */
    List<ProjectionCursor> getAllCursors();

    /**
     * Whether the cursor store is active (backing DB available).
     */
    boolean isActive();
}
