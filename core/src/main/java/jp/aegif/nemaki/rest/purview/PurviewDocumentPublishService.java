package jp.aegif.nemaki.rest.purview;

import java.util.List;

import jp.aegif.nemaki.model.Content;

public interface PurviewDocumentPublishService {

    int publishRepositoryDocuments(String repositoryId);

    int upsertDocuments(String repositoryId, List<Content> documents);
}
