package jp.aegif.nemaki.rest.purview;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

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
                purviewConfig.getTenantId(),
                purviewConfig.getClientId(),
                purviewConfig.getClientSecret(),
                purviewConfig.getConnectTimeoutMs(),
                purviewConfig.getReadTimeoutMs());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
