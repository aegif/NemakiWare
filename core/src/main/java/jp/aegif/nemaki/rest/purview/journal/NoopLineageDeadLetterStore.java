package jp.aegif.nemaki.rest.purview.journal;

import java.util.List;
import java.util.Map;

/**
 * No-op implementation of {@link LineageDeadLetterStore}.
 */
public class NoopLineageDeadLetterStore implements LineageDeadLetterStore {

    @Override
    public void record(LineageEvent event, String reason) {}

    @Override
    public List<Map<String, Object>> findAll(int limit, int offset, Boolean replayed) {
        return List.of();
    }

    @Override
    public Map<String, Object> findByEventId(String eventId) {
        return null;
    }

    @Override
    public boolean replay(String eventId, LineageJournalStore journalStore) {
        return false;
    }

    @Override
    public int replayAll(LineageJournalStore journalStore) {
        return 0;
    }

    @Override
    public long count(Boolean replayed) {
        return 0;
    }

    @Override
    public int purgeReplayed() {
        return 0;
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
