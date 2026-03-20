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
}
