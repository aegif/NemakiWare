package jp.aegif.nemaki.rest.purview.relationship;

import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Content;

public interface PurviewDocumentTypeRelationshipService {

    default int upsertDocumentTypeRelationships(String repositoryId, List<Content> contents) {
        return upsertDocumentTypeRelationships(repositoryId, contents, Map.of());
    }

    int upsertDocumentTypeRelationships(String repositoryId, List<Content> contents, Map<String, String> guidByQualifiedName);
}
