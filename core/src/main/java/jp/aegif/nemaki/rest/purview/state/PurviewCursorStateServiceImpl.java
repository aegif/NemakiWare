package jp.aegif.nemaki.rest.purview.state;

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

    @Override
    public java.util.List<CloudMetadataCursorInspection> inspectCloudMetadataCursors(
            java.util.Collection<String> configuredRepositoryIds) {
        String streamKind = jp.aegif.nemaki.rest.purview.publish
                .CloudMetadataSnapshotFormat.STREAM_KIND;
        // The union: configured repositories, plus every repository that has a persisted
        // cursor whether or not configuration still mentions it.
        java.util.Set<String> repositoryIds = new java.util.LinkedHashSet<>();
        if (configuredRepositoryIds != null) {
            for (String id : configuredRepositoryIds) {
                if (id != null && !id.isBlank()) {
                    repositoryIds.add(id.trim());
                }
            }
        }
        java.util.List<CloudMetadataCursorInspection> inspections = new java.util.ArrayList<>();
        try {
            String prefix = KEY_PREFIX + ".";
            String suffix = "." + streamKind + ".cursor";
            for (String key : stateStore.getAllStrict().keySet()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                if (key.endsWith(suffix)) {
                    repositoryIds.add(key.substring(prefix.length(),
                            key.length() - suffix.length()));
                }
            }
        } catch (RuntimeException e) {
            // The inventory itself could not be enumerated: report it as an unnamed ERROR
            // rather than returning a set that looks complete.
            inspections.add(new CloudMetadataCursorInspection("<inventory>",
                    PurviewStateStore.Presence.ERROR, 0, 0, 0, false,
                    e.getClass().getSimpleName()));
        }
        for (String repositoryId : repositoryIds) {
            inspections.add(CloudMetadataCursorInspection.ofAll(repositoryId,
                    stateStore.getRawEverywhere(buildStreamKeyPrefix(repositoryId, streamKind)
                            + ".cursor")));
        }
        return inspections;
    }

    private String buildStreamKeyPrefix(String repositoryId, String streamKind) {
        return KEY_PREFIX + "." + repositoryId.trim() + "." + streamKind.trim();
    }
}
