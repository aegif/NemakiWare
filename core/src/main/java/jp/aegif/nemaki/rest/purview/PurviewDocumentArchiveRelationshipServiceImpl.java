package jp.aegif.nemaki.rest.purview;

import java.util.List;

import org.springframework.stereotype.Service;

import jp.aegif.nemaki.model.Archive;

@Service
public class PurviewDocumentArchiveRelationshipServiceImpl implements PurviewDocumentArchiveRelationshipService {

    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewDocumentArchiveRelationshipServiceImpl(
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.purviewConfig = purviewConfig;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int upsertDocumentArchiveRelationships(String repositoryId, List<Archive> archives) {
        if (archives == null || archives.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        for (Archive archive : archives) {
            if (!isRelationshipCandidate(archive)) {
                continue;
            }

            try {
                PurviewEntityPublishResult result = entityRegistryClient.createRelationship(
                        buildConnectionRequest(),
                        entityPayloadFactory.buildDocumentArchiveRelationship(repositoryId, archive));
                if (!result.isSuccess()) {
                    throw new IllegalStateException(result.getMessage());
                }
                processedCount++;
            } catch (PurviewClientException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }

        return processedCount;
    }

    private boolean isRelationshipCandidate(Archive archive) {
        return archive != null
                && archive.getId() != null
                && !archive.getId().isBlank()
                && archive.getOriginalId() != null
                && !archive.getOriginalId().isBlank();
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
