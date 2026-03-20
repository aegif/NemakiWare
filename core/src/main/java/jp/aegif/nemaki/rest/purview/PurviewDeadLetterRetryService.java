package jp.aegif.nemaki.rest.purview;

public interface PurviewDeadLetterRetryService {

    PurviewJobState startRetryFailed(String repositoryId, String requestedBy);
}
