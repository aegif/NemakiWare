package jp.aegif.nemaki.rest.purview;

public interface PurviewArchivePublishService {

    int publishRepositoryArchives(String repositoryId);

    String buildRepositoryArchiveSnapshot(String repositoryId);

    PurviewArchiveSyncResult syncRepositoryArchivesIfChanged(String repositoryId, String previousSnapshot);
}
