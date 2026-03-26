package jp.aegif.nemaki.rest.purview.state;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public interface PurviewStateStore {

    String getString(String key);

    int getInt(String key);

    Map<String, Object> getAll();

    /**
     * Returns entries whose key starts with the given prefix.
     * Implementations may use CouchDB views for efficient querying.
     */
    default Map<String, Object> getAllByPrefix(String keyPrefix) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getAll().entrySet()) {
            if (entry.getKey().startsWith(keyPrefix)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    void putAll(Map<String, Object> values);

    void removeAll(Collection<String> keys);

    /**
     * Saves a composite object as a single CouchDB document.
     * The value map is stored in the document's "value" field as-is.
     */
    @SuppressWarnings("unchecked")
    default void putObject(String key, Map<String, Object> value) {
        putAll(Map.of(key, (Object) value));
    }

    /**
     * Retrieves a composite object stored as a single CouchDB document.
     * Returns null if the key does not exist or the value is not a Map.
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> getObject(String key) {
        Object val = getAll().get(key);
        if (val instanceof Map) {
            return (Map<String, Object>) val;
        }
        return null;
    }
}
