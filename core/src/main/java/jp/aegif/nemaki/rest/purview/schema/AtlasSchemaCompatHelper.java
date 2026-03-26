package jp.aegif.nemaki.rest.purview.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Patches schema payloads for Apache Atlas 2.3 compatibility.
 * Atlas 2.3 requires {@code options.maxStrLength} on businessMetadata attributeDefs,
 * which Microsoft Purview (DataMap) does not require.
 */
@Component
public class AtlasSchemaCompatHelper {

    /**
     * Patches businessMetadataDefs for Atlas 2.3 compatibility.
     * Adds {@code options.maxStrLength: "500"} to attribute definitions that lack options.
     * Does not mutate the input map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> patchForAtlas(Map<String, Object> payload) {
        Map<String, Object> patched = new LinkedHashMap<>(payload);

        Object businessMetadataDefs = patched.get("businessMetadataDefs");
        if (businessMetadataDefs instanceof List<?> defs && !defs.isEmpty()) {
            List<Object> patchedDefs = new ArrayList<>();
            for (Object def : defs) {
                if (def instanceof Map<?, ?> defMap) {
                    Map<String, Object> patchedDef = new LinkedHashMap<>((Map<String, Object>) defMap);
                    Object attributeDefs = patchedDef.get("attributeDefs");
                    if (attributeDefs instanceof List<?> attrs) {
                        List<Object> patchedAttrs = new ArrayList<>();
                        for (Object attr : attrs) {
                            if (attr instanceof Map<?, ?> attrMap) {
                                Map<String, Object> patchedAttr = new LinkedHashMap<>((Map<String, Object>) attrMap);
                                Object options = patchedAttr.get("options");
                                if (options == null || (options instanceof Map<?, ?> optMap && optMap.isEmpty())) {
                                    patchedAttr.put("options", Map.of("maxStrLength", "500"));
                                }
                                patchedAttrs.add(patchedAttr);
                            } else {
                                patchedAttrs.add(attr);
                            }
                        }
                        patchedDef.put("attributeDefs", patchedAttrs);
                    }
                    patchedDefs.add(patchedDef);
                } else {
                    patchedDefs.add(def);
                }
            }
            patched.put("businessMetadataDefs", patchedDefs);
        }

        return patched;
    }

    /**
     * Removes businessMetadataDefs from the payload (replaces with empty list).
     * Used as a fallback when Atlas rejects the businessMetadataDefs.
     * Does not mutate the input map.
     */
    public Map<String, Object> removeBusinessMetadataDefs(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        result.put("businessMetadataDefs", List.of());
        return result;
    }
}
