package jp.aegif.nemaki.rest.purview.lineage;

import java.util.List;

import jp.aegif.nemaki.model.Content;

public interface PurviewImportExportLineageService {

    int upsertFilesystemImportLineage(
            String repositoryId,
            String folderId,
            String sourcePath,
            String requestedBy,
            long objectCount);

    int upsertUploadedImportLineage(
            String repositoryId,
            String folderId,
            String importMode,
            String requestedBy,
            long objectCount);

    int upsertFilesystemExportLineage(
            String repositoryId,
            String folderId,
            String targetPath,
            String requestedBy,
            long objectCount);

    int upsertZipFolderExportLineage(
            String repositoryId,
            String folderId,
            String folderName,
            String requestedBy,
            long objectCount);

    int upsertSelectedObjectsExportLineage(
            String repositoryId,
            List<? extends Content> contents,
            String requestedBy,
            long objectCount);
}
