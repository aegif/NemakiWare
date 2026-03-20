package jp.aegif.nemaki.rest.purview;

import java.util.Collection;
import java.util.Map;

public interface PurviewStateStore {

    String getString(String key);

    int getInt(String key);

    Map<String, Object> getAll();

    void putAll(Map<String, Object> values);

    void removeAll(Collection<String> keys);
}
