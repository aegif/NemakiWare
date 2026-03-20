package jp.aegif.nemaki.rest.purview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class PurviewStateOverviewServiceImpl implements PurviewStateOverviewService {

    private static final String SCHEMA_PREFIX = "purview.schema.state.";
    private static final String JOB_PREFIX = "purview.job.";
    private static final String CURSOR_PREFIX = "purview.cursor.state.";
    private static final String LOCK_PREFIX = "purview.lock.repository.";
    private static final String TOMBSTONE_PREFIX = "purview.tombstone.state.";
    private static final String DEAD_LETTER_PREFIX = "purview.dead-letter.state.";

    private final PurviewStateStore stateStore;

    public PurviewStateOverviewServiceImpl(PurviewStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public PurviewStateOverview getStateOverview(String collection) {
        Map<String, Object> persisted = stateStore.getAll();
        return new PurviewStateOverview(
                collection,
                buildSchemaState(collection, persisted),
                buildJobs(persisted),
                buildCursors(persisted),
                buildLocks(persisted),
                buildTombstones(persisted),
                buildDeadLetters(persisted));
    }

    private PurviewSchemaState buildSchemaState(String collection, Map<String, Object> persisted) {
        String keyPrefix = SCHEMA_PREFIX + collection + ".";
        return new PurviewSchemaState(
                collection,
                getString(persisted, keyPrefix + "schemaVersion"),
                getString(persisted, keyPrefix + "schemaHash"),
                getString(persisted, keyPrefix + "lastAppliedAt"),
                getString(persisted, keyPrefix + "lastAppliedBy"),
                getString(persisted, keyPrefix + "lastDiffSummary"));
    }

    private List<PurviewJobState> buildJobs(Map<String, Object> persisted) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : persisted.entrySet()) {
            if (!entry.getKey().startsWith(JOB_PREFIX)) {
                continue;
            }

            String suffix = entry.getKey().substring(JOB_PREFIX.length());
            int separatorIndex = suffix.indexOf('.');
            if (separatorIndex <= 0 || separatorIndex == suffix.length() - 1) {
                continue;
            }

            String jobId = suffix.substring(0, separatorIndex);
            String fieldName = suffix.substring(separatorIndex + 1);
            grouped.computeIfAbsent(jobId, ignored -> new LinkedHashMap<>()).put(fieldName, entry.getValue());
        }

        List<PurviewJobState> jobs = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : grouped.entrySet()) {
            Map<String, Object> fields = entry.getValue();
            jobs.add(new PurviewJobState(
                    entry.getKey(),
                    stringValue(fields.get("jobKind")),
                    stringValue(fields.get("repositoryId")),
                    stringValue(fields.get("status")),
                    stringValue(fields.get("startedAt")),
                    stringValue(fields.get("endedAt")),
                    intValue(fields.get("processedCount")),
                    intValue(fields.get("failedCount")),
                    stringValue(fields.get("checkpoint")),
                    stringValue(fields.get("errorSummary"))));
        }
        jobs.sort(Comparator.comparing(PurviewJobState::getStartedAt).reversed()
                .thenComparing(PurviewJobState::getJobId));
        return jobs;
    }

    private List<PurviewCursorState> buildCursors(Map<String, Object> persisted) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : persisted.entrySet()) {
            if (!entry.getKey().startsWith(CURSOR_PREFIX)) {
                continue;
            }

            ParsedEntry parsed = parseScopedEntry(entry.getKey(), CURSOR_PREFIX);
            if (parsed == null) {
                continue;
            }

            grouped.computeIfAbsent(parsed.scope(), ignored -> new LinkedHashMap<>())
                    .put(parsed.fieldName(), entry.getValue());
        }

        List<PurviewCursorState> cursors = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : grouped.entrySet()) {
            int separatorIndex = entry.getKey().lastIndexOf('.');
            if (separatorIndex <= 0 || separatorIndex == entry.getKey().length() - 1) {
                continue;
            }

            String repositoryId = entry.getKey().substring(0, separatorIndex);
            String streamKind = entry.getKey().substring(separatorIndex + 1);
            Map<String, Object> fields = entry.getValue();
            cursors.add(new PurviewCursorState(
                    repositoryId,
                    streamKind,
                    stringValue(fields.get("cursor")),
                    stringValue(fields.get("cursorKind")),
                    stringValue(fields.get("lastRunAt")),
                    stringValue(fields.get("lastSuccessAt")),
                    stringValue(fields.get("lastErrorAt")),
                    stringValue(fields.get("lastErrorMessage")),
                    intValue(fields.get("consecutiveFailureCount")),
                    intValue(fields.get("deadLetterCount"))));
        }
        cursors.sort(Comparator.comparing(PurviewCursorState::getRepositoryId)
                .thenComparing(PurviewCursorState::getStreamKind));
        return cursors;
    }

    private List<PurviewLockState> buildLocks(Map<String, Object> persisted) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : persisted.entrySet()) {
            if (!entry.getKey().startsWith(LOCK_PREFIX)) {
                continue;
            }

            ParsedEntry parsed = parseScopedEntry(entry.getKey(), LOCK_PREFIX);
            if (parsed == null) {
                continue;
            }

            grouped.computeIfAbsent(parsed.scope(), ignored -> new LinkedHashMap<>())
                    .put(parsed.fieldName(), entry.getValue());
        }

        List<PurviewLockState> locks = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : grouped.entrySet()) {
            int separatorIndex = entry.getKey().lastIndexOf('.');
            if (separatorIndex <= 0 || separatorIndex == entry.getKey().length() - 1) {
                continue;
            }

            String repositoryId = entry.getKey().substring(0, separatorIndex);
            String jobKind = entry.getKey().substring(separatorIndex + 1);
            String ownerJobId = stringValue(entry.getValue().get("ownerJobId"));
            locks.add(new PurviewLockState(
                    repositoryId,
                    jobKind,
                    !ownerJobId.isBlank(),
                    ownerJobId,
                    stringValue(entry.getValue().get("lockedAt"))));
        }
        locks.sort(Comparator.comparing(PurviewLockState::getRepositoryId)
                .thenComparing(PurviewLockState::getJobKind));
        return locks;
    }

    private List<PurviewTombstoneState> buildTombstones(Map<String, Object> persisted) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : persisted.entrySet()) {
            if (!entry.getKey().startsWith(TOMBSTONE_PREFIX)) {
                continue;
            }

            ParsedEntry parsed = parseScopedEntry(entry.getKey(), TOMBSTONE_PREFIX);
            if (parsed == null) {
                continue;
            }

            grouped.computeIfAbsent(parsed.scope(), ignored -> new LinkedHashMap<>())
                    .put(parsed.fieldName(), entry.getValue());
        }

        List<PurviewTombstoneState> tombstones = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : grouped.entrySet()) {
            int separatorIndex = entry.getKey().indexOf('.');
            if (separatorIndex <= 0 || separatorIndex == entry.getKey().length() - 1) {
                continue;
            }

            String repositoryId = entry.getKey().substring(0, separatorIndex);
            String objectId = entry.getKey().substring(separatorIndex + 1);
            Map<String, Object> fields = entry.getValue();
            tombstones.add(new PurviewTombstoneState(
                    repositoryId,
                    objectId,
                    stringValue(fields.get("typeName")),
                    stringValue(fields.get("qualifiedName")),
                    stringValue(fields.get("changeToken")),
                    stringValue(fields.get("firstSeenAt")),
                    stringValue(fields.get("dueAt")),
                    stringValue(fields.get("status"))));
        }
        tombstones.sort(Comparator.comparing(PurviewTombstoneState::getRepositoryId)
                .thenComparing(PurviewTombstoneState::getDueAt)
                .thenComparing(PurviewTombstoneState::getObjectId));
        return tombstones;
    }

    private List<PurviewDeadLetterState> buildDeadLetters(Map<String, Object> persisted) {
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : persisted.entrySet()) {
            if (!entry.getKey().startsWith(DEAD_LETTER_PREFIX)) {
                continue;
            }

            ParsedEntry parsed = parseScopedEntry(entry.getKey(), DEAD_LETTER_PREFIX);
            if (parsed == null) {
                continue;
            }

            grouped.computeIfAbsent(parsed.scope(), ignored -> new LinkedHashMap<>())
                    .put(parsed.fieldName(), entry.getValue());
        }

        List<PurviewDeadLetterState> deadLetters = new ArrayList<>();
        for (Map<String, Object> fields : grouped.values()) {
            deadLetters.add(new PurviewDeadLetterState(
                    stringValue(fields.get("repositoryId")),
                    stringValue(fields.get("streamKind")),
                    stringValue(fields.get("entryKey")),
                    stringValue(fields.get("typeName")),
                    stringValue(fields.get("qualifiedName")),
                    stringValue(fields.get("firstFailedAt")),
                    stringValue(fields.get("lastFailedAt")),
                    intValue(fields.get("failureCount")),
                    stringValue(fields.get("checkpoint")),
                    stringValue(fields.get("errorSummary"))));
        }
        deadLetters.sort(Comparator.comparing(PurviewDeadLetterState::getRepositoryId)
                .thenComparing(PurviewDeadLetterState::getStreamKind)
                .thenComparing(PurviewDeadLetterState::getEntryKey));
        return deadLetters;
    }

    private ParsedEntry parseScopedEntry(String key, String keyPrefix) {
        String suffix = key.substring(keyPrefix.length());
        int separatorIndex = suffix.lastIndexOf('.');
        if (separatorIndex <= 0 || separatorIndex == suffix.length() - 1) {
            return null;
        }

        return new ParsedEntry(suffix.substring(0, separatorIndex), suffix.substring(separatorIndex + 1));
    }

    private String getString(Map<String, Object> values, String key) {
        return stringValue(values.get(key));
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record ParsedEntry(String scope, String fieldName) {
    }
}
