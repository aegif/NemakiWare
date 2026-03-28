package jp.aegif.nemaki.rest.purview.journal;

import java.time.Instant;

/**
 * Tracks the last processed sequence number per (target, repositoryId) pair.
 *
 * <p>Used by the projection loop to ensure in-order, exactly-once processing
 * of lineage events for each repository and target combination.
 */
public record ProjectionCursor(
        String target,
        String repositoryId,
        long lastProcessedSequence,
        Instant updatedAt
) {

    /**
     * Returns the CouchDB document ID for this cursor.
     */
    public String docId() {
        return "projection_cursor:" + target + ":" + repositoryId;
    }

    /**
     * Creates a new cursor with an advanced sequence number.
     */
    public ProjectionCursor withSequence(long newSequence) {
        return new ProjectionCursor(target, repositoryId, newSequence, Instant.now());
    }
}
