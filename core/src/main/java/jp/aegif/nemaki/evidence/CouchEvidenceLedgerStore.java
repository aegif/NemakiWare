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
package jp.aegif.nemaki.evidence;

import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions;
import com.ibm.cloud.cloudant.v1.model.PutDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.ViewResult;
import com.ibm.cloud.cloudant.v1.model.ViewResultRow;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;
import tools.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.evidence.anchor.CouchAnchorReceiptStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The evidence ledger in CouchDB (P1-3 §1).
 *
 * <h2>Its own database, because retention is the whole reason it exists</h2>
 *
 * <p>The delivery journal is purged on {@code lineage.retention.days}. If the ledger shared that
 * database it would share the purge, and the chain would break the day retention first came due
 * — which is the failure the ledger was built to survive. So: {@code nemaki_evidence_ledger},
 * with no purge path in this class at all.
 *
 * <h2>Create-if-absent, never update</h2>
 *
 * <p>{@link #append} writes at {@code entry.documentId()}, which encodes the domain and the
 * sequence. A second writer at the same position therefore loses a 409 rather than producing a
 * silent second row, and the caller can see that it lost. There is no update method here for the
 * same reason the interface has none.
 *
 * <p>A conflict is distinguished from an outage: swallowing both as "false" would let a database
 * being down look like a position already taken, and the caller would advance past a sequence it
 * never actually wrote.
 */
@Component
public class CouchEvidenceLedgerStore implements EvidenceLedgerStore {

    private static final Logger logger = LoggerFactory.getLogger(CouchEvidenceLedgerStore.class);

    static final String DB_NAME = "nemaki_evidence_ledger";
    public static final String DESIGN_DOC = "evidence_ledger";
    static final String VIEW_ENTRIES = "entries_by_domain_sequence";
    static final String VIEW_CHECKPOINTS = "checkpoints_by_domain_to";

    /**
     * Emits {@code [domain, sequence]}. NOT keyed on {@code entryHash}: two rows at one sequence
     * is precisely what a fork looks like, and a key that made them collide would hide it.
     */
    private static final String MAP_ENTRIES =
            "function(doc) { if (doc.type === '" + EvidenceLedgerEntry.TYPE + "' && doc.domain) {"
            + " emit([doc.domain, doc.sequence], null); } }";

    private static final String MAP_CHECKPOINTS =
            "function(doc) { if (doc.type === '" + EvidenceCheckpoint.TYPE + "' && doc.domain) {"
            + " emit([doc.domain, doc.toSequence], null); } }";

    /**
     * Deploys every view in ONE put.
     *
     * <p>Its own method so a test can drive the deployment itself. Asserting on
     * {@code viewSources()} instead only compares a literal with itself and leaves the thing
     * being protected — that this is one put and not five — untested.
     */
    static void deployViews(CloudantClientWrapper wrapper) {
        wrapper.putDesignDocumentWithReducesIfChanged(DESIGN_DOC, viewSources());
    }

    /**
     * The design document as one map, so it is deployed in a single put.
     *
     * <p>Includes the anchor-receipt views: they share this database, and a separate put for
     * them would change the signature again.
     */
    static Map<String, CloudantClientWrapper.ViewSource> viewSources() {
        Map<String, CloudantClientWrapper.ViewSource> views = new java.util.LinkedHashMap<>();
        views.put(VIEW_ENTRIES, new CloudantClientWrapper.ViewSource(MAP_ENTRIES));
        views.put(VIEW_CHECKPOINTS, new CloudantClientWrapper.ViewSource(MAP_CHECKPOINTS));
        views.put(CouchAnchorReceiptStore.VIEW_BY_CHECKPOINT,
                new CloudantClientWrapper.ViewSource(CouchAnchorReceiptStore.MAP_BY_CHECKPOINT));
        views.put(CouchAnchorReceiptStore.VIEW_PENDING,
                new CloudantClientWrapper.ViewSource(CouchAnchorReceiptStore.MAP_PENDING));
        views.put(CouchAnchorReceiptStore.VIEW_CONFIRMED,
                new CloudantClientWrapper.ViewSource(CouchAnchorReceiptStore.MAP_CONFIRMED));
        return views;
    }

    private final AtomicBoolean provisioned = new AtomicBoolean(false);

    private CloudantClientPool connectorPool;
    private ObjectMapper objectMapper;
    private volatile CloudantClientWrapper client;

    @Autowired(required = false)
    public void setConnectorPool(CloudantClientPool connectorPool) {
        this.connectorPool = connectorPool;
    }

    @Autowired(required = false)
    @Qualifier("couchdbObjectMapper")
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private CloudantClientWrapper client() {
        if (!provisioned.get()) {
            ensureDatabase();
        }
        return client;
    }

    /**
     * The provisioned client, for stores that share this database.
     *
     * <p>Anchor receipts live here too: a receipt is evidence ABOUT the ledger, and giving it a
     * second database would mean two retention policies for one story.
     */
    public CloudantClientWrapper clientForSiblingStores() {
        return client();
    }

    /** For tests: use an already-built client and skip provisioning. */
    void useClientForTests(CloudantClientWrapper wrapper) {
        this.client = wrapper;
        this.provisioned.set(true);
    }

    void ensureDatabase() {
        if (provisioned.get()) {
            return;
        }
        synchronized (this) {
            if (provisioned.get()) {
                return;
            }
            CloudantClientWrapper any = connectorPool.getClient("nemaki_conf");
            Cloudant cloudant = any.getClient();
            if (objectMapper == null) {
                // Deliberately NOT falling back to a freshly constructed mapper. The
                // couchdbObjectMapper carries this deployment's configuration, and a default
                // one serialises differently in ways that only show up as a wrong document on
                // disk. An unwired mapper is a wiring bug, and JacksonMigrationBoundaryTest
                // bans the bare construction by scanning the SOURCE TEXT — so even naming it
                // in a comment trips the guard, which is the guard working as intended.
                throw new IllegalStateException("the evidence ledger has no couchdbObjectMapper");
            }
            CloudantClientWrapper wrapper =
                    new CloudantClientWrapper(cloudant, DB_NAME, objectMapper);
            try {
                cloudant.getDatabaseInformation(
                        new GetDatabaseInformationOptions.Builder().db(DB_NAME).build()).execute();
            } catch (NotFoundException e) {
                cloudant.putDatabase(new PutDatabaseOptions.Builder().db(DB_NAME).build())
                        .execute();
                logger.info("Created evidence ledger database '{}'", DB_NAME);
            }
            // ONE put for all five. createOrUpdateView does a get-modify-put PER VIEW, so the
            // previous five calls produced five design-document signatures and CouchDB
            // discarded the index it had just built four times over — the same lesson the
            // lineage store already records, repeated here because I wrote the calls one at a
            // time. On an existing deployment that meant every restart rebuilt every view, and
            // a view answers 200 with an incomplete index while it rebuilds.
            deployViews(wrapper);
            client = wrapper;
            provisioned.set(true);
        }
    }

    @Override
    public boolean append(EvidenceLedgerEntry entry) {
        if (entry == null) {
            return false;
        }
        try {
            // The RESULT is checked, not just the absence of an exception. create(String, Map)
            // returns null during startup by design, and returning true for that would report
            // an entry as chained when nothing was written — the one thing a ledger must never
            // do. (Review: the first version ignored the return entirely and always said true.)
            com.ibm.cloud.cloudant.v1.model.DocumentResult result =
                    client().create(entry.documentId(), entry.toDocument());
            if (result == null || !Boolean.TRUE.equals(result.isOk())) {
                throw new IllegalStateException("the ledger entry at " + entry.documentId()
                        + " was not written (the database answered " + (result == null
                        ? "nothing" : "not-ok") + "); it must not be reported as appended");
            }
            return true;
        } catch (RuntimeException e) {
            if (isConflict(e)) {
                // An ordinary CAS loss. The caller re-reads and decides.
                logger.debug("Evidence ledger position {} was already taken", entry.documentId());
                return false;
            }
            // NOT swallowed into `false`: a database that is down would then look like a
            // position already written, and the caller would move past a sequence that has
            // nothing in it.
            throw e;
        }
    }

    @Override
    public long highestSequence(String domain) {
        Map<String, Object> params = new HashMap<>();
        params.put("descending", true);
        params.put("limit", 1);
        params.put("include_docs", false);
        // Descending swaps the ends: startkey is the HIGH one.
        params.put("startkey", List.of(domain, maxKey()));
        params.put("endkey", List.of(domain, 0));
        ViewResult result = client().queryView(DESIGN_DOC, VIEW_ENTRIES, params);
        if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
            return -1L;
        }
        Object key = result.getRows().get(0).getKey();
        if (key instanceof List<?> parts && parts.size() == 2
                && parts.get(1) instanceof Number n) {
            return n.longValue();
        }
        return -1L;
    }

    @Override
    public List<EvidenceLedgerEntry> range(String domain, long fromSequence, long toSequence,
            int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("include_docs", true);
        params.put("startkey", List.of(domain, fromSequence));
        params.put("endkey", List.of(domain, toSequence));
        if (limit > 0) {
            params.put("limit", limit);
        }
        ViewResult result = client().queryView(DESIGN_DOC, VIEW_ENTRIES, params);
        List<EvidenceLedgerEntry> entries = new ArrayList<>();
        if (result == null || result.getRows() == null) {
            return entries;
        }
        for (ViewResultRow row : result.getRows()) {
            // Rows at the same sequence are BOTH returned. Collapsing them here would make the
            // verifier structurally unable to report a fork.
            EvidenceLedgerEntry entry = decode(row);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    @Override
    public boolean appendCheckpoint(EvidenceCheckpoint checkpoint) {
        if (checkpoint == null) {
            return false;
        }
        try {
            com.ibm.cloud.cloudant.v1.model.DocumentResult result =
                    client().create(checkpoint.documentId(), checkpoint.toDocument());
            if (result == null || !Boolean.TRUE.equals(result.isOk())) {
                throw new IllegalStateException("the checkpoint at " + checkpoint.documentId()
                        + " was not written; it must not be reported as sealed");
            }
            return true;
        } catch (RuntimeException e) {
            if (isConflict(e)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public EvidenceCheckpoint latestCheckpoint(String domain) {
        Map<String, Object> params = new HashMap<>();
        params.put("descending", true);
        params.put("limit", 1);
        params.put("include_docs", true);
        params.put("startkey", List.of(domain, maxKey()));
        params.put("endkey", List.of(domain, 0));
        return firstCheckpoint(client().queryView(DESIGN_DOC, VIEW_CHECKPOINTS, params));
    }

    @Override
    public EvidenceCheckpoint checkpointEndingBefore(String domain, long fromSequence) {
        Map<String, Object> params = new HashMap<>();
        params.put("descending", true);
        params.put("limit", 1);
        params.put("include_docs", true);
        // Exclusive upper bound: a checkpoint ending AT fromSequence does not end BEFORE it.
        params.put("startkey", List.of(domain, fromSequence - 1));
        params.put("endkey", List.of(domain, 0));
        return firstCheckpoint(client().queryView(DESIGN_DOC, VIEW_CHECKPOINTS, params));
    }

    @Override
    public boolean isActive() {
        try {
            return client() != null;
        } catch (Exception e) {
            // "Cannot reach the ledger" is not "the ledger is empty": the second reads as
            // "nothing has happened", which is the wrong thing to tell somebody checking it.
            logger.warn("Evidence ledger is not reachable: {}", e.getMessage());
            return false;
        }
    }

    private EvidenceCheckpoint firstCheckpoint(ViewResult result) {
        if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
            return null;
        }
        Map<String, Object> doc = documentOf(result.getRows().get(0));
        return doc == null ? null : EvidenceCheckpoint.fromDocument(doc);
    }

    private EvidenceLedgerEntry decode(ViewResultRow row) {
        Map<String, Object> doc = documentOf(row);
        return doc == null ? null : EvidenceLedgerEntry.fromDocument(doc);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentOf(ViewResultRow row) {
        if (row == null || row.getDoc() == null) {
            return null;
        }
        Object properties = row.getDoc().getProperties();
        return properties instanceof Map ? (Map<String, Object>) properties : null;
    }

    /** A key that sorts above any sequence. CouchDB orders objects after numbers. */
    private static Object maxKey() {
        return new HashMap<String, Object>();
    }

    /**
     * Whether this failure is an ordinary CAS loss.
     *
     * <p>By TYPE first. The string fallback exists for wrappers that lose the type, but it is
     * checked LAST and never against a message this class composed: the "was not written"
     * messages below embed a document id, and {@code String.format("%019d", 409)} puts the
     * literal {@code 409} in it — so sequence 409 (and 4090-4099, and any repository whose name
     * contains "conflict") turned "the database refused the write" into "somebody already holds
     * this position". The caller then retries for ever or tells an operator a checkpoint exists
     * where none does. Found by review, not by a test.
     */
    private static boolean isConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.ibm.cloud.sdk.core.service.exception.ConflictException) {
                return true;
            }
            if (t instanceof IllegalStateException) {
                // Ours, from the not-written guards. Never a conflict, whatever it says.
                return false;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return isConflictByMessage(e);
    }

    private static boolean isConflictByMessage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && (message.contains("409") || message.toLowerCase()
                    .contains("conflict"))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
