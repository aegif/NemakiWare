package jp.aegif.nemaki.rest.purview.lineage;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import jp.aegif.nemaki.model.Content;

@Service
public class PurviewImportExportLineageServiceImpl implements PurviewImportExportLineageService {

    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewImportExportLineageServiceImpl(
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.purviewConfig = purviewConfig;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int upsertFilesystemImportLineage(
            String repositoryId,
            String folderId,
            String sourcePath,
            String requestedBy,
            long objectCount) {
        if (!purviewConfig.isEnabled()
                || isBlank(repositoryId)
                || isBlank(folderId)
                || isBlank(sourcePath)) {
            return 0;
        }
        long occurredAtMillis = Instant.now().toEpochMilli();
        return publish(List.of(
                entityPayloadFactory.buildFilesystemExternalAssetEntity(
                        repositoryId,
                        sourcePath,
                        requestedBy,
                        occurredAtMillis),
                entityPayloadFactory.buildFilesystemImportProcessEntity(
                        repositoryId,
                        folderId,
                        sourcePath,
                        requestedBy,
                        occurredAtMillis,
                        objectCount)));
    }

    @Override
    public int upsertUploadedImportLineage(
            String repositoryId,
            String folderId,
            String importMode,
            String requestedBy,
            long objectCount) {
        if (!purviewConfig.isEnabled()
                || isBlank(repositoryId)
                || isBlank(folderId)
                || isBlank(importMode)
                || objectCount <= 0L) {
            return 0;
        }
        long occurredAtMillis = Instant.now().toEpochMilli();
        return publish(List.of(
                entityPayloadFactory.buildUploadedImportProcessEntity(
                        repositoryId,
                        folderId,
                        importMode,
                        requestedBy,
                        occurredAtMillis,
                        objectCount)));
    }

    @Override
    public int upsertFilesystemExportLineage(
            String repositoryId,
            String folderId,
            String targetPath,
            String requestedBy,
            long objectCount) {
        if (!purviewConfig.isEnabled()
                || isBlank(repositoryId)
                || isBlank(folderId)
                || isBlank(targetPath)) {
            return 0;
        }
        long occurredAtMillis = Instant.now().toEpochMilli();
        return publish(List.of(
                entityPayloadFactory.buildFilesystemExternalAssetEntity(
                        repositoryId,
                        targetPath,
                        requestedBy,
                        occurredAtMillis),
                entityPayloadFactory.buildFilesystemExportProcessEntity(
                        repositoryId,
                        folderId,
                        targetPath,
                        requestedBy,
                        occurredAtMillis,
                        objectCount)));
    }

    @Override
    public int upsertZipFolderExportLineage(
            String repositoryId,
            String folderId,
            String folderName,
            String requestedBy,
            long objectCount) {
        if (!purviewConfig.isEnabled()
                || isBlank(repositoryId)
                || isBlank(folderId)
                || objectCount <= 0L) {
            return 0;
        }
        long occurredAtMillis = Instant.now().toEpochMilli();
        return publish(List.of(
                entityPayloadFactory.buildZipFolderExportProcessEntity(
                        repositoryId,
                        folderId,
                        folderName,
                        requestedBy,
                        occurredAtMillis,
                        objectCount)));
    }

    @Override
    public int upsertSelectedObjectsExportLineage(
            String repositoryId,
            List<? extends Content> contents,
            String requestedBy,
            long objectCount) {
        if (!purviewConfig.isEnabled()
                || isBlank(repositoryId)
                || contents == null
                || contents.isEmpty()
                || objectCount <= 0L) {
            return 0;
        }
        long occurredAtMillis = Instant.now().toEpochMilli();
        return publish(List.of(
                entityPayloadFactory.buildSelectedObjectsExportProcessEntity(
                        repositoryId,
                        contents,
                        requestedBy,
                        occurredAtMillis,
                        objectCount)));
    }

    private int publish(List<Map<String, Object>> entities) {
        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(entities));
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            return 1;
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
