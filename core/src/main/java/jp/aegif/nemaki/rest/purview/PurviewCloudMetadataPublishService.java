package jp.aegif.nemaki.rest.purview;

public interface PurviewCloudMetadataPublishService {

    String buildRepositoryCloudMetadataSnapshot(String repositoryId);

    int publishRepositoryCloudSyncLineage(String repositoryId);

    PurviewCloudMetadataSyncResult syncRepositoryCloudMetadataIfChanged(String repositoryId, String previousSnapshot);
}
