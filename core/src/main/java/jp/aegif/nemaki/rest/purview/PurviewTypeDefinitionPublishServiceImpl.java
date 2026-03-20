package jp.aegif.nemaki.rest.purview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.NemakiTypeDefinition;

@Service
public class PurviewTypeDefinitionPublishServiceImpl implements PurviewTypeDefinitionPublishService {

    private static final int ENTITY_BATCH_SIZE = 100;
    private static final String TYPE_DEFINITION_TYPE_NAME = "nemaki_type_definition";
    private static final String UNIQUE_ATTRIBUTE_NAME = "qualifiedName";

    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final ContentDaoService contentDaoService;

    public PurviewTypeDefinitionPublishServiceImpl(
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
    public int publishRepositoryTypeDefinitions(String repositoryId) {
        return publishTypeDefinitions(repositoryId, loadValidTypeDefinitions(repositoryId));
    }

    @Override
    public String buildRepositoryTypeDefinitionSnapshot(String repositoryId) {
        return buildSnapshot(loadValidTypeDefinitions(repositoryId));
    }

    @Override
    public PurviewTypeDefinitionSyncResult syncRepositoryTypeDefinitionsIfChanged(String repositoryId, String previousSnapshot) {
        List<NemakiTypeDefinition> typeDefinitions = loadValidTypeDefinitions(repositoryId);
        String currentSnapshot = buildSnapshot(typeDefinitions);
        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);
        if (Objects.equals(currentSnapshot, normalizedPreviousSnapshot)) {
            return new PurviewTypeDefinitionSyncResult(currentSnapshot, false, 0, 0);
        }

        int publishedCount = publishTypeDefinitions(repositoryId, typeDefinitions);
        int deletedCount = deleteMissingTypeDefinitions(repositoryId, normalizedPreviousSnapshot, typeDefinitions);
        return new PurviewTypeDefinitionSyncResult(currentSnapshot, true, publishedCount, deletedCount);
    }

    private List<NemakiTypeDefinition> loadValidTypeDefinitions(String repositoryId) {
        List<NemakiTypeDefinition> typeDefinitions = contentDaoService.getTypeDefinitions(repositoryId);
        if (typeDefinitions == null || typeDefinitions.isEmpty()) {
            return List.of();
        }

        return typeDefinitions.stream()
                .filter(Objects::nonNull)
                .filter(typeDefinition -> typeDefinition.getTypeId() != null && !typeDefinition.getTypeId().isBlank())
                .sorted(Comparator.comparing(NemakiTypeDefinition::getTypeId))
                .toList();
    }

    private int publishTypeDefinitions(String repositoryId, List<NemakiTypeDefinition> typeDefinitions) {
        if (typeDefinitions.isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> entityBatch = new ArrayList<>();
        int processedCount = 0;
        for (NemakiTypeDefinition typeDefinition : typeDefinitions) {
            entityBatch.add(entityPayloadFactory.buildTypeDefinitionEntity(repositoryId, typeDefinition));
            processedCount += flushIfNeeded(entityBatch);
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

    private int deleteMissingTypeDefinitions(
            String repositoryId,
            String previousSnapshot,
            List<NemakiTypeDefinition> currentTypeDefinitions) {
        Set<String> currentTypeIds = currentTypeDefinitions.stream()
                .map(NemakiTypeDefinition::getTypeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int deletedCount = 0;
        for (String previousTypeId : parseSnapshotTypeIds(previousSnapshot)) {
            if (currentTypeIds.contains(previousTypeId)) {
                continue;
            }

            try {
                PurviewEntityPublishResult result = entityRegistryClient.deleteByUniqueAttribute(
                        buildConnectionRequest(),
                        TYPE_DEFINITION_TYPE_NAME,
                        UNIQUE_ATTRIBUTE_NAME,
                        buildTypeDefinitionQualifiedName(repositoryId, previousTypeId));
                if (!result.isSuccess()) {
                    throw new IllegalStateException(result.getMessage());
                }
                deletedCount += result.getPublishedCount();
            } catch (PurviewClientException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        return deletedCount;
    }

    private Set<String> parseSnapshotTypeIds(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> typeIds = new LinkedHashSet<>();
        for (String line : snapshot.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int separatorIndex = line.indexOf('|');
            String typeId = separatorIndex >= 0 ? line.substring(0, separatorIndex) : line;
            if (!typeId.isBlank()) {
                typeIds.add(typeId);
            }
        }
        return typeIds;
    }

    private String buildSnapshot(List<NemakiTypeDefinition> typeDefinitions) {
        return typeDefinitions.stream()
                .map(typeDefinition -> String.join("|",
                        typeDefinition.getTypeId(),
                        String.valueOf(resolveModifiedMillis(typeDefinition)),
                        nullToEmpty(typeDefinition.getRevision()),
                        String.valueOf(typeDefinition.getProperties() == null ? 0 : typeDefinition.getProperties().size())))
                .collect(Collectors.joining("\n"));
    }

    private long resolveModifiedMillis(NemakiTypeDefinition typeDefinition) {
        if (typeDefinition.getModified() != null) {
            return typeDefinition.getModified().getTimeInMillis();
        }
        if (typeDefinition.getCreated() != null) {
            return typeDefinition.getCreated().getTimeInMillis();
        }
        return 0L;
    }

    private String buildTypeDefinitionQualifiedName(String repositoryId, String typeId) {
        return "nemaki://" + repositoryId + "/types/" + typeId;
    }

    private String normalizeSnapshot(String snapshot) {
        return snapshot == null ? "" : snapshot;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
