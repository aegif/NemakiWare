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

import jp.aegif.nemaki.model.Archive;

@Service
public class PurviewDocumentArchiveRelationshipServiceImpl implements PurviewDocumentArchiveRelationshipService {

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewDocumentArchiveRelationshipServiceImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.connectionResolver = connectionResolver;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int upsertDocumentArchiveRelationships(String repositoryId, List<Archive> archives, Map<String, String> guidByQualifiedName) {
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
                        entityPayloadFactory.buildDocumentArchiveRelationship(repositoryId, archive, guidByQualifiedName));
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
        return connectionResolver.buildConnectionRequest();
    }
}
