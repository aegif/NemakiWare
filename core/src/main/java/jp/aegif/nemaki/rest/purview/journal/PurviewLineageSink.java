package jp.aegif.nemaki.rest.purview.journal;

import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
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
    private MetadataCatalogConnectionResolver connectionResolver;

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

        // Add process-type-specific required fields
        addProcessTypeAttributes(processAttrs, event);

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
        if (!connectionResolver.isAnyEnabled()) {
            return false;
        }
        try {
            PurviewConnectionRequest request = connectionResolver.buildConnectionRequest();
            return request != null && request.getEndpoint() != null && !request.getEndpoint().isBlank();
        } catch (Exception e) {
            return false;
        }
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
            case CLOUD_SYNC_UPLOAD, CLOUD_SYNC_DOWNLOAD,
                 FILE_SHARE_SYNC_UPLOAD, FILE_SHARE_SYNC_DOWNLOAD -> "nemaki_cloud_sync_process";
            case EXTERNAL_NOTE_IMPORT, EXTERNAL_ATTACHMENT_IMPORT,
                 BUSINESS_RECORD_IMPORT, CHAT_ATTACHMENT_IMPORT -> "nemaki_import_process";
        };
    }

    // ---------------------------------------------------------------
    // URI → Purview entity helpers
    // ---------------------------------------------------------------

    /**
     * Derives a Purview typeName from a URI based on scheme and path segments.
     *
     * <p>Supported URI schemes:
     * <ul>
     *   <li>{@code upload://} → {@code nemaki_external_asset}</li>
     *   <li>{@code file://} → {@code nemaki_external_asset}</li>
     *   <li>{@code cloud://} → {@code nemaki_external_asset}</li>
     *   <li>{@code cold://} → {@code nemaki_external_asset}</li>
     *   <li>{@code nemaki://.../archives/} → {@code nemaki_archive}</li>
     *   <li>{@code nemaki://.../external-assets/} → {@code nemaki_external_asset}</li>
     *   <li>{@code nemaki://.../objects/} → {@code nemaki_document}</li>
     * </ul>
     */
    static String inferAssetTypeName(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "nemaki_document";
        }
        // External scheme-based URIs (legacy and canonical ingestion)
        if (uri.startsWith("upload://") || uri.startsWith("file://")
                || uri.startsWith("cloud://") || uri.startsWith("cold://")) {
            return "nemaki_external_asset";
        }
        // Canonical external source URIs: {sourceSystem}://...
        // Any URI with :// that is NOT nemaki:// is an external asset
        if (uri.contains("://") && !uri.startsWith("nemaki://")) {
            return "nemaki_external_asset";
        }
        // nemaki:// path-based URIs
        if (uri.contains("/archives/")) {
            return "nemaki_archive";
        }
        if (uri.contains("/external-assets/")) {
            return "nemaki_external_asset";
        }
        return "nemaki_document";
    }

    /**
     * Infers the sourceSystem from a URI scheme for external assets.
     */
    static String inferSourceSystem(String uri) {
        if (uri == null) return "unknown";
        if (uri.startsWith("upload://")) return "upload";
        if (uri.startsWith("file://")) return "filesystem";
        if (uri.startsWith("cloud://")) return "cloud-drive";
        if (uri.startsWith("cold://")) return "cold-storage";
        // Canonical external source URIs: extract sourceSystem from scheme
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd > 0 && !uri.startsWith("nemaki://")) {
            return uri.substring(0, schemeEnd);
        }
        return "nemakiware";
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
            case IMPORT_FILESYSTEM, IMPORT_UPLOADED,
                 EXTERNAL_NOTE_IMPORT, EXTERNAL_ATTACHMENT_IMPORT,
                 BUSINESS_RECORD_IMPORT, CHAT_ATTACHMENT_IMPORT -> "import-processes";
            case EXPORT_FILESYSTEM, EXPORT_ZIP_FOLDER, EXPORT_SELECTED_OBJECTS -> "export-processes";
            case CLOUD_SYNC_UPLOAD, CLOUD_SYNC_DOWNLOAD,
                 FILE_SHARE_SYNC_UPLOAD, FILE_SHARE_SYNC_DOWNLOAD -> "cloud-sync-processes";
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
     * Builds a full asset entity for the bulk payload with type-specific required fields.
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

        // Type-specific required fields
        switch (typeName) {
            case "nemaki_external_asset" -> {
                attrs.put("externalStableKey", uri);
                attrs.put("sourceSystem", inferSourceSystem(uri));
                String path = extractPathFromUri(uri);
                if (path != null) attrs.put("externalPath", path);
            }
            case "nemaki_archive" -> {
                attrs.put("originalObjectId",
                        snapshotAttributes.getOrDefault("originalId", extractLastSegment(uri)));
                attrs.put("archiveRepositoryId",
                        snapshotAttributes.getOrDefault("repositoryId", extractRepositoryIdFromUri(uri)));
                attrs.put("lifecycleState", "ARCHIVED");
            }
            case "nemaki_document" -> {
                attrs.put("repositoryId", extractRepositoryIdFromUri(uri));
                attrs.put("objectId", extractLastSegment(uri));
            }
            default -> { /* no additional fields */ }
        }

        entity.put("attributes", attrs);
        return entity;
    }

    /**
     * Adds process-type-specific required fields to the process entity attributes.
     */
    private static void addProcessTypeAttributes(Map<String, Object> processAttrs,
                                                  LineageEvent event) {
        if (event.processType() == null) return;
        Map<String, String> snap = event.snapshotAttributes();

        switch (event.processType()) {
            case IMPORT_FILESYSTEM, IMPORT_UPLOADED -> {
                processAttrs.putIfAbsent("repositoryId", event.repositoryId());
                if (!event.outputs().isEmpty()) {
                    processAttrs.putIfAbsent("folderId", extractLastSegment(event.outputs().get(0)));
                }
                processAttrs.putIfAbsent("importMode", snap.getOrDefault("importMode", "uploaded"));
            }
            case EXPORT_FILESYSTEM, EXPORT_ZIP_FOLDER, EXPORT_SELECTED_OBJECTS -> {
                processAttrs.putIfAbsent("repositoryId", event.repositoryId());
                processAttrs.putIfAbsent("exportMode", event.processType().name().toLowerCase());
            }
            case CLOUD_SYNC_UPLOAD, CLOUD_SYNC_DOWNLOAD -> {
                processAttrs.putIfAbsent("repositoryId", event.repositoryId());
                if (!event.outputs().isEmpty()) {
                    processAttrs.putIfAbsent("objectId", extractLastSegment(event.outputs().get(0)));
                }
                processAttrs.putIfAbsent("cloudProvider", snap.getOrDefault("provider", "unknown"));
                // Find cloud:// URI in inputs or outputs for externalStableKey
                for (String uri : event.inputs()) {
                    if (uri.startsWith("cloud://")) { processAttrs.putIfAbsent("externalStableKey", uri); break; }
                }
                for (String uri : event.outputs()) {
                    if (uri.startsWith("cloud://")) { processAttrs.putIfAbsent("externalStableKey", uri); break; }
                }
            }
            case ARCHIVE_COLD, ARCHIVE_LOCAL -> {
                processAttrs.putIfAbsent("repositoryId", event.repositoryId());
                if (!event.outputs().isEmpty()) {
                    String outputUri = event.outputs().get(0);
                    processAttrs.putIfAbsent("archiveId", extractLastSegment(outputUri));
                    processAttrs.putIfAbsent("externalStableKey", outputUri);
                }
            }
            case FILE_SHARE_SYNC_UPLOAD, FILE_SHARE_SYNC_DOWNLOAD -> {
                processAttrs.putIfAbsent("repositoryId", event.repositoryId());
                if (!event.outputs().isEmpty()) {
                    processAttrs.putIfAbsent("objectId", extractLastSegment(event.outputs().get(0)));
                }
                processAttrs.putIfAbsent("cloudProvider",
                        snap.getOrDefault("sourceSystem", snap.getOrDefault("provider", "unknown")));
                // Find external source URI in inputs for externalStableKey
                for (String uri : event.inputs()) {
                    if (uri.contains("://") && !uri.startsWith("nemaki://")) {
                        processAttrs.putIfAbsent("externalStableKey", uri);
                        break;
                    }
                }
            }
            case EXTERNAL_NOTE_IMPORT, EXTERNAL_ATTACHMENT_IMPORT,
                 BUSINESS_RECORD_IMPORT, CHAT_ATTACHMENT_IMPORT -> {
                processAttrs.putIfAbsent("repositoryId", event.repositoryId());
                // Use targetFolderId from snapshot (set by CanonicalImportService),
                // NOT from outputs which contains the created document objectId.
                processAttrs.putIfAbsent("folderId",
                        snap.getOrDefault("targetFolderId", ""));
                processAttrs.putIfAbsent("importMode",
                        snap.getOrDefault("sourceArchetype", "external"));
                processAttrs.putIfAbsent("sourceDescription",
                        snap.getOrDefault("sourceSystem", "") + ":"
                        + snap.getOrDefault("sourceObjectId", ""));
                // External source URI as stableKey
                for (String uri : event.inputs()) {
                    if (uri.contains("://") && !uri.startsWith("nemaki://")) {
                        processAttrs.putIfAbsent("externalStableKey", uri);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Extracts a display name from a URI (last path segment).
     */
    static String extractNameFromUri(String uri) {
        if (uri == null || uri.isEmpty()) return "unknown";
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < uri.length() - 1) {
            return uri.substring(lastSlash + 1);
        }
        return uri;
    }

    /**
     * Extracts the last path segment from a URI.
     */
    static String extractLastSegment(String uri) {
        if (uri == null || uri.isEmpty()) return "";
        // Remove trailing slash
        String clean = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        int lastSlash = clean.lastIndexOf('/');
        return (lastSlash >= 0 && lastSlash < clean.length() - 1) ? clean.substring(lastSlash + 1) : clean;
    }

    /**
     * Extracts the path portion from a URI (after scheme://host).
     */
    static String extractPathFromUri(String uri) {
        if (uri == null) return null;
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) return uri;
        return uri.substring(schemeEnd + 3);
    }

    /**
     * Extracts the repositoryId from a nemaki:// URI.
     * Format: nemaki://{repositoryId}/...
     */
    static String extractRepositoryIdFromUri(String uri) {
        if (uri == null || !uri.startsWith("nemaki://")) return "";
        String afterScheme = uri.substring("nemaki://".length());
        int slash = afterScheme.indexOf('/');
        return slash > 0 ? afterScheme.substring(0, slash) : afterScheme;
    }

    private PurviewConnectionRequest buildConnectionRequest() {
        return connectionResolver.buildConnectionRequest();
    }
}
