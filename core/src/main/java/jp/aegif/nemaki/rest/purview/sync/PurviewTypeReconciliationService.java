package jp.aegif.nemaki.rest.purview.sync;


import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
public interface PurviewTypeReconciliationService {

    PurviewJobState startTypeReconciliation(String repositoryId, String requestedBy);
}
