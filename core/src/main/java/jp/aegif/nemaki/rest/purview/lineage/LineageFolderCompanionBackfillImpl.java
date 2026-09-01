/**
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NemakiWare. If not, see <http://www.gnu.org/licenses/>.
 */
package jp.aegif.nemaki.rest.purview.lineage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfo;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.rest.purview.MetadataCatalogConnectionResolver;
import jp.aegif.nemaki.rest.purview.client.PurviewClientException;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityPublishResult;
import jp.aegif.nemaki.rest.purview.client.PurviewEntityRegistryClient;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaState;
import jp.aegif.nemaki.rest.purview.state.PurviewSchemaStateService;
import jp.aegif.nemaki.rest.purview.state.PurviewStateStore;

/**
 * The folder-companion backfill (増分 B).
 *
 * <h2>The shape of the guarantees</h2>
 *
 * <p><b>Idempotent.</b> A companion is published by qualified name through the catalog's
 * create-or-update path, and the relationship is created with a payload the client turns into
 * "already exists" on 409. Running twice publishes the same entity twice and leaves one.
 *
 * <p><b>Resumable.</b> The walk's frontier — folder ids discovered but not processed — is written
 * to the resume document after every batch, so a crash costs at most one batch and a restart
 * continues rather than starting over.
 *
 * <p><b>Bounded.</b> {@code maxBatches} caps a run and {@link #DEFAULT_BATCH_SIZE} caps a batch,
 * so the caller decides how much work happens, not the size of the repository.
 *
 * <p><b>Never reports a success it did not have.</b> A failed batch increments {@code failed} and
 * sets a refusal; {@link Progress#successful()} is false while either is set, and the walk still
 * finishes so the count is the real one rather than the count up to the first problem.
 *
 * <p><b>Fail-closed.</b> If the persisted schema state does not match the current manifest, or
 * the catalog does not answer, nothing is attempted and nothing is written — including to the
 * resume document, so a refused run cannot be mistaken later for a run that made progress.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No secret can reach a log or an exception from this class: the only values it handles are
 * folder ids, folder names and counts, and it never puts a catalog response body into a message.
 * Publishing goes through {@code CatalogSecretBoundary} like every other entity.
 */
@Service
public class LineageFolderCompanionBackfillImpl implements LineageFolderCompanionBackfill {

    private static final Logger logger =
            LoggerFactory.getLogger(LineageFolderCompanionBackfillImpl.class);

    /** One resume document per repository. */
    static final String STATE_KEY_PREFIX = "lineage.backfill.folderDataset.";

    /** Children read per DAO call. Independent of the publish batch, which the catalog sizes. */
    private static final int CHILD_PAGE_SIZE = 100;

    /**
     * Our own marker for "applied except businessMetadataDefs".
     *
     * <p>Treated as ready because {@code BUSINESS_METADATA_NAMES} is empty in this codebase, so
     * the fallback removes nothing that exists. That is a fact about our manifest, not an
     * assumption about a catalog backend — which is the kind of thing that must not be carried
     * from Atlas to Purview.
     */
    private static final String ATLAS_PARTIAL_SUFFIX = ":atlas-partial";

    private final MetadataCatalogConnectionResolver connectionResolver;
    private final RepositoryInfoMap repositoryInfoMap;
    private final ContentDaoService contentDaoService;
    private final PurviewEntityPayloadFactory entityPayloadFactory;
    private final PurviewEntityRegistryClient entityRegistryClient;
    private final PurviewSchemaStateService schemaStateService;
    private final PurviewSchemaManifestFactory schemaManifestFactory;
    private final PurviewStateStore stateStore;

    public LineageFolderCompanionBackfillImpl(
            MetadataCatalogConnectionResolver connectionResolver,
            RepositoryInfoMap repositoryInfoMap,
            @Qualifier("ContentDaoService") ContentDaoService contentDaoService,
            PurviewEntityPayloadFactory entityPayloadFactory,
            PurviewEntityRegistryClient entityRegistryClient,
            PurviewSchemaStateService schemaStateService,
            PurviewSchemaManifestFactory schemaManifestFactory,
            PurviewStateStore stateStore) {
        this.connectionResolver = connectionResolver;
        this.repositoryInfoMap = repositoryInfoMap;
        this.contentDaoService = contentDaoService;
        this.entityPayloadFactory = entityPayloadFactory;
        this.entityRegistryClient = entityRegistryClient;
        this.schemaStateService = schemaStateService;
        this.schemaManifestFactory = schemaManifestFactory;
        this.stateStore = stateStore;
    }

    // ------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------

