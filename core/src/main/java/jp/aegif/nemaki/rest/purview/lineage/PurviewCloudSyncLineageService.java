package jp.aegif.nemaki.rest.purview.lineage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.model.Content;

public interface PurviewCloudSyncLineageService {

    int upsertCloudSyncLineage(String repositoryId, List<Content> documents);

    int reconcileRemovedCloudSyncLineage(String repositoryId, Map<String, String> obsoleteSnapshotEntries, Set<String> activeStableKeys);

    /**
     * Deletes cloud-sync-lineage entities (process + external asset) from Atlas
     * for a specific objectId. Used for manual cleanup of orphaned entities.
     *
     * @param repositoryId repository
     * @param objectId     the NemakiWare objectId whose cloud-sync entities should be deleted
     * @param stableKey    the external stable key (e.g. "google:fileId") for the external asset; may be null to skip
     * @return number of entities deleted
     */
    int deleteCloudSyncLineageByObjectId(String repositoryId, String objectId, String stableKey);

    /**
     * Deletes a single external-asset entity from Atlas/Purview that is no longer
     * referenced by any document (orphaned due to a stableKey change).
     *
     * @param repositoryId repository
     * @param stableKey    the external stable key (e.g. "google:fileId")
     * @return number of entities deleted (0 or 1)
     */
    int deleteObsoleteExternalAsset(String repositoryId, String stableKey);
}
