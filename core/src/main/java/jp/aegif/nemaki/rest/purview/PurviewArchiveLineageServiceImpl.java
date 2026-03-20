package jp.aegif.nemaki.rest.purview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import jp.aegif.nemaki.model.Archive;

@Service
public class PurviewArchiveLineageServiceImpl implements PurviewArchiveLineageService {

    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewArchiveLineageServiceImpl(
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.purviewConfig = purviewConfig;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int upsertArchiveLineage(String repositoryId, List<Archive> archives) {
        if (archives == null || archives.isEmpty()) {
            return 0;
        }

        Map<String, Map<String, Object>> externalAssetsByStableKey = new LinkedHashMap<>();
        List<Map<String, Object>> processEntities = new ArrayList<>();

        for (Archive archive : archives) {
            if (archive == null || !entityPayloadFactory.hasArchiveLineageTarget(archive)) {
                continue;
            }

            String stableKey = entityPayloadFactory.resolveArchiveExternalStableKey(archive);
            if (stableKey == null || stableKey.isBlank()) {
                continue;
            }

            externalAssetsByStableKey.computeIfAbsent(stableKey,
                    ignored -> entityPayloadFactory.buildExternalAssetEntity(repositoryId, archive));
            processEntities.add(entityPayloadFactory.buildArchiveProcessEntity(repositoryId, archive));
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
