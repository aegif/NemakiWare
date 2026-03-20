package jp.aegif.nemaki.rest.purview.sync;


import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
public interface PurviewArchiveReconciliationService {

    PurviewJobState startArchiveReconciliation(String repositoryId, String requestedBy);
}
