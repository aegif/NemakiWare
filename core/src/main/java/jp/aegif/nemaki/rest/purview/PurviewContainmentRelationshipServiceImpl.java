package jp.aegif.nemaki.rest.purview;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewContainmentRelationshipServiceImpl implements PurviewContainmentRelationshipService {

    private final RepositoryInfoMap repositoryInfoMap;
    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;

    public PurviewContainmentRelationshipServiceImpl(
            RepositoryInfoMap repositoryInfoMap,
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient) {
        this.repositoryInfoMap = repositoryInfoMap;
        this.purviewConfig = purviewConfig;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
    }

    @Override
    public int upsertContainmentRelationships(String repositoryId, List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        RepositoryInfo repositoryInfo = repositoryInfoMap.get(repositoryId);
        if (repositoryInfo == null || repositoryInfo.getRootFolderId() == null || repositoryInfo.getRootFolderId().isBlank()) {
            throw new IllegalStateException("Root folder ID is not configured for repository " + repositoryId);
        }

        int processedCount = 0;
        for (Content content : contents) {
            Map<String, Object> relationship = buildRelationshipPayload(repositoryId, repositoryInfo, content);
            if (relationship == null) {
                continue;
            }

            try {
                PurviewEntityPublishResult result = entityRegistryClient.createRelationship(
                        buildConnectionRequest(),
                        relationship);
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

    private Map<String, Object> buildRelationshipPayload(String repositoryId, RepositoryInfo repositoryInfo, Content content) {
        if (content == null || content.getId() == null || content.getId().isBlank()) {
            return null;
        }
        if (content.isFolder() && content.getId().equals(repositoryInfo.getRootFolderId())) {
            return entityPayloadFactory.buildRepositoryFolderRelationship(repositoryId, content);
        }
        if (content.getParentId() == null || content.getParentId().isBlank()) {
            return null;
        }
        if (content.isFolder()) {
            return entityPayloadFactory.buildFolderFolderRelationship(repositoryId, content);
        }
        if (content.isDocument()) {
            return entityPayloadFactory.buildFolderDocumentRelationship(repositoryId, content);
        }
        return null;
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
