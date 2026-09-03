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

public class ConnectorDefinitionServiceImpl implements ConnectorDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectorDefinitionServiceImpl.class);
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();

    private CloudantClientPool connectorPool;

    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    @Override
    public ConnectorDefinition create(ConnectorDefinition def) {
        validateRequiredFields(def);
        if (exists(def.getConnectorId())) {
            throw new IllegalStateException("Connector already exists: " + def.getConnectorId());
        }
        String now = Instant.now().toString();
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        upsertDocument(def, true);
        logger.info("Created connector definition: {}", def.getConnectorId());
        return def;
    }

    @Override
    public ConnectorDefinition get(String connectorId) {
        // Null means "no such connector", not a crash: Map.of rejects null values with an NPE,
        // so an ingest request that simply omits connectorId used to answer 500 with a stack
        // trace, while a WRONG id answered a clean 404. Callers already treat null as
        // not-found. findBySystemAndArchetype below has guarded this way all along.
        if (connectorId == null) return null;
        List<ConnectorDefinition> results = findBySelector(Map.of(
                "type", ConnectorDefinition.DOC_TYPE,
                "connectorId", connectorId));
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<ConnectorDefinition> list() {
        return findBySelector(Map.of("type", ConnectorDefinition.DOC_TYPE));
    }

    @Override
    public List<ConnectorDefinition> listByArchetype(SourceArchetype archetype) {
        return findBySelector(Map.of(
                "type", ConnectorDefinition.DOC_TYPE,
                "sourceArchetype", archetype.name()));
    }

    @Override
    public ConnectorDefinition update(ConnectorDefinition def) {
        validateRequiredFields(def);
        def.setUpdatedAt(Instant.now().toString());
        upsertDocument(def, false);
        logger.info("Updated connector definition: {}", def.getConnectorId());
        return def;
    }

    @Override
    public void delete(String connectorId) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        List<com.ibm.cloud.cloudant.v1.model.Document> docs = findRawDocs(cloudant, dbName,
                Map.of("type", ConnectorDefinition.DOC_TYPE, "connectorId", connectorId));
        for (com.ibm.cloud.cloudant.v1.model.Document doc : docs) {
            cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                    .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();
        }
        logger.info("Deleted connector definition: {}", connectorId);
    }

    @Override
    public void delete(String connectorId, String docId) {
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
        if (props == null
                || !ConnectorDefinition.DOC_TYPE.equals(props.get("type"))
                || !connectorId.equals(props.get("connectorId"))) {
            // An id-addressed delete with the WRONG target is worse than the divergence it
            // resolves: refusing is the only answer that cannot destroy an unrelated row.
            throw new IllegalArgumentException("row " + docId + " is not a definition of"
                    + " connector " + connectorId + "; refusing to delete it");
        }
        cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions
                .Builder().db(dbName).docId(docId).rev(row.getRev()).build()).execute();
        logger.info("Deleted connector definition row {} of {}", docId, connectorId);
    }

    @Override
    public boolean exists(String connectorId) {
        return get(connectorId) != null;
    }

    @Override
    public ConnectorDefinition findBySystemAndArchetype(String sourceSystem, SourceArchetype archetype) {
        if (sourceSystem == null || archetype == null) return null;
        List<ConnectorDefinition> candidates = findBySelector(Map.of(
                "type", ConnectorDefinition.DOC_TYPE,
                "sourceSystem", sourceSystem,
                "sourceArchetype", archetype.name(),
                "enabled", true));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static final int MAX_ID_LENGTH = 255;
    private static final int MAX_NAME_LENGTH = 1024;
    private static final int MAX_URL_LENGTH = 2048;

    private void validateRequiredFields(ConnectorDefinition def) {
        if (def.getConnectorId() == null || def.getConnectorId().isBlank()) {
            throw new IllegalArgumentException("connectorId is required");
        }
        if (def.getConnectorId().length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("connectorId exceeds max length of " + MAX_ID_LENGTH);
        }
        if (def.getDisplayName() != null && def.getDisplayName().length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName exceeds max length of " + MAX_NAME_LENGTH);
        }
        if (def.getEndpoint() != null && def.getEndpoint().length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("endpoint URL exceeds max length of " + MAX_URL_LENGTH);
        }
        // SSRF prevention: reject private/loopback endpoint URLs
        if (def.getEndpoint() != null && !def.getEndpoint().isBlank()) {
            try {
                AdapterHttpClient.validateExternalUrl(def.getEndpoint());
            } catch (SecurityException se) {
                throw new IllegalArgumentException("endpoint: " + se.getMessage());
            }
        }
        if (def.getSourceArchetype() == null) {
            throw new IllegalArgumentException("sourceArchetype is required. Valid values: "
                    + java.util.Arrays.toString(SourceArchetype.values()));
        }
        if (def.getSourceSystem() == null || def.getSourceSystem().isBlank()) {
            throw new IllegalArgumentException("sourceSystem is required. Valid values: "
                    + String.join(", ", AdapterRegistry.allSourceSystems()));
        }
        // Normalize legacy short aliases before registry validation
        String system = def.getSourceSystem();
        if ("google".equals(system)) { def.setSourceSystem("google_drive"); system = "google_drive"; }
        else if ("microsoft".equals(system)) { def.setSourceSystem("onedrive"); system = "onedrive"; }
        // Validate sourceSystem against adapter registry — reject unknown values
        if (!AdapterRegistry.isRegistered(system)) {
            throw new IllegalArgumentException("Unknown sourceSystem '" + def.getSourceSystem()
                    + "'. Valid values: " + String.join(", ", AdapterRegistry.allSourceSystems()));
        }
        AdapterDescriptor desc = AdapterRegistry.get(def.getSourceSystem());
        if (desc.archetype() != def.getSourceArchetype()) {
            throw new IllegalArgumentException("sourceSystem '" + def.getSourceSystem()
                    + "' expects archetype " + desc.archetype()
                    + " but connector declares " + def.getSourceArchetype());
        }
        validateDelegationFields(def);
    }

    /**
     * Delegation invariants (enforced even though admin sets them, to keep
     * the data shape consistent and to surface configuration mistakes early):
     *
     * <ul>
     *   <li>If {@code delegated=false}, the scope fields must be empty —
     *       leaving stale folder/principal IDs around when delegation is
     *       toggled off would be confusing on a later re-enable.</li>
     *   <li>If {@code delegated=true} AND {@code delegateAllFolders=false},
     *       {@code allowedFolderIds} must be non-empty. Empty means
     *       "no delegation" by design (we deliberately don't treat empty
     *       as "all folders" — that would turn an admin oversight into a
     *       silent broad credential delegation).</li>
     *   <li>If {@code delegated=false} AND {@code delegateAllFolders=true},
     *       reject — the combination is meaningless and likely a typo.</li>
     * </ul>
     */
    private void validateDelegationFields(ConnectorDefinition def) {
        boolean hasFolderScope = def.getAllowedFolderIds() != null && !def.getAllowedFolderIds().isEmpty();
        boolean hasPrincipalScope = def.getAllowedPrincipalIds() != null && !def.getAllowedPrincipalIds().isEmpty();

        if (!def.isDelegated()) {
            if (def.isDelegateAllFolders() || hasFolderScope || hasPrincipalScope) {
                throw new IllegalArgumentException(
                        "delegateAllFolders / allowedFolderIds / allowedPrincipalIds may only be set when delegated=true");
            }
            return;
        }

        if (!def.isDelegateAllFolders() && !hasFolderScope) {
            throw new IllegalArgumentException(
                    "delegated=true requires either delegateAllFolders=true or a non-empty allowedFolderIds list. "
                            + "Empty allowedFolderIds is treated as 'no delegation' and is not the safe default for repository-wide delegation");
        }
        if (def.isDelegateAllFolders() && hasFolderScope) {
            throw new IllegalArgumentException(
                    "delegateAllFolders=true and a non-empty allowedFolderIds list are mutually exclusive");
        }
        for (String fid : def.getAllowedFolderIds() != null ? def.getAllowedFolderIds() : List.<String>of()) {
            if (fid == null || fid.isBlank()) {
                throw new IllegalArgumentException("allowedFolderIds must not contain null/blank entries");
            }
        }
        for (String pid : def.getAllowedPrincipalIds() != null ? def.getAllowedPrincipalIds() : List.<String>of()) {
            if (pid == null || pid.isBlank()) {
                throw new IllegalArgumentException("allowedPrincipalIds must not contain null/blank entries");
            }
        }
    }

    // --- Internal ---

    @SuppressWarnings("unchecked")
    private void upsertDocument(ConnectorDefinition def, boolean creating) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        Map<String, Object> jsonMap = MAPPER.convertValue(def, Map.class);
        jsonMap.put("type", ConnectorDefinition.DOC_TYPE);

        Document doc = new Document();
        for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
            doc.put(entry.getKey(), entry.getValue());
        }

        // Find existing document for _id and _rev
        List<com.ibm.cloud.cloudant.v1.model.Document> existing = findRawDocs(cloudant, dbName,
                Map.of("type", ConnectorDefinition.DOC_TYPE, "connectorId", def.getConnectorId()));
        com.ibm.cloud.cloudant.v1.model.Document deterministic = existing.isEmpty()
                ? readByDeterministicId(cloudant, dbName, def.getConnectorId())
                : null;
        if (!existing.isEmpty()) {
            doc.setId(existing.get(0).getId());
            doc.setRev(existing.get(0).getRev());
        } else if (deterministic != null) {
            // The selector said "no such connector" and an ID-ADDRESSED read says otherwise.
            // A Mango index being rebuilt answers empty, and writing on the strength of it
            // is what produces a second definition. An id-addressed read needs no index.
            //
            // What to do about it depends on what was asked, and the first version of this
            // refused both the same way. A CREATE must still be refused — the connector is
            // there, and overwriting it is not what "create" means. An UPDATE must NOT be:
            // the id-addressed read conclusively found the row the administrator meant and
            // handed back its _id AND _rev, which is everything a conflict-safe write needs.
            // Refusing it turned a perfectly ordinary PUT into a 500 whenever the index
            // happened to be rebuilding — over-throwing on evidence that was conclusive.
            if (creating) {
                throw new IllegalStateException("Connector already exists: "
                        + def.getConnectorId() + " (found by an id-addressed read; the index"
                        + " did not report it, so the duplicate check before this one passed)");
            }
            // An UPDATE is refused here too, and the round that changed this to "adopt the
            // row, it carries _id and _rev" was wrong in a way worth writing down.
            //
            // _id and _rev make the write safe against a CONCURRENT writer. They say nothing
            // about whether the PAYLOAD is complete — and it is not. The controller rebuilds
            // the masked secrets and the omitted delegation arrays from
            // connectorDefinitionService.get(), which is answered by the SAME Mango selector
            // that just missed. So on exactly this path the request arriving here carries the
            // literal string "[configured]" where a credential belongs and nulls where the
            // scope arrays belong. Adopting the row would write that over the real
            // configuration: the refusal was not over-throwing, it was the only thing
            // standing between a rebuilding index and a destroyed connector.
            //
            // What WAS a real defect is that this reached the client as a 500. It is a
            // transient, retryable condition and now says so.
            throw new ConnectorIndexNotReadyException("connector " + def.getConnectorId()
                    + " exists under its deterministic id but the index did not report it."
                    + " The update is refused rather than applied because the request was"
                    + " assembled against that same index — masked secrets and omitted"
                    + " scope lists could not be restored from it. Retry once the index has"
                    + " caught up.");
        } else {
            // Third door, and the one that does not depend on any index at all: a LEGACY
            // generated-id row is invisible to the selector (its index may be rebuilding)
            // AND to the deterministic-id read. The startup migration usually removes such
            // rows, but a create must not bet on the migration having run or succeeded —
            // ordering closed the window only when the pass completes cleanly, and a
            // review showed the failed-pass path recreating the exact divergent twin the
            // migration exists to prevent. _all_docs cannot under-report.
            boolean someRowDefinesThisConnector;
            try {
                someRowDefinesThisConnector =
                        aConnectorRowExistsIndexFree(cloudant, dbName, def.getConnectorId());
            } catch (IllegalStateException unprovable) {
                // The scan could not CLASSIFY a row, so uniqueness is unprovable right now.
                // For a CREATE that stays the existing contract (IllegalStateException →
                // 400, with its own lock). For an UPDATE it used to escape as a 500 —
                // recorded at closure time as "twin-free but unlocked" — while the
                // condition is exactly as transient as the rebuilding-index refusals this
                // exception exists for. A retry reads the row and proceeds.
                if (creating) {
                    throw unprovable;
                }
                throw new ConnectorIndexNotReadyException(unprovable.getMessage());
            }
            if (someRowDefinesThisConnector) {
                if (creating) {
                    throw new IllegalStateException("Connector already exists: "
                            + def.getConnectorId() + " (found by an index-free scan; neither"
                            + " the selector nor the deterministic id reported it — a legacy"
                            + " row awaiting migration)");
                }
                // The UPDATE arm, which the first version left open — the same one-arm
                // shape this batch keeps finding, named by a review before first contact.
                // A real-value PUT landing here would write a NEW deterministic row while
                // the legacy row (invisible to the rebuilding selector) still defines the
                // connector: the divergent twin, created with a 200 that looks like
                // success, after which get() answers whichever row the recovered selector
                // returns first. Refuse retryably instead — the startup migration rewrites
                // the legacy row, and this same PUT then lands on it normally.
                throw new ConnectorIndexNotReadyException("connector " + def.getConnectorId()
                        + " exists as a legacy row the rebuilding index cannot show;"
                        + " writing under the deterministic id would create a second"
                        + " definition. Retry once the index has caught up.");
            }
            // A DETERMINISTIC id for anything created from here on.
            //
            // Existence is decided by a Mango selector, and a selector whose index is being
            // rebuilt answers "no such connector" — after which this method used to save
            // under a CouchDB-generated id, so a second document appeared with nothing able
            // to reject it. Patch_DefaultCloudDriveConnectorProfile does exactly that
            // sequence at startup. The patch gate added for it probes each repository's
            // _repo views, which say nothing about THIS database's index; a review pointed
            // that out. Deriving the id from the connectorId closes it where it actually
            // happens: the second write is a 409, not a duplicate.
            //
            // Documents created before this keep their GENERATED ids. They are found by the
            // selector above when it answers, and the id-addressed check just above cannot
            // see them — so a legacy document plus a stale selector can still produce a
            // second definition, once, after which both are deterministic-id protected. A
            // review pointed out that the first version of this comment said "nothing needs
            // migrating", which was the stronger claim: what is true is that nothing needs
            // migrating FOR THE NEW PATH, and a legacy row stays exposed until it is
            // rewritten (an update through this method rewrites it under its own id, not the
            // deterministic one — closing that is a migration, and it is not done here).
            doc.setId(ConnectorDefinition.DOC_TYPE + ":" + def.getConnectorId());
        }

        PostDocumentOptions options = new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build();
        DocumentResult result = cloudant.postDocument(options).execute().getResult();
        if (!result.isOk()) {
            throw new IllegalStateException("Failed to save connector " + def.getConnectorId() + ": " + result.getError());
        }
    }

    /**
     * The store holds the connector but the index has not caught up, so the request cannot be
     * completed SAFELY — not that anything is wrong with it. Separate from
     * {@link IllegalStateException} so the controller can answer 503 rather than 500: a
     * caller that retries succeeds, and one that reads 500 opens a ticket.
     */
    public static class ConnectorIndexNotReadyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConnectorIndexNotReadyException(String message) {
            super(message);
        }
    }

    /**
     * An id-addressed read of the deterministic document id. Needs no index, so it answers
     * while a Mango index is being rebuilt — which is the window this class has to survive.
     */
    private com.ibm.cloud.cloudant.v1.model.Document readByDeterministicId(
            com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName, String connectorId) {
        try {
            return cloudant.getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions
                    .Builder().db(dbName)
                    .docId(ConnectorDefinition.DOC_TYPE + ":" + connectorId).build())
                    .execute().getResult();
        } catch (com.ibm.cloud.sdk.core.service.exception.NotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<ConnectorDefinition> findBySelector(Map<String, Object> selector) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();

        List<com.ibm.cloud.cloudant.v1.model.Document> rawDocs = findRawDocs(cloudant, dbName, selector);
        List<ConnectorDefinition> results = new ArrayList<>();
        for (com.ibm.cloud.cloudant.v1.model.Document rawDoc : rawDocs) {
            try {
                Map<String, Object> props = new HashMap<>(rawDoc.getProperties());
                props.remove("_id");
                props.remove("_rev");
                props.remove("type");
                results.add(MAPPER.convertValue(props, ConnectorDefinition.class));
            } catch (Exception e) {
                logger.warn("Failed to deserialize connector definition: {}", e.getMessage());
            }
        }
        return results;
    }

    private List<com.ibm.cloud.cloudant.v1.model.Document> findRawDocs(
            com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName, Map<String, Object> selector) {
        PostFindOptions findOptions = new PostFindOptions.Builder()
                .db(dbName).selector(selector).limit(200).build();
        FindResult findResult = cloudant.postFind(findOptions).execute().getResult();
        List<com.ibm.cloud.cloudant.v1.model.Document> docs = findResult.getDocs();
        return docs != null ? docs : List.of();
    }

    /** One page of an {@code _all_docs} walk. Small on purpose: nemaki_conf holds config
     *  rows in the tens, and the paging test builds a full page of mocks. */
    static final int MIGRATION_PAGE = 200;

    /**
     * Walks every row of the database through {@code _all_docs}, fail-closed.
     *
     * <p>Continuation is {@code startKey(lastSeenId)} WITHOUT a server-side {@code skip(1)}.
     * The continuation key can be a row the caller just DELETED (a migrated legacy row at a
     * page boundary); CouchDB then starts at the first key after it, and a server-side skip
     * would discard a live row — a legacy connector at position page+1 was silently missed
     * and the pass reported clean. A review caught it before this ever ran. The still-present
     * case (the key is re-served as the first row) is dropped by id comparison instead.
     */
    private void forEachAllDocsRow(com.ibm.cloud.cloudant.v1.Cloudant cloudant, String dbName,
            java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow) {
        String resumeAfterId = null;
        while (true) {
            com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.Builder page =
                    new com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions.Builder()
                            .db(dbName).includeDocs(true).limit((long) MIGRATION_PAGE);
            if (resumeAfterId != null) {
                page.startKey(resumeAfterId);
            }
            com.ibm.cloud.cloudant.v1.model.AllDocsResult listing =
                    cloudant.postAllDocs(page.build()).execute().getResult();
            if (listing == null || listing.getRows() == null) {
                // The ENUMERATION did not answer. Returning what has been seen so far would
                // read as "migration complete" to the caller — the same failure-as-absence
                // this migration exists to close, one layer up.
                throw new IllegalStateException("the _all_docs listing of '" + dbName
                        + "' did not answer, so whether any legacy connector rows remain"
                        + " cannot be established; the migration will retry on the next"
                        + " startup");
            }
            String lastNonNullId = null;
            for (com.ibm.cloud.cloudant.v1.model.DocsResultRow row : listing.getRows()) {
                String id = row.getId();
                if (id != null) {
                    if (id.equals(resumeAfterId)) {
                        // the continuation key itself, re-served because it still exists
                        continue;
                    }
                    lastNonNullId = id;
                }
                perRow.accept(row);
            }
            if (lastNonNullId != null) {
                resumeAfterId = lastNonNullId;
            } else if (listing.getRows().size() >= MIGRATION_PAGE) {
                // A FULL page advanced the cursor by nothing: repeating the query would loop
                // on the same page for ever, and stopping quietly would claim the rest of
                // the database was seen.
                throw new IllegalStateException("a full _all_docs page of '" + dbName
                        + "' carried no usable row ids, so the walk cannot make progress");
            }
            if (listing.getRows().size() < MIGRATION_PAGE) {
                break;
            }
        }
    }

    @Override
    public LegacyIdMigrationResult migrateLegacyGeneratedIds() {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();
        LegacyIdMigrationResult result = new LegacyIdMigrationResult();

        // _all_docs, not the Mango selector. The selector is answered by the index whose
        // rebuild opens the §62 window in the first place — a migration that trusted it
        // would silently skip rows exactly when it is needed. _all_docs is the primary
        // index and cannot under-report.
        forEachAllDocsRow(cloudant, dbName, row -> {
            if (row.getError() != null) {
                // One row that cannot be classified is reported loudly and does not stop
                // the others: throwing here would make a single odd row brick the
                // migration on every startup (over-throwing as a standing state).
                result.failures.add(String.valueOf(row.getId())
                        + " (listing row carries error: " + row.getError() + ")");
                return;
            }
            String id = row.getId();
            if (id == null) {
                // Not a silent skip: if this IS a legacy connector row, it stays invisible
                // to the duplicate check with nothing saying so.
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
            if (!ConnectorDefinition.DOC_TYPE.equals(props.get("type"))) {
                return;
            }
            Object cid = props.get("connectorId");
            String connectorId = cid instanceof String ? (String) cid : null;
            if (connectorId == null || connectorId.isBlank()) {
                result.failures.add(id + " (a connector_definition row without a usable"
                        + " connectorId cannot be given a deterministic id)");
                logger.error("Connector row {} has no usable connectorId; it cannot be"
                        + " migrated and stays invisible to the duplicate check", id);
                return;
            }
            String deterministicId = ConnectorDefinition.DOC_TYPE + ":" + connectorId;
            if (deterministicId.equals(id)) {
                return;
            }
            migrateOneLegacyRow(client, cloudant, dbName, doc, id, connectorId,
                    deterministicId, result);
        });
        return result;
    }

    /**
     * The row's CONTENT: everything except storage bookkeeping.
     *
     * <p>{@code findBySelector} in this same class strips {@code _id}/{@code _rev} before
     * mapping — recorded evidence that those keys CAN surface inside {@code getProperties()}
     * — and the migration's first version compared and copied the map raw. Two rows
     * identical in every content field then read as DIVERGENT (a false standing ERROR),
     * and worse, a copy carrying the legacy {@code _rev} corrupts the create. A review
     * caught the asymmetry against findBySelector before this ever ran.
     */
    private static Map<String, Object> contentOnly(Map<String, Object> props) {
        Map<String, Object> content = new HashMap<>(props);
        content.remove("_id");
        content.remove("_rev");
        content.remove("_attachments");
        return content;
    }

    /**
     * Moves one legacy row to its deterministic id: copy, verify the copy exists, then
     * retire the original — conditional on the revision it was READ at, so a concurrent
     * edit wins over the retirement (the delete conflicts, both rows stay, and the next
     * pass reports them as divergent instead of silently dropping the edit).
     */
    private void migrateOneLegacyRow(CloudantClientWrapper wrapper,
            com.ibm.cloud.cloudant.v1.Cloudant cloudant,
            String dbName, com.ibm.cloud.cloudant.v1.model.Document legacy, String legacyId,
            String connectorId, String deterministicId, LegacyIdMigrationResult result) {
        try {
            if (legacy.getAttachments() != null && !legacy.getAttachments().isEmpty()) {
                // getProperties() does not carry attachments, so the copy would silently
                // drop them and the retirement would destroy the only holder. No current
                // writer puts attachments on connector rows, but a migration must not bet
                // on that: the row is left in place and reported until someone looks.
                result.failures.add(connectorId + " (the legacy row " + legacyId
                        + " carries attachments, which this migration does not copy; the"
                        + " row is left in place rather than migrated incompletely)");
                logger.error("Connector row {} carries attachments and was NOT migrated;"
                        + " move it by hand or drop the attachments first", legacyId);
                return;
            }
            com.ibm.cloud.cloudant.v1.model.Document existing =
                    readByDeterministicId(cloudant, dbName, connectorId);
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
                    // A previously DELETED deterministic id leaves a tombstone, and CouchDB
                    // answers 409 for a create against it — while the id-addressed read
                    // above says "absent", because it reads live documents. Without this
                    // arm the row retried on every startup for ever, with a message that
                    // never named the cause. purgeTombstone acts only when a tombstone
                    // actually exists; false means the 409 was something else (a concurrent
                    // creation), and the rethrow lands in the ordinary failure arm — the
                    // next pass then sees the concurrent row and compares content.
                    if (wrapper.purgeTombstone(deterministicId)) {
                        logger.info("Purged the tombstone of {} left by an earlier deletion;"
                                + " retrying the copy of connector {}", deterministicId,
                                connectorId);
                        created = cloudant.postDocument(write).execute().getResult();
                    } else {
                        throw firstAttempt;
                    }
                }
                if (created == null || !Boolean.TRUE.equals(created.isOk())) {
                    // The copy is not known to exist, so the original MUST NOT be retired.
                    result.failures.add(connectorId + " (the deterministic copy was not"
                            + " written: " + (created == null ? "no result" : created.getError())
                            + "); the legacy row is untouched");
                    logger.error("Migration of connector {} failed at the copy step", connectorId);
                    return;
                }
                createdNow = true;
            } else if (!java.util.Objects.equals(contentOnly(legacy.getProperties()),
                    contentOnly(existing.getProperties()))) {
                // The real §62 damage, or an admin edit that landed on one of the twins.
                // Choosing a winner here silently destroys the other row's configuration —
                // the exact loss this migration exists to prevent — so NEITHER is touched
                // and the disagreement is reported until a human resolves it.
                result.divergent.add(connectorId + " (legacy " + legacyId + " vs "
                        + deterministicId + ")");
                logger.error("Connector {} exists as BOTH {} and {} with DIFFERENT content."
                        + " Neither row was touched. Resolve by deleting the row you do NOT"
                        + " want: DELETE .../admin/connectors/{}?docId=<one of the two ids"
                        + " above>", connectorId, legacyId, deterministicId, connectorId);
                return;
            }
            // The deterministic row exists and carries the same content (just written, or
            // the leftover of an interrupted earlier pass). Retire the legacy row at the
            // revision it was read at.
            cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions
                    .Builder().db(dbName).docId(legacyId).rev(legacy.getRev()).build())
                    .execute();
            if (createdNow) {
                result.migrated++;
                logger.info("Migrated connector {} from generated id {} to {}", connectorId,
                        legacyId, deterministicId);
            } else {
                result.sweptDuplicates++;
                logger.info("Retired leftover legacy row {} of connector {} (identical"
                        + " deterministic twin already present)", legacyId, connectorId);
            }
        } catch (Exception e) {
            // Includes a 409 on the conditional delete: a concurrent edit moved the legacy
            // row past the revision this pass read, so the retirement is abandoned and the
            // next pass sees the rows disagree — loudly — instead of the edit vanishing.
            result.failures.add(connectorId + " (" + e.getMessage() + ")");
            logger.error("Migration of connector {} did not complete; it will retry on the"
                    + " next startup", connectorId, e);
        }
    }

    /**
     * Does ANY row — deterministic, legacy, whatever its id — define this connector?
     * Answered from {@code _all_docs} only, so it holds while every Mango index rebuilds.
     *
     * <p>Unlike the migration's walk, a row this scan cannot classify REFUSES: the caller is
     * about to create on the strength of "no such connector", and a claim of uniqueness that
     * skipped an unreadable row is not a claim at all.
     */
    private boolean aConnectorRowExistsIndexFree(com.ibm.cloud.cloudant.v1.Cloudant cloudant,
            String dbName, String connectorId) {
        boolean[] found = new boolean[1];
        forEachAllDocsRow(cloudant, dbName, row -> {
            if (found[0]) {
                return;
            }
            String id = row.getId();
            if (row.getError() != null || id == null) {
                throw new IllegalStateException("the uniqueness of connector '" + connectorId
                        + "' cannot be established: a row of '" + dbName
                        + "' could not be read (" + (row.getError() != null
                                ? row.getError() : "no id") + ")");
            }
            if (id.startsWith("_design/")) {
                return;
            }
            Map<String, Object> props = row.getDoc() != null
                    ? row.getDoc().getProperties() : null;
            if (props == null) {
                throw new IllegalStateException("the uniqueness of connector '" + connectorId
                        + "' cannot be established: row " + id + " came back without a body");
            }
            if (ConnectorDefinition.DOC_TYPE.equals(props.get("type"))
                    && connectorId.equals(props.get("connectorId"))) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private CloudantClientWrapper getConfClient() {
        CloudantClientWrapper client = connectorPool.getClient(SystemConst.NEMAKI_CONF_DB);
        if (client == null) {
            throw new IllegalStateException("nemaki_conf database client not available");
        }
        return client;
    }
}
