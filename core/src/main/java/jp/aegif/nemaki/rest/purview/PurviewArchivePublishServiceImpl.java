package jp.aegif.nemaki.rest.purview;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Archive;

@Service
public class PurviewArchivePublishServiceImpl implements PurviewArchivePublishService {

    private static final int ARCHIVE_FETCH_PAGE_SIZE = 100;
    private static final int ENTITY_BATCH_SIZE = 100;

    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final ContentDaoService contentDaoService;

    public PurviewArchivePublishServiceImpl(
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService) {
        this.purviewConfig = purviewConfig;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.contentDaoService = contentDaoService;
    }

    @Override
    public int publishRepositoryArchives(String repositoryId) {
        List<Map<String, Object>> entityBatch = new ArrayList<>();
        int processedCount = 0;

        for (int skip = 0; ; skip += ARCHIVE_FETCH_PAGE_SIZE) {
            List<Archive> archives = contentDaoService.getArchives(repositoryId, skip, ARCHIVE_FETCH_PAGE_SIZE, Boolean.FALSE);
            if (archives == null || archives.isEmpty()) {
                break;
            }

            for (Archive archive : archives) {
                if (archive == null || archive.getId() == null || archive.getId().isBlank()
                        || archive.getOriginalId() == null || archive.getOriginalId().isBlank()) {
                    continue;
                }
                entityBatch.add(entityPayloadFactory.buildArchivedDocumentEntity(repositoryId, archive));
                processedCount += flushIfNeeded(entityBatch);
                entityBatch.add(entityPayloadFactory.buildArchiveEntity(repositoryId, archive));
                processedCount += flushIfNeeded(entityBatch);
            }

            if (archives.size() < ARCHIVE_FETCH_PAGE_SIZE) {
                break;
            }
        }

        return processedCount + flushEntities(entityBatch);
    }

    private int flushIfNeeded(List<Map<String, Object>> entities) {
        if (entities.size() < ENTITY_BATCH_SIZE) {
            return 0;
        }
        return flushEntities(entities);
    }

    private int flushEntities(List<Map<String, Object>> entities) {
        if (entities.isEmpty()) {
            return 0;
        }

        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(entities));
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            int publishedCount = entities.size();
            entities.clear();
            return publishedCount;
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
