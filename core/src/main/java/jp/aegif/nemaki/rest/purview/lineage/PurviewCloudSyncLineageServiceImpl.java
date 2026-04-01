package jp.aegif.nemaki.rest.purview.lineage;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.model.Content;

@Service
public class PurviewCloudSyncLineageServiceImpl implements PurviewCloudSyncLineageService {

    private static final Logger logger = LoggerFactory.getLogger(PurviewCloudSyncLineageServiceImpl.class);

    private static final String UNIQUE_ATTRIBUTE_NAME = "qualifiedName";
    private static final String EXTERNAL_ASSET_TYPE_NAME = "nemaki_external_asset";
    private static final String CLOUD_SYNC_PROCESS_TYPE_NAME = "nemaki_cloud_sync_process";

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewCloudSyncLineageServiceImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.connectionResolver = connectionResolver;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int upsertCloudSyncLineage(String repositoryId, List<Content> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }

        Map<String, Map<String, Object>> externalAssetsByStableKey = new LinkedHashMap<>();
        List<Map<String, Object>> processEntities = new ArrayList<>();

        for (Content content : documents) {
            if (content == null || !entityPayloadFactory.hasCloudSyncLineageTarget(content)) {
                continue;
            }

            String stableKey = entityPayloadFactory.resolveCloudExternalStableKey(content);
            if (stableKey == null || stableKey.isBlank()) {
                continue;
            }

            externalAssetsByStableKey.computeIfAbsent(stableKey,
                    ignored -> entityPayloadFactory.buildExternalAssetEntity(repositoryId, content));
            processEntities.add(entityPayloadFactory.buildCloudSyncProcessEntity(repositoryId, content));
        }

        if (externalAssetsByStableKey.isEmpty() && processEntities.isEmpty()) {
            return 0;
        }

        try {
            PurviewConnectionRequest connReq = buildConnectionRequest();

            // Phase 1: Create/update external asset entities first so that Atlas
            // can resolve relationship references when process entities are created.
            if (!externalAssetsByStableKey.isEmpty()) {
                List<Map<String, Object>> assetEntities = new ArrayList<>(externalAssetsByStableKey.values());
                PurviewEntityPublishResult assetResult = entityRegistryClient.bulkCreateOrUpdateEntities(
                        connReq, entityPayloadFactory.buildBulkPayload(assetEntities));
                if (!assetResult.isSuccess()) {
                    throw new IllegalStateException("External asset publish failed: " + assetResult.getMessage());
                }
            }

            // Phase 2: Create/update process entities with lineage relationships
            // (inputs → external asset, outputs → nemaki document).
            if (!processEntities.isEmpty()) {
                PurviewEntityPublishResult processResult = entityRegistryClient.bulkCreateOrUpdateEntities(
                        connReq, entityPayloadFactory.buildBulkPayload(processEntities));
                if (!processResult.isSuccess()) {
                    throw new IllegalStateException("Cloud sync process publish failed: " + processResult.getMessage());
                }
            }

            return processEntities.size();
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public int reconcileRemovedCloudSyncLineage(String repositoryId, Map<String, String> obsoleteSnapshotEntries, Set<String> activeStableKeys) {
        if (obsoleteSnapshotEntries == null || obsoleteSnapshotEntries.isEmpty()) {
            return 0;
        }

        Set<String> safeActiveKeys = (activeStableKeys != null) ? activeStableKeys : Set.of();
        int reconciledCount = 0;
        for (Map.Entry<String, String> entry : obsoleteSnapshotEntries.entrySet()) {
            String objectId = entry.getKey();
            String stableKey = parseStableKey(entry.getValue());
            if (objectId == null || objectId.isBlank()) {
                continue;
            }

            // Always delete the cloud-sync process entity (unique per document)
            reconciledCount += deleteEntity(
                    CLOUD_SYNC_PROCESS_TYPE_NAME,
                    entityPayloadFactory.buildCloudSyncProcessQualifiedName(repositoryId, objectId));

            // Only delete the external asset if no other current document still uses the same stable key
            if (stableKey != null && !stableKey.isBlank() && !safeActiveKeys.contains(stableKey)) {
                reconciledCount += deleteEntity(
                        EXTERNAL_ASSET_TYPE_NAME,
                        entityPayloadFactory.buildExternalAssetQualifiedName(repositoryId, stableKey));
            }
        }
        return reconciledCount;
    }


    @Override
    public int deleteCloudSyncLineageByObjectId(String repositoryId, String objectId, String stableKey) {
        if (objectId == null || objectId.isBlank()) {
            return 0;
        }
        int deletedCount = 0;
        // Delete the cloud-sync process entity (always unique per objectId)
        try {
            deletedCount += deleteEntity(
                    CLOUD_SYNC_PROCESS_TYPE_NAME,
                    entityPayloadFactory.buildCloudSyncProcessQualifiedName(repositoryId, objectId));
        } catch (IllegalStateException e) {
            // Entity may not exist — ignore 404-style errors
            if (!e.getMessage().contains("404")) {
                throw e;
            }
        }
        // External asset entities may be shared across multiple documents with the
        // same stableKey. The reconcile path checks activeStableKeys before deleting,
        // but this manual cleanup path cannot determine whether other documents still
        // reference the same key without a full scan. Skip deletion and log a notice
        // so the admin can use reconciliation to clean up orphaned assets safely.
        if (stableKey != null && !stableKey.isBlank()) {
            logger.info("Skipping external asset deletion for stableKey '{}' during manual cleanup of object '{}' "
                    + "in repository '{}'. Use full reconciliation to safely remove orphaned external assets.",
                    stableKey, objectId, repositoryId);
        }
        return deletedCount;
    }

    @Override
    public int deleteObsoleteExternalAsset(String repositoryId, String stableKey) {
        if (stableKey == null || stableKey.isBlank()) {
            return 0;
        }
        try {
            return deleteEntity(
                    EXTERNAL_ASSET_TYPE_NAME,
                    entityPayloadFactory.buildExternalAssetQualifiedName(repositoryId, stableKey));
        } catch (IllegalStateException e) {
            if (!e.getMessage().contains("404")) {
                throw e;
            }
            return 0;
        }
    }

    private String parseStableKey(String snapshotEntry) {
        if (snapshotEntry == null || snapshotEntry.isBlank()) {
            return null;
        }
        String[] parts = snapshotEntry.split("\\|", -1);
        if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return parts[1] + ":" + parts[2];
    }

    private int deleteEntity(String typeName, String qualifiedName) {
        try {
            PurviewEntityPublishResult result = entityRegistryClient.deleteByUniqueAttribute(
                    buildConnectionRequest(),
                    typeName,
                    UNIQUE_ATTRIBUTE_NAME,
                    qualifiedName);
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            return result.getPublishedCount();
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private PurviewConnectionRequest buildConnectionRequest() {
        return connectionResolver.buildConnectionRequest();
    }
}
