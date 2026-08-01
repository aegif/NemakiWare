package jp.aegif.nemaki.rest.purview.publish;

import jp.aegif.nemaki.rest.purview.PurviewCloudMetadataSupport;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.sync.PurviewCloudMetadataSyncResult;
import jp.aegif.nemaki.rest.purview.lineage.PurviewCloudSyncLineageService;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterState;
import jp.aegif.nemaki.rest.purview.state.PurviewDeadLetterStateService;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
public class PurviewCloudMetadataPublishServiceImpl implements PurviewCloudMetadataPublishService {

    private static final int CHILD_FETCH_PAGE_SIZE = 100;
    private static final String CLOUD_LINEAGE_STREAM_KIND = "cloud-sync-lineage";
    private static final String CLOUD_LINEAGE_TYPE_NAME = "nemaki_cloud_sync_process";

    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final PurviewDocumentPublishService documentPublishService;
    private final PurviewCloudSyncLineageService cloudSyncLineageService;
    private final PurviewDeadLetterStateService deadLetterStateService;
    private final PurviewEntityPayloadFactory entityPayloadFactory;

    public PurviewCloudMetadataPublishServiceImpl(
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewDocumentPublishService documentPublishService,
            PurviewCloudSyncLineageService cloudSyncLineageService,
            PurviewDeadLetterStateService deadLetterStateService,
            PurviewEntityPayloadFactory entityPayloadFactory) {
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.documentPublishService = documentPublishService;
        this.cloudSyncLineageService = cloudSyncLineageService;
        this.deadLetterStateService = deadLetterStateService;
        this.entityPayloadFactory = entityPayloadFactory;
    }

    @Override
    public String buildRepositoryCloudMetadataSnapshot(String repositoryId) {
        return buildSnapshot(loadCloudMetadataDocuments(repositoryId));
    }

    @Override
    public int publishRepositoryCloudSyncLineage(String repositoryId) {
        List<Content> cloudMetadataDocuments = loadCloudMetadataDocuments(repositoryId);
        if (cloudMetadataDocuments.isEmpty()) {
            return 0;
        }
        try {
            int publishedCount = cloudSyncLineageService.upsertCloudSyncLineage(repositoryId, cloudMetadataDocuments);
            deadLetterStateService.deleteDeadLetterState(repositoryId, CLOUD_LINEAGE_STREAM_KIND, repositoryId);
            return publishedCount;
        } catch (RuntimeException e) {
            deadLetterStateService.saveDeadLetterState(buildRepositoryDeadLetterState(
                    repositoryId,
                    "",
                    buildErrorSummary(e)));
            return 0;
        }
    }

    @Override
    public int retryRepositoryCloudSyncLineage(String repositoryId, String previousSnapshot) {
        List<Content> cloudMetadataDocuments = loadCloudMetadataDocuments(repositoryId);
        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);
        Map<String, String> previousByObjectId = parseSnapshot(normalizedPreviousSnapshot);
        List<Content> changedDocuments = new ArrayList<>();
        for (Content content : cloudMetadataDocuments) {
            String snapshotEntry = buildSnapshotEntry(content);
            String previousSnapshotEntry = previousByObjectId.remove(content.getId());
            if (!Objects.equals(snapshotEntry, previousSnapshotEntry)) {
                changedDocuments.add(content);
            }
        }
        // After the loop, previousByObjectId contains only removed documents
        Map<String, String> obsoleteSnapshotEntries = previousByObjectId;

        // Collect active stable keys to protect shared external assets
        Set<String> activeStableKeys = new LinkedHashSet<>();
        for (Content content : cloudMetadataDocuments) {
            String sk = entityPayloadFactory.resolveCloudExternalStableKey(content);
            if (sk != null && !sk.isBlank()) {
                activeStableKeys.add(sk);
            }
        }

