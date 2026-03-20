package jp.aegif.nemaki.rest.purview;

import java.util.List;

import jp.aegif.nemaki.model.Content;

public interface PurviewContainmentRelationshipService {

    int upsertContainmentRelationships(String repositoryId, List<Content> contents);

    String buildRepositoryContainmentSnapshot(String repositoryId);

    PurviewContainmentSyncResult syncRepositoryContainmentRelationshipsIfChanged(String repositoryId, String previousSnapshot);
}
