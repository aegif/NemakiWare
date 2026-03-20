package jp.aegif.nemaki.rest.purview.relationship;

import java.util.List;

import jp.aegif.nemaki.model.Archive;

public interface PurviewDocumentArchiveRelationshipService {

    int upsertDocumentArchiveRelationships(String repositoryId, List<Archive> archives);
}
