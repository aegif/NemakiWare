package jp.aegif.nemaki.rest.purview.relationship;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import jp.aegif.nemaki.model.Content;

@Service
public class PurviewDocumentTypeRelationshipServiceImpl implements PurviewDocumentTypeRelationshipService {

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewDocumentTypeRelationshipServiceImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.connectionResolver = connectionResolver;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int upsertDocumentTypeRelationships(String repositoryId, List<Content> contents, Map<String, String> guidByQualifiedName) {
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
                        entityPayloadFactory.buildDocumentTypeRelationship(repositoryId, content, guidByQualifiedName));
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
        return connectionResolver.buildConnectionRequest();
    }
}
