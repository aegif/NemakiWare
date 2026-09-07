package jp.aegif.nemaki.rest.ingest;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.DocumentResult;
import com.ibm.cloud.cloudant.v1.model.FindResult;
import com.ibm.cloud.cloudant.v1.model.PostDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostFindOptions;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jp.aegif.nemaki.config.ObjectMapperFactory;

public class ImportProfileDefinitionServiceImpl implements ImportProfileDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(ImportProfileDefinitionServiceImpl.class);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private CloudantClientPool connectorPool;
    private ConnectorDefinitionService connectorDefinitionService;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    public void setConnectorDefinitionService(ConnectorDefinitionService connectorDefinitionService) {
        this.connectorDefinitionService = connectorDefinitionService;
    }

    @Override
    public ImportProfileDefinition create(ImportProfileDefinition def) {
        validateRequiredFields(def, true);
        if (exists(def.getProfileId())) {
            throw new IllegalStateException("Import profile already exists: " + def.getProfileId());
        }
        String now = Instant.now().toString();
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        upsertDocument(def, true);
        logger.info("Created import profile: {}", def.getProfileId());
        return def;
    }

    @Override
    public ImportProfileDefinition get(String profileId) {
        // Null means "no such profile", not a crash — see ConnectorDefinitionServiceImpl.get.
        if (profileId == null) return null;
        // The selector call is WRAPPED. It was not, so a Mango read that threw left this
        // method as a raw RuntimeException — a 500 in front of GET, PUT and ownership
        // transfer, the very verbs this batch made index-free. (The plain DELETE was fixed by
        // deleting its selector read; a review found the other three still exposed.) A failed
        // selector is not an answer: fall through to the id-addressed read, which needs no
        // index, and let it decide.
        List<ImportProfileDefinition> results;
        try {
            results = findBySelector(Map.of(
                    "type", ImportProfileDefinition.DOC_TYPE,
                    "profileId", profileId));
        } catch (RuntimeException selectorFailed) {
            logger.debug("selector read for profile {} failed; falling back to the"
                    + " deterministic id: {}", profileId, selectorFailed.getMessage());
            results = List.of();
        }
        if (!results.isEmpty()) {
            return results.get(0);
        }
        // The selector answered nothing — which, while its index rebuilds, is also its
        // answer for a profile that IS there. Every caller reads null as "no such profile",
        // including the import path, which resolves the profile through an index-free walk
        // and then looked it up again HERE and lost it. One id-addressed read, on the miss
        // path only. A review found the round trip.
        try {
            CloudantClientWrapper client = getConfClient();
            com.ibm.cloud.cloudant.v1.model.Document row = readByDeterministicId(
                    client.getClient(), client.getDatabaseName(), profileId);
            if (row == null) {
                return null;
            }
            // Confirm the row IS this profile. The id is deterministic, not reserved: any
            // document occupying that id was deserialised and returned, so GET /{id} could
            // answer with a different row. The connector twin has always checked this; a
            // review found the profile half missing it.
            Map<String, Object> props = row.getProperties();
            if (props == null
                    || !ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))
                    || !profileId.equals(props.get("profileId"))) {
                logger.warn("document {} occupies the deterministic id of import profile {}"
                        + " but does not define it; ignoring",
                        ImportProfileDefinition.DOC_TYPE + ":" + profileId, profileId);
                return null;
            }
            return fromRawDoc(row);
        } catch (RuntimeException idReadFailed) {
            // Opportunistic only: this read can improve the selector's answer, never worsen
            // it. Whether a hidden row exists is decided by existsIndexFree, which refuses
            // rather than answers when it cannot read — that is where "could not ask" is
            // kept apart from "no".
            logger.debug("id-addressed fallback for profile {} failed: {}", profileId,
                    idReadFailed.getMessage());
            return null;
        }
    }

    /** One raw row as a definition, with the storage bookkeeping removed (see findBySelector). */
    private static ImportProfileDefinition fromRawDoc(com.ibm.cloud.cloudant.v1.model.Document raw) {
        Map<String, Object> props = new HashMap<>(raw.getProperties());
        props.remove("_id");
        props.remove("_rev");
        props.remove("type");
        return MAPPER.convertValue(props, ImportProfileDefinition.class);
    }

    @Override
    public List<ImportProfileDefinition> list() {
        return findBySelector(Map.of("type", ImportProfileDefinition.DOC_TYPE));
    }

    @Override
    public List<ImportProfileDefinition> listByRepository(String repositoryId) {
        return findBySelector(Map.of(
                "type", ImportProfileDefinition.DOC_TYPE,
                "repositoryId", repositoryId));
    }

    @Override
    public ImportProfileDefinition update(ImportProfileDefinition def) {
        validateRequiredFields(def, false);
        def.setUpdatedAt(Instant.now().toString());
        upsertDocument(def, false);
        logger.info("Updated import profile: {}", def.getProfileId());
        return def;
    }

    @Override
    public int delete(String profileId, String repositoryId) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        // Index-free, and confined to the caller's repository. The selector-based delete
        // removed only the rows the rebuilding index showed, and the controller then
        // audited a complete deletion and stopped the scheduler while a hidden twin lived
        // on. Rows of OTHER repositories are not the caller's to delete and are left alone
        // without comment. An unreadable row refuses: "deleted" must mean deleted.
        List<com.ibm.cloud.cloudant.v1.model.Document> targets = new ArrayList<>();
        try {
            NemakiConfAllDocs.forEachRow(cloudant, dbName, row -> {
                String id = row.getId();
                if (row.getError() != null || id == null) {
                    throw new IllegalStateException("import profile " + profileId
                            + " cannot be deleted completely: a row of '" + dbName
                            + "' could not be read");
                }
                if (id.startsWith("_design/")) {
                    return;
                }
                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                Map<String, Object> props = doc != null ? doc.getProperties() : null;
                if (props == null) {
                    throw new IllegalStateException("import profile " + profileId
                            + " cannot be deleted completely: row " + id
                            + " came back without a body");
                }
                if (ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))
                        && profileId.equals(props.get("profileId"))
                        && repositoryId != null && repositoryId.equals(props.get("repositoryId"))) {
                    targets.add(doc);
                }
            });
        } catch (IllegalStateException unprovable) {
            throw new ProfileIndexNotReadyException(unprovable.getMessage());
        }
        int removed = 0;
        for (com.ibm.cloud.cloudant.v1.model.Document doc : targets) {
            try {
                cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                        .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();
                removed++;
            } catch (RuntimeException rowFailed) {
                if (removed == 0) {
                    // Nothing was removed: this is the store refusing the whole operation
                    // (authentication, permission, a broken connection). Not "partly
                    // deleted", and not retryable by itself — it escapes as it is.
                    throw rowFailed;
                }
                // Some rows ARE gone and the caller must know: a retry removes the rest,
                // and reporting this as an ordinary failure reads as "nothing happened".
                // The controller's blanket retry classification was a review finding.
                throw new ProfilePartiallyDeletedException(kindMessage("import profile", profileId, removed, targets.size(),
                        rowFailed.getMessage()), rowFailed);
            }
        }
        logger.info("Deleted import profile {} ({} row(s)) in repository {}", profileId,
                targets.size(), repositoryId);
        // Across ALL repositories: the scheduler is keyed by profileId alone, so the caller
        // must not stop it while another repository still has this profile. A review found
        // a repository-confined delete cutting the other repository's live mail capture.
        try {
            return countProfileRowsIndexFree(cloudant, dbName, profileId, null);
        } catch (RuntimeException unreadable) {
            logger.warn("import profile {} was deleted in repository {}, but whether any"
                    + " repository still has it could not be established: {}", profileId,
                    repositoryId, unreadable.getMessage());
            return -1;
        }
    }

    @Override
    public int delete(String profileId, String docId, String repositoryId) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();
        com.ibm.cloud.cloudant.v1.model.Document row;
        try {
            row = cloudant.getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions
                    .Builder().db(dbName).docId(docId).build()).execute().getResult();
        } catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
            throw new IllegalArgumentException("no row " + docId + " exists; nothing was"
                    + " deleted", e);
        }
        Map<String, Object> props = row != null ? row.getProperties() : null;
        // A row that names NO repository belongs to none: the migration leaves it deliberately,
        // every repository-confined read skips it, and yet it counts towards the global row
        // count that refuses writes — so it made PUT answer 409 for ever while both delete
        // verbs refused to touch it. Any administrator may remove it: no repository can be
        // using it, because the ingest paths now refuse a profile bound to no repository.
        // (They did not: the confinement check read "repositoryId != null && !equals(caller)",
        // so such a row acted as a wildcard profile for every repository — a second review
        // found this sentence claiming something the runtime contradicted, and both were
        // fixed in the same round.)
        // Blank counts as unowned, not just null. The migration classifies both as malformed
        // and tells the operator to remove them with ?docId=, while this path recognised only
        // literal null — so a blank row was undeletable through the very API the message
        // prescribes, and went on blocking every write of that profileId. A review found the
        // two halves disagreeing.
        boolean unowned = props != null && isBlank(props.get("repositoryId"));
        if (props == null
                || !ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))
                || !profileId.equals(props.get("profileId"))
                || repositoryId == null
                || !(unowned || repositoryId.equals(props.get("repositoryId")))) {
            // An id-addressed delete with the WRONG target is worse than the divergence it
            // resolves: refusing is the only answer that cannot destroy an unrelated row.
            // The repository is checked HERE, on the addressed row — the controller's
            // ownership check runs on whichever twin the selector returned first, and a
            // divergent twin can differ in exactly that field, so without this a caller
            // authorised to repository A could delete repository B's row. A review caught
            // it before first contact.
            throw new IllegalArgumentException("row " + docId + " is not a definition of"
                    + " import profile " + profileId + " in repository " + repositoryId
                    + "; refusing to delete it");
        }
        // This operation resolves a DIVERGENT PAIR. If the addressed row is the only row
        // that defines the profile, removing it deletes the profile outright — through a
        // path that deliberately skips the scheduler stop and the deletion record, because it
        // assumes the profile survives. A review found the assumption unchecked; the plain
        // DELETE is the operation for removing a profile.
        int rows;
        try {
            // An unowned row is counted across ALL repositories: the confined count cannot
            // see it, so the "no twin" and "the reads disagree" arms below would both fire
            // on the very row they are meant to let an administrator clean up.
            rows = countProfileRowsIndexFree(cloudant, dbName, profileId,
                    unowned ? null : repositoryId);
        } catch (IllegalStateException unprovable) {
            // The count reads rows; one it cannot classify means "could not ask", and this
            // path had no catch for it — the raw IllegalStateException became a 500 with no
            // audit entry, while every other caller of the same count wraps it. A review
            // found the one-armed path.
            throw new ProfileIndexNotReadyException(unprovable.getMessage());
        }
        if (rows == 0) {
            // The row was READ by id and the scan counted none: the two reads disagree, so
            // "this is the only row" is not established. The write path refuses exactly this
            // disagreement retryably.
            throw new ProfileIndexNotReadyException("row " + docId + " of import profile "
                    + profileId + " was read by id, but an index-free scan counted no rows"
                    + " defining it in this repository; the two reads disagree. Retry shortly.");
        }
        if (rows == 1 && !unowned) {
            // NOT IllegalArgumentException: the controller maps that to "row not found", and
            // this row exists. It is the state that is wrong, not the address.
            // An unowned row is exempt: it defines the profile for no repository, so removing
            // it cannot take a profile away from anyone, and it is the only way to clear the
            // standing 409 it causes.
            throw new ProfileHasNoTwinException("row " + docId + " is the only definition row"
                    + " of import profile " + profileId + " in this repository; this"
                    + " operation resolves a divergent pair. Use DELETE without docId to"
                    + " remove the profile.");
        }
        cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions
                .Builder().db(dbName).docId(docId).rev(row.getRev()).build()).execute();
        logger.info("Deleted import profile row {} of {}", docId, profileId);
        // How many rows are LEFT. Two administrators can each address a different twin, both
        // see two rows, and both delete — and then the definition is gone through a path that
        // skips the scheduler stop and the deletion record because it assumes a survivor. The
        // count before the delete cannot prevent that (CouchDB has no cross-document
        // transaction); reporting the survivors lets the caller finish the job it skipped.
        // A review named the race.
        try {
            // ACROSS ALL REPOSITORIES, not just this one. The caller decides from this
            // number whether to stop the scheduler, and the scheduler is keyed by profileId
            // alone — a repository-confined count would let this path cut another
            // repository's live capture, the defect the plain delete was changed to avoid.
            // A review found the two paths disagreeing.
            return countProfileRowsIndexFree(cloudant, dbName, profileId, null);
        } catch (RuntimeException unreadable) {
            logger.warn("row {} of profile {} was deleted, but how many rows remain could not be"
                    + " established: {}", docId, profileId, unreadable.getMessage());
            return -1;
        }
    }

    /**
     * The store holds the profile but the index has not caught up, so the request cannot be
     * completed SAFELY. Separate from {@link IllegalStateException} so the controller can
     * answer 503 rather than 500 — the connector service's twin.
     */
    public static class ProfileIndexNotReadyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProfileIndexNotReadyException(String message) {
            super(message);
        }
    }

    /**
     * The row-addressed resolver was pointed at a row that is the ONLY definition of its
     * profile. The address is right and the row is there; what is absent is the divergent
     * pair this operation exists to resolve — so it is a conflict with the current state
     * (409), not "no such row" (404). Removing it here would delete the profile through a
     * path that skips the scheduler stop and the deletion record.
     */
    public static class ProfileHasNoTwinException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProfileHasNoTwinException(String message) {
            super(message);
        }
    }

    /**
     * Two or more rows define one profile and both answer. Not retryable — an administrator
     * deletes the unwanted row ({@code DELETE .../admin/import-profiles/{id}?docId=...}) — so it is
     * neither the index-not-ready type (503, "wait") nor {@code IllegalStateException}
     * (400, "bad request"): the controllers answer 409.
     */
    public static class ProfileHasTwinRowsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProfileHasTwinRowsException(String message) {
            super(message);
        }
    }

    @Override
    public ConnectorDefinitionService.LegacyIdMigrationResult migrateLegacyGeneratedIds() {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();
        ConnectorDefinitionService.LegacyIdMigrationResult result =
                new ConnectorDefinitionService.LegacyIdMigrationResult();

        // profileId/connectorId -> (row id -> row). Filled by the walk, acted on after it.
        Map<String, Map<String, com.ibm.cloud.cloudant.v1.model.Document>> legacyRows =
                new java.util.LinkedHashMap<>();

        // The shared _all_docs walk, not the Mango selector — the selector is answered by
        // the index whose rebuild opens the window this migration closes.
        NemakiConfAllDocs.forEachRow(cloudant, dbName, row -> {
            if (row.getError() != null) {
                result.failures.add(String.valueOf(row.getId())
                        + " (listing row carries error: " + row.getError() + ")");
                return;
            }
            String id = row.getId();
            if (id == null) {
                result.failures.add("(a row with no id cannot be classified)");
                return;
            }
            if (id.startsWith("_design/")) {
                return;
            }
            com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
            Map<String, Object> props = doc != null ? doc.getProperties() : null;
            if (props == null) {
                result.failures.add(id + " (no document body came back with the row,"
                        + " so it cannot be classified)");
                return;
            }
            if (!ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))) {
                return;
            }
            Object pid = props.get("profileId");
            String profileId = pid instanceof String ? (String) pid : null;
            if (profileId == null || profileId.isBlank()) {
                result.failures.add(id + " (an import_profile_definition row without a"
                        + " usable profileId cannot be given a deterministic id)");
                logger.error("Import profile row {} has no usable profileId; it cannot be"
                        + " migrated and stays invisible to the duplicate check", id);
                return;
            }
            Object rid = props.get("repositoryId");
            if (!(rid instanceof String) || ((String) rid).isBlank()) {
                // Every supported writer has required repositoryId since the profile
                // service's first commit, so this is a malformed row. Rewriting it under a
                // deterministic id would hide it from the repository-confined delete. Left
                // in place and reported; any administrator can remove it with ?docId=. A
                // review found the ERROR still saying "repair the database" after the
                // delete path had been opened.
                result.failures.add(id + " (an import_profile_definition row without a"
                        + " usable repositoryId is malformed and is not migrated; delete it"
                        + " with DELETE .../admin/import-profiles/" + profileId + "?docId="
                        + id + ")");
                logger.error("Import profile row {} has no usable repositoryId; it is left in"
                        + " place. Any administrator can remove it with DELETE"
                        + " .../admin/import-profiles/{}?docId={}", id, profileId, id);
                return;
            }
            String deterministicId = ImportProfileDefinition.DOC_TYPE + ":" + profileId;
            if (deterministicId.equals(id)) {
                return;
            }
            // COLLECTED, not migrated here. Two reasons, both found by review. (1) With two
            // legacy rows and no deterministic row, migrating during the walk made the FIRST
            // one encountered canonical and reported only the second as divergent — the
            // release notes promise neither row is touched, and enumeration order was
            // choosing a winner. (2) Writing while enumerating is what made the walk's
            // skip-after-delete subtlety load-bearing in the first place.
            legacyRows.computeIfAbsent(profileId, k -> new java.util.LinkedHashMap<>())
                    .put(id, doc);
        });
        for (Map.Entry<String, Map<String, com.ibm.cloud.cloudant.v1.model.Document>> entry
                : legacyRows.entrySet()) {
            String profileId = entry.getKey();
            Map<String, com.ibm.cloud.cloudant.v1.model.Document> rows = entry.getValue();
            String deterministicId = ImportProfileDefinition.DOC_TYPE + ":" + profileId;
            if (rows.size() > 1) {
                // Which repositories do these rows belong to? Rows in DIFFERENT repositories
                // are not a divergent pair — they are two repositories that happen to share a
                // profileId, and the deterministic id has no repository in it, so only one of
                // them can hold it. The row-addressed resolver refuses them (its count is
                // repository-confined, so each side sees one row), and prescribing it was
                // prescribing the impossible — the same defect this migration's message was
                // once fixed for, in its cross-repository form. A review found it.
                java.util.Set<Object> repositories = new java.util.LinkedHashSet<>();
                for (com.ibm.cloud.cloudant.v1.model.Document legacy : rows.values()) {
                    repositories.add(legacy.getProperties() == null ? null
                            : legacy.getProperties().get("repositoryId"));
                }
                if (repositories.size() > 1) {
                    result.divergent.add(profileId + " (" + rows.size() + " legacy rows "
                            + rows.keySet() + " across repositories " + repositories
                            + "; the deterministic id has no repository in it, so only one"
                            + " repository can keep this profileId)");
                    logger.error("Import profile {} has legacy rows {} in different"
                            + " repositories {}; none is migrated. Only one repository can"
                            + " keep this profileId: as the administrator of the repository"
                            + " that should give it up, remove its rows with DELETE"
                            + " .../admin/import-profiles/{} (no docId).",
                            profileId, rows.keySet(), repositories, profileId);
                    continue;
                }
                result.divergent.add(profileId + " (" + rows.size() + " legacy rows "
                        + rows.keySet() + "; migrating one of them would make it canonical by"
                        + " nothing but enumeration order, so none is touched)");
                logger.error("Import profile {} has {} legacy rows {}; none is migrated —"
                        + " delete the unwanted ones with DELETE"
                        + " .../admin/import-profiles/{}?docId=...", profileId, rows.size(),
                        rows.keySet(), profileId);
                continue;
            }
            Map.Entry<String, com.ibm.cloud.cloudant.v1.model.Document> only =
                    rows.entrySet().iterator().next();
            migrateOneLegacyRow(client, cloudant, dbName, only.getValue(), only.getKey(),
                    profileId, deterministicId, result);
        }
        return result;
    }

    private static String kindMessage(String what, String id, int removed, int total,
            String cause) {
        return what + " " + id + " is PARTLY deleted: " + removed + " of " + total
                + " definition row(s) were removed before the store refused (" + cause
                + "). Retry to remove the rest.";
    }

    /**
     * Some definition rows were deleted and at least one was not. CouchDB has no transaction
     * across documents, so this outcome exists; what must not happen is reporting it as an
     * ordinary failure ("nothing happened") or as success. Retryable: the retry removes what
     * remains.
     */
    public static class ProfilePartiallyDeletedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProfilePartiallyDeletedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The row's CONTENT: everything except storage bookkeeping (see findBySelector). */
    private static Map<String, Object> contentOnly(Map<String, Object> props) {
        Map<String, Object> content = new HashMap<>(props);
        content.remove("_id");
        content.remove("_rev");
        content.remove("_attachments");
        return content;
    }

    /**
     * Moves one legacy row to its deterministic id: copy, verify the copy exists, then
     * retire the original at the revision it was READ at. The import-profile twin of the
     * connector migration; the per-row policy is identical and each arm is locked on both
     * sides, because the two services are where a fix would otherwise land on one only.
     */
    private void migrateOneLegacyRow(CloudantClientWrapper wrapper,
            com.ibm.cloud.cloudant.v1.Cloudant cloudant,
            String dbName, com.ibm.cloud.cloudant.v1.model.Document legacy, String legacyId,
            String profileId, String deterministicId,
            ConnectorDefinitionService.LegacyIdMigrationResult result) {
        try {
            if (legacy.getAttachments() != null && !legacy.getAttachments().isEmpty()) {
                result.failures.add(profileId + " (the legacy row " + legacyId
                        + " carries attachments, which this migration does not copy; the"
                        + " row is left in place rather than migrated incompletely)");
                logger.error("Import profile row {} carries attachments and was NOT migrated",
                        legacyId);
                return;
            }
            com.ibm.cloud.cloudant.v1.model.Document existing =
                    readByDeterministicId(cloudant, dbName, profileId);
            boolean createdNow = false;
            if (existing == null) {
                Document copy = new Document();
                for (Map.Entry<String, Object> entry
                        : contentOnly(legacy.getProperties()).entrySet()) {
                    copy.put(entry.getKey(), entry.getValue());
                }
                copy.setId(deterministicId);
                PostDocumentOptions write = new PostDocumentOptions.Builder()
                        .db(dbName).document(copy).build();
                DocumentResult created;
                try {
                    created = cloudant.postDocument(write).execute().getResult();
                } catch (Exception firstAttempt) {
                    // A previously DELETED deterministic id leaves a tombstone that answers
                    // 409 to a create while the id-addressed read says "absent". Purge it
                    // once and retry; a false purge means the 409 was something else.
                    if (wrapper.purgeTombstone(deterministicId)) {
                        logger.info("Purged the tombstone of {}; retrying the copy of import"
                                + " profile {}", deterministicId, profileId);
                        created = cloudant.postDocument(write).execute().getResult();
                    } else {
                        throw firstAttempt;
                    }
                }
                if (created == null || !Boolean.TRUE.equals(created.isOk())) {
                    result.failures.add(profileId + " (the deterministic copy was not"
                            + " written: " + (created == null ? "no result" : created.getError())
                            + "); the legacy row is untouched");
                    logger.error("Migration of import profile {} failed at the copy step",
                            profileId);
                    return;
                }
                createdNow = true;
            } else if (!java.util.Objects.equals(contentOnly(legacy.getProperties()),
                    contentOnly(existing.getProperties()))) {
                // Choosing a winner silently destroys the other row's configuration — the
                // loss this migration exists to prevent — so NEITHER is touched.
                Object legacyRepo = legacy.getProperties() == null ? null
                        : legacy.getProperties().get("repositoryId");
                Object standingRepo = existing.getProperties() == null ? null
                        : existing.getProperties().get("repositoryId");
                if (!java.util.Objects.equals(legacyRepo, standingRepo)) {
                    // The two rows belong to DIFFERENT repositories. The row-addressed
                    // resolver counts within one repository, so it sees a single row on each
                    // side and refuses — prescribing it here prescribed the impossible. A
                    // review found the cross-repository form of a defect this migration's
                    // message was once fixed for.
                    result.divergent.add(profileId + " (legacy " + legacyId + " in repository "
                            + legacyRepo + " vs " + deterministicId + " in " + standingRepo
                            + "; the deterministic id has no repository in it, so only one"
                            + " repository can keep this profileId)");
                    logger.error("Import profile {} exists as {} in repository {} and as {}"
                            + " in {}. Neither row was touched, and only one repository can"
                            + " keep this profileId: as the administrator of the repository"
                            + " that should give it up, remove its rows with DELETE"
                            + " .../admin/import-profiles/{} (no docId).", profileId,
                            legacyId, legacyRepo, deterministicId, standingRepo, profileId);
                    return;
                }
                result.divergent.add(profileId + " (legacy " + legacyId + " vs "
                        + deterministicId + ")");
                logger.error("Import profile {} exists as BOTH {} and {} with DIFFERENT"
                        + " content. Neither row was touched. Resolve by deleting the row"
                        + " you do NOT want: DELETE .../admin/import-profiles/{}?docId=<one"
                        + " of the two ids above>", profileId, legacyId, deterministicId,
                        profileId);
                return;
            }
            cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions
                    .Builder().db(dbName).docId(legacyId).rev(legacy.getRev()).build())
                    .execute();
            if (createdNow) {
                result.migrated++;
                logger.info("Migrated import profile {} from generated id {} to {}",
                        profileId, legacyId, deterministicId);
            } else {
                result.sweptDuplicates++;
                logger.info("Retired leftover legacy row {} of import profile {} (identical"
                        + " deterministic twin already present)", legacyId, profileId);
            }
        } catch (Exception e) {
            // Includes a 409 on the conditional delete: a concurrent edit wins, both rows
            // stay, and the next pass reports them as divergent instead of the edit
            // vanishing.
            result.failures.add(profileId + " (" + e.getMessage() + ")");
            logger.error("Migration of import profile {} did not complete; it will retry on"
                    + " the next startup", profileId, e);
        }
    }

    /**
     * How many rows define this profile, read without the index. Never bounded to the
     * deterministic-id range: this count decides existence, and ABSENCE is the answer a
     * stale range verdict would get wrong. A bounded variant was added and then withdrawn —
     * nothing called it, and the ledger had claimed otherwise.
     */
    private int countProfileRowsIndexFree(com.ibm.cloud.cloudant.v1.Cloudant cloudant,
            String dbName, String profileId, String repositoryId) {
        int[] found = new int[1];
        java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow = row -> {
            String id = row.getId();
            if (row.getError() != null || id == null) {
                throw new IllegalStateException("the uniqueness of import profile '"
                        + profileId + "' cannot be established: a row of '" + dbName
                        + "' could not be read (" + (row.getError() != null
                                ? row.getError() : "no id") + ")");
            }
            if (id.startsWith("_design/")) {
                return;
            }
            Map<String, Object> props = row.getDoc() != null
                    ? row.getDoc().getProperties() : null;
            if (props == null) {
                throw new IllegalStateException("the uniqueness of import profile '"
                        + profileId + "' cannot be established: row " + id
                        + " came back without a body");
            }
            if (ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))
                    && profileId.equals(props.get("profileId"))
                    && (repositoryId == null || repositoryId.equals(props.get("repositoryId")))) {
                found[0]++;
            }
        };
        NemakiConfAllDocs.forEachRow(cloudant, dbName, perRow);
        return found[0];
    }

    @Override
    public ImportProfileDefinition getForRepository(String profileId, String repositoryId) {
        if (profileId == null || repositoryId == null) {
            return null;
        }
        CloudantClientWrapper client;
        try {
            client = getConfClient();
        } catch (RuntimeException couldNotAsk) {
            throw new ProfileIndexNotReadyException(couldNotAsk.getMessage());
        }
        String dbName = client.getDatabaseName();
        ImportProfileDefinition[] found = new ImportProfileDefinition[1];
        java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow = row -> {
            String id = row.getId();
            if (row.getError() != null || id == null) {
                throw new IllegalStateException("import profile " + profileId + " of repository"
                        + " '" + repositoryId + "' cannot be looked up: a row of '" + dbName
                        + "' could not be read");
            }
            if (id.startsWith("_design/")) {
                return;
            }
            Map<String, Object> props = row.getDoc() != null ? row.getDoc().getProperties() : null;
            if (props == null) {
                throw new IllegalStateException("import profile " + profileId + " of repository"
                        + " '" + repositoryId + "' cannot be looked up: row " + id
                        + " came back without a body");
            }
            // Only the row this call ASKS about is deserialised. The first version reused the
            // uniqueness listing, which reads every profile of the repository and refuses on
            // any it cannot deserialise — so one unrelated broken row (a newer node's value,
            // or the repositoryId-less row the migration deliberately leaves in place) made
            // every read of every profile answer 503. That is the connector over-throw this
            // batch fixed one round earlier, reintroduced here; a review caught it.
            if (!ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))
                    || !repositoryId.equals(props.get("repositoryId"))
                    || !profileId.equals(props.get("profileId"))) {
                return;
            }
            Map<String, Object> content = contentOnly(props);
            content.remove("type");
            if (found[0] != null) {
                // TWO rows of this profile in this repository. Returning either is the
                // silent choice this batch exists to prevent — and the caller authorises a
                // delete from what it returns, then deletes EVERY row of the repository, so
                // a delegated caller could be authorised by one twin and remove the other.
                // A review found the pair being picked from.
                throw new ProfileHasTwinRowsException("import profile " + profileId
                        + " has more than one definition row in repository '" + repositoryId
                        + "'; resolve the pair first (DELETE .../admin/import-profiles/"
                        + profileId + "?docId=...)");
            }
            try {
                found[0] = MAPPER.convertValue(content, ImportProfileDefinition.class);
            } catch (Exception e) {
                throw new IllegalStateException("import profile " + profileId + " of repository"
                        + " '" + repositoryId + "' cannot be looked up: row " + id
                        + " could not be read as a profile (" + e.getMessage() + ")");
            }
        };
        try {
            NemakiConfAllDocs.forEachRow(client.getClient(), dbName, perRow);
        } catch (IllegalStateException unprovable) {
            throw new ProfileIndexNotReadyException(unprovable.getMessage());
        }
        return found[0];
    }

    @Override
    public ImportProfileDefinition getOwnedRowIndexFree(String profileId) {
        if (profileId == null) {
            return null;
        }
        CloudantClientWrapper client;
        try {
            client = getConfClient();
        } catch (RuntimeException couldNotAsk) {
            throw new ProfileIndexNotReadyException(couldNotAsk.getMessage());
        }
        String dbName = client.getDatabaseName();
        ImportProfileDefinition[] found = new ImportProfileDefinition[1];
        java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow = row -> {
            String id = row.getId();
            if (row.getError() != null || id == null) {
                throw new IllegalStateException("import profile " + profileId
                        + " cannot be looked up: a row of '" + dbName
                        + "' could not be read");
            }
            if (id.startsWith("_design/")) {
                return;
            }
            Map<String, Object> props = row.getDoc() != null ? row.getDoc().getProperties() : null;
            if (props == null) {
                throw new IllegalStateException("import profile " + profileId
                        + " cannot be looked up: row " + id + " came back without a body");
            }
            if (!ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))
                    || !profileId.equals(props.get("profileId"))
                    || isBlank(props.get("repositoryId"))) {
                // Blank, not only null: a blank row belongs to no repository either, and
                // treating it as owned would start a capture on a row no repository can
                // manage.
                return;
            }
            Map<String, Object> content = contentOnly(props);
            content.remove("type");
            if (found[0] != null) {
                throw new ProfileHasTwinRowsException("import profile " + profileId
                        + " has more than one owned definition row; resolve the pair before"
                        + " starting a capture that is keyed by profileId alone");
            }
            try {
                found[0] = MAPPER.convertValue(content, ImportProfileDefinition.class);
            } catch (Exception e) {
                throw new IllegalStateException("import profile " + profileId
                        + " cannot be looked up: row " + id
                        + " could not be read as a profile (" + e.getMessage() + ")");
            }
        };
        try {
            NemakiConfAllDocs.forEachRow(client.getClient(), dbName, perRow);
        } catch (IllegalStateException unprovable) {
            throw new ProfileIndexNotReadyException(unprovable.getMessage());
        }
        return found[0];
    }

    @Override
    public boolean existsIndexFree(String profileId, String repositoryId) {
        if (profileId == null || repositoryId == null) {
            return false;
        }
        CloudantClientWrapper client;
        try {
            client = getConfClient();
        } catch (RuntimeException couldNotAsk) {
            throw new ProfileIndexNotReadyException(couldNotAsk.getMessage());
        }
        try {
            // Confined to the caller's repository: a row hidden in repository B must not
            // turn repository A's 404 into a 503 — that discloses B's row exists, which
            // the controllers' cross-repository rule forbids. A review caught the blind
            // spot after the gate landed.
            return countProfileRowsIndexFree(client.getClient(), client.getDatabaseName(),
                    profileId, repositoryId) > 0;
        } catch (IllegalStateException unprovable) {
            // The caller is deciding between "404" and "503, retry": a row it cannot read
            // is the second, never the first.
            throw new ProfileIndexNotReadyException(unprovable.getMessage());
        }
    }

    @Override
    public List<ImportProfileDefinition> listScheduledIndexFree() {
        // The rows the walk could not read are logged by it and not reported further here:
        // the scheduler has no caller to answer, and refusing the poll would let one broken
        // row stop every scheduled capture.
        return listOwnedRowsIndexFree("the scheduled profiles", "scheduled-profile enumeration",
                "is not being scheduled", def -> def.isEnabled() && def.isSchedulerEnabled())
                .profiles();
    }

    @Override
    public OwnedProfiles listOwnedIndexFree() {
        return listOwnedRowsIndexFree("the profiles", "profile enumeration",
                "is not being listed", def -> true);
    }

    /**
     * The one walk behind {@link #listScheduledIndexFree()} and {@link #listOwnedIndexFree()}.
     * Two copies of the per-row policy is how the webhook side would have kept a skip the
     * scheduler side had fixed — so the policy lives once and each caller names only its
     * filter and the words its messages use.
     *
     * @param what      the noun the refusal names ("the scheduled profiles cannot be listed")
     * @param context   the log prefix that lets an operator find a skipped row
     * @param consequence what the skip means for the row ("is not being scheduled")
     */
    private OwnedProfiles listOwnedRowsIndexFree(String what, String context,
            String consequence, java.util.function.Predicate<ImportProfileDefinition> wanted) {
        CloudantClientWrapper client;
        try {
            client = getConfClient();
        } catch (RuntimeException couldNotAsk) {
            throw new ProfileIndexNotReadyException(couldNotAsk.getMessage());
        }
        String dbName = client.getDatabaseName();
        List<ImportProfileDefinition> listed = new ArrayList<>();
        List<UninterpretableRow> uninterpretable = new ArrayList<>();
        java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow = row -> {
            String id = row.getId();
            if (row.getError() != null || id == null) {
                throw new IllegalStateException(what + " cannot be listed: a row"
                        + " of '" + dbName + "' could not be read ("
                        + (row.getError() != null ? row.getError() : "no id") + ")");
            }
            if (id.startsWith("_design/")) {
                return;
            }
            Map<String, Object> props = row.getDoc() != null
                    ? row.getDoc().getProperties() : null;
            if (props == null) {
                throw new IllegalStateException(what + " cannot be listed: row "
                        + id + " came back without a body");
            }
            if (!ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))
                    || isBlank(props.get("repositoryId"))) {
                return;
            }
            Map<String, Object> content = contentOnly(props);
            content.remove("type");
            ImportProfileDefinition def;
            try {
                def = MAPPER.convertValue(content, ImportProfileDefinition.class);
            } catch (Exception e) {
                // Standing, not transient: no poll repairs this row, and refusing the whole
                // enumeration would let one broken row stop every scheduled capture. Named so
                // an operator can find it — and reported to the caller with the raw fields
                // that say whom it was addressed to, so a caller with someone to answer does
                // not answer "none" for a recipient it could not read.
                logger.error("{}: row {} could not be read as a profile and {} ({})",
                        context, id, consequence, e.getMessage());
                reportUninterpretable(uninterpretable, id, props, e.getMessage());
                return;
            }
            if (def.getProfileId() == null || def.getProfileId().isBlank()) {
                // Deserialisable but with no identity. The per-row skip above only caught
                // rows that FAILED to deserialise; this one entered the schedule, and the
                // delegated tick then put a null id into a ConcurrentHashMap key set — an NPE
                // that escaped to the poll's outer catch and stopped every profile after it.
                // A review traced the second shape of "one broken row".
                logger.error("{}: row {} has no profileId and {}", context, id, consequence);
                reportUninterpretable(uninterpretable, id, props, "the row has no profileId");
                return;
            }
            if (wanted.test(def)) {
                listed.add(def);
            }
        };
        try {
            NemakiConfAllDocs.forEachRow(client.getClient(), dbName, perRow);
        } catch (IllegalStateException unprovable) {
            throw new ProfileIndexNotReadyException(unprovable.getMessage());
        } catch (RuntimeException transportFailed) {
            throw new ProfileIndexNotReadyException(transportFailed.getMessage());
        }
        return new OwnedProfiles(listed, uninterpretable);
    }

    /**
     * Records a row the walk could not read, with the raw fields a caller needs to tell
     * whether the row was addressed to it. A row whose raw {@code enabled} says disabled
     * (the literal {@code false} or the string) is not recorded: it could not have been a
     * recipient of anything.
     */
    private static void reportUninterpretable(List<UninterpretableRow> sink, String docId,
            Map<String, Object> props, String reason) {
        if (isRawDisabled(props.get("enabled"))) {
            return;
        }
        Object defaultConnector = props.get("defaultConnectorId");
        Object allowedConnectors = props.get("allowedConnectorIds");
        // A connector field that is there but not in a readable shape leaves the addressee
        // unknown — the row then names every connector, not none. A review found the first
        // version reading such a field as "names nobody", which is the skip this record
        // exists to prevent, one field down.
        boolean addresseeUnknown = (defaultConnector != null && !(defaultConnector instanceof String))
                || (allowedConnectors != null && !isListOfStrings(allowedConnectors));
        sink.add(new UninterpretableRow(docId, rawString(props.get("profileId")),
                rawString(defaultConnector), rawStrings(allowedConnectors), addresseeUnknown,
                reason));
    }

    /**
     * The literal {@code false}, or the string {@code "false"} — the same two shapes the
     * connector listing reads as disabled. A row this node cannot interpret has no other
     * reader to normalise the string, so the raw check has to accept both; an absent value
     * is not a "no".
     */
    private static boolean isRawDisabled(Object enabled) {
        return Boolean.FALSE.equals(enabled)
                || "false".equalsIgnoreCase(String.valueOf(enabled));
    }

    private static String rawString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static boolean isListOfStrings(Object value) {
        if (!(value instanceof List<?> values)) {
            return false;
        }
        for (Object v : values) {
            if (!(v instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> rawStrings(Object value) {
        if (!isListOfStrings(value)) {
            return null;
        }
        List<String> strings = new ArrayList<>();
        for (Object v : (List<?>) value) {
            strings.add((String) v);
        }
        return strings;
    }

    /** Null, or a value whose text is empty — a row that names no repository either way. */
    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String && ((String) value).isBlank());
    }

    /** An id-addressed read of the deterministic document id; needs no index. */
    private com.ibm.cloud.cloudant.v1.model.Document readByDeterministicId(
            com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName, String profileId) {
        try {
            return cloudant.getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions
                    .Builder().db(dbName)
                    .docId(ImportProfileDefinition.DOC_TYPE + ":" + profileId).build())
                    .execute().getResult();
        } catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
            return null;
        }
    }

    @Override
    public boolean exists(String profileId) {
        return get(profileId) != null;
    }

    @Override
    public ImportProfileDefinition findDefaultForRepository(String repositoryId, SourceArchetype archetype, String connectorId) {
        if (repositoryId == null) return null;
        // Index-free. This decides WHERE ingested content lands; a selector hiding the
        // intended default while a fallback stayed visible sent content under the wrong
        // profile during an index rebuild — silently, with a successful import. Unreadable
        // rows refuse (retryably) rather than resolve to whatever was visible.
        // The WHOLE config database, every time. A bounded walk over the deterministic-id
        // range was built here for cost and withdrawn after three review rounds found it
        // unsound in three different ways: it could not see legacy rows at all; its verdict
        // (a clean migration pass) goes stale during a rolling upgrade; and — the one that
        // ended it — a bounded walk that FINDS something is still incomplete, so the
        // "two defaults refuse" rule silently stopped applying when the second default was
        // outside the range. Completeness is what these rules are made of.
        //
        // Cost, recorded rather than hidden: nemaki_conf also accumulates ingest-job and
        // dead-letter records, so this is one paged walk of that whole database per
        // auto-resolved import. The follow-up that would make it cheap without weakening it
        // is to stop storing job records in the configuration database.
        List<ImportProfileDefinition> candidates = listByRepositoryIndexFree(repositoryId, false);
        // First pass: prefer profiles where this connector is the explicit default
        List<ImportProfileDefinition> byConnector = candidates.stream()
                .filter(p -> p.isEnabled() && p.isArchetypeAllowed(archetype) && p.isConnectorAllowed(connectorId)
                        && connectorId != null && connectorId.equals(p.getDefaultConnectorId()))
                .toList();
        if (byConnector.size() == 1) return byConnector.get(0);
        if (byConnector.size() > 1) {
            throw new IllegalStateException("Ambiguous auto-resolve: " + byConnector.size()
                    + " profiles claim defaultConnectorId=" + connectorId + " in repository " + repositoryId
                    + " — set defaultConnectorId on exactly one profile");
        }

        // Second pass: prefer profiles marked as defaultProfile
        List<ImportProfileDefinition> byDefault = candidates.stream()
                .filter(p -> p.isEnabled() && p.isDefaultProfile()
                        && p.isArchetypeAllowed(archetype) && p.isConnectorAllowed(connectorId))
                .toList();
        if (byDefault.size() == 1) return byDefault.get(0);
        if (byDefault.size() > 1) {
            throw new IllegalStateException("Ambiguous auto-resolve: " + byDefault.size()
                    + " profiles have defaultProfile=true in repository " + repositoryId
                    + " — set defaultProfile on exactly one profile");
        }
        // Third pass: any compatible profile — require exactly one match for determinism
        List<ImportProfileDefinition> fallbacks = candidates.stream()
                .filter(p -> p.isEnabled() && p.isArchetypeAllowed(archetype) && p.isConnectorAllowed(connectorId))
                .toList();
        if (fallbacks.size() == 1) {
            return fallbacks.get(0);
        }
        if (fallbacks.size() > 1) {
            throw new IllegalStateException(
                    "Ambiguous auto-resolve: " + fallbacks.size() + " profiles ("
                    + fallbacks.stream().map(ImportProfileDefinition::getProfileId).toList()
                    + ") match connector " + connectorId + " in repository " + repositoryId
                    + " — set defaultProfile=true or defaultConnectorId on exactly one profile");
        }
        return null;
    }

    private static final int MAX_ID_LENGTH = 255;

    private void validateRequiredFields(ImportProfileDefinition def, boolean creating) {
        if (def.getProfileId() == null || def.getProfileId().isBlank()) {
            throw new IllegalArgumentException("profileId is required");
        }
        if (def.getProfileId().length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("profileId exceeds max length of " + MAX_ID_LENGTH);
        }
        if (def.getRepositoryId() == null || def.getRepositoryId().isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        boolean hasFolderId = def.getTargetFolderId() != null && !def.getTargetFolderId().isBlank();
        boolean hasFolderPath = def.getTargetFolderPath() != null && !def.getTargetFolderPath().isBlank();
        if (!hasFolderId && !hasFolderPath) {
            throw new IllegalArgumentException("Either targetFolderId or targetFolderPath is required");
        }
        // Validate scheduler-enabled profiles have required source-scope and connector params.
        // Manual/import-only profiles (schedulerEnabled=false) are not validated for scope params
        // since they don't participate in scheduled fetches or webhook dispatch.
        if (def.isSchedulerEnabled()) {
            validateSchedulerParams(def);
        }
        // Validate auto-resolve uniqueness invariants at save time (only for enabled profiles)
        if (def.isEnabled()) {
            validateAutoResolveUniqueness(def, creating);
        }
    }

    /**
     * Prevents saving profiles that would cause ambiguous auto-resolve at runtime.
     */
    private void validateAutoResolveUniqueness(ImportProfileDefinition def, boolean creating) {
        String repoId = def.getRepositoryId();
        if (repoId == null) return;
        // Through the walk, not the selector. This validation guards "one default profile /
        // one default connector per repository", and a selector that answers empty while
        // its index rebuilds let a second default through — the auto-resolution then
        // throws ambiguity once the index recovers. The scan that closed the profileId
        // door does not look at these fields, so this door had to be closed on its own.
        // A review found it after the first closure.
        // Only the rule's own fields are interpreted: a row whose other fields this node
        // cannot read still counts for the rule and no longer blocks the write.
        List<ImportProfileDefinition> existing = listByRepositoryIndexFree(repoId, creating,
                UNIQUENESS_RULE_FIELDS);

        // Check defaultConnectorId uniqueness
        if (def.getDefaultConnectorId() != null && !def.getDefaultConnectorId().isBlank()) {
            for (ImportProfileDefinition other : existing) {
                if (other.getProfileId().equals(def.getProfileId())) continue;
                if (def.getDefaultConnectorId().equals(other.getDefaultConnectorId())
                        && other.isEnabled()) {
                    throw new IllegalArgumentException(
                            "Profile '" + other.getProfileId() + "' already claims defaultConnectorId='"
                            + def.getDefaultConnectorId() + "' in repository '" + repoId
                            + "'. Only one enabled profile per defaultConnectorId is allowed.");
                }
            }
        }
        // Check defaultProfile uniqueness
        if (def.isDefaultProfile()) {
            for (ImportProfileDefinition other : existing) {
                if (other.getProfileId().equals(def.getProfileId())) continue;
                if (other.isDefaultProfile() && other.isEnabled()) {
                    throw new IllegalArgumentException(
                            "Profile '" + other.getProfileId() + "' already has defaultProfile=true in repository '"
                            + repoId + "'. Only one enabled default profile per repository is allowed.");
                }
            }
        }
    }

    /**
     * Validates that scheduler-enabled profiles reference a usable connector
     * and that adapter-specific required parameters are present in schedulerParams.
     */
    private void validateSchedulerParams(ImportProfileDefinition def) {
        String defaultConnectorId = def.getDefaultConnectorId();
        if (defaultConnectorId == null || defaultConnectorId.isBlank()) {
            throw new IllegalArgumentException(
                    "schedulerEnabled profiles require a defaultConnectorId to determine the fetch adapter");
        }
        if (connectorDefinitionService != null) {
            ConnectorDefinition connector = connectorDefinitionService.get(defaultConnectorId);
            if (connector == null) {
                throw new IllegalArgumentException(
                        "defaultConnectorId '" + defaultConnectorId + "' does not exist");
            }
            if (!connector.isEnabled()) {
                throw new IllegalArgumentException(
                        "defaultConnectorId '" + defaultConnectorId + "' is disabled");
            }
            if (!def.isConnectorAllowed(connector.getConnectorId())) {
                throw new IllegalArgumentException(
                        "defaultConnectorId '" + defaultConnectorId + "' is not in allowedConnectorIds");
            }
            if (!def.isArchetypeAllowed(connector.getSourceArchetype())) {
                throw new IllegalArgumentException(
                        "Connector archetype '" + connector.getSourceArchetype()
                                + "' is not in profile's allowedArchetypes");
            }
            // Adapter-specific required parameters
            validateAdapterRequiredParams(connector.getSourceSystem(), def.getSchedulerParams());
        }
    }

    /**
     * Validates adapter-specific required schedulerParams based on sourceSystem.
     * Only checks params that have no meaningful default — adapters like IMAP, Gmail,
     * M365, Notion, Salesforce work with built-in defaults.
     */
    /**
     * Validate adapter-specific required schedulerParams using the
     * {@link AdapterRegistry} as the single source of truth.
     */
    private void validateAdapterRequiredParams(String sourceSystem, Map<String, String> params) {
        if (sourceSystem == null) return;
        AdapterDescriptor descriptor = AdapterRegistry.get(sourceSystem);
        if (descriptor == null) {
            // Unknown adapter — warn but don't block (forward-compatible)
            logger.warn("Unknown sourceSystem '{}' — no parameter validation applied", sourceSystem);
            return;
        }
        var errors = descriptor.validateParams(params);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Collects non-fatal configuration warnings for a profile (exposed via REST API).
     */
    public List<String> collectWarnings(ImportProfileDefinition def) {
        List<String> warnings = new ArrayList<>();
        if (def.isSchedulerEnabled()) {
            Map<String, String> params = def.getSchedulerParams();
            if (params == null || params.isEmpty()) {
                warnings.add("schedulerEnabled is on but schedulerParams is empty — adapters will use default scope");
            }
        }
        return warnings;
    }

    // --- Internal ---

    @SuppressWarnings("unchecked")
    private void upsertDocument(ImportProfileDefinition def, boolean creating) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        Map<String, Object> jsonMap = MAPPER.convertValue(def, Map.class);
        jsonMap.put("type", ImportProfileDefinition.DOC_TYPE);

        Document doc = new Document();
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            doc.put(entry.getKey(), entry.getValue());
        }

        List<com.ibm.cloud.cloudant.v1.model.Document> existing = findRawDocs(cloudant, dbName,
                Map.of("type", ImportProfileDefinition.DOC_TYPE, "profileId", def.getProfileId()));
        // The index-free count runs on EVERY write, before any door is chosen. The
        // selector can be PARTIAL — one twin visible, another hidden while the index
        // rebuilds — and the first version consulted the walk only when the selector
        // returned nothing, so an update adopted the visible twin without knowing the
        // hidden one existed and the pair diverged silently. A review caught it in round 2.
        int rowsDefiningThisProfile;
        try {
            rowsDefiningThisProfile = countProfileRowsIndexFree(cloudant, dbName,
                    def.getProfileId(), null);
        } catch (IllegalStateException unprovable) {
            // The scan could not CLASSIFY a row, so uniqueness is unprovable right now. A
            // create keeps the existing contract (IllegalStateException → 400, locked); an
            // update is refused retryably (503) — the condition is as transient as an index
            // rebuild.
            if (creating) {
                throw unprovable;
            }
            throw new ProfileIndexNotReadyException(unprovable.getMessage());
        }
        if (rowsDefiningThisProfile > 1) {
            // Two or more rows define this profile — a standing pair a rebuilding index once
            // created. Decided on the COUNT alone, before the index is consulted: the first
            // version compared the count with what the selector showed and so refused only
            // HIDDEN twins; a visible pair went on to adopt existing.get(0) and answered 200,
            // choosing a winner silently — the loss the migration refuses to resolve on its
            // own. A parallel review caught it; a second one caught the arm being evaluated
            // AFTER the hidden arm, which answered "retry" for a pair the count had already
            // established. Not a 503: no retry makes a standing twin go away, an
            // administrator does.
            // The count above is GLOBAL and ?docId= is confined to the caller's repository,
            // so naming it unconditionally told the caller to run something that would be
            // refused — the two commonest shapes of this 409 are exactly the ones it cannot
            // repair. The advice is derived from where the rows actually are. A review found
            // the same mismatch a third time.
            String repair = repairAdvice(cloudant, dbName, def.getProfileId(),
                    def.getRepositoryId(), rowsDefiningThisProfile);
            if (creating) {
                throw new IllegalStateException("Import profile already exists: "
                        + def.getProfileId() + " (" + rowsDefiningThisProfile
                        + " definition rows. " + repair + ")");
            }
            throw new ProfileHasTwinRowsException("import profile " + def.getProfileId()
                    + " has " + rowsDefiningThisProfile + " definition rows; an update would"
                    + " write to one of them and choose a winner silently. " + repair);
        }
        if (rowsDefiningThisProfile > existing.size()) {
            // rows <= 1 here: every pair was taken by the arm above, so this is the ONE row
            // the selector did not show — hidden while the index rebuilds, i.e. transient.
            // Rows the selector did not show. For a CREATE that is a duplicate whatever the
            // index says; for an UPDATE it is a write that would land on one twin while
            // another stays invisible — refused retryably until the index shows them all.
            if (creating) {
                // Name the row honestly: this arm runs BEFORE the id-addressed read, so the
                // hidden row is as often the deterministic one (index rebuilding on a
                // migrated installation) as a legacy one. A review found the old wording
                // reporting "legacy row awaiting migration" on every such startup.
                // Three-valued on purpose: a FAILED id read establishes nothing about
                // where the row lives, and calling it "legacy" was a stronger claim than
                // the scan supports. A review named it.
                String whereItLives;
                try {
                    whereItLives = readByDeterministicId(cloudant, dbName, def.getProfileId()) != null
                            ? "the row exists under its deterministic id and the index has"
                                    + " not caught up"
                            : "a legacy row awaiting migration";
                } catch (RuntimeException unreadable) {
                    whereItLives = "the id-addressed read could not say whether it is a legacy"
                            + " row or a deterministic one (" + unreadable.getMessage() + ")";
                }
                throw new IllegalStateException("Import profile already exists: "
                        + def.getProfileId() + " (found by an index-free scan; the"
                        + " selector did not report it — " + whereItLives + ")");
            }
            throw new ProfileIndexNotReadyException("import profile " + def.getProfileId()
                    + " has " + rowsDefiningThisProfile + " definition row(s) but the"
                    + " rebuilding index shows " + existing.size() + "; writing now would"
                    + " land on one twin while another stays hidden. Retry once the index"
                    + " has caught up.");
        }
        if (rowsDefiningThisProfile < existing.size()) {
            // The SELECTOR reported more rows than the authoritative walk found. One of the
            // two reads is stale (they are separate requests, not one snapshot), so which
            // rows define this import profile is not established — and the next line would write to
            // existing.get(0), a row the walk says is not there. Refused retryably on both
            // arms: a retry reads both again and one of them wins honestly.
            throw new ProfileIndexNotReadyException("import profile " + def.getProfileId()
                    + ": the index listed " + existing.size() + " definition row(s) but an"
                    + " index-free scan found " + rowsDefiningThisProfile + "; the two reads disagree, so"
                    + " which row to write to is not established. Retry shortly.");
        }
        if (creating && rowsDefiningThisProfile > 0) {
            // A CREATE never adopts a row. create() checks existence through the selector
            // first, but that check and this write are separate requests: two concurrent
            // creates both passed it, and the slower one then overwrote the faster one's
            // configuration and reported 201. A review caught it. The count is index-free,
            // so this refusal holds while the index rebuilds.
            throw new IllegalStateException("Import profile already exists: " + def.getProfileId()
                    + " (an index-free scan found " + rowsDefiningThisProfile + " definition row(s); a"
                    + " create never overwrites one)");
        }
        if (!existing.isEmpty()) {
            doc.setId(existing.get(0).getId());
            doc.setRev(existing.get(0).getRev());
        } else {
            com.ibm.cloud.cloudant.v1.model.Document deterministic =
                    readByDeterministicId(cloudant, dbName, def.getProfileId());
            if (deterministic != null) {
                // Belt over braces: the count above already refused this case (a hidden
                // deterministic row is a row the selector did not show), and the
                // id-addressed read is cheap insurance against a walk that answered zero
                // rows through a bug rather than an empty database.
                if (creating) {
                    throw new IllegalStateException("Import profile already exists: "
                            + def.getProfileId() + " (found by an id-addressed read; the"
                            + " index did not report it)");
                }
                throw new ProfileIndexNotReadyException("import profile " + def.getProfileId()
                        + " exists under its deterministic id but the index did not report"
                        + " it. Retry once the index has caught up.");
            }
            // A DETERMINISTIC id for anything created from here on. The first version of
            // this class saved under a CouchDB-GENERATED id whenever the selector missed,
            // which is exactly how Patch_DefaultCloudDriveConnectorProfile creates a second
            // cloud-import-{repo} while an index rebuilds at startup.
            doc.setId(ImportProfileDefinition.DOC_TYPE + ":" + def.getProfileId());
        }

        PostDocumentOptions options = new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build();
        DocumentResult result = cloudant.postDocument(options).execute().getResult();
        if (!result.isOk()) {
            throw new IllegalStateException("Failed to save import profile " + def.getProfileId() + ": " + result.getError());
        }
    }

    /** The fields the auto-resolve uniqueness rule reads; nothing else in a row concerns it. */
    private static final java.util.Set<String> UNIQUENESS_RULE_FIELDS = java.util.Set.of(
            "profileId", "defaultConnectorId", "enabled", "defaultProfile");

    private List<ImportProfileDefinition> listByRepositoryIndexFree(String repositoryId,
            boolean creating) {
        return listByRepositoryIndexFree(repositoryId, creating, null);
    }

    /**
     * Every ENABLED profile of {@code repositoryId}, read through the shared walk so the
     * answer holds while the Mango index rebuilds. A row that cannot be classified refuses —
     * with the same create/update type-split as the uniqueness scan — because a uniqueness
     * rule checked against a list that silently dropped a row is not a rule.
     *
     * <p>{@code onlyFields}, when given, is the whole of what each row is interpreted for;
     * the rest of the row is not read. A row whose OTHER fields this node cannot interpret —
     * a value a newer node wrote during a rolling upgrade, or a corrupt one — then neither
     * refuses the caller nor slips past its rule. Before this, one such row stopped every
     * create (400) and update (503) of its repository, over fields the rule never looks at.
     * Null interprets the whole row, which the auto-resolver needs because it returns it.
     *
     * <p>A row that says {@code enabled: false} — the literal or the string, the two shapes
     * the connector listing reads as disabled — is not interpreted either way. Every caller's
     * rule applies to enabled rows only, so it cannot change an answer — and refusing on a
     * disabled row this node cannot read stopped the auto-resolution of a whole repository
     * over a row that could not have been chosen. (The first version read only the literal
     * and left the string to Jackson — which never runs on the row that matters here, the
     * one Jackson cannot read. A review found it.) An absent value is not a "no".
     */
    private List<ImportProfileDefinition> listByRepositoryIndexFree(String repositoryId,
            boolean creating, java.util.Set<String> onlyFields) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();
        List<ImportProfileDefinition> results = new ArrayList<>();
        java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow = row -> {
                String id = row.getId();
                if (row.getError() != null || id == null) {
                    throw new IllegalStateException("the profiles of repository '"
                            + repositoryId + "' cannot be listed: a row of '" + dbName
                            + "' could not be read (" + (row.getError() != null
                                    ? row.getError() : "no id") + ")");
                }
                if (id.startsWith("_design/")) {
                    return;
                }
                Map<String, Object> props = row.getDoc() != null
                        ? row.getDoc().getProperties() : null;
                if (props == null) {
                    throw new IllegalStateException("the profiles of repository '"
                            + repositoryId + "' cannot be listed: row " + id
                            + " came back without a body");
                }
                if (!ImportProfileDefinition.DOC_TYPE.equals(props.get("type"))
                        || !repositoryId.equals(props.get("repositoryId"))) {
                    return;
                }
                if (isRawDisabled(props.get("enabled"))) {
                    return;
                }
                // After the disabled skip, not before it: a disabled row with no identity
                // cannot be chosen either, and refusing on it would stop the repository over
                // a row no rule can read anything from. A review found the order.
                Object pid = props.get("profileId");
                if (!(pid instanceof String) || ((String) pid).isBlank()) {
                    // Deserialisable, but with no identity: the uniqueness comparison
                    // dereferenced it and answered 500. A profile row without a profileId
                    // is a row this rule cannot reason about.
                    throw new IllegalStateException("the profiles of repository '"
                            + repositoryId + "' cannot be listed: row " + id
                            + " has no usable profileId");
                }
                Map<String, Object> content = contentOnly(props);
                content.remove("type");
                if (onlyFields != null) {
                    content.keySet().retainAll(onlyFields);
                }
                try {
                    results.add(MAPPER.convertValue(content, ImportProfileDefinition.class));
                } catch (Exception e) {
                    // Not the findBySelector skip: a profile that cannot be deserialised is
                    // still a profile for the uniqueness rule, and dropping it here is how
                    // a second default gets past it.
                    throw new IllegalStateException("the profiles of repository '"
                            + repositoryId + "' cannot be listed: row " + id
                            + " could not be read as a profile (" + e.getMessage() + ")");
                }
            };
        try {
            NemakiConfAllDocs.forEachRow(cloudant, dbName, perRow);
        } catch (IllegalStateException unprovable) {
            if (creating) {
                throw unprovable;
            }
            throw new ProfileIndexNotReadyException(unprovable.getMessage());
        }
        return results;
    }

    /**
     * The sentence that tells the caller of a twin-row 409 what will actually work. The
     * row-addressed delete is confined to the caller's repository, so it repairs a pair only
     * when both rows are HERE; rows in another repository are that repository's to remove,
     * and rows that name no repository are reachable only by their docId.
     */
    private String repairAdvice(com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName,
            String profileId, String repositoryId, int rowsAnywhere) {
        int here;
        try {
            here = repositoryId == null ? 0
                    : countProfileRowsIndexFree(cloudant, dbName, profileId, repositoryId);
        } catch (RuntimeException couldNotAsk) {
            // Advice, not a decision: an unanswerable count must not turn a 409 into a 500.
            return "Some of the rows may be in another repository; list them before deleting.";
        }
        if (here > 1) {
            return "Delete the unwanted row first (DELETE .../admin/import-profiles/"
                    + profileId + "?docId=...).";
        }
        // There was a third arm here for "here == rowsAnywhere". It cannot happen: this
        // method is called only when rowsAnywhere > 1, and here > 1 has already returned, so
        // here <= 1 < rowsAnywhere always. A review found the dead arm; an unreachable branch
        // is a claim nothing can measure.
        return "The other " + (rowsAnywhere - here) + " row(s) are not in this repository:"
                + " another repository's administrator must delete theirs (DELETE without"
                + " docId), and a row that belongs to no repository is removed by its docId.";
    }

    @SuppressWarnings("unchecked")
    private List<ImportProfileDefinition> findBySelector(Map<String, Object> selector) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        List<com.ibm.cloud.cloudant.v1.model.Document> rawDocs = findRawDocs(cloudant, dbName, selector);
        List<ImportProfileDefinition> results = new ArrayList<>();
        for (com.ibm.cloud.cloudant.v1.model.Document rawDoc : rawDocs) {
            try {
                Map<String, Object> props = new HashMap<>(rawDoc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, ImportProfileDefinition.class));
            } catch (Exception e) {
                logger.warn("Failed to deserialize import profile: {}", e.getMessage());
            }
        }
        return results;
    }

    /**
     * Every row the selector shows — all of its pages, not the first one. The single page
     * this used to return capped every selector listing at 200 rows without saying so.
     *
     * <p>A listing that cannot be completed is the typed refusal (503 at the controllers),
     * not a bare {@code IllegalStateException} — which the create path answers 400 for.
     */
    private List<com.ibm.cloud.cloudant.v1.model.Document> findRawDocs(
            com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName, Map<String, Object> selector) {
        try {
            return NemakiConfFind.allMatching(cloudant, dbName, selector);
        } catch (IllegalStateException incomplete) {
            throw new ProfileIndexNotReadyException(incomplete.getMessage());
        } catch (RuntimeException transportFailed) {
            // The SDK's own failure (a reset, a 5xx) is a listing that could not be
            // completed too. Left raw it escaped the controllers' handlers as a 500 while
            // the three refusals above answered 503. A review found the fourth shape. This
            // arm takes every RuntimeException, a programming error included, and the
            // admin @ExceptionHandlers do not log — so the cause is kept here, where the
            // container's stack trace used to be. (get()'s fallback records the same failure
            // at DEBUG: one failure, two lines, the stack trace only here.)
            logger.warn("the selector listing of '{}' could not be read", dbName, transportFailed);
            throw new ProfileIndexNotReadyException("the selector listing of '" + dbName
                    + "' could not be read: " + transportFailed.getMessage());
        }
    }

    private CloudantClientWrapper getConfClient() {
        CloudantClientWrapper client = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (client == null) {
            throw new IllegalStateException("nemaki_conf database client not available");
        }
        return client;
    }
}
