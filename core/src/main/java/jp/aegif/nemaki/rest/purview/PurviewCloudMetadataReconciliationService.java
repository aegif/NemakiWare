package jp.aegif.nemaki.rest.purview;

public interface PurviewCloudMetadataReconciliationService {

    PurviewJobState startCloudMetadataReconciliation(String repositoryId, String requestedBy);
}
