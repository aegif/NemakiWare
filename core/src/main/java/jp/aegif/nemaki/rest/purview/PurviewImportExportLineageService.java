package jp.aegif.nemaki.rest.purview;

public interface PurviewImportExportLineageService {

    int upsertFilesystemImportLineage(
            String repositoryId,
            String folderId,
            String sourcePath,
            String requestedBy,
            long objectCount);

    int upsertFilesystemExportLineage(
            String repositoryId,
            String folderId,
            String targetPath,
            String requestedBy,
            long objectCount);
}
