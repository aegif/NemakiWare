package jp.aegif.nemaki.rest.purview;

import java.util.List;

import jp.aegif.nemaki.model.Content;

public interface PurviewDocumentPublishService {

    int publishRepositoryHierarchy(String repositoryId);

    int upsertContents(String repositoryId, List<Content> contents);

    default int publishRepositoryDocuments(String repositoryId) {
        return publishRepositoryHierarchy(repositoryId);
    }

    default int upsertDocuments(String repositoryId, List<Content> documents) {
        return upsertContents(repositoryId, documents);
    }
}
