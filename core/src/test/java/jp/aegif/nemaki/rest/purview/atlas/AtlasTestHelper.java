package jp.aegif.nemaki.rest.purview.atlas;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.rest.purview.atlas.AtlasDirectClient.AtlasResponse;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory;
import jp.aegif.nemaki.rest.purview.schema.AtlasSchemaCompatHelper;

/**
 * Shared test utilities for Atlas integration tests.
 * Handles Atlas 2.3-specific differences (e.g., businessMetadataDefs compatibility).
 */
final class AtlasTestHelper {

    private AtlasTestHelper() {
    }

    /**
     * Applies the NemakiWare schema to Atlas with automatic fallback for
     * Atlas 2.3-specific differences:
     * <ul>
     *   <li>businessMetadataDefs attributes require options.maxStrLength (Purview does not)</li>
     *   <li>If businessMetadataDefs fails, retries without them</li>
     * </ul>
     *
     * @return the final AtlasResponse (may be 200, 409 for existing types, or error)
     */
    private static final AtlasSchemaCompatHelper COMPAT_HELPER = new AtlasSchemaCompatHelper();

    static AtlasResponse applySchemaWithFallback(AtlasDirectClient client, Map<String, Object> schemaPayload)
            throws IOException, InterruptedException {
        // First attempt: full payload with businessMetadataDefs patched for Atlas
        Map<String, Object> atlasPayload = COMPAT_HELPER.patchForAtlas(schemaPayload);
        AtlasResponse response = client.applySchema(atlasPayload);

        if (response.isSuccess() || response.statusCode() == 409) {
            return response;
        }

        // Fallback: remove businessMetadataDefs entirely
        Map<String, Object> fallbackPayload = COMPAT_HELPER.removeBusinessMetadataDefs(schemaPayload);
        return client.applySchema(fallbackPayload);
    }

    /**
     * Ensures the NemakiWare schema is applied to Atlas (for use in @BeforeAll).
     * Returns true if schema was successfully applied or already exists.
     */
    static boolean ensureSchemaApplied(AtlasDirectClient client) throws IOException, InterruptedException {
        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaPayloadFactory payloadFactory = new PurviewSchemaPayloadFactory();
        Map<String, Object> payload = payloadFactory.buildTypeDefinitionsPayload(manifestFactory.buildManifest());

        AtlasResponse response = applySchemaWithFallback(client, payload);
        return response.isSuccess() || response.statusCode() == 409;
    }

    /**
     * Resolves an entity's GUID by qualifiedName with retry.
     * Atlas has eventual consistency, so entities may not be immediately queryable after bulk creation.
     *
     * @return the GUID, or null if not found after retries
     */
    @SuppressWarnings("unchecked")
    static String resolveGuidWithRetry(AtlasDirectClient client, String typeName, String qualifiedName,
            int maxRetries, long retryDelayMs) throws IOException, InterruptedException {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            AtlasResponse resp = client.getEntityByQualifiedName(typeName, qualifiedName);
            if (resp.isSuccess()) {
                Map<String, Object> entity = (Map<String, Object>) resp.body().get("entity");
                if (entity != null && entity.get("guid") != null) {
                    return (String) entity.get("guid");
                }
            }
            if (attempt < maxRetries) {
                Thread.sleep(retryDelayMs);
            }
        }
        return null;
    }

    /**
     * Extracts GUIDs from a bulk create/update response keyed by typeName.
     * Atlas bulk responses contain mutatedEntities.CREATE (or UPDATE) with guid and typeName.
     *
     * <p>Note: Atlas only returns attributes for some entities in the bulk response,
     * so we key by typeName which is always present. This works when there is at most
     * one entity per type in the bulk request.</p>
     *
     * @return map of typeName → guid
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> extractGuidsFromBulkResponse(AtlasResponse response) {
        Map<String, String> guidMap = new LinkedHashMap<>();
        if (!response.isSuccess()) {
            return guidMap;
        }
        Map<String, Object> body = response.body();
        Map<String, Object> mutatedEntities = (Map<String, Object>) body.get("mutatedEntities");
        if (mutatedEntities == null) {
            return guidMap;
        }
        // Atlas uses CREATE for new entities, UPDATE for existing ones
        for (String action : List.of("CREATE", "UPDATE")) {
            Object entries = mutatedEntities.get(action);
            if (entries instanceof List<?> entryList) {
                for (Object entry : entryList) {
                    if (entry instanceof Map<?, ?> entryMap) {
                        String guid = (String) entryMap.get("guid");
                        String typeName = (String) entryMap.get("typeName");
                        if (guid != null && typeName != null) {
                            guidMap.put(typeName, guid);
                        }
                    }
                }
            }
        }
        return guidMap;
    }

}
