package jp.aegif.nemaki.rest.purview.relationship;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.PurviewConfig;
import jp.aegif.nemaki.rest.purview.client.PurviewConnectionRequest;
import jp.aegif.nemaki.rest.purview.sync.PurviewContainmentSyncResult;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.state.PurviewStateStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewContainmentRelationshipServiceImpl implements PurviewContainmentRelationshipService {

    private static final int CHILD_FETCH_PAGE_SIZE = 100;
    private static final String STATE_KEY_PREFIX = "purview.containment.relationship.guid";

    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final PurviewConfig purviewConfig;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final PurviewStateStore stateStore;

    public PurviewContainmentRelationshipServiceImpl(
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewConfig purviewConfig,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            PurviewStateStore stateStore) {
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.purviewConfig = purviewConfig;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.stateStore = stateStore;
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
            processedCount += createRelationship(repositoryId, relationship);
        }

        return processedCount;
    }

    @Override
    public String buildRepositoryContainmentSnapshot(String repositoryId) {
        return loadContainmentEdges(repositoryId).stream()
                .map(ContainmentEdge::edgeKey)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Override
    public PurviewContainmentSyncResult syncRepositoryContainmentRelationshipsIfChanged(String repositoryId, String previousSnapshot) {
        List<ContainmentEdge> currentEdges = loadContainmentEdges(repositoryId);
        String currentSnapshot = currentEdges.stream()
                .map(ContainmentEdge::edgeKey)
                .collect(java.util.stream.Collectors.joining("\n"));
        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);
        if (Objects.equals(currentSnapshot, normalizedPreviousSnapshot)) {
            return new PurviewContainmentSyncResult(currentSnapshot, false, 0, 0);
        }

        Set<String> previousEdgeKeys = parseSnapshot(normalizedPreviousSnapshot);
        Set<String> currentEdgeKeys = currentEdges.stream()
                .map(ContainmentEdge::edgeKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        int publishedCount = 0;
        for (ContainmentEdge edge : currentEdges) {
            if (!previousEdgeKeys.contains(edge.edgeKey())) {
                publishedCount += createRelationship(repositoryId, edge.relationshipPayload());
            }
        }

        int reconciledCount = 0;
        for (String previousEdgeKey : previousEdgeKeys) {
            if (currentEdgeKeys.contains(previousEdgeKey)) {
                continue;
            }
            reconciledCount += deleteRelationship(repositoryId, previousEdgeKey);
        }

        return new PurviewContainmentSyncResult(currentSnapshot, true, publishedCount, reconciledCount);
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

    private List<ContainmentEdge> loadContainmentEdges(String repositoryId) {
        RepositoryInfo repositoryInfo = repositoryInfoMap.get(repositoryId);
        if (repositoryInfo == null || repositoryInfo.getRootFolderId() == null || repositoryInfo.getRootFolderId().isBlank()) {
            throw new IllegalStateException("Root folder ID is not configured for repository " + repositoryId);
        }

        String rootFolderId = repositoryInfo.getRootFolderId();
        Content rootFolder = contentDaoService.getContent(repositoryId, rootFolderId);
        if (rootFolder == null || !rootFolder.isFolder()) {
            throw new IllegalStateException("Root folder content is not available for repository " + repositoryId);
        }

        List<ContainmentEdge> edges = new ArrayList<>();
        Map<String, Object> rootRelationship = buildRelationshipPayload(repositoryId, repositoryInfo, rootFolder);
        if (rootRelationship != null) {
            edges.add(new ContainmentEdge(buildRelationshipKey(rootRelationship), rootRelationship));
        }

        Deque<String> folderQueue = new ArrayDeque<>();
        folderQueue.add(rootFolderId);
        while (!folderQueue.isEmpty()) {
            String folderId = folderQueue.removeFirst();
            long totalChildren = Math.max(0L, contentDaoService.getChildrenCount(repositoryId, folderId));
            for (int skip = 0; skip < totalChildren; skip += CHILD_FETCH_PAGE_SIZE) {
                List<Content> children = contentDaoService.getChildrenPaged(repositoryId, folderId, skip, CHILD_FETCH_PAGE_SIZE);
                if (children == null || children.isEmpty()) {
                    break;
                }

                for (Content child : children) {
                    Map<String, Object> relationship = buildRelationshipPayload(repositoryId, repositoryInfo, child);
                    if (relationship != null) {
                        edges.add(new ContainmentEdge(buildRelationshipKey(relationship), relationship));
                    }
                    if (child != null && child.isFolder() && child.getId() != null && !child.getId().isBlank()) {
                        folderQueue.addLast(child.getId());
                    }
                }

                if (children.size() < CHILD_FETCH_PAGE_SIZE) {
                    break;
                }
            }
        }

        return edges.stream()
                .sorted(Comparator.comparing(ContainmentEdge::edgeKey))
                .toList();
    }

    private int createRelationship(String repositoryId, Map<String, Object> relationship) {
        String edgeKey = buildRelationshipKey(relationship);
        try {
            PurviewEntityPublishResult result = entityRegistryClient.createRelationship(
                    buildConnectionRequest(),
                    relationship);
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            if (result.getResourceGuid() != null && !result.getResourceGuid().isBlank()) {
                stateStore.putAll(Map.of(buildRelationshipGuidStateKey(repositoryId, edgeKey), result.getResourceGuid()));
            }
            return result.getPublishedCount();
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private int deleteRelationship(String repositoryId, String edgeKey) {
        String guid = stateStore.getString(buildRelationshipGuidStateKey(repositoryId, edgeKey));
        if (guid == null || guid.isBlank()) {
            throw new IllegalStateException("Containment relationship GUID is not tracked for edge " + edgeKey);
        }
        try {
            PurviewEntityPublishResult result = entityRegistryClient.deleteRelationshipByGuid(buildConnectionRequest(), guid);
            if (!result.isSuccess()) {
                throw new IllegalStateException(result.getMessage());
            }
            stateStore.removeAll(List.of(buildRelationshipGuidStateKey(repositoryId, edgeKey)));
            return result.getPublishedCount();
        } catch (PurviewClientException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String buildRelationshipKey(Map<String, Object> relationship) {
        Map<String, Object> end1 = (Map<String, Object>) relationship.get("end1");
        Map<String, Object> end2 = (Map<String, Object>) relationship.get("end2");
        String end1QualifiedName = extractQualifiedName(end1);
        String end2QualifiedName = extractQualifiedName(end2);
        return String.join("|",
                relationship.get("typeName").toString(),
                end1QualifiedName,
                end2QualifiedName);
    }

    @SuppressWarnings("unchecked")
    private String extractQualifiedName(Map<String, Object> end) {
        Map<String, Object> uniqueAttributes = (Map<String, Object>) end.get("uniqueAttributes");
        Object qualifiedName = uniqueAttributes.get("qualifiedName");
        return qualifiedName == null ? "" : qualifiedName.toString();
    }

    private Set<String> parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> edgeKeys = new LinkedHashSet<>();
        for (String line : snapshot.split("\\R")) {
            if (line != null && !line.isBlank()) {
                edgeKeys.add(line);
            }
        }
        return edgeKeys;
    }

    private String normalizeSnapshot(String snapshot) {
        return snapshot == null ? "" : snapshot;
    }

    private String buildRelationshipGuidStateKey(String repositoryId, String edgeKey) {
        return STATE_KEY_PREFIX + "." + repositoryId + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(edgeKey.getBytes(StandardCharsets.UTF_8));
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

    private record ContainmentEdge(String edgeKey, Map<String, Object> relationshipPayload) {
    }
}
