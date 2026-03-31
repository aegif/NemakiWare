package jp.aegif.nemaki.rest.purview.journal;

import java.util.List;
import java.util.Map;

/**
 * Persistence interface for dead-letter lineage events.
 *
 * <p>Dead-letter events are events that failed to persist or publish
 * and have been logged to the dead-letter sink. This store provides
 * durable CouchDB-backed storage for dead-letter events, enabling
 * listing, replay, and purge via the admin UI.
 */
public interface LineageDeadLetterStore {

    /**
     * Records a dead-letter event in the persistent store.
     *
     * @param event  the failed event
     * @param reason the failure description
     */
    void record(LineageEvent event, String reason);

    /**
     * Returns all dead-letter records with pagination.
     *
     * @param limit    max records to return
     * @param offset   skip count
     * @param replayed if non-null, filter by replayed status
     * @return list of dead-letter records as maps
     */
    List<Map<String, Object>> findAll(int limit, int offset, Boolean replayed);

    /**
     * Returns a single dead-letter record by event ID.
     */
    Map<String, Object> findByEventId(String eventId);

    /**
     * Replays a single dead-letter event.
     *
     * <p>If the original journal event still exists, only FAILED/DISCARDED targets
     * are reset to PENDING via {@code updatePublishStatus()}; already-successful
     * targets are left untouched. If the original was purged, a new journal event
     * is re-appended with only the failed targets from the dead-letter record.
     *
     * @param eventId the event ID to replay
     * @param journalStore the journal store to update or append to
     * @return true if replayed successfully, false if not found or already replayed
     */
    boolean replay(String eventId, LineageJournalStore journalStore);

    /**
     * Replays all un-replayed dead-letter events.
     *
     * @param journalStore the journal store to append to
     * @return number of events replayed
     */
    int replayAll(LineageJournalStore journalStore);

    /**
     * Returns the total count of dead-letter records.
     *
     * @param replayed if non-null, filter by replayed status
     */
    long count(Boolean replayed);

    /**
     * Purges all replayed dead-letter records.
     *
     * @return number of records purged
     */
    int purgeReplayed();

    boolean isActive();
}