        int lineagePublishedCount = changedDocuments.isEmpty() ? 0 : cloudSyncLineageService.upsertCloudSyncLineage(repositoryId, changedDocuments);
        int lineageReconciledCount = obsoleteSnapshotEntries.isEmpty()
                ? 0
                : cloudSyncLineageService.reconcileRemovedCloudSyncLineage(repositoryId, obsoleteSnapshotEntries, activeStableKeys);
        return lineagePublishedCount + lineageReconciledCount;
    }

    @Override
    public PurviewCloudMetadataSyncResult syncRepositoryCloudMetadataIfChanged(String repositoryId, String previousSnapshot) {
        List<Content> cloudMetadataDocuments = loadCloudMetadataDocuments(repositoryId);
        String currentSnapshot = buildSnapshot(cloudMetadataDocuments);
        // Format-normalised as well as null-normalised: a cursor stored before the URL left the
        // format must compare equal to a fresh snapshot of unchanged documents, or the first sync
        // after upgrade republishes everything for no observable catalog difference.
        String normalizedPreviousSnapshot =
                CloudMetadataSnapshotFormat.normalize(normalizeSnapshot(previousSnapshot));
        if (Objects.equals(currentSnapshot, normalizedPreviousSnapshot)) {
            return new PurviewCloudMetadataSyncResult(currentSnapshot, false, 0, 0);
        }

        Map<String, String> previousByObjectId = parseSnapshot(normalizedPreviousSnapshot);
        Map<String, String> currentByObjectId = new LinkedHashMap<>();
        List<Content> changedDocuments = new ArrayList<>();
        Set<String> obsoleteStableKeys = new LinkedHashSet<>();
        for (Content content : cloudMetadataDocuments) {
            String snapshotEntry = buildSnapshotEntry(content);
            String previousSnapshotEntry = previousByObjectId.remove(content.getId());
            currentByObjectId.put(content.getId(), snapshotEntry);
            if (!Objects.equals(snapshotEntry, previousSnapshotEntry)) {
                changedDocuments.add(content);
                // Detect stableKey changes: if the document's cloud link target changed,
                // the old external asset becomes orphaned in Atlas/Purview.
                if (previousSnapshotEntry != null) {
                    String oldStableKey = parseStableKeyFromEntry(previousSnapshotEntry);
                    String newStableKey = parseStableKeyFromEntry(snapshotEntry);
                    if (oldStableKey != null && !oldStableKey.equals(newStableKey)) {
                        obsoleteStableKeys.add(oldStableKey);
                    }
                }
            }
        }

        // After the loop, previousByObjectId contains only documents that
        // disappeared from the current set (cloud link removed or document deleted).
        Map<String, String> obsoleteSnapshotEntries = previousByObjectId;

        // Collect the set of stable keys still in active use, so reconciliation
        // does not delete shared external assets that other documents still reference.
        Set<String> activeStableKeys = new LinkedHashSet<>();
        for (Content content : cloudMetadataDocuments) {
            String sk = entityPayloadFactory.resolveCloudExternalStableKey(content);
            if (sk != null && !sk.isBlank()) {
                activeStableKeys.add(sk);
            }
        }

        List<Content> clearedDocuments = loadClearedDocuments(repositoryId, previousByObjectId.keySet());
        int publishedCount = changedDocuments.isEmpty() ? 0 : documentPublishService.upsertContents(repositoryId, changedDocuments);
        int reconciledCount = clearedDocuments.isEmpty() ? 0 : documentPublishService.upsertContents(repositoryId, clearedDocuments);
        int lineagePublishedCount = 0;
        int lineageReconciledCount = 0;
        try {
            lineagePublishedCount = changedDocuments.isEmpty() ? 0 : cloudSyncLineageService.upsertCloudSyncLineage(repositoryId, changedDocuments);
            lineageReconciledCount = obsoleteSnapshotEntries.isEmpty()
                    ? 0
                    : cloudSyncLineageService.reconcileRemovedCloudSyncLineage(repositoryId, obsoleteSnapshotEntries, activeStableKeys);
            // Clean up orphaned external assets from stableKey changes
            for (String obsoleteKey : obsoleteStableKeys) {
                if (!activeStableKeys.contains(obsoleteKey)) {
                    cloudSyncLineageService.deleteObsoleteExternalAsset(repositoryId, obsoleteKey);
                }
            }
            deadLetterStateService.deleteDeadLetterState(repositoryId, CLOUD_LINEAGE_STREAM_KIND, repositoryId);
        } catch (RuntimeException e) {
            deadLetterStateService.saveDeadLetterState(buildRepositoryDeadLetterState(
                    repositoryId,
                    normalizedPreviousSnapshot,
                    buildErrorSummary(e)));
        }
        return new PurviewCloudMetadataSyncResult(
                currentSnapshot,
                true,
                publishedCount + lineagePublishedCount,
                reconciledCount + lineageReconciledCount);
    }

    private List<Content> loadCloudMetadataDocuments(String repositoryId) {
        String rootFolderId = resolveRootFolderId(repositoryId);
        Content rootFolder = contentDaoService.getContent(repositoryId, rootFolderId);
        if (rootFolder == null || !rootFolder.isFolder()) {
            throw new IllegalStateException("Root folder content is not available for repository " + repositoryId);
        }

        List<Content> documents = new ArrayList<>();
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
                    if (child == null || child.getId() == null || child.getId().isBlank()) {
                        continue;
                    }
                    if (child.isFolder()) {
                        folderQueue.addLast(child.getId());
                        continue;
                    }
                    if (child.isDocument() && PurviewCloudMetadataSupport.hasCloudMetadata(child)) {
                        documents.add(child);
                    }
                }

                if (children.size() < CHILD_FETCH_PAGE_SIZE) {
                    break;
                }
            }
        }

        return documents.stream()
                .sorted(Comparator.comparing(Content::getId))
                .toList();
    }

    private List<Content> loadClearedDocuments(String repositoryId, java.util.Set<String> removedObjectIds) {
        if (removedObjectIds == null || removedObjectIds.isEmpty()) {
            return List.of();
        }

        List<Content> clearedDocuments = new ArrayList<>();
        for (String objectId : new LinkedHashSet<>(removedObjectIds)) {
            Content liveContent = contentDaoService.getContentFresh(repositoryId, objectId);
            if (liveContent != null && liveContent.isDocument()) {
                clearedDocuments.add(liveContent);
            }
        }
        return clearedDocuments;
    }

    private String buildSnapshot(List<Content> documents) {
        return documents.stream()
                .map(this::buildSnapshotEntry)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String buildSnapshotEntry(Content content) {
        // No cloudFileUrl: the snapshot is persisted as the cursor and served by the admin API,
        // and a drive URL's query string is where sharing tokens live. Identity is provider+fileId
        // and change detection no longer needs the URL — the catalog does not carry it (A-1g).
        return CloudMetadataSnapshotFormat.entry(
                content.getId(),
                PurviewCloudMetadataSupport.getCloudProvider(content),
                PurviewCloudMetadataSupport.getExternalFileId(content),
                PurviewCloudMetadataSupport.getCloudLastSyncedAt(content));
    }

    private Map<String, String> parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return new LinkedHashMap<>();
        }

        LinkedHashMap<String, String> byObjectId = new LinkedHashMap<>();
        for (String line : snapshot.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 1 && !parts[0].isBlank()) {
                // Normalised so a cursor stored before the URL left the format compares equal to
                // a fresh snapshot of unchanged documents — no spurious republish wave, and a
                // URL-only change no longer counts as a change (the catalog cannot see it).
                byObjectId.put(parts[0], CloudMetadataSnapshotFormat.normalizeLineForCompare(line));
            }
        }
        return byObjectId;
    }

    private String resolveRootFolderId(String repositoryId) {
        RepositoryInfo repositoryInfo = repositoryInfoMap.get(repositoryId);
        if (repositoryInfo == null || repositoryInfo.getRootFolderId() == null || repositoryInfo.getRootFolderId().isBlank()) {
            throw new IllegalStateException("Root folder ID is not configured for repository " + repositoryId);
        }
        return repositoryInfo.getRootFolderId();
    }

    private String normalizeSnapshot(String snapshot) {
        return snapshot == null ? "" : snapshot;
    }

    private String parseStableKeyFromEntry(String snapshotEntry) {
        if (snapshotEntry == null || snapshotEntry.isBlank()) {
            return null;
        }
        String[] parts = snapshotEntry.split("\\|", -1);
        if (parts.length < 3 || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return parts[1] + ":" + parts[2];
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private PurviewDeadLetterState buildRepositoryDeadLetterState(
            String repositoryId,
            String checkpoint,
            String errorSummary) {
        String now = java.time.Instant.now().toString();
        PurviewDeadLetterState existingState = deadLetterStateService.getDeadLetterState(repositoryId, CLOUD_LINEAGE_STREAM_KIND, repositoryId);
        String firstFailedAt = existingState == null || existingState.getFirstFailedAt() == null
                || existingState.getFirstFailedAt().isBlank()
                        ? now
                        : existingState.getFirstFailedAt();
        int failureCount = existingState == null ? 1 : Math.max(0, existingState.getFailureCount()) + 1;
        return new PurviewDeadLetterState(
                repositoryId,
                CLOUD_LINEAGE_STREAM_KIND,
                repositoryId,
                CLOUD_LINEAGE_TYPE_NAME,
                "nemaki://" + repositoryId + "/purview/" + CLOUD_LINEAGE_STREAM_KIND,
                firstFailedAt,
                now,
                failureCount,
                checkpoint == null ? "" : checkpoint,
                errorSummary);
    }

    private String buildErrorSummary(RuntimeException e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getMessage();
    }
}
