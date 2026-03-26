package jp.aegif.nemaki.rest.purview.relationship;

import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Archive;

public interface PurviewDocumentArchiveRelationshipService {

    default int upsertDocumentArchiveRelationships(String repositoryId, List<Archive> archives) {
        return upsertDocumentArchiveRelationships(repositoryId, archives, Map.of());
    }

    int upsertDocumentArchiveRelationships(String repositoryId, List<Archive> archives, Map<String, String> guidByQualifiedName);
}
