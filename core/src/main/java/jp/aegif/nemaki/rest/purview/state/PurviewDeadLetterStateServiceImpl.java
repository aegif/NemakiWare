package jp.aegif.nemaki.rest.purview.state;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class PurviewDeadLetterStateServiceImpl implements PurviewDeadLetterStateService {

    private static final String KEY_PREFIX = "purview.dead-letter.state";
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
        String keyPrefix = buildKeyPrefix(
                deadLetterState.getRepositoryId(),
                deadLetterState.getStreamKind(),
                deadLetterState.getEntryKey());
        Map<String, Object> updatedMap = new LinkedHashMap<>();
        updatedMap.put(keyPrefix + ".repositoryId", safe(deadLetterState.getRepositoryId()));
        updatedMap.put(keyPrefix + ".streamKind", safe(deadLetterState.getStreamKind()));
        updatedMap.put(keyPrefix + "." + FIELD_ENTRY_KEY, safe(deadLetterState.getEntryKey()));
        updatedMap.put(keyPrefix + "." + FIELD_TYPE_NAME, safe(deadLetterState.getTypeName()));
        updatedMap.put(keyPrefix + "." + FIELD_QUALIFIED_NAME, safe(deadLetterState.getQualifiedName()));
        updatedMap.put(keyPrefix + "." + FIELD_FIRST_FAILED_AT, safe(deadLetterState.getFirstFailedAt()));
        updatedMap.put(keyPrefix + "." + FIELD_LAST_FAILED_AT, safe(deadLetterState.getLastFailedAt()));
        updatedMap.put(keyPrefix + "." + FIELD_FAILURE_COUNT, deadLetterState.getFailureCount());
        updatedMap.put(keyPrefix + "." + FIELD_CHECKPOINT, safe(deadLetterState.getCheckpoint()));
        updatedMap.put(keyPrefix + "." + FIELD_ERROR_SUMMARY, safe(deadLetterState.getErrorSummary()));
        stateStore.putAll(updatedMap);
        return getDeadLetterState(
                deadLetterState.getRepositoryId(),
                deadLetterState.getStreamKind(),
                deadLetterState.getEntryKey());
    }

    @Override
    public PurviewDeadLetterState getDeadLetterState(String repositoryId, String streamKind, String entryKey) {
        String keyPrefix = buildKeyPrefix(repositoryId, streamKind, entryKey);
        return new PurviewDeadLetterState(
                repositoryId,
                streamKind,
                stateStore.getString(keyPrefix + "." + FIELD_ENTRY_KEY),
                stateStore.getString(keyPrefix + "." + FIELD_TYPE_NAME),
                stateStore.getString(keyPrefix + "." + FIELD_QUALIFIED_NAME),
                stateStore.getString(keyPrefix + "." + FIELD_FIRST_FAILED_AT),
                stateStore.getString(keyPrefix + "." + FIELD_LAST_FAILED_AT),
                stateStore.getInt(keyPrefix + "." + FIELD_FAILURE_COUNT),
                stateStore.getString(keyPrefix + "." + FIELD_CHECKPOINT),
                stateStore.getString(keyPrefix + "." + FIELD_ERROR_SUMMARY));
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
        String queryPrefix = buildQueryPrefix(repositoryId, streamKind);
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : stateStore.getAllByPrefix(queryPrefix).entrySet()) {
            ParsedEntry parsed = parseEntry(entry.getKey());
            if (parsed == null) {
                continue;
            }

            grouped.computeIfAbsent(parsed.groupKey(), ignored -> new LinkedHashMap<>())
                    .put(parsed.fieldName(), entry.getValue());
        }

        List<PurviewDeadLetterState> states = new ArrayList<>();
        for (Map<String, Object> fields : grouped.values()) {
            states.add(new PurviewDeadLetterState(
                    stringValue(fields.get("repositoryId")),
                    stringValue(fields.get("streamKind")),
                    stringValue(fields.get(FIELD_ENTRY_KEY)),
                    stringValue(fields.get(FIELD_TYPE_NAME)),
                    stringValue(fields.get(FIELD_QUALIFIED_NAME)),
                    stringValue(fields.get(FIELD_FIRST_FAILED_AT)),
                    stringValue(fields.get(FIELD_LAST_FAILED_AT)),
                    intValue(fields.get(FIELD_FAILURE_COUNT)),
                    stringValue(fields.get(FIELD_CHECKPOINT)),
                    stringValue(fields.get(FIELD_ERROR_SUMMARY))));
        }
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
        String keyPrefix = buildKeyPrefix(repositoryId, streamKind, entryKey);
        stateStore.removeAll(List.of(
                keyPrefix + ".repositoryId",
                keyPrefix + ".streamKind",
                keyPrefix + "." + FIELD_ENTRY_KEY,
                keyPrefix + "." + FIELD_TYPE_NAME,
                keyPrefix + "." + FIELD_QUALIFIED_NAME,
                keyPrefix + "." + FIELD_FIRST_FAILED_AT,
                keyPrefix + "." + FIELD_LAST_FAILED_AT,
                keyPrefix + "." + FIELD_FAILURE_COUNT,
                keyPrefix + "." + FIELD_CHECKPOINT,
                keyPrefix + "." + FIELD_ERROR_SUMMARY));
    }

    private String buildKeyPrefix(String repositoryId, String streamKind, String entryKey) {
        return KEY_PREFIX + "." + repositoryId.trim() + "." + streamKind.trim() + "." + encodeEntryKey(entryKey);
    }

    private String buildQueryPrefix(String repositoryId, String streamKind) {
        if (repositoryId != null && streamKind != null) {
            return KEY_PREFIX + "." + repositoryId.trim() + "." + streamKind.trim() + ".";
        }
        if (repositoryId != null) {
            return KEY_PREFIX + "." + repositoryId.trim() + ".";
        }
        return KEY_PREFIX + ".";
    }

    private ParsedEntry parseEntry(String key) {
        String suffix = key.substring((KEY_PREFIX + ".").length());
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
        return new ParsedEntry(
                repositoryId,
                streamKind,
                decodeEntryKey(encodedEntryKey),
                repositoryId + "\u0000" + streamKind + "\u0000" + encodedEntryKey,
                fieldName);
    }

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

    private record ParsedEntry(
            String repositoryId,
            String streamKind,
            String entryKey,
            String groupKey,
            String fieldName) {
    }
}