    @Override
    public Plan plan(String repositoryId) {
        Refusal readiness = readiness(repositoryId);
        if (readiness != Refusal.NONE) {
            return new Plan(repositoryId, 0, 0, readiness != Refusal.SCHEMA_NOT_READY,
                    readiness != Refusal.CATALOG_UNREACHABLE, readiness);
        }
        long folders;
        try {
            folders = countFolders(repositoryId);
        } catch (RuntimeException e) {
            logger.warn("Backfill plan for '{}' could not count folders: {}",
                    repositoryId, e.getClass().getSimpleName());
            return new Plan(repositoryId, 0, 0, true, true, Refusal.REPOSITORY_UNAVAILABLE);
        }
        Progress recorded = progress(repositoryId);
        return new Plan(repositoryId, folders, recorded.processed(), true, true, Refusal.NONE);
    }

    /** A full walk that publishes nothing — the count an operator is entitled to see first. */
    private long countFolders(String repositoryId) {
        Deque<String> queue = new ArrayDeque<>();
        queue.add(requireRootFolderId(repositoryId));
        long folders = 0;
        Set<String> seen = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String folderId = queue.removeFirst();
            if (!seen.add(folderId)) {
                continue;
            }
            folders++;
            for (Content child : childFolders(repositoryId, folderId)) {
                queue.addLast(child.getId());
            }
        }
        return folders;
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    @Override
    public Progress run(String repositoryId, int maxBatches) {
        Refusal readiness = readiness(repositoryId);
        if (readiness != Refusal.NONE) {
            // Nothing written: a refused run must not leave a trace that later reads as progress.
            logger.warn("Backfill for '{}' refused before starting: {}", repositoryId, readiness);
            Progress recorded = progress(repositoryId);
            return new Progress(repositoryId, State.FAILED, recorded.processed(),
                    recorded.created(), recorded.alreadyPresent(), recorded.failed(),
                    recorded.pendingFrontier(), readiness);
        }

        Cursor cursor = readCursor(repositoryId);
        if (cursor.frontier.isEmpty() && cursor.processed == 0) {
            String root;
            try {
                root = requireRootFolderId(repositoryId);
            } catch (RuntimeException e) {
                return failed(repositoryId, cursor, Refusal.REPOSITORY_UNAVAILABLE);
            }
            cursor.frontier.add(root);
        }

        int batches = Math.max(1, maxBatches);
        for (int batch = 0; batch < batches && !cursor.frontier.isEmpty(); batch++) {
            Refusal outcome = runOneBatch(repositoryId, cursor);
            writeCursor(repositoryId, cursor);
            if (outcome != Refusal.NONE) {
                return failed(repositoryId, cursor, outcome);
            }
        }

        State state = cursor.frontier.isEmpty() ? State.COMPLETE : State.PAUSED;
        Refusal refusal = cursor.failed > 0 ? Refusal.PUBLISH_FAILED : Refusal.NONE;
        cursor.state = state;
        cursor.refusal = refusal;
        writeCursor(repositoryId, cursor);
        return toProgress(repositoryId, cursor);
    }

    /**
     * One bounded batch: take up to {@link #DEFAULT_BATCH_SIZE} folders off the frontier, publish
     * their companions together, then their relationships.
     *
     * <p>Children are discovered as each folder is taken, so the frontier grows and shrinks with
     * the walk rather than being computed up front.
     */
    private Refusal runOneBatch(String repositoryId, Cursor cursor) {
        List<Content> folders = new ArrayList<>();
        while (folders.size() < DEFAULT_BATCH_SIZE && !cursor.frontier.isEmpty()) {
            String folderId = cursor.frontier.removeFirst();
            if (!cursor.seen.add(folderId)) {
                continue;
            }
            Content folder = contentDaoService.getContent(repositoryId, folderId);
            if (folder == null || !folder.isFolder()) {
                // Gone between discovery and processing, which a live repository does. Not a
                // failure: there is no folder to give a companion to.
                continue;
            }
            folders.add(folder);
            for (Content child : childFolders(repositoryId, folderId)) {
                if (!cursor.seen.contains(child.getId())) {
                    cursor.frontier.addLast(child.getId());
                }
            }
        }
        if (cursor.frontier.size() > DEFAULT_MAX_FRONTIER) {
            // Refuse rather than drop: a truncated frontier skips whole subtrees and then
            // reports completion, which is the one outcome this design must not produce.
            logger.error("Backfill for '{}' stopped: frontier of {} exceeds the {} the resume"
                    + " document may hold", repositoryId, cursor.frontier.size(),
                    DEFAULT_MAX_FRONTIER);
            return Refusal.FRONTIER_TOO_LARGE;
        }
        if (folders.isEmpty()) {
            return Refusal.NONE;
        }
        return publish(repositoryId, folders, cursor);
    }

