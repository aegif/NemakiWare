package jp.aegif.nemaki.rest.purview.sync;


import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
public interface PurviewIncrementalSyncService {

    PurviewJobState startIncrementalSync(String repositoryId, String requestedBy);
}
