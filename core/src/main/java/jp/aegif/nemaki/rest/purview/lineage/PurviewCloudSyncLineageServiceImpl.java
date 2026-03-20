package jp.aegif.nemaki.rest.purview.lineage;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import jp.aegif.nemaki.model.Content;

@Service
public class PurviewCloudSyncLineageServiceImpl implements PurviewCloudSyncLineageService {

    private static final String UNIQUE_ATTRIBUTE_NAME = "qualifiedName";
    private static final String EXTERNAL_ASSET_TYPE_NAME = "nemaki_external_asset";
    private static final String CLOUD_SYNC_PROCESS_TYPE_NAME = "nemaki_cloud_sync_process";

    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewCloudSyncLineageServiceImpl(
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.purviewConfig = purviewConfig;
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

        List<Map<String, Object>> entities = new ArrayList<>(externalAssetsByStableKey.values());
        entities.addAll(processEntities);
        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(entities));
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            return processEntities.size();
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public int reconcileRemovedCloudSyncLineage(String repositoryId, Map<String, String> obsoleteSnapshotEntries) {
        if (obsoleteSnapshotEntries == null || obsoleteSnapshotEntries.isEmpty()) {
            return 0;
        }

        int reconciledCount = 0;
        for (Map.Entry<String, String> entry : obsoleteSnapshotEntries.entrySet()) {
            String objectId = entry.getKey();
            String stableKey = parseStableKey(entry.getValue());
            if (objectId == null || objectId.isBlank()) {
                continue;
            }

            reconciledCount += deleteEntity(
                    CLOUD_SYNC_PROCESS_TYPE_NAME,
                    entityPayloadFactory.buildCloudSyncProcessQualifiedName(repositoryId, objectId));
            if (stableKey != null && !stableKey.isBlank()) {
                reconciledCount += deleteEntity(
                        EXTERNAL_ASSET_TYPE_NAME,
                        entityPayloadFactory.buildExternalAssetQualifiedName(repositoryId, stableKey));
            }
        }
        return reconciledCount;
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
        return new PurviewConnectionRequest(
                purviewConfig.getEndpoint(),
                purviewConfig.getAtlasBasePath(),
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getConnectTimeoutMs(),
                purviewConfig.getReadTimeoutMs());
    }
}
