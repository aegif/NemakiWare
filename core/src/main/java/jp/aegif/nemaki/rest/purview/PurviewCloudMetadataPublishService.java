package jp.aegif.nemaki.rest.purview;

public interface PurviewCloudMetadataPublishService {

    String buildRepositoryCloudMetadataSnapshot(String repositoryId);

    PurviewCloudMetadataSyncResult syncRepositoryCloudMetadataIfChanged(String repositoryId, String previousSnapshot);
}
