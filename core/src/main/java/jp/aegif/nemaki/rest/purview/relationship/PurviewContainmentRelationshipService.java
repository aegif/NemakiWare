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

    /**
     * Forgets the recorded relationship GUIDs for a repository, so the next sync re-creates
     * every containment edge in the external catalog.
     *
     * <p>The publish path skips an edge whose GUID this store already holds — cheap, and
     * right whenever we are the only writer. A relationship deleted in the catalog
     * out-of-band breaks that assumption: the recorded GUID is what WE did, not proof of what
     * the catalog still holds, and no diff will ever notice because our snapshot and the
     * catalog agree from our side. Detecting it would mean reading every relationship back on
     * every cycle; making it REPAIRABLE costs one call. Returns how many recorded GUIDs were
     * forgotten.
     */
    default int forgetRecordedRelationshipGuids(String repositoryId) {
        return 0;
    }

    PurviewContainmentSyncResult syncRepositoryContainmentRelationshipsIfChanged(String repositoryId, String previousSnapshot);
}
