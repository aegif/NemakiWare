package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LineageTargetSink} implementation that publishes lineage events
 * to Microsoft Purview (Atlas API).
 *
 * <p>Operates exclusively on the event's URI and snapshotAttributes —
 * no Content DB access required.
 */
@Component
public class PurviewLineageSink implements LineageTargetSink {

    private static final Logger logger = LoggerFactory.getLogger(PurviewLineageSink.class);

    @Autowired
    private PurviewEntityRegistryClient registryClient;

    @Autowired
    private PurviewConfig purviewConfig;

    @Override
    public String targetName() {
        return "purview";
    }

    @Override
    public LineageTargetSinkResult publish(LineageEvent event) throws Exception {
        if (event == null) {
            return LineageTargetSinkResult.skipped("null event");
        }

        PurviewConnectionRequest connReq = buildConnectionRequest();

        // Build process entity payload
        String processTypeName = mapProcessTypeName(event.processType());
        String processQualifiedName = buildProcessQualifiedName(event);

        Map<String, Object> processEntity = new LinkedHashMap<>();
        processEntity.put("typeName", processTypeName);
        Map<String, Object> processAttrs = new LinkedHashMap<>();
        processAttrs.put("qualifiedName", processQualifiedName);
        processAttrs.put("name", processTypeName + ":" + event.eventKey());

        // Add snapshot attributes as custom attributes
        if (!event.snapshotAttributes().isEmpty()) {
            for (Map.Entry<String, String> entry : event.snapshotAttributes().entrySet()) {
                processAttrs.put(entry.getKey(), entry.getValue());
            }
        }

        // Build inputs
        List<Map<String, Object>> inputRefs = new ArrayList<>();
        for (String uri : event.inputs()) {
            inputRefs.add(buildEntityRef(uri));
        }
        processAttrs.put("inputs", inputRefs);

        // Build outputs
        List<Map<String, Object>> outputRefs = new ArrayList<>();
        for (String uri : event.outputs()) {
            outputRefs.add(buildEntityRef(uri));
        }
        processAttrs.put("outputs", outputRefs);

        processEntity.put("attributes", processAttrs);

        // Build entity list: process + input entities + output entities
        List<Map<String, Object>> entities = new ArrayList<>();
        entities.add(processEntity);

        for (String uri : event.inputs()) {
            entities.add(buildAssetEntity(uri, event.snapshotAttributes()));
        }
        for (String uri : event.outputs()) {
            entities.add(buildAssetEntity(uri, event.snapshotAttributes()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entities", entities);

        PurviewEntityPublishResult result = registryClient.bulkCreateOrUpdateEntities(connReq, payload);

        if (result.isSuccess()) {
            logger.debug("Published lineage to Purview: eventKey={}, entities={}",
                    event.eventKey(), result.getPublishedCount());
            return LineageTargetSinkResult.success(result.getPublishedCount(),
                    "Published " + result.getPublishedCount() + " entities");
        } else {
            String msg = "Purview publish failed: " + result.getMessage();
            logger.warn("Failed to publish lineage to Purview: eventKey={}, error={}",
                    event.eventKey(), result.getMessage());
            return LineageTargetSinkResult.failure(msg);
        }
    }

    @Override
    public boolean isAvailable() {
        return purviewConfig.isEnabled()
                && !purviewConfig.getEndpoint().isEmpty();
    }

    // ---------------------------------------------------------------
    // processType → Purview typeName mapping
    // ---------------------------------------------------------------

    static String mapProcessTypeName(LineageProcessType processType) {
        if (processType == null) {
            return "nemaki_unknown_process";
        }
        return switch (processType) {
            case ARCHIVE_COLD, ARCHIVE_LOCAL -> "nemaki_archive_process";
            case IMPORT_FILESYSTEM, IMPORT_UPLOADED -> "nemaki_import_process";
            case EXPORT_FILESYSTEM, EXPORT_ZIP_FOLDER, EXPORT_SELECTED_OBJECTS -> "nemaki_export_process";
            case CLOUD_SYNC_UPLOAD, CLOUD_SYNC_DOWNLOAD -> "nemaki_cloud_sync_process";
        };
    }

    // ---------------------------------------------------------------
    // URI → Purview entity helpers
    // ---------------------------------------------------------------

    /**
     * Derives a Purview typeName from a nemaki URI based on path segments.
     *
     * <p>URI format: {@code nemaki://{repositoryId}/{segment}/{objectId}}
     * <ul>
     *   <li>{@code /objects/} → {@code nemaki_document}</li>
     *   <li>{@code /archives/} → {@code nemaki_archive}</li>
     *   <li>{@code /external-assets/} → {@code nemaki_external_asset}</li>
     * </ul>
     */
    static String inferAssetTypeName(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "nemaki_document";
        }
        if (uri.contains("/archives/")) {
            return "nemaki_archive";
        }
        if (uri.contains("/external-assets/")) {
            return "nemaki_external_asset";
        }
        return "nemaki_document";
    }

    /**
     * Builds a process qualifiedName.
     *
     * <p>Format: {@code nemaki://{repositoryId}/{process-segment}/{eventKey}}
     */
    static String buildProcessQualifiedName(LineageEvent event) {
        String segment = processTypeToSegment(event.processType());
        return "nemaki://" + event.repositoryId() + "/" + segment + "/" + event.eventKey();
    }

    private static String processTypeToSegment(LineageProcessType pt) {
        if (pt == null) return "processes";
        return switch (pt) {
            case ARCHIVE_COLD, ARCHIVE_LOCAL -> "archive-processes";
            case IMPORT_FILESYSTEM, IMPORT_UPLOADED -> "import-processes";
            case EXPORT_FILESYSTEM, EXPORT_ZIP_FOLDER, EXPORT_SELECTED_OBJECTS -> "export-processes";
            case CLOUD_SYNC_UPLOAD, CLOUD_SYNC_DOWNLOAD -> "cloud-sync-processes";
        };
    }

    /**
     * Builds a minimal entity reference (for inputs/outputs of a process).
     */
    private static Map<String, Object> buildEntityRef(String uri) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("typeName", inferAssetTypeName(uri));
        ref.put("uniqueAttributes", Map.of("qualifiedName", uri));
        return ref;
    }

    /**
     * Builds a full asset entity for the bulk payload.
     */
    private static Map<String, Object> buildAssetEntity(String uri, Map<String, String> snapshotAttributes) {
        Map<String, Object> entity = new LinkedHashMap<>();
        String typeName = inferAssetTypeName(uri);
        entity.put("typeName", typeName);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("qualifiedName", uri);
        // Use name from snapshot if available, otherwise derive from URI
        String name = snapshotAttributes.getOrDefault("name", extractNameFromUri(uri));
        attrs.put("name", name);

        entity.put("attributes", attrs);
        return entity;
    }

    /**
     * Extracts a display name from a nemaki URI (last path segment).
     */
    static String extractNameFromUri(String uri) {
        if (uri == null || uri.isEmpty()) return "unknown";
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < uri.length() - 1) {
            return uri.substring(lastSlash + 1);
        }
        return uri;
    }

    private PurviewConnectionRequest buildConnectionRequest() {
        return new PurviewConnectionRequest(
                purviewConfig.getEndpoint(),
                purviewConfig.getAtlasBasePath(),
                purviewConfig.getAuthType(),
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getBasicUsername(),
                purviewConfig.getBasicPassword(),
                purviewConfig.getConnectTimeoutMs(),
                purviewConfig.getReadTimeoutMs());
    }
}
