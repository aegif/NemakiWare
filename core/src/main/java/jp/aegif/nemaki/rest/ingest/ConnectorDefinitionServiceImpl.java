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
        if (!results.isEmpty()) {
            return results.get(0);
        }
        // The profile twin of this fallback, mirrored: a selector that answers nothing while
        // its index rebuilds answers the same as one that has no such row, and every caller
        // reads null as absence. One id-addressed read, on the miss path only.
        CloudantClientWrapper client = getConfClient();
        try {
            com.ibm.cloud.cloudant.v1.model.Document row = readByDeterministicId(
                    client.getClient(), client.getDatabaseName(), connectorId);
            return row == null ? null : fromRawDoc(row);
        } catch (RuntimeException idReadFailed) {
            // Opportunistic only: it can improve the selector's answer, never worsen it.
            // "Could not ask" is kept apart from "no" by existsIndexFree, which refuses.
            logger.debug("id-addressed fallback for connector {} failed: {}", connectorId,
                    idReadFailed.getMessage());
            return null;
        }
    }

    /** One raw row as a definition, with the storage bookkeeping removed (see findBySelector). */
    private static ConnectorDefinition fromRawDoc(com.ibm.cloud.cloudant.v1.model.Document raw) {
        Map<String, Object> props = new HashMap<>(raw.getProperties());
        props.remove("_id");
        props.remove("_rev");
        props.remove("type");
        return MAPPER.convertValue(props, ConnectorDefinition.class);
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

        // Index-free. The selector-based delete removed only the rows the rebuilding index
        // showed and then reported success — "消したつもりが残る", recorded at closure time —
        // while a hidden twin lived on. An unreadable row refuses: "deleted" must mean
        // deleted.
        List<com.ibm.cloud.cloudant.v1.model.Document> targets = new ArrayList<>();
        try {
            NemakiConfAllDocs.forEachRow(cloudant, dbName, row -> {
                String id = row.getId();
                if (row.getError() != null || id == null) {
                    throw new IllegalStateException("connector " + connectorId
                            + " cannot be deleted completely: a row of '" + dbName
                            + "' could not be read");
                }
                if (id.startsWith("_design/")) {
                    return;
                }
                com.ibm.cloud.cloudant.v1.model.Document doc = row.getDoc();
                Map<String, Object> props = doc != null ? doc.getProperties() : null;
                if (props == null) {
                    throw new IllegalStateException("connector " + connectorId
                            + " cannot be deleted completely: row " + id
                            + " came back without a body");
                }
                if (ConnectorDefinition.DOC_TYPE.equals(props.get("type"))
                        && connectorId.equals(props.get("connectorId"))) {
                    targets.add(doc);
                }
            });
        } catch (IllegalStateException unprovable) {
            throw new ConnectorIndexNotReadyException(unprovable.getMessage());
        }
        int removed = 0;
        for (com.ibm.cloud.cloudant.v1.model.Document doc : targets) {
            try {
                cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions.Builder()
                        .db(dbName).docId(doc.getId()).rev(doc.getRev()).build()).execute();
                removed++;
            } catch (RuntimeException rowFailed) {
                if (removed == 0) {
                    // Nothing was removed: the store refused the whole operation
                    // (authentication, permission, a broken connection). Not "partly
                    // deleted", and not retryable by itself — it escapes as it is.
                    throw rowFailed;
                }
                // Some rows ARE gone and the caller must know: a retry removes the rest, and
                // reporting this as an ordinary failure reads as "nothing happened". The
                // controller's blanket retry classification was a review finding.
                throw new ConnectorPartiallyDeletedException(kindMessage("connector",
                        connectorId, removed, targets.size(), rowFailed.getMessage()),
                        rowFailed);
            }
        }
        logger.info("Deleted connector definition {} ({} row(s))", connectorId, targets.size());
    }

    @Override
    public int delete(String connectorId, String docId) {
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
        // The profile twin of this guard, mirrored: this operation resolves a divergent
        // PAIR, and if the addressed row is the only one defining the connector it would
        // delete the connector outright through a path that assumes it survives.
        int rows;
        try {
            rows = countConnectorRowsIndexFree(cloudant, dbName, connectorId);
        } catch (IllegalStateException unprovable) {
            // The count reads rows; one it cannot classify means "could not ask", and this
            // path had no catch for it — the raw IllegalStateException became a 500 with no
            // audit entry, while every other caller of the same count wraps it. A review
            // found the one-armed path.
            throw new ConnectorIndexNotReadyException(unprovable.getMessage());
        }
        if (rows == 0) {
            // The row was READ by id and the scan counted none: the two reads disagree, so
            // "this is the only row" is not established. The write path refuses exactly this
            // disagreement retryably; answering a definitive 409 here would claim more than
            // the reads support.
            throw new ConnectorIndexNotReadyException("row " + docId + " of connector "
                    + connectorId + " was read by id, but an index-free scan counted no rows"
                    + " defining it; the two reads disagree. Retry shortly.");
        }
        if (rows == 1) {
            // NOT IllegalArgumentException: that is mapped to 400/404 "no such row", and this
            // row exists. It is the state that is wrong, not the address.
            throw new ConnectorHasNoTwinException("row " + docId + " is the only definition row"
                    + " of connector " + connectorId + "; this operation resolves a divergent"
                    + " pair. Use DELETE without docId to remove the connector.");
        }
        cloudant.deleteDocument(new com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions
                .Builder().db(dbName).docId(docId).rev(row.getRev()).build()).execute();
        logger.info("Deleted connector definition row {} of {}", docId, connectorId);
        // How many rows are LEFT. Two administrators can each address a different twin, both
        // see two rows, and both delete — and then the connector is gone while each of them
        // was told "one row of a pair was resolved". The count before the delete cannot
        // prevent that (CouchDB has no cross-document transaction); reporting the survivors
        // lets the controller say what actually happened. -1 means the count itself could
        // not answer, which is NOT the same as "a row survives".
        try {
            return countConnectorRowsIndexFree(cloudant, dbName, connectorId);
        } catch (RuntimeException unreadable) {
            logger.warn("row {} of connector {} was deleted, but how many rows remain could not be"
                    + " established: {}", docId, connectorId, unreadable.getMessage());
            return -1;
        }
    }

    @Override
    public boolean exists(String connectorId) {
        return get(connectorId) != null;
    }

    @Override
    public boolean existsIndexFree(String connectorId) {
        if (connectorId == null) {
            return false;
        }
        CloudantClientWrapper client = getConfClient();
        try {
            return countConnectorRowsIndexFree(client.getClient(), client.getDatabaseName(),
                    connectorId) > 0;
        } catch (IllegalStateException unprovable) {
            // The interface promises this type, and the controller's 503 branch is written
            // for it: an unclassifiable row means "could not ask", which must not leave here
            // as a 500. The profile twin wrapped it from the start; a review found this arm
            // passing the raw IllegalStateException through, so the branch was dead code.
            throw new ConnectorIndexNotReadyException(unprovable.getMessage());
        }
    }

    @Override
    public ConnectorDefinition findBySystemAndArchetype(String sourceSystem, SourceArchetype archetype) {
        if (sourceSystem == null || archetype == null) return null;
        // Index-free, like the profile resolver beside it. This decides WHICH connector an
        // auto-resolved import uses; answered by a selector it reported "no enabled connector
        // found" whenever the index was rebuilding — a read that failed reported as a read
        // that answered nothing, on the runtime path. A review refused the "out of scope"
        // boundary for the connector half of the same entry point.
        return findBySystemsAndArchetype(List.of(sourceSystem), archetype);
    }

    @Override
    public ConnectorDefinition findBySystemsAndArchetype(List<String> sourceSystems,
            SourceArchetype archetype) {
        if (sourceSystems == null || sourceSystems.isEmpty() || archetype == null) return null;
        // ONE walk of the WHOLE config database for all the alias keys together. The bounded
        // variant was withdrawn (a bounded walk that finds something is still incomplete, so
        // the ambiguity refusal below silently stopped applying), and walking once per key
        // multiplied the cost by the number of aliases.
        // Only the rows whose RAW sourceSystem/archetype can match are deserialised. A row
        // that cannot match cannot be an answer or an ambiguity, and deserialising it first
        // made one unrelated connector — a newer archetype value written by a newer node
        // during a rolling upgrade — refuse every auto-resolved import. A review named the
        // over-throw. A row that DOES match and cannot be read still refuses: that one could
        // be the answer.
        List<ConnectorDefinition> all = listIndexFree(sourceSystems, archetype);
        // The key ORDER is a preference the caller declares: the spelling the request used
        // first, its alias second. Keeping it is why this is a loop and not one filter over
        // every key — collapsing the keys would turn "prefer the exact spelling" into an
        // ambiguity error for an installation that legitimately holds one connector saved as
        // "google" and another as "google_drive". What was arbitrary, and is refused, is a
        // tie WITHIN one key: there the old code returned whichever row the index handed back.
        for (String system : sourceSystems) {
            List<ConnectorDefinition> matches = all.stream()
                    .filter(c -> c.isEnabled() && c.getSourceArchetype() == archetype
                            && system.equals(c.getSourceSystem()))
                    .toList();
            if (matches.size() > 1) {
                throw new IllegalStateException("Ambiguous auto-resolve: " + matches.size()
                        + " enabled connectors match sourceSystem=" + system
                        + ", archetype=" + archetype + " ("
                        + matches.stream().map(ConnectorDefinition::getConnectorId).toList()
                        + ") — name the connector in the request, or disable all but one");
            }
            if (!matches.isEmpty()) {
                return matches.get(0);
            }
        }
        return null;
    }

    /**
     * Every connector definition, read without the Mango index — the whole config database,
     * every time. A variant bounded to the deterministic-id range was withdrawn: a bounded
     * walk that finds something is still incomplete, and the callers' rules (refuse when
     * several match) are made of completeness.
     */
    private List<ConnectorDefinition> listIndexFree(List<String> onlySystems,
            SourceArchetype onlyArchetype) {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();
        List<ConnectorDefinition> results = new ArrayList<>();
        java.util.function.Consumer<com.ibm.cloud.cloudant.v1.model.DocsResultRow> perRow = row -> {
            String id = row.getId();
            if (row.getError() != null || id == null) {
                throw new IllegalStateException("the connectors of '" + dbName + "' cannot be"
                        + " listed: a row could not be read (" + (row.getError() != null
                                ? row.getError() : "no id") + ")");
            }
            if (id.startsWith("_design/")) {
                return;
            }
            Map<String, Object> props = row.getDoc() != null ? row.getDoc().getProperties() : null;
            if (props == null) {
                throw new IllegalStateException("the connectors of '" + dbName + "' cannot be"
                        + " listed: row " + id + " came back without a body");
            }
            if (!ConnectorDefinition.DOC_TYPE.equals(props.get("type"))) {
                return;
            }
            if (onlySystems != null) {
                Object system = props.get("sourceSystem");
                // NOT List.contains(system): the list is a List.of(...), and contains(null)
                // throws there — so a row without a sourceSystem took the whole resolution
                // down with an NPE that no catch on the way out converts. Before this filter
                // existed the comparison ran on the deserialised value and was null-safe;
                // the filter reintroduced the hazard. A review caught the regression.
                if (system == null || !onlySystems.contains(system)) {
                    return;
                }
            }
            if (onlyArchetype != null && !onlyArchetype.name().equals(props.get("sourceArchetype"))) {
                return;
            }
            // Boolean false OR the string "false": a hand-written or legacy row can carry
            // either, and Jackson reads both as disabled — so skipping only the Boolean left
            // a row the resolver would have ignored anyway able to refuse the whole
            // resolution by failing to deserialise. A review named the gap.
            Object enabledRaw = props.get("enabled");
            boolean explicitlyDisabled = Boolean.FALSE.equals(enabledRaw)
                    || "false".equalsIgnoreCase(String.valueOf(enabledRaw));
            if (onlySystems != null && explicitlyDisabled) {
                // A DISABLED row can be neither the answer nor half of an ambiguity, so
                // reading it cannot change the outcome — and reading it is what let one
                // unreadable disabled row refuse every import. Only an explicit false is
                // skipped: an absent value is not a "no".
                return;
            }
            Map<String, Object> content = new HashMap<>(props);
            content.remove("_id");
            content.remove("_rev");
            content.remove("type");
            try {
                results.add(MAPPER.convertValue(content, ConnectorDefinition.class));
            } catch (Exception e) {
                // Not the findBySelector skip: a connector that cannot be deserialised is
                // still a connector for a resolution rule, and dropping it here is how the
                // wrong one gets chosen.
                throw new IllegalStateException("the connectors of '" + dbName + "' cannot be"
                        + " listed: row " + id + " could not be read as a connector ("
                        + e.getMessage() + ")");
            }
        };
        try {
            NemakiConfAllDocs.forEachRow(cloudant, dbName, perRow);
        } catch (IllegalStateException unprovable) {
            throw new ConnectorIndexNotReadyException(unprovable.getMessage());
        }
        return results;
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
        // The index-free count runs on EVERY write, before any door is chosen. The
        // selector can be PARTIAL — one twin visible, another hidden while the index
        // rebuilds — and consulting the walk only when the selector returned nothing let
        // an update adopt the visible twin without knowing the hidden one existed, so the
        // pair diverged silently. Found on the profile twin in review round 2; mirrored.
        int rowsDefiningThisConnector;
        try {
            rowsDefiningThisConnector = countConnectorRowsIndexFree(cloudant, dbName,
                    def.getConnectorId());
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
        if (rowsDefiningThisConnector > 1) {
            // Two or more rows define this connector — a standing pair a rebuilding index
            // once created. Decided on the COUNT alone, before the index is consulted: the
            // first version compared the count with what the selector showed and so refused
            // only HIDDEN twins; a visible pair went on to adopt existing.get(0) and answered
            // 200, choosing a winner silently. Found on the profile twin by a parallel
            // review; the arm order (count first, hidden arm second) by the next one.
            // Not a 503: no retry makes a standing twin go away.
            if (creating) {
                throw new IllegalStateException("Connector already exists: "
                        + def.getConnectorId() + " (" + rowsDefiningThisConnector
                        + " definition rows; delete the unwanted one first: DELETE"
                        + " .../admin/connectors/" + def.getConnectorId() + "?docId=...)");
            }
            throw new ConnectorHasTwinRowsException("connector " + def.getConnectorId()
                    + " has " + rowsDefiningThisConnector + " definition rows; an update would"
                    + " write to one of them and choose a winner silently. Delete the unwanted"
                    + " row first (DELETE .../admin/connectors/" + def.getConnectorId()
                    + "?docId=...)");
        }
        if (rowsDefiningThisConnector > existing.size()) {
            // rows <= 1 here: every pair was taken by the arm above, so this is the ONE row
            // the selector did not show — hidden while the index rebuilds, i.e. transient.
            if (creating) {
                // Name the row honestly: this arm runs BEFORE the id-addressed read, so the
                // hidden row is as often the deterministic one (index rebuilding on a
                // migrated installation) as a legacy one.
                // Three-valued on purpose: a FAILED id read establishes nothing about
                // where the row lives, and calling it "legacy" was a stronger claim than
                // the scan supports. A review named it.
                String whereItLives;
                try {
                    whereItLives = readByDeterministicId(cloudant, dbName, def.getConnectorId()) != null
                            ? "the row exists under its deterministic id and the index has"
                                    + " not caught up"
                            : "a legacy row awaiting migration";
                } catch (RuntimeException unreadable) {
                    whereItLives = "the id-addressed read could not say whether it is a legacy"
                            + " row or a deterministic one (" + unreadable.getMessage() + ")";
                }
                throw new IllegalStateException("Connector already exists: "
                        + def.getConnectorId() + " (found by an index-free scan; the"
                        + " selector did not report it — " + whereItLives + ")");
            }
            // The UPDATE arm: a real-value PUT landing here would write while a row the
            // rebuilding selector cannot show still defines the connector — a divergent
            // twin, created with a 200 that looks like success. Refuse retryably instead.
            throw new ConnectorIndexNotReadyException("connector " + def.getConnectorId()
                    + " has " + rowsDefiningThisConnector + " definition row(s) but the"
                    + " rebuilding index shows " + existing.size() + "; writing now would"
                    + " land on one twin while another stays hidden. Retry once the index"
                    + " has caught up.");
        }
        if (rowsDefiningThisConnector < existing.size()) {
            // The SELECTOR reported more rows than the authoritative walk found. One of the
            // two reads is stale (they are separate requests, not one snapshot), so which
            // rows define this connector is not established — and the next line would write to
            // existing.get(0), a row the walk says is not there. Refused retryably on both
            // arms: a retry reads both again and one of them wins honestly.
            throw new ConnectorIndexNotReadyException("connector " + def.getConnectorId()
                    + ": the index listed " + existing.size() + " definition row(s) but an"
                    + " index-free scan found " + rowsDefiningThisConnector + "; the two reads disagree, so"
                    + " which row to write to is not established. Retry shortly.");
        }
        if (creating && rowsDefiningThisConnector > 0) {
            // A CREATE never adopts a row. create() checks existence through the selector
            // first, but that check and this write are separate requests: two concurrent
            // creates both passed it, and the slower one then overwrote the faster one's
            // configuration and reported 201. A review caught it. The count is index-free,
            // so this refusal holds while the index rebuilds.
            throw new IllegalStateException("Connector already exists: " + def.getConnectorId()
                    + " (an index-free scan found " + rowsDefiningThisConnector + " definition row(s); a"
                    + " create never overwrites one)");
        }
        com.ibm.cloud.cloudant.v1.model.Document deterministic = existing.isEmpty()
                ? readByDeterministicId(cloudant, dbName, def.getConnectorId())
                : null;
        if (!existing.isEmpty()) {
            doc.setId(existing.get(0).getId());
            doc.setRev(existing.get(0).getRev());
        } else if (deterministic != null) {
            // Belt over braces: the count above already refused this case, and the
            // id-addressed read is cheap insurance against a walk that answered zero rows
            // through a bug rather than an empty database.
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
            throw new ConnectorIndexNotReadyException("connector " + def.getConnectorId()
                    + " exists under its deterministic id but the index did not report it."
                    + " The update is refused rather than applied because the request was"
                    + " assembled against that same index — masked secrets and omitted"
                    + " scope lists could not be restored from it. Retry once the index has"
                    + " caught up.");
        } else {
            // A DETERMINISTIC id for anything created from here on. Existence is decided by
            // a Mango selector, and a selector whose index is being rebuilt answers "no such
            // connector" — after which this method used to save under a CouchDB-generated
            // id, so a second document appeared with nothing able to reject it.
            doc.setId(ConnectorDefinition.DOC_TYPE + ":" + def.getConnectorId());
        }

        PostDocumentOptions options = new PostDocumentOptions.Builder()
                .db(dbName).document(doc).build();
        DocumentResult result = cloudant.postDocument(options).execute().getResult();
        if (!result.isOk()) {
            throw new IllegalStateException("Failed to save connector " + def.getConnectorId() + ": " + result.getError());
        }
    }

    /** The message a partly-completed delete carries: what was removed, and what to do. */
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
    public static class ConnectorPartiallyDeletedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConnectorPartiallyDeletedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The store holds the connector but the index has not caught up — or a row could not be
     * classified at all, which is "could not ask", not "no". Retryable: the controllers map
     * it to 503 so a caller that would succeed on retry is not sent a 500 or a false 404.
     * (This javadoc had drifted onto a helper below when the class moved; a review found the
     * type undocumented.)
     */
    public static class ConnectorIndexNotReadyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConnectorIndexNotReadyException(String message) {
            super(message);
        }
    }

    /**
     * The row-addressed resolver was pointed at the ONLY definition row of its connector.
     * The address is right; what is absent is the divergent pair the operation resolves —
     * a conflict with the current state (409), not "no such row".
     */
    public static class ConnectorHasNoTwinException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConnectorHasNoTwinException(String message) {
            super(message);
        }
    }

    /**
     * Two or more rows define one connector and both answer. Not retryable — an administrator
     * deletes the unwanted row ({@code DELETE .../admin/connectors/{id}?docId=...}) — so it is
     * neither the index-not-ready type (503, "wait") nor {@code IllegalStateException}
     * (400, "bad request"): the controllers answer 409.
     */
    public static class ConnectorHasTwinRowsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConnectorHasTwinRowsException(String message) {
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

    /** The shared walk's page size, re-exported so the paging test can build a full page. */
    static final int MIGRATION_PAGE = NemakiConfAllDocs.MIGRATION_PAGE;

    @Override
    public LegacyIdMigrationResult migrateLegacyGeneratedIds() {
        CloudantClientWrapper client = getConfClient();
        String dbName = client.getDatabaseName();
        com.ibm.cloud.cloudant.v1.Cloudant cloudant = client.getClient();
        LegacyIdMigrationResult result = new LegacyIdMigrationResult();
        // connectorId -> (row id -> row). Filled by the walk, acted on after it.
        Map<String, Map<String, com.ibm.cloud.cloudant.v1.model.Document>> legacyRows =
                new java.util.LinkedHashMap<>();

        // _all_docs, not the Mango selector. The selector is answered by the index whose
        // rebuild opens the §62 window in the first place — a migration that trusted it
        // would silently skip rows exactly when it is needed. _all_docs is the primary
        // index and cannot under-report.
        NemakiConfAllDocs.forEachRow(cloudant, dbName, row -> {
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
            // COLLECTED, not migrated here — the profile twin of this change, mirrored. With
            // two legacy rows and no deterministic row, migrating during the walk made the
            // FIRST one encountered canonical and reported only the second as divergent,
            // while the release notes promise neither row is touched. Writing during the
            // enumeration is also what made the walk's skip-after-delete subtlety
            // load-bearing.
            legacyRows.computeIfAbsent(connectorId, k -> new java.util.LinkedHashMap<>())
                    .put(id, doc);
        });
        for (Map.Entry<String, Map<String, com.ibm.cloud.cloudant.v1.model.Document>> entry
                : legacyRows.entrySet()) {
            String connectorId = entry.getKey();
            Map<String, com.ibm.cloud.cloudant.v1.model.Document> rows = entry.getValue();
            String deterministicId = ConnectorDefinition.DOC_TYPE + ":" + connectorId;
            if (rows.size() > 1) {
                result.divergent.add(connectorId + " (" + rows.size() + " legacy rows "
                        + rows.keySet() + "; migrating one of them would make it canonical by"
                        + " nothing but enumeration order, so none is touched)");
                logger.error("Connector {} has {} legacy rows {}; none is migrated — delete"
                        + " the unwanted ones with DELETE .../admin/connectors/{id}?docId=...",
                        connectorId, rows.size(), rows.keySet());
                continue;
            }
            Map.Entry<String, com.ibm.cloud.cloudant.v1.model.Document> only =
                    rows.entrySet().iterator().next();
            migrateOneLegacyRow(client, cloudant, dbName, only.getValue(), only.getKey(),
                    connectorId, deterministicId, result);
        }
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
     * How many rows — deterministic, legacy, whatever their ids — define this connector,
     * answered from {@code _all_docs} only, so it holds while every Mango index rebuilds. A
     * COUNT rather than a boolean, because the selector can be PARTIAL: one twin visible and
     * one hidden while the index rebuilds, which an update would then adopt without knowing
     * the other exists (a round-2 review of the profile twin found it; the arm is mirrored).
     *
     * <p>Unlike the migration's walk, a row this scan cannot classify REFUSES: the caller is
     * about to write on the strength of what it saw, and a claim of uniqueness that skipped
     * an unreadable row is not a claim at all.
     */
    private int countConnectorRowsIndexFree(com.ibm.cloud.cloudant.v1.Cloudant cloudant,
            String dbName, String connectorId) {
        int[] found = new int[1];
        NemakiConfAllDocs.forEachRow(cloudant, dbName, row -> {
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
                found[0]++;
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
