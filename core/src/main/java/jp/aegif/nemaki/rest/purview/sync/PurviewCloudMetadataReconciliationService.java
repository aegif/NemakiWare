package jp.aegif.nemaki.rest.purview.sync;


import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
public interface PurviewCloudMetadataReconciliationService {

    PurviewJobState startCloudMetadataReconciliation(String repositoryId, String requestedBy);
}
