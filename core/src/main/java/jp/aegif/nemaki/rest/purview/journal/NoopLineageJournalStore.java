package jp.aegif.nemaki.rest.purview.journal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    public List<LineageJournalRow> findByRepositoryId(String repositoryId, int limit, int offset) {
        return List.of();
    }

    @Override
    public List<LineageJournalRow> findByProcessType(String repositoryId, LineageProcessType processType, int limit, int offset) {
        return List.of();
    }

    @Override
    public List<LineageJournalRow> findByProcessType(LineageProcessType processType, int limit, int offset) {
        return List.of();
    }

    @Override
    public int updatePublishStatus(String recordId, String target, LineagePublishStatus status) {
        return 0;
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        return 0;
    }

    @Override
    public int discardEvent(String recordId, String target) {
        return 0;
    }

    @Override
    public long countNonTerminalByTarget(String target) {
        return 0;
    }


    @Override
    public List<LineageJournalRow> findAll(int limit, int offset) {
        return List.of();
    }

    @Override
    public LineageJournalRow findByRecordId(String recordId) {
        return null;
    }

    @Override
    public Map<LineageProcessType, Long> countByProcessType() {
        return Map.of();
    }

    @Override
    public List<LineageJournalRow> findByTargetAndStatus(String target, LineagePublishStatus status, int limit) {
        return List.of();
    }

    @Override
    public List<LineageJournalRow> findByTargetAndStatusOldestFirst(String target, LineagePublishStatus status, int limit) {
        return List.of();
    }

    @Override
    public int reapStaleProjecting(String target, int staleMinutes) {
        return 0;
    }

    @Override
    public int getRetryCount(String recordId, String target) {
        return 0;
    }



    @Override
    public List<LineageJournalRow> findByDateRange(String start, String end, int limit, int offset) {
        return List.of();
    }

    @Override
    public List<LineageJournalRow> findByRepositoryAndSequenceRange(String repositoryId, long fromSequence, int limit) {
        return List.of();
    }

    @Override
    public long getEstimatedNonTerminalSizeBytes(String target) {
        return 0;
    }

    @Override
    public List<String> findDistinctNonTerminalRepositoryIds(String target) {
        return List.of();
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
