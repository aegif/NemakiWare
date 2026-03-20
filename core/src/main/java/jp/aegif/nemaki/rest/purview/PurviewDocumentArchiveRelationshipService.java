package jp.aegif.nemaki.rest.purview;

import java.util.List;

import jp.aegif.nemaki.model.Archive;

public interface PurviewDocumentArchiveRelationshipService {

    int upsertDocumentArchiveRelationships(String repositoryId, List<Archive> archives);
}
