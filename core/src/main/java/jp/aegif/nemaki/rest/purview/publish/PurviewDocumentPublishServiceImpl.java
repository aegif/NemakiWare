package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.relationship.PurviewContainmentRelationshipService;
import jp.aegif.nemaki.rest.purview.relationship.PurviewDocumentTypeRelationshipService;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewDocumentPublishServiceImpl implements PurviewDocumentPublishService {

    private static final Logger logger = LoggerFactory.getLogger(PurviewDocumentPublishServiceImpl.class);

    private static final int CHILD_FETCH_PAGE_SIZE = 100;
    private static final int ENTITY_BATCH_SIZE = 100;
    private static final String DOCUMENT_ENTITY_STREAM_KIND = "document-entity";

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final PurviewContainmentRelationshipService containmentRelationshipService;
    private final PurviewDocumentTypeRelationshipService documentTypeRelationshipService;
    private final PurviewDeadLetterStateService deadLetterStateService;

    public PurviewDocumentPublishServiceImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            PurviewContainmentRelationshipService containmentRelationshipService,
            PurviewDocumentTypeRelationshipService documentTypeRelationshipService,
            PurviewDeadLetterStateService deadLetterStateService) {
        this.connectionResolver = connectionResolver;
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.containmentRelationshipService = containmentRelationshipService;
        this.documentTypeRelationshipService = documentTypeRelationshipService;
        this.deadLetterStateService = deadLetterStateService;
    }

    @Override
    public int publishRepositoryHierarchy(String repositoryId) {
        RepositoryInfo repositoryInfo = resolveRepositoryInfo(repositoryId);
        String rootFolderId = resolveRootFolderId(repositoryId);
        Deque<String> folderQueue = new ArrayDeque<>();
        folderQueue.add(rootFolderId);
        List<Map<String, Object>> entityBatch = new ArrayList<>();
        List<Content> containmentCandidates = new ArrayList<>();
        List<Content> relationshipCandidates = new ArrayList<>();
        Map<String, String> pathMap = new HashMap<>();
        pathMap.put(rootFolderId, "/");
        Map<String, String> guidAccumulator = new HashMap<>();
        Set<String> failedQualifiedNames = new HashSet<>();
        int processedCount = 0;

        entityBatch.add(entityPayloadFactory.buildRepositoryEntity(repositoryInfo));
        processedCount += flushIfNeeded(repositoryId, entityBatch, guidAccumulator, failedQualifiedNames);

        Content rootFolder = contentDaoService.getContent(repositoryId, rootFolderId);
        if (rootFolder == null || !rootFolder.isFolder()) {
            throw new IllegalStateException("Root folder content is not available for repository " + repositoryId);
        }
        entityBatch.add(entityPayloadFactory.buildFolderEntity(repositoryId, rootFolder, "/"));
        containmentCandidates.add(rootFolder);
        processedCount += flushIfNeeded(repositoryId, entityBatch, guidAccumulator, failedQualifiedNames);

        while (!folderQueue.isEmpty()) {
            String folderId = folderQueue.removeFirst();
            String parentPath = pathMap.getOrDefault(folderId, "/");
            long totalChildren = Math.max(0L, contentDaoService.getChildrenCount(repositoryId, folderId));
            for (int skip = 0; skip < totalChildren; skip += CHILD_FETCH_PAGE_SIZE) {
                List<Content> children = contentDaoService.getChildrenPaged(
                        repositoryId,
                        folderId,
                        skip,
                        CHILD_FETCH_PAGE_SIZE);
                if (children == null || children.isEmpty()) {
                    break;
                }

                for (Content child : children) {
                    if (child == null || child.getId() == null || child.getId().isBlank()) {
                        continue;
                    }

                    if (child.isFolder()) {
                        String childName = entityPayloadFactory.firstNonBlank(child.getName(), child.getId());
                        String childPath = "/".equals(parentPath)
                                ? "/" + childName
                                : parentPath + "/" + childName;
                        pathMap.put(child.getId(), childPath);
                        entityBatch.add(entityPayloadFactory.buildFolderEntity(repositoryId, child, childPath));
                        containmentCandidates.add(child);
                        processedCount += flushIfNeeded(repositoryId, entityBatch, guidAccumulator, failedQualifiedNames);
                        folderQueue.addLast(child.getId());
                        continue;
                    }
                    if (!child.isDocument()) {
                        continue;
                    }

                    entityBatch.add(entityPayloadFactory.buildDocumentEntity(repositoryId, child, parentPath));
                    containmentCandidates.add(child);
                    relationshipCandidates.add(child);
                    processedCount += flushIfNeeded(repositoryId, entityBatch, guidAccumulator, failedQualifiedNames);
                }
            }
        }

        processedCount += flushEntities(repositoryId, entityBatch, guidAccumulator, failedQualifiedNames);
        pruneFailedCandidates(repositoryId, containmentCandidates, failedQualifiedNames);
        pruneFailedCandidates(repositoryId, relationshipCandidates, failedQualifiedNames);
        return processedCount
                + containmentRelationshipService.upsertContainmentRelationships(repositoryId, containmentCandidates, guidAccumulator)
                + documentTypeRelationshipService.upsertDocumentTypeRelationships(repositoryId, relationshipCandidates, guidAccumulator);
    }

    @Override
    public int publishRepositoryDocuments(String repositoryId) {
        return publishRepositoryHierarchy(repositoryId);
    }

    @Override
    public int upsertContents(String repositoryId, List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        Map<String, String> pathCache = new HashMap<>();
        String rootFolderId = resolveRootFolderIdQuietly(repositoryId);
        if (rootFolderId != null) {
            pathCache.put(rootFolderId, "/");
        }

        List<Map<String, Object>> pending = new ArrayList<>();
        List<Content> containmentCandidates = new ArrayList<>();
        List<Content> relationshipCandidates = new ArrayList<>();
        Map<String, String> guidAccumulator = new HashMap<>();
        Set<String> failedQualifiedNames = new HashSet<>();
        int processedCount = 0;
        for (Content content : contents) {
            String folderPath = resolveFolderPath(repositoryId, content, pathCache);
            Map<String, Object> entity = buildContentEntity(repositoryId, content, folderPath);
            if (entity == null) {
                continue;
            }
            pending.add(entity);
            containmentCandidates.add(content);
            if (content != null && content.isDocument()) {
                relationshipCandidates.add(content);
            }
            processedCount += flushIfNeeded(repositoryId, pending, guidAccumulator, failedQualifiedNames);
        }
        processedCount += flushEntities(repositoryId, pending, guidAccumulator, failedQualifiedNames);
        pruneFailedCandidates(repositoryId, containmentCandidates, failedQualifiedNames);
        pruneFailedCandidates(repositoryId, relationshipCandidates, failedQualifiedNames);
        return processedCount
                + containmentRelationshipService.upsertContainmentRelationships(repositoryId, containmentCandidates, guidAccumulator)
                + documentTypeRelationshipService.upsertDocumentTypeRelationships(repositoryId, relationshipCandidates, guidAccumulator);
    }

    @Override
    public int upsertDocuments(String repositoryId, List<Content> documents) {
        return upsertContents(repositoryId, documents);
    }

    private Map<String, Object> buildContentEntity(String repositoryId, Content content, String folderPath) {
        if (content == null) {
            return null;
        }
        if (content.isFolder()) {
            return entityPayloadFactory.buildFolderEntity(repositoryId, content, folderPath);
        }
        if (content.isDocument()) {
            return entityPayloadFactory.buildDocumentEntity(repositoryId, content, folderPath);
        }
        return null;
    }

    /**
     * Resolves the folder path for a content item by walking up the parent chain.
     * Results are cached in {@code pathCache} so the same parent is not fetched twice within a batch.
     * For documents, returns the parent folder's path. For folders, returns the folder's own path.
     */
    private String resolveFolderPath(String repositoryId, Content content, Map<String, String> pathCache) {
        if (content == null || content.getId() == null) {
            return null;
        }

        // For documents: resolve the parent folder's path
        if (content.isDocument()) {
            String parentId = content.getParentId();
            if (parentId == null || parentId.isBlank()) {
                return null;
            }
            return resolvePathForFolder(repositoryId, parentId, pathCache);
        }

        // For folders: resolve this folder's own path (parent path + own name)
        if (content.isFolder()) {
            String parentId = content.getParentId();
            if (parentId == null || parentId.isBlank()) {
                // This is a root folder
                pathCache.put(content.getId(), "/");
                return "/";
            }
            String parentPath = resolvePathForFolder(repositoryId, parentId, pathCache);
            if (parentPath == null) {
                return null;
            }
            String name = entityPayloadFactory.firstNonBlank(content.getName(), content.getId());
            String folderPath = "/".equals(parentPath) ? "/" + name : parentPath + "/" + name;
            pathCache.put(content.getId(), folderPath);
            return folderPath;
        }

        return null;
    }

    private static final int MAX_PATH_DEPTH = 100;

    private String resolvePathForFolder(String repositoryId, String folderId, Map<String, String> pathCache) {
        if (pathCache.containsKey(folderId)) {
            return pathCache.get(folderId);
        }

        // Walk up the parent chain, collecting ancestors
        List<Content> ancestors = new ArrayList<>();
        String currentId = folderId;
        for (int depth = 0; depth < MAX_PATH_DEPTH; depth++) {
            if (pathCache.containsKey(currentId)) {
                break; // Found a cached ancestor
            }
            Content folder = contentDaoService.getContent(repositoryId, currentId);
            if (folder == null || !folder.isFolder()) {
                return null; // Cannot resolve
            }
            ancestors.add(folder);
            String parentId = folder.getParentId();
            if (parentId == null || parentId.isBlank()) {
                // Reached root
                pathCache.put(folder.getId(), "/");
                break;
            }
            currentId = parentId;
        }

        // Build paths from the deepest known ancestor down
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            Content ancestor = ancestors.get(i);
            String parentId = ancestor.getParentId();
            if (parentId == null || parentId.isBlank()) {
                pathCache.put(ancestor.getId(), "/");
                continue;
            }
            String parentPath = pathCache.get(parentId);
            if (parentPath == null) {
                continue; // Should not happen if the walk was correct
            }
            String name = entityPayloadFactory.firstNonBlank(ancestor.getName(), ancestor.getId());
            String path = "/".equals(parentPath) ? "/" + name : parentPath + "/" + name;
            pathCache.put(ancestor.getId(), path);
        }

        return pathCache.get(folderId);
    }

    /**
     * Removes Content items from the candidate list whose entity upsert failed.
     * Matches by converting Content.getId() to the qualifiedName format used by the payload factory.
     */
    private void pruneFailedCandidates(String repositoryId, List<Content> candidates, Set<String> failedQualifiedNames) {
        if (failedQualifiedNames == null || failedQualifiedNames.isEmpty()) {
            return;
        }
        candidates.removeIf(content -> {
            if (content == null || content.getId() == null) {
                return false;
            }
            String qualifiedName = entityPayloadFactory.buildObjectQualifiedName(repositoryId, content.getId());
            return failedQualifiedNames.contains(qualifiedName);
        });
    }

    private String resolveRootFolderIdQuietly(String repositoryId) {
        try {
            RepositoryInfo repositoryInfo = resolveRepositoryInfo(repositoryId);
            if (repositoryInfo != null && repositoryInfo.getRootFolderId() != null
                    && !repositoryInfo.getRootFolderId().isBlank()) {
                return repositoryInfo.getRootFolderId();
            }
        } catch (RuntimeException e) {
            logger.debug("Could not resolve root folder ID for repository {}: {}", repositoryId, e.getMessage());
        }
        return null;
    }

    private int flushIfNeeded(String repositoryId, List<Map<String, Object>> entities,
            Map<String, String> guidAccumulator, Set<String> failedQualifiedNames) {
        if (entities.size() < ENTITY_BATCH_SIZE) {
            return 0;
        }
        return flushEntities(repositoryId, entities, guidAccumulator, failedQualifiedNames);
    }

    private int flushEntities(String repositoryId, List<Map<String, Object>> entities,
            Map<String, String> guidAccumulator, Set<String> failedQualifiedNames) {
        if (entities.isEmpty()) {
            return 0;
        }

        // Capture sent qualifiedNames and typeNames before the list is cleared,
        // so we can resolve missing GUIDs via follow-up lookups on Atlas.
        List<SentEntityRef> sentRefs = extractSentEntityRefs(entities);

        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(entities));
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            if (result.hasFailures() && repositoryId != null) {
                for (PurviewEntityPublishResult.FailedItem failedItem : result.getFailedItems()) {
                    logger.warn("Purview bulk partial failure: qualifiedName={}, type={}, error={}",
                            failedItem.getQualifiedName(), failedItem.getTypeName(), failedItem.getErrorMessage());
                    deadLetterStateService.saveDeadLetterState(buildEntityDeadLetterState(
                            repositoryId,
                            failedItem.getQualifiedName(),
                            failedItem.getTypeName(),
                            failedItem.getErrorMessage()));
                    if (failedQualifiedNames != null && failedItem.getQualifiedName() != null) {
                        failedQualifiedNames.add(failedItem.getQualifiedName());
                    }
                }
            }
            if (guidAccumulator != null && result.getEntityGuids() != null) {
                guidAccumulator.putAll(result.getEntityGuids());
            }

            // Atlas on-prem: resolve missing GUIDs via individual lookups
            if (guidAccumulator != null && connectionResolver.isAtlasOnPrem()) {
                resolveUnmappedGuids(sentRefs, guidAccumulator, result.getEntityGuids());
            }

            int publishedCount = result.getPublishedCount();
            entities.clear();
            return publishedCount;
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Atlas 2.3 bulk responses sometimes omit qualifiedName from mutated entries.
     * For any sent entity whose qualifiedName is missing from the GUID map,
     * attempt a single lookup to resolve its GUID.
     */
    private void resolveUnmappedGuids(
            List<SentEntityRef> sentRefs,
            Map<String, String> guidAccumulator,
            Map<String, String> responseGuids) {
        for (SentEntityRef ref : sentRefs) {
            if (ref.qualifiedName == null || ref.typeName == null) {
                continue;
            }
            if (responseGuids != null && responseGuids.containsKey(ref.qualifiedName)) {
                continue; // already resolved from bulk response
            }
            if (guidAccumulator.containsKey(ref.qualifiedName)) {
                continue; // resolved from a previous batch
            }
            try {
                Map<String, Object> entity = entityRegistryClient.getEntityByUniqueAttribute(
                        buildConnectionRequest(),
                        ref.typeName,
                        "qualifiedName",
                        ref.qualifiedName);
                if (entity != null) {
                    String guid = extractGuidFromEntityResponse(entity);
                    if (guid != null) {
                        guidAccumulator.put(ref.qualifiedName, guid);
                        logger.debug("Atlas GUID follow-up resolved: {} → {}", ref.qualifiedName, guid);
                    }
                }
            } catch (PurviewClientException e) {
                logger.debug("Atlas GUID follow-up lookup failed for {}: {}", ref.qualifiedName, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String extractGuidFromEntityResponse(Map<String, Object> response) {
        Object entity = response.get("entity");
        if (entity instanceof Map<?, ?> entityMap) {
            Object guid = entityMap.get("guid");
            if (guid != null) {
                return String.valueOf(guid);
            }
        }
        // Direct guid field (some Atlas response shapes)
        Object directGuid = response.get("guid");
        return directGuid != null ? String.valueOf(directGuid) : null;
    }

    @SuppressWarnings("unchecked")
    private List<SentEntityRef> extractSentEntityRefs(List<Map<String, Object>> entities) {
        List<SentEntityRef> refs = new ArrayList<>();
        for (Map<String, Object> entity : entities) {
            String typeName = entity.get("typeName") instanceof String t ? t : null;
            String qualifiedName = null;
            Object attrs = entity.get("attributes");
            if (attrs instanceof Map<?, ?> attrMap) {
                Object qn = attrMap.get("qualifiedName");
                if (qn instanceof String s) {
                    qualifiedName = s;
                }
            }
            refs.add(new SentEntityRef(typeName, qualifiedName));
        }
        return refs;
    }

    private record SentEntityRef(String typeName, String qualifiedName) {
    }

    private PurviewDeadLetterState buildEntityDeadLetterState(
            String repositoryId,
            String qualifiedName,
            String typeName,
            String errorSummary) {
        String entryKey = qualifiedName != null ? qualifiedName : "unknown";
        String now = Instant.now().toString();
        PurviewDeadLetterState existingState = deadLetterStateService.getDeadLetterState(
                repositoryId, DOCUMENT_ENTITY_STREAM_KIND, entryKey);
        String firstFailedAt = existingState == null || existingState.getFirstFailedAt() == null
                || existingState.getFirstFailedAt().isBlank()
                        ? now
                        : existingState.getFirstFailedAt();
        int failureCount = existingState == null ? 1 : Math.max(0, existingState.getFailureCount()) + 1;
        return new PurviewDeadLetterState(
                repositoryId,
                DOCUMENT_ENTITY_STREAM_KIND,
                entryKey,
                typeName != null ? typeName : "",
                qualifiedName != null ? qualifiedName : "",
                firstFailedAt,
                now,
                failureCount,
                "",
                errorSummary != null ? errorSummary : "");
    }

    private PurviewConnectionRequest buildConnectionRequest() {
        return connectionResolver.buildConnectionRequest();
    }

    private String resolveRootFolderId(String repositoryId) {
        RepositoryInfo repositoryInfo = resolveRepositoryInfo(repositoryId);
        if (repositoryInfo == null || repositoryInfo.getRootFolderId() == null || repositoryInfo.getRootFolderId().isBlank()) {
            throw new IllegalStateException("Root folder ID is not configured for repository " + repositoryId);
        }
        return repositoryInfo.getRootFolderId();
    }

    private RepositoryInfo resolveRepositoryInfo(String repositoryId) {
        return repositoryInfoMap.get(repositoryId);
    }
}
