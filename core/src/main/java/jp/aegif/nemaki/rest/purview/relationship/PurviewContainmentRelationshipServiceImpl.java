package jp.aegif.nemaki.rest.purview.relationship;

import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PurviewContainmentRelationshipServiceImpl.class);


    private static final int CHILD_FETCH_PAGE_SIZE = 100;
    private static final String STATE_KEY_PREFIX = "purview.containment.relationship.guid";

    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final MetadataCatalogConnectionResolver connectionResolver;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final PurviewStateStore stateStore;

    public PurviewContainmentRelationshipServiceImpl(
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            MetadataCatalogConnectionResolver connectionResolver,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            PurviewStateStore stateStore) {
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.connectionResolver = connectionResolver;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.stateStore = stateStore;
    }

    @Override
    public int upsertContainmentRelationships(String repositoryId, List<Content> contents, Map<String, String> guidByQualifiedName) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }

        RepositoryInfo repositoryInfo = repositoryInfoMap.get(repositoryId);
        if (repositoryInfo == null || repositoryInfo.getRootFolderId() == null || repositoryInfo.getRootFolderId().isBlank()) {
            throw new IllegalStateException("Root folder ID is not configured for repository " + repositoryId);
        }

        int processedCount = 0;
        for (Content content : contents) {
            Map<String, Object> relationship = buildRelationshipPayload(repositoryId, repositoryInfo, content, guidByQualifiedName);
            if (relationship == null) {
                continue;
            }
            processedCount += createRelationship(repositoryId, relationship);
        }

        return processedCount;
    }

    @Override
    public String buildRepositoryContainmentSnapshot(String repositoryId) {
        String snapshot = loadContainmentEdges(repositoryId).stream()
                .map(ContainmentEdge::edgeKey)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (lastWalkIncomplete.get()) {
            // A snapshot built from a short walk becomes the baseline every later diff deletes
            // against. Guarded here, not only in the diff, so a new caller cannot seed it.
            throw new IllegalStateException("the containment walk could not read every child"
                    + " row, so a snapshot built from it would be a short baseline");
        }
        return snapshot;
    }

    @Override
    public PurviewContainmentSyncResult syncRepositoryContainmentRelationshipsIfChanged(String repositoryId, String previousSnapshot) {
        List<ContainmentEdge> currentEdges = loadContainmentEdges(repositoryId);
        String currentSnapshot = currentEdges.stream()
                .map(ContainmentEdge::edgeKey)
                .collect(java.util.stream.Collectors.joining("\n"));
        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);
        if (lastWalkIncomplete.get()) {
            // An edge that is merely INVISIBLE must not be treated as deleted. Adding what WAS
            // seen is safe (those edges exist); deleting by absence from an incomplete walk
            // removed real containment from the external catalog. The snapshot must not become
            // this walk — that would make the invisible edges "new" next time and, worse, make
            // this walk the baseline a later complete walk diffs against.
            //
            // But the PREVIOUS snapshot alone is a hole too: an edge CREATED during this round
            // is published below, yet absent from the baseline — if it vanishes before a
            // complete walk, that walk sees it in neither side and the external catalog keeps
            // it for ever. So the snapshot becomes previous ∪ published. Reaching the return
            // means the loop finished without throwing, so every added key was either
            // created just now or has a GUID this store recorded at some earlier create.
            // A recorded GUID is what WE did, not proof of what the catalog still holds —
            // a relationship deleted externally out-of-band is not DETECTED here (that would
            // mean reading every relationship back on every cycle). It is REPAIRABLE:
            // forgetRecordedRelationshipGuids() drops the records so the next sync re-creates
            // every edge.
            log.warn("Containment walk for " + repositoryId + " could not read every child row;"
                    + " publishing the edges that were seen, deleting nothing, and widening the"
                    + " previous snapshot with them until a complete walk succeeds");
            Set<String> previousKeys = parseSnapshot(normalizedPreviousSnapshot);
            LinkedHashSet<String> mergedKeys = new LinkedHashSet<>(previousKeys);
            int published = 0;
            for (ContainmentEdge edge : currentEdges) {
                if (!previousKeys.contains(edge.edgeKey())) {
                    published += createRelationship(repositoryId, edge.relationshipPayload());
                    mergedKeys.add(edge.edgeKey());
                }
            }
            return new PurviewContainmentSyncResult(String.join("\n", mergedKeys),
                    published > 0, published, 0);
        }
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

    private Map<String, Object> buildRelationshipPayload(String repositoryId, RepositoryInfo repositoryInfo, Content content,
            Map<String, String> guidByQualifiedName) {
        if (content == null || content.getId() == null || content.getId().isBlank()) {
            return null;
        }
        if (content.isFolder() && content.getId().equals(repositoryInfo.getRootFolderId())) {
            return entityPayloadFactory.buildRepositoryFolderRelationship(repositoryId, content, guidByQualifiedName);
        }
        if (content.getParentId() == null || content.getParentId().isBlank()) {
            return null;
        }
        if (content.isFolder()) {
            return entityPayloadFactory.buildFolderFolderRelationship(repositoryId, content, guidByQualifiedName);
        }
        if (content.isDocument()) {
            return entityPayloadFactory.buildFolderDocumentRelationship(repositoryId, content, guidByQualifiedName);
        }
        return null;
    }

    /** Set by {@link #loadContainmentEdges}: whether the last walk saw every child row. */
    private final ThreadLocal<Boolean> lastWalkIncomplete = ThreadLocal.withInitial(() -> false);

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
        Map<String, Object> rootRelationship = buildRelationshipPayload(repositoryId, repositoryInfo, rootFolder, Map.of());
        if (rootRelationship != null) {
            edges.add(new ContainmentEdge(buildRelationshipKey(rootRelationship), rootRelationship));
        }

        Deque<String> folderQueue = new ArrayDeque<>();
        folderQueue.add(rootFolderId);
        boolean incomplete = false;
        while (!folderQueue.isEmpty()) {
            String folderId = folderQueue.removeFirst();
            long totalChildren = Math.max(0L, contentDaoService.getChildrenCount(repositoryId, folderId));
            for (int skip = 0; skip < totalChildren; skip += CHILD_FETCH_PAGE_SIZE) {
                List<Content> children = contentDaoService.getChildrenPaged(repositoryId, folderId, skip, CHILD_FETCH_PAGE_SIZE);
                // A row the store could not decode is ABSENT from this page without any
                // exception. Two things used to go wrong at once: the count was never read,
                // so the missing edge was later treated as a DELETED relationship and removed
                // from the external catalog; and the short page tripped the last-page break
                // below, abandoning every later page of the folder — multiplying one unreadable
                // row into a missing subtree.
                if (contentDaoService.lastUnreadableChildCount() > 0) {
                    incomplete = true;
                }
                if (children == null || children.isEmpty()) {
                    // Only a TRULY empty page ends the folder: a page whose every row failed
                    // to decode is empty too, and later offsets may still hold readable rows.
                    if (contentDaoService.lastUnreadableChildCount() > 0) {
                        continue;
                    }
                    break;
                }

                for (Content child : children) {
                    Map<String, Object> relationship = buildRelationshipPayload(repositoryId, repositoryInfo, child, Map.of());
                    if (relationship != null) {
                        edges.add(new ContainmentEdge(buildRelationshipKey(relationship), relationship));
                    }
                    if (child != null && child.isFolder() && child.getId() != null && !child.getId().isBlank()) {
                        folderQueue.addLast(child.getId());
                    }
                }
                // No early break on a short page: the loop is already bounded by
                // totalChildren, and "shorter than the page size" is exactly what a
                // decode-shortened page looks like.
            }
        }

        lastWalkIncomplete.set(incomplete);
        return edges.stream()
                .sorted(Comparator.comparing(ContainmentEdge::edgeKey))
                .toList();
    }

    private int createRelationship(String repositoryId, Map<String, Object> relationship) {
        String edgeKey = buildRelationshipKey(relationship);
        // Already created: the GUID is recorded on success, and an INCOMPLETE walk keeps the
        // previous snapshot — so on every cycle until the broken row is repaired, the same
        // seen-edges diff arrives here again. Without this check that meant re-sending the
        // same create to the external catalog each cycle, and whether that is a harmless
        // upsert or a duplicate is the far end's choice, not ours to gamble on.
        String existingGuid = stateStore.getString(buildRelationshipGuidStateKey(repositoryId, edgeKey));
        if (existingGuid != null && !existingGuid.isBlank()) {
            return 0;
        }
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

    @Override
    public int forgetRecordedRelationshipGuids(String repositoryId) {
        String prefix = STATE_KEY_PREFIX + "." + repositoryId + ".";
        java.util.Set<String> keys = stateStore.getAllByPrefix(prefix).keySet();
        if (keys.isEmpty()) {
            return 0;
        }
        stateStore.removeAll(new java.util.ArrayList<>(keys));
        log.warn("Forgot " + keys.size() + " recorded containment relationship GUID(s) for "
                + repositoryId + "; the next sync will re-create every edge in the external"
                + " catalog");
        return keys.size();
    }

    private String buildRelationshipGuidStateKey(String repositoryId, String edgeKey) {
        return STATE_KEY_PREFIX + "." + repositoryId + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(edgeKey.getBytes(StandardCharsets.UTF_8));
    }

    private PurviewConnectionRequest buildConnectionRequest() {
        return connectionResolver.buildConnectionRequest();
    }

    private record ContainmentEdge(String edgeKey, Map<String, Object> relationshipPayload) {
    }
}
