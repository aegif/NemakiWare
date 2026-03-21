package jp.aegif.nemaki.rest.purview.state;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class PurviewTombstoneStateServiceImpl implements PurviewTombstoneStateService {

    private static final String KEY_PREFIX = "purview.tombstone.state";
    private static final String FIELD_TYPE_NAME = "typeName";
    private static final String FIELD_QUALIFIED_NAME = "qualifiedName";
    private static final String FIELD_CHANGE_TOKEN = "changeToken";
    private static final String FIELD_FIRST_SEEN_AT = "firstSeenAt";
    private static final String FIELD_DUE_AT = "dueAt";
    private static final String FIELD_STATUS = "status";
    private static final String STATUS_PENDING = "PENDING";

    private final PurviewStateStore stateStore;

    public PurviewTombstoneStateServiceImpl(PurviewStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public PurviewTombstoneState saveTombstoneState(PurviewTombstoneState tombstoneState) {
        String keyPrefix = buildKeyPrefix(tombstoneState.getRepositoryId(), tombstoneState.getObjectId());
        stateStore.putAll(Map.of(
                keyPrefix + ".typeName", tombstoneState.getTypeName(),
                keyPrefix + ".qualifiedName", tombstoneState.getQualifiedName(),
                keyPrefix + ".changeToken", tombstoneState.getChangeToken(),
                keyPrefix + ".firstSeenAt", tombstoneState.getFirstSeenAt(),
                keyPrefix + ".dueAt", tombstoneState.getDueAt(),
                keyPrefix + ".status", tombstoneState.getStatus()));
        return getTombstoneState(tombstoneState.getRepositoryId(), tombstoneState.getObjectId());
    }

    @Override
    public PurviewTombstoneState getTombstoneState(String repositoryId, String objectId) {
        String keyPrefix = buildKeyPrefix(repositoryId, objectId);
        return new PurviewTombstoneState(
                repositoryId,
                objectId,
                stateStore.getString(keyPrefix + "." + FIELD_TYPE_NAME),
                stateStore.getString(keyPrefix + "." + FIELD_QUALIFIED_NAME),
                stateStore.getString(keyPrefix + "." + FIELD_CHANGE_TOKEN),
                stateStore.getString(keyPrefix + "." + FIELD_FIRST_SEEN_AT),
                stateStore.getString(keyPrefix + "." + FIELD_DUE_AT),
                stateStore.getString(keyPrefix + "." + FIELD_STATUS));
    }

    @Override
    public List<PurviewTombstoneState> listTombstoneStates(String repositoryId) {
        String repositoryPrefix = buildRepositoryPrefix(repositoryId);
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : stateStore.getAll().entrySet()) {
            if (!entry.getKey().startsWith(repositoryPrefix)) {
                continue;
            }

            ParsedEntry parsed = parseRepositoryEntry(repositoryId, entry.getKey());
            if (parsed == null) {
                continue;
            }
            grouped.computeIfAbsent(parsed.objectId(), ignored -> new LinkedHashMap<>())
                    .put(parsed.fieldName(), entry.getValue());
        }

        List<PurviewTombstoneState> states = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : grouped.entrySet()) {
            Map<String, Object> fields = entry.getValue();
            states.add(new PurviewTombstoneState(
                    repositoryId,
                    entry.getKey(),
                    stringValue(fields.get(FIELD_TYPE_NAME)),
                    stringValue(fields.get(FIELD_QUALIFIED_NAME)),
                    stringValue(fields.get(FIELD_CHANGE_TOKEN)),
                    stringValue(fields.get(FIELD_FIRST_SEEN_AT)),
                    stringValue(fields.get(FIELD_DUE_AT)),
                    stringValue(fields.get(FIELD_STATUS))));
        }
        states.sort(Comparator.comparing(PurviewTombstoneState::getDueAt)
                .thenComparing(PurviewTombstoneState::getObjectId));
        return states;
    }

    @Override
    public List<PurviewTombstoneState> listDueTombstoneStates(String repositoryId, Instant dueAtOrBefore) {
        List<PurviewTombstoneState> dueStates = new ArrayList<>();
        for (PurviewTombstoneState state : listTombstoneStates(repositoryId)) {
            if (!STATUS_PENDING.equals(state.getStatus())) {
                continue;
            }
            if (state.getDueAt().isBlank()) {
                continue;
            }
            try {
                Instant dueAt = Instant.parse(state.getDueAt());
                if (!dueAt.isAfter(dueAtOrBefore)) {
                    dueStates.add(state);
                }
            } catch (DateTimeParseException e) {
                // Ignore malformed dueAt and let reconciliation overwrite it later.
            }
        }
        return dueStates;
    }

    @Override
    public void deleteTombstoneState(String repositoryId, String objectId) {
        String keyPrefix = buildKeyPrefix(repositoryId, objectId);
        stateStore.removeAll(List.of(
                keyPrefix + "." + FIELD_TYPE_NAME,
                keyPrefix + "." + FIELD_QUALIFIED_NAME,
                keyPrefix + "." + FIELD_CHANGE_TOKEN,
                keyPrefix + "." + FIELD_FIRST_SEEN_AT,
                keyPrefix + "." + FIELD_DUE_AT,
                keyPrefix + "." + FIELD_STATUS));
    }

    private String buildKeyPrefix(String repositoryId, String objectId) {
        return KEY_PREFIX + "." + repositoryId.trim() + "." + objectId.trim();
    }

    private String buildRepositoryPrefix(String repositoryId) {
        return KEY_PREFIX + "." + repositoryId.trim() + ".";
    }

    private ParsedEntry parseRepositoryEntry(String repositoryId, String key) {
        String repositoryPrefix = buildRepositoryPrefix(repositoryId);
        String suffix = key.substring(repositoryPrefix.length());
        int separatorIndex = suffix.lastIndexOf('.');
        if (separatorIndex <= 0 || separatorIndex == suffix.length() - 1) {
            return null;
        }
        return new ParsedEntry(suffix.substring(0, separatorIndex), suffix.substring(separatorIndex + 1));
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record ParsedEntry(String objectId, String fieldName) {
    }
}
