package jp.aegif.nemaki.rest.purview.state;

import org.springframework.stereotype.Service;

@Service
public class PurviewSchemaStateServiceImpl implements PurviewSchemaStateService {

    private static final String KEY_PREFIX = "purview.schema.state";

    private final PurviewStateStore stateStore;

    public PurviewSchemaStateServiceImpl(PurviewStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public PurviewSchemaState getSchemaState(String collection) {
        String keyPrefix = buildCollectionKeyPrefix(collection);
        return new PurviewSchemaState(
                collection,
                stateStore.getString(keyPrefix + ".schemaVersion"),
                stateStore.getString(keyPrefix + ".schemaHash"),
                stateStore.getString(keyPrefix + ".lastAppliedAt"),
                stateStore.getString(keyPrefix + ".lastAppliedBy"),
                stateStore.getString(keyPrefix + ".lastDiffSummary"));
    }

    @Override
    public PurviewSchemaState saveSchemaState(PurviewSchemaState schemaState) {
        String keyPrefix = buildCollectionKeyPrefix(schemaState.getCollection());
        java.util.Map<String, Object> updatedMap = new java.util.LinkedHashMap<>();
        updatedMap.put(keyPrefix + ".schemaVersion", schemaState.getSchemaVersion());
        updatedMap.put(keyPrefix + ".schemaHash", schemaState.getSchemaHash());
        updatedMap.put(keyPrefix + ".lastAppliedAt", schemaState.getLastAppliedAt());
        updatedMap.put(keyPrefix + ".lastAppliedBy", schemaState.getLastAppliedBy());
        updatedMap.put(keyPrefix + ".lastDiffSummary", schemaState.getLastDiffSummary());
        stateStore.putAll(updatedMap);

        return getSchemaState(schemaState.getCollection());
    }

    private String buildCollectionKeyPrefix(String collection) {
        return KEY_PREFIX + "." + collection.trim();
    }
}
