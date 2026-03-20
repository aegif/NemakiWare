package jp.aegif.nemaki.rest.purview;

public interface PurviewArchivePublishService {

    int publishRepositoryArchives(String repositoryId);

    int retryRepositoryArchiveLineage(String repositoryId, String previousSnapshot);

    String buildRepositoryArchiveSnapshot(String repositoryId);

    PurviewArchiveSyncResult syncRepositoryArchivesIfChanged(String repositoryId, String previousSnapshot);
}
