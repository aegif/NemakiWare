package jp.aegif.nemaki.rest.purview.sync;


import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
public interface PurviewFullSyncService {

    PurviewJobState startFullSync(String repositoryId, String requestedBy);
}
