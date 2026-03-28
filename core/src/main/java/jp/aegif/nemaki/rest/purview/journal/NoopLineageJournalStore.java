package jp.aegif.nemaki.rest.purview.journal;

import java.time.Instant;
import java.util.List;

/**
 * No-op implementation of {@link LineageJournalStore} used when lineage mode
 * is {@link LineageMode#DISABLED} or {@link LineageMode#DIRECT}.
 * Performs zero I/O and creates no CouchDB databases.
 */
public class NoopLineageJournalStore implements LineageJournalStore {

    @Override
    public void append(LineageEvent event) {
        // no-op
    }

    @Override
    public void appendAll(List<LineageEvent> events) {
        // no-op
    }

    @Override
    public List<LineageEvent> findByRepositoryId(String repositoryId, int limit) {
        return List.of();
    }

    @Override
    public List<LineageEvent> findByProcessType(String repositoryId, LineageProcessType processType, int limit) {
        return List.of();
    }

    @Override
    public int updatePublishStatus(String eventId, String target, LineagePublishStatus status) {
        return 0;
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        return 0;
    }

    @Override
    public int discardEvent(String eventId, String target) {
        return 0;
    }

    @Override
    public long countNonTerminalByTarget(String target) {
        return 0;
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