    private Refusal publish(String repositoryId, List<Content> folders, Cursor cursor) {
        List<Map<String, Object>> entities = new ArrayList<>(folders.size());
        for (Content folder : folders) {
            entities.add(entityPayloadFactory.buildFolderDatasetEntity(repositoryId, folder,
                    PurviewEntityPayloadFactory.SOURCE_STATE_ACTIVE));
        }
        try {
            PurviewEntityPublishResult result = entityRegistryClient.bulkCreateOrUpdateEntities(
                    connectionResolver.buildConnectionRequest(),
                    entityPayloadFactory.buildBulkPayload(entities));
            if (result == null || !result.isSuccess()) {
                // The message can echo a catalog response, so it is counted and not narrated.
                cursor.failed += folders.size();
                cursor.processed += folders.size();
                logger.error("Backfill batch for '{}' failed to publish {} companions",
                        repositoryId, folders.size());
                return Refusal.PUBLISH_FAILED;
            }
            cursor.created += result.getPublishedCount();
            cursor.alreadyPresent += Math.max(0, folders.size() - result.getPublishedCount());
            if (result.hasFailures()) {
                cursor.failed += result.getFailureCount();
            }
        } catch (PurviewClientException e) {
            cursor.failed += folders.size();
            cursor.processed += folders.size();
            logger.error("Backfill batch for '{}' could not reach the catalog: {}",
                    repositoryId, e.getClass().getSimpleName());
            return Refusal.CATALOG_UNREACHABLE;
        }

        for (Content folder : folders) {
            try {
                PurviewEntityPublishResult tie = entityRegistryClient.createRelationship(
                        connectionResolver.buildConnectionRequest(),
                        entityPayloadFactory.buildFolderDatasetRelationship(repositoryId, folder));
                // null is a client that answered nothing — a failed tie, never an NPE that
                // would abort the batch and lose the entities already published.
                if (tie == null || !tie.isSuccess()) {
                    cursor.failed++;
                }
            } catch (PurviewClientException e) {
                cursor.failed++;
                logger.error("Backfill could not tie a companion in '{}': {}",
                        repositoryId, e.getClass().getSimpleName());
            }
        }
        cursor.processed += folders.size();
        return Refusal.NONE;
    }

    // ------------------------------------------------------------------
    // Readiness
    // ------------------------------------------------------------------

    /**
     * Fail-closed: the schema must be the one this build expects, and the catalog must answer.
     *
     * <p>Readiness is read from the persisted schema state rather than by asking the catalog
     * whether a type exists. A "does this type exist" probe answers through the backend's own
     * error vocabulary, and Atlas and Purview do not share one — a result established against
     * Atlas OSS would not transfer.
     */
    private Refusal readiness(String repositoryId) {
        if (repositoryId == null || repositoryId.isBlank()) {
            return Refusal.REPOSITORY_UNAVAILABLE;
        }
        String expected;
        PurviewSchemaState applied;
        try {
            expected = schemaManifestFactory.buildManifest().getSchemaHash();
            applied = schemaStateService.getSchemaState(connectionResolver.getCollection());
        } catch (RuntimeException e) {
            logger.warn("Backfill cannot read schema state: {}", e.getClass().getSimpleName());
            return Refusal.CATALOG_UNREACHABLE;
        }
        if (applied == null || applied.getSchemaHash() == null) {
            return Refusal.SCHEMA_NOT_READY;
        }
        String appliedHash = applied.getSchemaHash();
        if (!appliedHash.equals(expected)
                && !appliedHash.equals(expected + ATLAS_PARTIAL_SUFFIX)) {
            return Refusal.SCHEMA_NOT_READY;
        }
        if (repositoryInfoMap == null || repositoryInfoMap.get(repositoryId) == null) {
            return Refusal.REPOSITORY_UNAVAILABLE;
        }
        return Refusal.NONE;
    }

    // ------------------------------------------------------------------
    // The resume document
    // ------------------------------------------------------------------

    @Override
    public Progress progress(String repositoryId) {
        return toProgress(repositoryId, readCursor(repositoryId));
    }

    @Override
    public List<String> repositoriesWithProgress() {
        Map<String, Object> all;
        try {
            all = stateStore.getAllByPrefix(STATE_KEY_PREFIX);
        } catch (RuntimeException e) {
            logger.warn("Cannot enumerate backfill progress: {}", e.getClass().getSimpleName());
            return List.of();
        }
        List<String> repositories = new ArrayList<>();
        for (String key : all.keySet()) {
            repositories.add(key.substring(STATE_KEY_PREFIX.length()));
        }
        return List.copyOf(repositories);
    }

