package jp.aegif.nemaki.rest.purview.state;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class PurviewDeadLetterStateServiceImpl implements PurviewDeadLetterStateService {

    private static final Log log = LogFactory.getLog(PurviewDeadLetterStateServiceImpl.class);

    /** New consolidated format: 1 entry = 1 document */
    private static final String CONSOLIDATED_PREFIX = "purview.dead-letter.entry";
    /** Legacy format: 1 entry = 10 documents */
    private static final String LEGACY_PREFIX = "purview.dead-letter.state";

    private static final String FIELD_ENTRY_KEY = "entryKey";
    private static final String FIELD_TYPE_NAME = "typeName";
    private static final String FIELD_QUALIFIED_NAME = "qualifiedName";
    private static final String FIELD_FIRST_FAILED_AT = "firstFailedAt";
    private static final String FIELD_LAST_FAILED_AT = "lastFailedAt";
    private static final String FIELD_FAILURE_COUNT = "failureCount";
    private static final String FIELD_CHECKPOINT = "checkpoint";
    private static final String FIELD_ERROR_SUMMARY = "errorSummary";

    private final PurviewStateStore stateStore;

    public PurviewDeadLetterStateServiceImpl(PurviewStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public PurviewDeadLetterState saveDeadLetterState(PurviewDeadLetterState deadLetterState) {
        String consolidatedKey = buildConsolidatedKey(
                deadLetterState.getRepositoryId(),
                deadLetterState.getStreamKind(),
                deadLetterState.getEntryKey());

        // Save as single consolidated document
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("repositoryId", safe(deadLetterState.getRepositoryId()));
        fields.put("streamKind", safe(deadLetterState.getStreamKind()));
        fields.put(FIELD_ENTRY_KEY, safe(deadLetterState.getEntryKey()));
        fields.put(FIELD_TYPE_NAME, safe(deadLetterState.getTypeName()));
        fields.put(FIELD_QUALIFIED_NAME, safe(deadLetterState.getQualifiedName()));
        fields.put(FIELD_FIRST_FAILED_AT, safe(deadLetterState.getFirstFailedAt()));
        fields.put(FIELD_LAST_FAILED_AT, safe(deadLetterState.getLastFailedAt()));
        fields.put(FIELD_FAILURE_COUNT, deadLetterState.getFailureCount());
        fields.put(FIELD_CHECKPOINT, safe(deadLetterState.getCheckpoint()));
        fields.put(FIELD_ERROR_SUMMARY, safe(deadLetterState.getErrorSummary()));
        stateStore.putObject(consolidatedKey, fields);

        // Clean up legacy 10-document format if present
        cleanupLegacyKeys(deadLetterState.getRepositoryId(),
                deadLetterState.getStreamKind(),
                deadLetterState.getEntryKey());

        return getDeadLetterState(
                deadLetterState.getRepositoryId(),
                deadLetterState.getStreamKind(),
                deadLetterState.getEntryKey());
    }

    @Override
    public PurviewDeadLetterState getDeadLetterState(String repositoryId, String streamKind, String entryKey) {
        // Try consolidated format first
        String consolidatedKey = buildConsolidatedKey(repositoryId, streamKind, entryKey);
        Map<String, Object> fields = stateStore.getObject(consolidatedKey);
        if (fields != null && !fields.isEmpty()) {
            return buildStateFromFields(fields, repositoryId, streamKind);
        }

        // Fallback to legacy format
        String legacyPrefix = buildLegacyKeyPrefix(repositoryId, streamKind, entryKey);
        String entryKeyValue = stateStore.getString(legacyPrefix + "." + FIELD_ENTRY_KEY);
        if (entryKeyValue == null || entryKeyValue.isEmpty()) {
            return null;
        }
        return new PurviewDeadLetterState(
                repositoryId,
                streamKind,
                stateStore.getString(legacyPrefix + "." + FIELD_ENTRY_KEY),
                stateStore.getString(legacyPrefix + "." + FIELD_TYPE_NAME),
                stateStore.getString(legacyPrefix + "." + FIELD_QUALIFIED_NAME),
                stateStore.getString(legacyPrefix + "." + FIELD_FIRST_FAILED_AT),
                stateStore.getString(legacyPrefix + "." + FIELD_LAST_FAILED_AT),
                stateStore.getInt(legacyPrefix + "." + FIELD_FAILURE_COUNT),
                stateStore.getString(legacyPrefix + "." + FIELD_CHECKPOINT),
                stateStore.getString(legacyPrefix + "." + FIELD_ERROR_SUMMARY));
    }

    @Override
    public List<PurviewDeadLetterState> listDeadLetterStates() {
        return listDeadLetterStates(null, null);
    }

    @Override
    public List<PurviewDeadLetterState> listDeadLetterStates(String repositoryId) {
        return listDeadLetterStates(repositoryId, null);
    }

    @Override
    public List<PurviewDeadLetterState> listDeadLetterStates(String repositoryId, String streamKind) {
        // Collect from both formats with deduplication
        Map<String, PurviewDeadLetterState> deduped = new LinkedHashMap<>();

        // 1. Read consolidated entries
        String consolidatedQuery = buildConsolidatedQueryPrefix(repositoryId, streamKind);
        for (Map.Entry<String, Object> entry : stateStore.getAllByPrefix(consolidatedQuery).entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = (Map<String, Object>) value;
                PurviewDeadLetterState state = buildStateFromFields(fields, null, null);
                String dedupeKey = state.getRepositoryId() + "\0" + state.getStreamKind() + "\0" + state.getEntryKey();
                deduped.put(dedupeKey, state);
            }
        }

        // 2. Read legacy entries (only for entries not already found in consolidated)
        String legacyQuery = buildLegacyQueryPrefix(repositoryId, streamKind);
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : stateStore.getAllByPrefix(legacyQuery).entrySet()) {
            LegacyParsedEntry parsed = parseLegacyEntry(entry.getKey());
            if (parsed == null) {
                continue;
            }
            grouped.computeIfAbsent(parsed.groupKey(), ignored -> new LinkedHashMap<>())
                    .put(parsed.fieldName(), entry.getValue());
        }
        for (Map<String, Object> fields : grouped.values()) {
            PurviewDeadLetterState state = new PurviewDeadLetterState(
                    stringValue(fields.get("repositoryId")),
                    stringValue(fields.get("streamKind")),
                    stringValue(fields.get(FIELD_ENTRY_KEY)),
                    stringValue(fields.get(FIELD_TYPE_NAME)),
                    stringValue(fields.get(FIELD_QUALIFIED_NAME)),
                    stringValue(fields.get(FIELD_FIRST_FAILED_AT)),
                    stringValue(fields.get(FIELD_LAST_FAILED_AT)),
                    intValue(fields.get(FIELD_FAILURE_COUNT)),
                    stringValue(fields.get(FIELD_CHECKPOINT)),
                    stringValue(fields.get(FIELD_ERROR_SUMMARY)));
            String dedupeKey = state.getRepositoryId() + "\0" + state.getStreamKind() + "\0" + state.getEntryKey();
            deduped.putIfAbsent(dedupeKey, state);
        }

        List<PurviewDeadLetterState> states = new ArrayList<>(deduped.values());
        states.sort(Comparator.comparing(PurviewDeadLetterState::getRepositoryId)
                .thenComparing(PurviewDeadLetterState::getStreamKind)
                .thenComparing(PurviewDeadLetterState::getEntryKey));
        return states;
    }

    @Override
    public int countDeadLetterStates(String repositoryId, String streamKind) {
        return listDeadLetterStates(repositoryId, streamKind).size();
    }

    @Override
    public void deleteDeadLetterState(String repositoryId, String streamKind, String entryKey) {
        // Delete consolidated format
        String consolidatedKey = buildConsolidatedKey(repositoryId, streamKind, entryKey);
        stateStore.removeAll(List.of(consolidatedKey));

        // Delete legacy format
        cleanupLegacyKeys(repositoryId, streamKind, entryKey);
    }

    // --- Consolidated format helpers ---

    private String buildConsolidatedKey(String repositoryId, String streamKind, String entryKey) {
        return CONSOLIDATED_PREFIX + "." + repositoryId.trim() + "." + streamKind.trim() + "." + encodeEntryKey(entryKey);
    }

    private String buildConsolidatedQueryPrefix(String repositoryId, String streamKind) {
        if (repositoryId != null && streamKind != null) {
            return CONSOLIDATED_PREFIX + "." + repositoryId.trim() + "." + streamKind.trim() + ".";
        }
        if (repositoryId != null) {
            return CONSOLIDATED_PREFIX + "." + repositoryId.trim() + ".";
        }
        return CONSOLIDATED_PREFIX + ".";
    }

    private PurviewDeadLetterState buildStateFromFields(Map<String, Object> fields,
            String defaultRepositoryId, String defaultStreamKind) {
        return new PurviewDeadLetterState(
                stringValue(fields.getOrDefault("repositoryId", defaultRepositoryId)),
                stringValue(fields.getOrDefault("streamKind", defaultStreamKind)),
                stringValue(fields.get(FIELD_ENTRY_KEY)),
                stringValue(fields.get(FIELD_TYPE_NAME)),
                stringValue(fields.get(FIELD_QUALIFIED_NAME)),
                stringValue(fields.get(FIELD_FIRST_FAILED_AT)),
                stringValue(fields.get(FIELD_LAST_FAILED_AT)),
                intValue(fields.get(FIELD_FAILURE_COUNT)),
                stringValue(fields.get(FIELD_CHECKPOINT)),
                stringValue(fields.get(FIELD_ERROR_SUMMARY)));
    }

    // --- Legacy format helpers ---

    private String buildLegacyKeyPrefix(String repositoryId, String streamKind, String entryKey) {
        return LEGACY_PREFIX + "." + repositoryId.trim() + "." + streamKind.trim() + "." + encodeEntryKey(entryKey);
    }

    private String buildLegacyQueryPrefix(String repositoryId, String streamKind) {
        if (repositoryId != null && streamKind != null) {
            return LEGACY_PREFIX + "." + repositoryId.trim() + "." + streamKind.trim() + ".";
        }
        if (repositoryId != null) {
            return LEGACY_PREFIX + "." + repositoryId.trim() + ".";
        }
        return LEGACY_PREFIX + ".";
    }

    private void cleanupLegacyKeys(String repositoryId, String streamKind, String entryKey) {
        String legacyPrefix = buildLegacyKeyPrefix(repositoryId, streamKind, entryKey);
        try {
            stateStore.removeAll(List.of(
                    legacyPrefix + ".repositoryId",
                    legacyPrefix + ".streamKind",
                    legacyPrefix + "." + FIELD_ENTRY_KEY,
                    legacyPrefix + "." + FIELD_TYPE_NAME,
                    legacyPrefix + "." + FIELD_QUALIFIED_NAME,
                    legacyPrefix + "." + FIELD_FIRST_FAILED_AT,
                    legacyPrefix + "." + FIELD_LAST_FAILED_AT,
                    legacyPrefix + "." + FIELD_FAILURE_COUNT,
                    legacyPrefix + "." + FIELD_CHECKPOINT,
                    legacyPrefix + "." + FIELD_ERROR_SUMMARY));
        } catch (Exception e) {
            log.debug("Legacy DLQ key cleanup failed (non-fatal): " + e.getMessage());
        }
    }

    private LegacyParsedEntry parseLegacyEntry(String key) {
        String suffix = key.substring((LEGACY_PREFIX + ".").length());
        int firstSeparator = suffix.indexOf('.');
        int secondSeparator = suffix.indexOf('.', firstSeparator + 1);
        int thirdSeparator = suffix.indexOf('.', secondSeparator + 1);
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator || thirdSeparator <= secondSeparator) {
            return null;
        }

        String repositoryId = suffix.substring(0, firstSeparator);
        String streamKind = suffix.substring(firstSeparator + 1, secondSeparator);
        String encodedEntryKey = suffix.substring(secondSeparator + 1, thirdSeparator);
        String fieldName = suffix.substring(thirdSeparator + 1);
        return new LegacyParsedEntry(
                repositoryId,
                streamKind,
                decodeEntryKey(encodedEntryKey),
                repositoryId + "\0" + streamKind + "\0" + encodedEntryKey,
                fieldName);
    }

    // --- Common helpers ---

    private String encodeEntryKey(String entryKey) {
        if (entryKey == null) {
            return "";
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(entryKey.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeEntryKey(String encodedEntryKey) {
        if (encodedEntryKey == null || encodedEntryKey.isBlank()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(encodedEntryKey), StandardCharsets.UTF_8);
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

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record LegacyParsedEntry(
            String repositoryId,
            String streamKind,
            String entryKey,
            String groupKey,
            String fieldName) {
    }
}
