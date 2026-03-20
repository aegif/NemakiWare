package jp.aegif.nemaki.rest.purview;

public interface PurviewIncrementalSyncService {

    PurviewJobState startIncrementalSync(String repositoryId, String requestedBy);
}
