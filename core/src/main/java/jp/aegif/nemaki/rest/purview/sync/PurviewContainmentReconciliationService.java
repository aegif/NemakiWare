package jp.aegif.nemaki.rest.purview.sync;


import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
public interface PurviewContainmentReconciliationService {

    PurviewJobState startContainmentReconciliation(String repositoryId, String requestedBy);
}
