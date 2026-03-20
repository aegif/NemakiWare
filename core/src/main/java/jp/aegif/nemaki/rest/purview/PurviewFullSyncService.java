package jp.aegif.nemaki.rest.purview;

public interface PurviewFullSyncService {

    PurviewJobState startFullSync(String repositoryId, String requestedBy);
}
