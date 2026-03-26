package jp.aegif.nemaki.rest.purview.relationship;

import jp.aegif.nemaki.rest.purview.sync.PurviewContainmentSyncResult;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Content;

public interface PurviewContainmentRelationshipService {

    default int upsertContainmentRelationships(String repositoryId, List<Content> contents) {
        return upsertContainmentRelationships(repositoryId, contents, Map.of());
    }

    int upsertContainmentRelationships(String repositoryId, List<Content> contents, Map<String, String> guidByQualifiedName);

    String buildRepositoryContainmentSnapshot(String repositoryId);

    PurviewContainmentSyncResult syncRepositoryContainmentRelationshipsIfChanged(String repositoryId, String previousSnapshot);
}
