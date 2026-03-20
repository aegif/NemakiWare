package jp.aegif.nemaki.rest.purview;

import org.springframework.stereotype.Service;

@Service
public class PurviewCursorStateServiceImpl implements PurviewCursorStateService {

    private static final String KEY_PREFIX = "purview.cursor.state";

    private final PurviewStateStore stateStore;

    public PurviewCursorStateServiceImpl(PurviewStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public PurviewCursorState saveCursorState(PurviewCursorState cursorState) {
        String keyPrefix = buildStreamKeyPrefix(cursorState.getRepositoryId(), cursorState.getStreamKind());
        java.util.Map<String, Object> updatedMap = new java.util.LinkedHashMap<>();
        updatedMap.put(keyPrefix + ".cursor", cursorState.getCursor());
        updatedMap.put(keyPrefix + ".cursorKind", cursorState.getCursorKind());
        updatedMap.put(keyPrefix + ".lastRunAt", cursorState.getLastRunAt());
        updatedMap.put(keyPrefix + ".lastSuccessAt", cursorState.getLastSuccessAt());
        updatedMap.put(keyPrefix + ".lastErrorAt", cursorState.getLastErrorAt());
        updatedMap.put(keyPrefix + ".lastErrorMessage", cursorState.getLastErrorMessage());
        updatedMap.put(keyPrefix + ".consecutiveFailureCount", cursorState.getConsecutiveFailureCount());
        updatedMap.put(keyPrefix + ".deadLetterCount", cursorState.getDeadLetterCount());
        stateStore.putAll(updatedMap);

        return getCursorState(cursorState.getRepositoryId(), cursorState.getStreamKind());
    }

    @Override
    public PurviewCursorState getCursorState(String repositoryId, String streamKind) {
        String keyPrefix = buildStreamKeyPrefix(repositoryId, streamKind);
        return new PurviewCursorState(
                repositoryId,
                streamKind,
                stateStore.getString(keyPrefix + ".cursor"),
                stateStore.getString(keyPrefix + ".cursorKind"),
                stateStore.getString(keyPrefix + ".lastRunAt"),
                stateStore.getString(keyPrefix + ".lastSuccessAt"),
                stateStore.getString(keyPrefix + ".lastErrorAt"),
                stateStore.getString(keyPrefix + ".lastErrorMessage"),
                stateStore.getInt(keyPrefix + ".consecutiveFailureCount"),
                stateStore.getInt(keyPrefix + ".deadLetterCount"));
    }

    private String buildStreamKeyPrefix(String repositoryId, String streamKind) {
        return KEY_PREFIX + "." + repositoryId.trim() + "." + streamKind.trim();
    }
}
