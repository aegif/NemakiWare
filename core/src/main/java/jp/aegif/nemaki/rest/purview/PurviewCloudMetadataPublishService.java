package jp.aegif.nemaki.rest.purview;

public interface PurviewCloudMetadataPublishService {

    String buildRepositoryCloudMetadataSnapshot(String repositoryId);

    int publishRepositoryCloudSyncLineage(String repositoryId);

    int retryRepositoryCloudSyncLineage(String repositoryId, String previousSnapshot);

    PurviewCloudMetadataSyncResult syncRepositoryCloudMetadataIfChanged(String repositoryId, String previousSnapshot);
}
