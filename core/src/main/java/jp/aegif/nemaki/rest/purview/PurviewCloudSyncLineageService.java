package jp.aegif.nemaki.rest.purview;

import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Content;

public interface PurviewCloudSyncLineageService {

    int upsertCloudSyncLineage(String repositoryId, List<Content> documents);

    int reconcileRemovedCloudSyncLineage(String repositoryId, Map<String, String> obsoleteSnapshotEntries);
}
