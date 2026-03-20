package jp.aegif.nemaki.rest.purview;

import java.util.List;

import org.springframework.stereotype.Service;

import jp.aegif.nemaki.model.Content;

@Service
public class PurviewDocumentTypeRelationshipServiceImpl implements PurviewDocumentTypeRelationshipService {

    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewDocumentTypeRelationshipServiceImpl(
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.purviewConfig = purviewConfig;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int upsertDocumentTypeRelationships(String repositoryId, List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        int processedCount = 0;
        for (Content content : contents) {
            if (!isRelationshipCandidate(content)) {
                continue;
            }

            try {
                PurviewEntityPublishResult result = entityRegistryClient.createRelationship(
                        buildConnectionRequest(),
                        entityPayloadFactory.buildDocumentTypeRelationship(repositoryId, content));
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

    private boolean isRelationshipCandidate(Content content) {
        return content != null
                && content.isDocument()
                && content.getId() != null
                && !content.getId().isBlank()
                && content.getObjectType() != null
                && !content.getObjectType().isBlank()
                && !content.getObjectType().startsWith("cmis:");
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
