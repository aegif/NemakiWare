package jp.aegif.nemaki.rest.purview.sync;


import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
public interface PurviewDeadLetterRetryService {

    PurviewJobState startRetryFailed(String repositoryId, String requestedBy);
}
