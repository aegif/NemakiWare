/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - RAG index maintenance service implementation
 ******************************************************************************/
package jp.aegif.nemaki.rag.maintenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.RAGIndexMaintenanceService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.rag.config.RAGConfig;
import jp.aegif.nemaki.rag.config.SolrClientProvider;
import jp.aegif.nemaki.rag.indexing.RAGIndexingException;
import jp.aegif.nemaki.rag.indexing.RAGIndexingService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;

/**
 * Implementation of RAGIndexMaintenanceService.
 * Provides RAG index maintenance operations with async execution.
 */
@Component
public class RAGIndexMaintenanceServiceImpl implements RAGIndexMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(RAGIndexMaintenanceServiceImpl.class);

    private static final int BATCH_SIZE = 10;  // Smaller batch for embedding generation
    private static final int MAX_ERRORS = 100;

    private final ContentService contentService;
    private final RAGIndexingService ragIndexingService;
    private final RAGConfig ragConfig;
    private final RepositoryInfoMap repositoryInfoMap;
    private final SolrClientProvider solrClientProvider;

    private final Map<String, RAGReindexStatus> reindexStatuses = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Autowired
    public RAGIndexMaintenanceServiceImpl(
            ContentService contentService,
            RAGIndexingService ragIndexingService,
            RAGConfig ragConfig,
            RepositoryInfoMap repositoryInfoMap,
            SolrClientProvider solrClientProvider) {
        this.contentService = contentService;
        this.ragIndexingService = ragIndexingService;
        this.ragConfig = ragConfig;
        this.repositoryInfoMap = repositoryInfoMap;
        this.solrClientProvider = solrClientProvider;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean startFullRAGReindex(String repositoryId) {
        if (!isRAGEnabled()) {
            log.warn("RAG is not enabled, cannot start reindex");
            return false;
        }

        RAGReindexStatus status = getOrCreateStatus(repositoryId);
        if ("running".equals(status.getStatus())) {
            log.warn("RAG reindex already running for repository: " + repositoryId);
            return false;
        }

        // Reset status
        resetStatus(status, repositoryId);
        getCancelFlag(repositoryId).set(false);

        executorService.submit(() -> {
            try {
                runFullRAGReindex(repositoryId, status);
            } catch (Exception e) {
                log.error("RAG reindex failed for repository: " + repositoryId, e);
                status.setStatus("error");
                status.setErrorMessage(e.getMessage());
                status.setEndTime(System.currentTimeMillis());
            } catch (Throwable t) {
                log.error("RAG reindex THROWABLE for repository: " + repositoryId, t);
                status.setStatus("error");
                status.setErrorMessage(t.getMessage());
                status.setEndTime(System.currentTimeMillis());
            }
        });

        return true;
    }

    @Override
    public boolean startFolderRAGReindex(String repositoryId, String folderId, boolean recursive) {
        if (!isRAGEnabled()) {
            log.warn("RAG is not enabled, cannot start folder reindex");
            return false;
        }

        RAGReindexStatus status = getOrCreateStatus(repositoryId);
        if ("running".equals(status.getStatus())) {
            log.warn("RAG reindex already running for repository: " + repositoryId);
            return false;
        }

        // Reset status
        resetStatus(status, repositoryId);
        getCancelFlag(repositoryId).set(false);

        executorService.submit(() -> {
            try {
                runFolderRAGReindex(repositoryId, folderId, recursive, status);
            } catch (Exception e) {
                log.error("RAG folder reindex failed for repository: " + repositoryId, e);
                status.setStatus("error");
                status.setErrorMessage(e.getMessage());
                status.setEndTime(System.currentTimeMillis());
            }
        });

        return true;
    }

    @Override
    public RAGReindexStatus getRAGReindexStatus(String repositoryId) {
        return getOrCreateStatus(repositoryId);
    }

    @Override
    public boolean cancelRAGReindex(String repositoryId) {
        RAGReindexStatus status = reindexStatuses.get(repositoryId);
        if (status == null || !"running".equals(status.getStatus())) {
            return false;
        }

        getCancelFlag(repositoryId).set(true);
        status.setStatus("cancelled");
        status.setEndTime(System.currentTimeMillis());
        log.info("RAG reindex cancelled for repository: " + repositoryId);
        return true;
    }

    @Override
    public RAGHealthStatus checkRAGHealth(String repositoryId) {
        RAGHealthStatus health = new RAGHealthStatus();
        health.setRepositoryId(repositoryId);
        health.setCheckTime(System.currentTimeMillis());
        health.setEnabled(isRAGEnabled());

        if (!isRAGEnabled()) {
            health.setHealthy(false);
            health.setMessage("RAG is not enabled");
            return health;
        }

        try {
            SolrClient solrClient = solrClientProvider.getClient();
            // Count RAG documents (doc_type:document with vectors)
            SolrQuery docQuery = new SolrQuery();
            docQuery.setQuery("doc_type:document");
            docQuery.addFilterQuery("repository_id:" + repositoryId);
            docQuery.setRows(0);
            QueryResponse docResponse = solrClient.query("nemaki", docQuery);
            long ragDocCount = docResponse.getResults().getNumFound();
            health.setRagDocumentCount(ragDocCount);

            // Count RAG chunks
            SolrQuery chunkQuery = new SolrQuery();
            chunkQuery.setQuery("doc_type:chunk");
            chunkQuery.addFilterQuery("repository_id:" + repositoryId);
            chunkQuery.setRows(0);
            QueryResponse chunkResponse = solrClient.query("nemaki", chunkQuery);
            long ragChunkCount = chunkResponse.getResults().getNumFound();
            health.setRagChunkCount(ragChunkCount);

            // Count eligible documents (documents with supported MIME types)
            // This is approximated by counting all documents in the repository
            long eligibleCount = countEligibleDocuments(repositoryId);
            health.setEligibleDocuments(eligibleCount);

            health.setHealthy(true);
            health.setMessage(String.format("RAG index: %d documents, %d chunks (eligible: %d)",
                    ragDocCount, ragChunkCount, eligibleCount));

        } catch (Exception e) {
            log.error("Failed to check RAG health for repository: " + repositoryId, e);
            health.setHealthy(false);
            health.setMessage("Failed to check RAG health: " + e.getMessage());
        }

        return health;
    }

    @Override
    public boolean reindexDocument(String repositoryId, String objectId) {
        if (!isRAGEnabled()) {
            return false;
        }

        try {
            Content content = contentService.getContent(repositoryId, objectId);
            if (content instanceof Document) {
                ragIndexingService.indexDocument(repositoryId, (Document) content);
                log.info("RAG reindexed document: " + objectId);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to RAG reindex document: " + objectId, e);
            return false;
        }
    }

    @Override
    public boolean deleteFromRAGIndex(String repositoryId, String objectId) {
        if (!isRAGEnabled()) {
            return false;
        }

        try {
            ragIndexingService.deleteDocument(repositoryId, objectId);
            log.info("RAG deleted document from index: " + objectId);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete document from RAG index: " + objectId, e);
            return false;
        }
    }

    @Override
    public boolean clearRAGIndex(String repositoryId) {
        try {
            SolrClient solrClient = solrClientProvider.getClient();
            // Delete all RAG documents (doc_type:document or doc_type:chunk) for this repository
            solrClient.deleteByQuery("nemaki",
                    "(doc_type:document OR doc_type:chunk) AND repository_id:" + repositoryId);
            solrClient.commit("nemaki");
            log.info("RAG index cleared for repository: " + repositoryId);
            return true;
        } catch (Exception e) {
            log.error("Failed to clear RAG index for repository: " + repositoryId, e);
            return false;
        }
    }

    /**
     * How many RAG items this repository already has indexed, or {@code -1} for "unknown".
     *
     * <p>Deliberately NOT zero on failure. Zero is the value that lets the guard pass, and a
     * count that could not be taken is the case the guard exists for. Zero IS returned when
     * there is no Solr client at all, because then {@code clearRAGIndex} deletes nothing and
     * there is no index to protect — the same reasoning as the CMIS side.
     *
     * <p>Counts PARENT DOCUMENTS, matching what the folder walk counts. Counting chunks as well
     * compares different units, and one long document then looks like a 20-fold loss.
     */
    private long countIndexedRagDocuments(String repositoryId) {
        try {
            org.apache.solr.client.solrj.SolrClient solrClient = solrClientProvider.getClient();
            if (solrClient == null) {
                return 0;
            }
            // PARENT DOCUMENTS ONLY. The first version counted chunks too, and the walk it is
            // compared against counts DOCUMENTS — so one ordinary document with 20 chunks put
            // 21 items in the index against 1 in the walk, and the 10x refusal fired on a
            // healthy repository. A guard that refuses ordinary work is not a guard; it is an
            // outage, and it would have been met by an operator running the upgrade step
            // CLAUDE.md requires.
            //
            // clearRAGIndex deletes both kinds. That is right and unrelated: the guard's job is
            // to compare LIKE WITH LIKE, not to describe everything the clear removes.
            org.apache.solr.client.solrj.request.SolrQuery query =
                    new org.apache.solr.client.solrj.request.SolrQuery(
                            "doc_type:document AND repository_id:"
                                    + org.apache.solr.client.solrj.util.ClientUtils
                                            .escapeQueryChars(repositoryId));
            query.setRows(0);
            return solrClient.query("nemaki", query).getResults().getNumFound();
        } catch (Exception e) {
            log.error("Could not count RAG items for repository " + repositoryId
                    + " — treating it as unknown rather than as an empty index", e);
            return -1;
        }
    }

    @Override
    public boolean isRAGEnabled() {
        return ragConfig.isEnabled() && ragIndexingService.isEnabled();
    }

    // Private helper methods

    /**
     * The final status of a walk, which is not "completed" when the walk dropped folders.
     *
     * <p>Both entry points wrote {@code "completed"} on anything that was not cancelled. The
     * catch inside the recursive walk sits INSIDE the recursion, so a sub-tree that could not
     * be enumerated was skipped and the walk carried on — and {@code addError} only appends to
     * a capped list, never touching {@code errorCount}, which {@code processBatch} alone
     * increments. So the summary said "errors: 0" for a run that had cleared the index and then
     * silently left part of the repository out of it.
     */
    private void settleStatus(String repositoryId, RAGReindexStatus status) {
        // Here, so every ending gets it: this is the one place all of them pass through.
        noteTruncation(status);
        if (getCancelFlag(repositoryId).get()) {
            return;
        }
        if (status.getErrorCount() > 0) {
            status.setStatus("completed_with_errors");
            status.setErrorMessage(status.getErrorCount() + " folder(s) or batch(es) failed and "
                    + "are NOT in the rebuilt index. This is NOT a complete reindex.");
            return;
        }
        status.setStatus("completed");
    }

    /** The gap at which the walk is not believed. Same shape as the CMIS reindex guard. */
    private static final long ENUMERATION_GUARD_FACTOR = 10L;

    /** Below this, there is not enough in the index for the ratio to mean anything. */
    private static final long ENUMERATION_GUARD_FLOOR = 20L;

    private void runFullRAGReindex(String repositoryId, RAGReindexStatus status) {
        log.info("Starting full RAG reindex for repository: " + repositoryId);
        status.setStatus("running");
        status.setStartTime(System.currentTimeMillis());

        try {
            // Get root folder
            Folder rootFolder = contentService.getFolder(repositoryId, repositoryInfoMap.get(repositoryId).getRootFolderId());
            if (rootFolder == null) {
                throw new RuntimeException("Root folder not found");
            }

            // Count total documents for progress tracking
            long totalDocs = countDocumentsInFolder(repositoryId, rootFolder.getId(), true);
            status.setTotalDocuments(totalDocs);

            // ── Refuse to destroy an index we cannot rebuild ────────────────────────────────
            // The same guard the CMIS reindex has had since the incident, and the RAG path had
            // none — while CLAUDE.md tells operators to run BOTH as a required upgrade step.
            //
            // The walk below cannot tell "this folder has no children" from "the children could
            // not be read", and no exception fixes that: a CouchDB view whose map function
            // fails answers HTTP 200 with ZERO ROWS. That was reproduced on the CMIS side — a
            // 164-object repository reindexed to one document, errorCount 0, status completed.
            // Refusing an unanswered view (2026-08-28) narrows the window; it does not close
            // this one, because there is no error to see.
            //
            // The only thing that catches it is a yardstick the same failure cannot silence:
            // what the index ALREADY holds. Finding almost nothing to index while about to
            // delete a great deal is not a reindex, it is a wipe.
            long alreadyIndexed = countIndexedRagDocuments(repositoryId);
            if (alreadyIndexed < 0) {
                status.setStatus("error");
                status.setErrorMessage("Refusing to reindex: the RAG index could not be counted, "
                        + "so there is no way to tell a working enumeration from a silently "
                        + "empty one. Nothing was cleared.");
                status.setEndTime(System.currentTimeMillis());
                return;
            }
            if (alreadyIndexed >= ENUMERATION_GUARD_FLOOR
                    && totalDocs * ENUMERATION_GUARD_FACTOR < alreadyIndexed) {
                String msg = "Refusing to reindex: the folder walk found only " + totalDocs
                        + " document(s) while the RAG index currently holds " + alreadyIndexed
                        + " indexed document(s). That gap means the enumeration is not seeing "
                        + "the repository, and clearing now would destroy the index with "
                        + "nothing to put back. Nothing was cleared. If the repository really "
                        + "did shrink this much, clear it explicitly first.";
                log.error(msg);
                status.setStatus("error");
                status.setErrorMessage(msg);
                status.setEndTime(System.currentTimeMillis());
                return;
            }
            log.warn("RAG reindex for {} is about to clear {} indexed item(s) and expects to "
                    + "index {} document(s)", repositoryId, alreadyIndexed, totalDocs);

            // Clear existing RAG index. Its return value is not decoration: it catches its own
            // failures and answers false, and carrying on from there rebuilds ON TOP of entries
            // for documents that no longer exist — then reports the run as completed.
            if (!clearRAGIndex(repositoryId)) {
                status.setStatus("error");
                status.setErrorMessage("Refusing to reindex: the existing RAG index could not be "
                        + "cleared, so a rebuild would leave stale entries for documents that "
                        + "are gone. Nothing was indexed.");
                status.setEndTime(System.currentTimeMillis());
                return;
            }

            // Process all folders recursively
            reindexFolderRecursive(repositoryId, rootFolder.getId(), true, status);

            settleStatus(repositoryId, status);
            status.setEndTime(System.currentTimeMillis());
            log.info(String.format("RAG reindex completed for repository: %s (indexed: %d, skipped: %d, errors: %d)",
                    repositoryId, status.getIndexedCount(), status.getSkippedCount(), status.getErrorCount()));

        } catch (Exception e) {
            log.error("RAG reindex failed for repository: " + repositoryId, e);
            status.setStatus("error");
            status.setErrorMessage(e.getMessage());
            status.setEndTime(System.currentTimeMillis());
        }
    }

    private void runFolderRAGReindex(String repositoryId, String folderId, boolean recursive, RAGReindexStatus status) {
        log.info("Starting folder RAG reindex for repository: " + repositoryId + ", folder: " + folderId);
        status.setStatus("running");
        status.setStartTime(System.currentTimeMillis());

        try {
            // Count total documents for progress tracking
            long totalDocs = countDocumentsInFolder(repositoryId, folderId, recursive);
            status.setTotalDocuments(totalDocs);

            // Process folder
            reindexFolderRecursive(repositoryId, folderId, recursive, status);

            settleStatus(repositoryId, status);
            status.setEndTime(System.currentTimeMillis());
            log.info(String.format("RAG folder reindex completed for repository: %s (indexed: %d, skipped: %d, errors: %d)",
                    repositoryId, status.getIndexedCount(), status.getSkippedCount(), status.getErrorCount()));

        } catch (Exception e) {
            log.error("RAG folder reindex failed for repository: " + repositoryId, e);
            status.setStatus("error");
            status.setErrorMessage(e.getMessage());
            status.setEndTime(System.currentTimeMillis());
        }
    }

    private void reindexFolderRecursive(String repositoryId, String folderId, boolean recursive, RAGReindexStatus status) {
        if (getCancelFlag(repositoryId).get()) {
            return;
        }

        try {
            Folder folder = contentService.getFolder(repositoryId, folderId);
            if (folder == null) {
                return;
            }

            status.setCurrentFolder(folder.getName());

            // Get children
            List<Content> children = contentService.getChildren(repositoryId, folderId);
            if (children == null) {
                return;
            }
            // Same rule as the CMIS walk, added the same day for once — the last three of
            // these arrived one review round apart each.
            int unreadableHere = contentService.lastUnreadableChildCount();
            if (unreadableHere > 0) {
                status.setErrorCount(status.getErrorCount() + unreadableHere);
                addError(status, "Folder " + folderId + ": " + unreadableHere + " child row(s) "
                        + "could not be decoded and were NOT indexed");
            }

            List<Document> batch = new ArrayList<>();

            for (Content child : children) {
                if (getCancelFlag(repositoryId).get()) {
                    return;
                }

                if (child instanceof Document) {
                    Document doc = (Document) child;
                    batch.add(doc);

                    if (batch.size() >= BATCH_SIZE) {
                        processBatch(repositoryId, batch, status);
                        batch.clear();
                    }
                } else if (child instanceof Folder && recursive) {
                    // Flush current batch before processing subfolder
                    if (!batch.isEmpty()) {
                        processBatch(repositoryId, batch, status);
                        batch.clear();
                    }
                    reindexFolderRecursive(repositoryId, child.getId(), recursive, status);
                }
            }

            // Process remaining batch
            if (!batch.isEmpty()) {
                processBatch(repositoryId, batch, status);
            }

        } catch (Exception e) {
            // COUNTED. addError appends to a capped list and nothing else; errorCount is
            // what the summary and settleStatus read, and a skipped folder never reached it.
            log.error("Error processing folder: " + folderId, e);
            addError(status, "Error processing folder " + folderId + ": " + e.getMessage());
            status.setErrorCount(status.getErrorCount() + 1);
        }
    }

    private void processBatch(String repositoryId, List<Document> batch, RAGReindexStatus status) {
        for (Document doc : batch) {
            if (getCancelFlag(repositoryId).get()) {
                return;
            }

            status.setCurrentDocument(doc.getName());

            try {
                ragIndexingService.indexDocument(repositoryId, doc);
                status.setIndexedCount(status.getIndexedCount() + 1);
            } catch (RAGIndexingException e) {
                // Check if skipped due to unsupported MIME type or no content
                if (e.getMessage() != null &&
                    (e.getMessage().contains("MIME type not supported") ||
                     e.getMessage().contains("No text content"))) {
                    status.setSkippedCount(status.getSkippedCount() + 1);
                } else {
                    status.setErrorCount(status.getErrorCount() + 1);
                    addError(status, "Error indexing " + doc.getId() + ": " + e.getMessage());
                }
            } catch (Exception e) {
                status.setErrorCount(status.getErrorCount() + 1);
                addError(status, "Error indexing " + doc.getId() + ": " + e.getMessage());
            }
        }
    }

    private long countDocumentsInFolder(String repositoryId, String folderId, boolean recursive) {
        long count = 0;

        try {
            List<Content> children = contentService.getChildren(repositoryId, folderId);
            if (children == null) {
                return 0;
            }

            for (Content child : children) {
                if (child instanceof Document) {
                    count++;
                } else if (child instanceof Folder && recursive) {
                    count += countDocumentsInFolder(repositoryId, child.getId(), recursive);
                }
            }
        } catch (Exception e) {
            log.warn("Error counting documents in folder: " + folderId, e);
        }

        return count;
    }

    private long countEligibleDocuments(String repositoryId) {
        // Count documents that have supported MIME types
        // For simplicity, count all documents (actual filtering happens during indexing)
        try {
            Folder rootFolder = contentService.getFolder(repositoryId,
                    repositoryInfoMap.get(repositoryId).getRootFolderId());
            if (rootFolder != null) {
                return countDocumentsInFolder(repositoryId, rootFolder.getId(), true);
            }
        } catch (Exception e) {
            log.warn("Error counting eligible documents", e);
        }
        return 0;
    }

    private RAGReindexStatus getOrCreateStatus(String repositoryId) {
        return reindexStatuses.computeIfAbsent(repositoryId, k -> {
            RAGReindexStatus status = new RAGReindexStatus();
            status.setRepositoryId(repositoryId);
            status.setStatus("idle");
            status.setErrors(new ArrayList<>());
            return status;
        });
    }

    private void resetStatus(RAGReindexStatus status, String repositoryId) {
        status.setRepositoryId(repositoryId);
        status.setStatus("idle");
        status.setTotalDocuments(0);
        status.setIndexedCount(0);
        status.setSkippedCount(0);
        status.setErrorCount(0);
        status.setStartTime(0);
        status.setEndTime(0);
        status.setCurrentDocument(null);
        status.setErrorMessage(null);
        status.setErrors(new ArrayList<>());
    }

    private AtomicBoolean getCancelFlag(String repositoryId) {
        return cancelFlags.computeIfAbsent(repositoryId, k -> new AtomicBoolean(false));
    }

    private void addError(RAGReindexStatus status, String error) {
        if (status.getErrors() == null) {
            status.setErrors(new ArrayList<>());
        }
        if (status.getErrors().size() < MAX_ERRORS) {
            status.getErrors().add(error);
        }
    }

    /**
     * Says out loud that the message list is shorter than the count.
     *
     * <p>The CMIS sibling was corrected for this and THIS one was not, which is the shape this
     * batch keeps finding. The UI renders both numbers side by side — "5000" beside "error
     * details (100)" — through the same component for both reindexes, so the gap that was just
     * explained on one tab was still unexplained on the other. And this is the reindex CLAUDE.md
     * names as a required upgrade step.
     *
     * <p>Keyed on the COUNT, not the list size: exactly 100 failures fit in exactly 100
     * messages and nothing was dropped, so saying otherwise is its own small overclaim.
     */
    private void noteTruncation(RAGReindexStatus status) {
        if (status.getErrors() == null || status.getErrorCount() <= MAX_ERRORS) {
            return;
        }
        status.getErrors().add("... only the first " + MAX_ERRORS + " messages are kept; "
                + (status.getErrorCount() - MAX_ERRORS) + " further failure(s) are counted "
                + "above but not described here.");
    }
}
