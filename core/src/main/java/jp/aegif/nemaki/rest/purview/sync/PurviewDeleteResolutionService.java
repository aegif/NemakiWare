package jp.aegif.nemaki.rest.purview.sync;


import jp.aegif.nemaki.rest.purview.state.PurviewJobState;
public interface PurviewDeleteResolutionService {

    PurviewJobState startDeleteResolution(String repositoryId, String requestedBy);
}
