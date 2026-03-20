package jp.aegif.nemaki.rest.purview;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;

@Service
public class PurviewCloudMetadataPublishServiceImpl implements PurviewCloudMetadataPublishService {

    private static final int CHILD_FETCH_PAGE_SIZE = 100;

    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final PurviewDocumentPublishService documentPublishService;

    public PurviewCloudMetadataPublishServiceImpl(
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewDocumentPublishService documentPublishService) {
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.documentPublishService = documentPublishService;
    }

    @Override
    public String buildRepositoryCloudMetadataSnapshot(String repositoryId) {
        return buildSnapshot(loadCloudMetadataDocuments(repositoryId));
    }

    @Override
    public PurviewCloudMetadataSyncResult syncRepositoryCloudMetadataIfChanged(String repositoryId, String previousSnapshot) {
        List<Content> cloudMetadataDocuments = loadCloudMetadataDocuments(repositoryId);
        String currentSnapshot = buildSnapshot(cloudMetadataDocuments);
        String normalizedPreviousSnapshot = normalizeSnapshot(previousSnapshot);
        if (Objects.equals(currentSnapshot, normalizedPreviousSnapshot)) {
            return new PurviewCloudMetadataSyncResult(currentSnapshot, false, 0, 0);
        }

        Map<String, String> previousByObjectId = parseSnapshot(normalizedPreviousSnapshot);
        Map<String, String> currentByObjectId = new LinkedHashMap<>();
        List<Content> changedDocuments = new ArrayList<>();
        for (Content content : cloudMetadataDocuments) {
            String snapshotEntry = buildSnapshotEntry(content);
            currentByObjectId.put(content.getId(), snapshotEntry);
            if (!Objects.equals(snapshotEntry, previousByObjectId.remove(content.getId()))) {
                changedDocuments.add(content);
            }
        }

        List<Content> clearedDocuments = loadClearedDocuments(repositoryId, previousByObjectId.keySet());
        int publishedCount = changedDocuments.isEmpty() ? 0 : documentPublishService.upsertContents(repositoryId, changedDocuments);
        int reconciledCount = clearedDocuments.isEmpty() ? 0 : documentPublishService.upsertContents(repositoryId, clearedDocuments);
        return new PurviewCloudMetadataSyncResult(currentSnapshot, true, publishedCount, reconciledCount);
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
        return String.join("|",
                content.getId(),
                nullToEmpty(PurviewCloudMetadataSupport.getCloudProvider(content)),
                nullToEmpty(PurviewCloudMetadataSupport.getExternalFileId(content)),
                nullToEmpty(PurviewCloudMetadataSupport.getCloudFileUrl(content)),
                nullToEmpty(PurviewCloudMetadataSupport.getCloudLastSyncedAt(content)));
    }

    private Map<String, String> parseSnapshot(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return Map.of();
        }

        LinkedHashMap<String, String> byObjectId = new LinkedHashMap<>();
        for (String line : snapshot.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 1 && !parts[0].isBlank()) {
                byObjectId.put(parts[0], line);
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
