package jp.aegif.nemaki.rest.purview;

public interface PurviewDeleteResolutionService {

    PurviewJobState startDeleteResolution(String repositoryId, String requestedBy);
}
