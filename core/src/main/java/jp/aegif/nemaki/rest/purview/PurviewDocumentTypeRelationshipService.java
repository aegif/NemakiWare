package jp.aegif.nemaki.rest.purview;

import java.util.List;

import jp.aegif.nemaki.model.Content;

public interface PurviewDocumentTypeRelationshipService {

    int upsertDocumentTypeRelationships(String repositoryId, List<Content> contents);
}