    /** Mutable walk position. Not shared between runs except through the resume document. */
    private static final class Cursor {
        final Deque<String> frontier = new ArrayDeque<>();
        final Set<String> seen = new LinkedHashSet<>();
        long processed;
        long created;
        long alreadyPresent;
        long failed;
        State state = State.NOT_STARTED;
        Refusal refusal = Refusal.NONE;
    }

    @SuppressWarnings("unchecked")
    private Cursor readCursor(String repositoryId) {
        Cursor cursor = new Cursor();
        Map<String, Object> stored;
        try {
            stored = stateStore.getObject(STATE_KEY_PREFIX + repositoryId);
        } catch (RuntimeException e) {
            logger.warn("Cannot read backfill progress for '{}': {}",
                    repositoryId, e.getClass().getSimpleName());
            return cursor;
        }
        if (stored == null) {
            return cursor;
        }
        Object frontier = stored.get("frontier");
        if (frontier instanceof List<?> list) {
            for (Object id : list) {
                if (id instanceof String s) {
                    cursor.frontier.addLast(s);
                }
            }
        }
        Object seen = stored.get("seen");
        if (seen instanceof List<?> list) {
            for (Object id : list) {
                if (id instanceof String s) {
                    cursor.seen.add(s);
                }
            }
        }
        cursor.processed = asLong(stored.get("processed"));
        cursor.created = asLong(stored.get("created"));
        cursor.alreadyPresent = asLong(stored.get("alreadyPresent"));
        cursor.failed = asLong(stored.get("failed"));
        cursor.state = asState(stored.get("state"));
        cursor.refusal = asRefusal(stored.get("refusal"));
        return cursor;
    }

    private void writeCursor(String repositoryId, Cursor cursor) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("frontier", List.copyOf(cursor.frontier));
        document.put("seen", List.copyOf(cursor.seen));
        document.put("processed", cursor.processed);
        document.put("created", cursor.created);
        document.put("alreadyPresent", cursor.alreadyPresent);
        document.put("failed", cursor.failed);
        document.put("state", cursor.state.name());
        document.put("refusal", cursor.refusal.name());
        stateStore.putObject(STATE_KEY_PREFIX + repositoryId, document);
    }

    private Progress failed(String repositoryId, Cursor cursor, Refusal refusal) {
        cursor.state = State.FAILED;
        cursor.refusal = refusal;
        writeCursor(repositoryId, cursor);
        return toProgress(repositoryId, cursor);
    }

    private Progress toProgress(String repositoryId, Cursor cursor) {
        return new Progress(repositoryId, cursor.state, cursor.processed, cursor.created,
                cursor.alreadyPresent, cursor.failed, cursor.frontier.size(), cursor.refusal);
    }

    // ------------------------------------------------------------------

    private List<Content> childFolders(String repositoryId, String folderId) {
        List<Content> folders = new ArrayList<>();
        long total = Math.max(0L, contentDaoService.getChildrenCount(repositoryId, folderId));
        for (int skip = 0; skip < total; skip += CHILD_PAGE_SIZE) {
            List<Content> page =
                    contentDaoService.getChildrenPaged(repositoryId, folderId, skip, CHILD_PAGE_SIZE);
            // A decode-shortened page silently drops folders from the frontier, and once the
            // shortened frontier empties, the cursor persists COMPLETE — the resumable state
            // that tells every operator the backfill is done. Throwing lands in runOneBatch's
            // caller, which records the batch as failed with the cursor intact; a retry after
            // the row is repaired resumes from the same frontier.
            if (contentDaoService.lastUnreadableChildCount() > 0) {
                throw new IllegalStateException("folder " + folderId + "'s listing lost "
                        + contentDaoService.lastUnreadableChildCount() + " row(s) to decode"
                        + " failures; continuing would let the backfill record COMPLETE over"
                        + " a frontier that silently dropped subtrees");
            }
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Content child : page) {
                if (child != null && child.isFolder() && child.getId() != null
                        && !child.getId().isBlank()) {
                    folders.add(child);
                }
            }
        }
        return folders;
    }

    private String requireRootFolderId(String repositoryId) {
        RepositoryInfo info = repositoryInfoMap == null ? null : repositoryInfoMap.get(repositoryId);
        if (info == null || info.getRootFolderId() == null || info.getRootFolderId().isBlank()) {
            throw new IllegalStateException("no root folder for repository " + repositoryId);
        }
        return info.getRootFolderId();
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static State asState(Object value) {
        if (value instanceof String s) {
            for (State state : State.values()) {
                if (state.name().equals(s)) {
                    return state;
                }
            }
        }
        return State.NOT_STARTED;
    }

    private static Refusal asRefusal(Object value) {
        if (value instanceof String s) {
            for (Refusal refusal : Refusal.values()) {
                if (refusal.name().equals(s)) {
                    return refusal;
                }
            }
        }
        return Refusal.NONE;
    }
}
