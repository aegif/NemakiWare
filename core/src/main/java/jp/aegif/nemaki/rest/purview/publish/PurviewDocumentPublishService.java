package jp.aegif.nemaki.rest.purview.publish;

import java.util.List;

import jp.aegif.nemaki.model.Content;

public interface PurviewDocumentPublishService {

    int publishRepositoryHierarchy(String repositoryId);

    int upsertContents(String repositoryId, List<Content> contents);

    /**
     * How many of the last {@code upsertContents} call's documents did NOT get their entity
     * into the catalog — because the entity could not be built, or because the bulk that
     * carried it reported that qualified name as failed.
     *
     * <p>Exists because the return value of {@code upsertContents} cannot answer this: it is
     * entities PLUS containment PLUS document-type relationships, so a document whose entity
     * failed can still make the call return a positive number through its companion or its
     * edges. The cloud-metadata sync advances its baseline per document and needs to know
     * which documents actually landed; "&gt; 0" was measuring the wrong thing.
     *
     * <p>Per-thread and reset at the start of every {@code upsertContents} call, like the
     * {@code lastUnreadable*Count} counters in the DAO layer.
     */
    default int lastEntityPublishFailureCount() {
        return 0;
    }

    default int publishRepositoryDocuments(String repositoryId) {
        return publishRepositoryHierarchy(repositoryId);
    }

    default int upsertDocuments(String repositoryId, List<Content> documents) {
        return upsertContents(repositoryId, documents);
    }
}
