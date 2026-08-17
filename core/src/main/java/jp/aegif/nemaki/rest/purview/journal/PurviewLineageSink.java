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
    public LineageTargetSinkResult publish(LineageRecord record) throws Exception {
        if (record == null) {
            return LineageTargetSinkResult.skipped("null record");
        }
        String unresolved = LineageSinkAssets.firstUnresolvedReason(record);
        if (unresolved != null) {
            return LineageTargetSinkResult.failure(unresolved);
        }

        PurviewConnectionRequest connReq = buildConnectionRequest();

        // Build process entity payload
        String processTypeName = mapProcessTypeName(record.processType());
        String processQualifiedName = buildProcessQualifiedName(record);

        Map<String, Object> processEntity = new LinkedHashMap<>();
        processEntity.put("typeName", processTypeName);
        Map<String, Object> processAttrs = new LinkedHashMap<>();
        processAttrs.put("qualifiedName", processQualifiedName);
        processAttrs.put("name", processTypeName + ":" + record.processIdentity());

        // v1's event-level snapshot as custom attributes on the Process. These are event-level
        // facts (requestedBy, reason, objectCount), so the Process is where they belong; the
        // defect §2 fixed was copying them onto every *asset* as well, which is handled below.
        // A v2 record has none: its attributes travel on the endpoints.
        if (!record.legacyEventAttributes().isEmpty()) {
            for (Map.Entry<String, String> entry : record.legacyEventAttributes().entrySet()) {
                processAttrs.put(entry.getKey(), entry.getValue());
            }
        }

        // Build inputs
        List<Map<String, Object>> inputRefs = new ArrayList<>();
        for (LineageAssetRef ref : record.inputs()) {
            inputRefs.add(buildEntityRef(ref.qualifiedName()));
        }
        processAttrs.put("inputs", inputRefs);

        // Build outputs
        List<Map<String, Object>> outputRefs = new ArrayList<>();
        for (LineageAssetRef ref : record.outputs()) {
            outputRefs.add(buildEntityRef(ref.qualifiedName()));
        }
        processAttrs.put("outputs", outputRefs);

        // Add process-type-specific required fields
        addProcessTypeAttributes(processAttrs, record);

        processEntity.put("attributes", processAttrs);

        // Build entity list: process + input entities + output entities
        List<Map<String, Object>> entities = new ArrayList<>();
        entities.add(processEntity);

        for (LineageAssetRef ref : record.allAssets()) {
            entities.add(buildAssetEntity(ref, record.legacyEventAttributes()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entities", entities);

        PurviewEntityPublishResult result = registryClient.bulkCreateOrUpdateEntities(connReq, payload);

        if (result.isSuccess()) {
            logger.debug("Published lineage to Purview: processIdentity={}, entities={}",
                    record.processIdentity(), result.getPublishedCount());
            return LineageTargetSinkResult.success(result.getPublishedCount(),
                    "Published " + result.getPublishedCount() + " entities");
        } else {
            String msg = "Purview publish failed: " + result.getMessage();
            logger.warn("Failed to publish lineage to Purview: processIdentity={}, error={}",
                    record.processIdentity(), result.getMessage());
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
                 BUSINESS_RECORD_IMPORT, CHAT_ATTACHMENT_IMPORT,
                 MAIL_MESSAGE_IMPORT, MAIL_ATTACHMENT_IMPORT,
                 GENERIC_EXTERNAL_INGEST, CHAT_MESSAGE_IMPORT -> "nemaki_import_process";
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
     * <p>Format: {@code nemaki://{repositoryId}/{process-segment}/{processIdentity}}, where the
     * identity is the v1 {@code eventKey} or the v2 {@code processKey} (§3).
     */
    static String buildProcessQualifiedName(LineageRecord record) {
        String segment = processTypeToSegment(record.processType());
        return "nemaki://" + record.repositoryId() + "/" + segment + "/" + record.processIdentity();
    }

    /** The names only, for the scheme-sniffing branches below that predate typed endpoints. */
    private static List<String> qualifiedNames(List<LineageAssetRef> refs) {
        List<String> names = new ArrayList<>(refs.size());
        for (LineageAssetRef ref : refs) {
            names.add(ref.qualifiedName());
        }
        return names;
    }

    private static String processTypeToSegment(LineageProcessType pt) {
        if (pt == null) return "processes";
        return switch (pt) {
            case ARCHIVE_COLD, ARCHIVE_LOCAL -> "archive-processes";
            case IMPORT_FILESYSTEM, IMPORT_UPLOADED,
                 EXTERNAL_NOTE_IMPORT, EXTERNAL_ATTACHMENT_IMPORT,
                 BUSINESS_RECORD_IMPORT, CHAT_ATTACHMENT_IMPORT,
                 MAIL_MESSAGE_IMPORT, MAIL_ATTACHMENT_IMPORT,
                 GENERIC_EXTERNAL_INGEST, CHAT_MESSAGE_IMPORT -> "import-processes";
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
    private static Map<String, Object> buildAssetEntity(LineageAssetRef ref,
                                                        Map<String, String> legacyEventAttributes) {
        return switch (ref) {
            // A typed reference carries its own kind and its own attributes, so neither has to be
            // guessed from the name and neither comes from an event-level map. This is the whole
            // point of §2's endpoint-local snapshot: the v1 branch below applies one map to every
            // asset, so a two-document event gave both documents the same name.
            case LineageAssetRef.Typed typed -> typedAssetEntity(typed);
            case LineageAssetRef.LegacyName legacy ->
                    legacyAssetEntity(legacy.qualifiedName(), legacyEventAttributes);
            case LineageAssetRef.Unresolved unresolved -> throw new IllegalStateException(
                    "unresolved asset must not reach the payload: " + unresolved);
        };
    }

    /** An asset whose kind and attributes the event itself carries (v2). */
    private static Map<String, Object> typedAssetEntity(LineageAssetRef.Typed typed) {
        LineageEndpoint endpoint = typed.endpoint();
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("typeName", endpoint.kind().atlasTypeName());

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("qualifiedName", endpoint.catalogQualifiedName());
        // The allowlist in EndpointKind is already checked against the real Atlas schema by
        // EndpointKindSchemaAlignmentTest, so everything here is an attribute the type has.
        attrs.putAll(endpoint.attributes());
        entity.put("attributes", attrs);
        return entity;
    }

    /** A v1 asset: kind inferred from the name, attributes from the event-level snapshot. */
    private static Map<String, Object> legacyAssetEntity(String uri,
                                                         Map<String, String> snapshotAttributes) {
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
                                                  LineageRecord record) {
        if (record.processType() == null) return;
        Map<String, String> snap = record.legacyEventAttributes();

        switch (record.processType()) {
            case IMPORT_FILESYSTEM, IMPORT_UPLOADED -> {
                processAttrs.putIfAbsent("repositoryId", record.repositoryId());
                if (!record.outputs().isEmpty()) {
                    processAttrs.putIfAbsent("folderId",
                            extractLastSegment(record.outputs().get(0).qualifiedName()));
                }
                processAttrs.putIfAbsent("importMode", snap.getOrDefault("importMode", "uploaded"));
            }
            case EXPORT_FILESYSTEM, EXPORT_ZIP_FOLDER, EXPORT_SELECTED_OBJECTS -> {
                processAttrs.putIfAbsent("repositoryId", record.repositoryId());
                processAttrs.putIfAbsent("exportMode", record.processType().name().toLowerCase());
            }
            case CLOUD_SYNC_UPLOAD, CLOUD_SYNC_DOWNLOAD -> {
                processAttrs.putIfAbsent("repositoryId", record.repositoryId());
                if (!record.outputs().isEmpty()) {
                    processAttrs.putIfAbsent("objectId",
                            extractLastSegment(record.outputs().get(0).qualifiedName()));
                }
                processAttrs.putIfAbsent("cloudProvider", snap.getOrDefault("provider", "unknown"));
                // Find cloud:// URI in inputs or outputs for externalStableKey
                for (String uri : qualifiedNames(record.inputs())) {
                    if (uri.startsWith("cloud://")) { processAttrs.putIfAbsent("externalStableKey", uri); break; }
                }
                for (String uri : qualifiedNames(record.outputs())) {
                    if (uri.startsWith("cloud://")) { processAttrs.putIfAbsent("externalStableKey", uri); break; }
                }
            }
            case ARCHIVE_COLD, ARCHIVE_LOCAL -> {
                processAttrs.putIfAbsent("repositoryId", record.repositoryId());
                if (!record.outputs().isEmpty()) {
                    String outputUri = record.outputs().get(0).qualifiedName();
                    processAttrs.putIfAbsent("archiveId", extractLastSegment(outputUri));
                    processAttrs.putIfAbsent("externalStableKey", outputUri);
                }
            }
            case FILE_SHARE_SYNC_UPLOAD, FILE_SHARE_SYNC_DOWNLOAD -> {
                processAttrs.putIfAbsent("repositoryId", record.repositoryId());
                if (!record.outputs().isEmpty()) {
                    processAttrs.putIfAbsent("objectId",
                            extractLastSegment(record.outputs().get(0).qualifiedName()));
                }
                processAttrs.putIfAbsent("cloudProvider",
                        snap.getOrDefault("sourceSystem", snap.getOrDefault("provider", "unknown")));
                // Find external source URI in inputs for externalStableKey
                for (String uri : qualifiedNames(record.inputs())) {
                    if (uri.contains("://") && !uri.startsWith("nemaki://")) {
                        processAttrs.putIfAbsent("externalStableKey", uri);
                        break;
                    }
                }
            }
            case EXTERNAL_NOTE_IMPORT, EXTERNAL_ATTACHMENT_IMPORT,
                 BUSINESS_RECORD_IMPORT, CHAT_ATTACHMENT_IMPORT,
                 MAIL_MESSAGE_IMPORT, MAIL_ATTACHMENT_IMPORT,
                 GENERIC_EXTERNAL_INGEST, CHAT_MESSAGE_IMPORT -> {
                processAttrs.putIfAbsent("repositoryId", record.repositoryId());
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
                for (String uri : qualifiedNames(record.inputs())) {
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
