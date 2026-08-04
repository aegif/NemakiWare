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
package jp.aegif.nemaki.rest.purview.journal;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.GetDatabaseInformationOptions;
import com.ibm.cloud.sdk.core.service.exception.NotFoundException;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;

/**
 * §6-a's barrier documents, extracted from {@link CouchLineageJournalStore} unchanged.
 *
 * <p>This responsibility is the most independent of the six the journal store implemented: it
 * does not use the shared provisioning path at all. {@code ensureClientForRead} collapses a
 * verified absent database and an infrastructure failure into the same {@code false} and
 * deploys design documents on the way — and 4a's Pristine / Indeterminate distinction is built
 * on exactly that difference, so the barrier seam keeps its own client and its own strict
 * discovery.
 *
 * <p>Nothing here changed in the extraction: same documents, same ids, same CAS conditions,
 * same exception classification.
 */
final class CouchLineageBarrierStore implements LineageBarrierStore {

    static final String DB_NAME = CouchLineageJournalStore.DB_NAME;

    private final CloudantClientPool connectorPool;
    private final ObjectMapper objectMapper;

    /**
     * The seam's own client. Deliberately separate from the journal store's: caching into that
     * one would make {@code ensureClientForRead} return early and skip its view deployment,
     * silently disabling the projector.
     */
    private volatile CloudantClientWrapper barrierClient;

    CouchLineageBarrierStore(CloudantClientPool connectorPool, ObjectMapper objectMapper) {
        this.connectorPool = connectorPool;
        this.objectMapper = objectMapper;
    }

    /** The journal store's live client, when it has one, so a discovered client is reused. */
    void adoptClient(CloudantClientWrapper client) {
        if (client != null && barrierClient == null) {
            barrierClient = client;
        }
    }


    /**
     * Discovery for the barrier seam ONLY: a verified missing database is {@code null}, every
     * other failure throws, and no view is ever deployed.
     *
     * <p>{@code ensureClientForRead} cannot serve here — it answers {@code false} for both a
     * verified 404 and an outage, which is precisely the distinction the fence is built on,
     * and it deploys design documents on the way.
     */
    private CloudantClientWrapper discoverBarrierClientStrict() {
        CloudantClientWrapper cached = barrierClient;
        if (cached != null) {
            return cached;
        }
        try {
            Cloudant cloudant = connectorPool.getClient("nemaki_conf").getClient();
            cloudant.getDatabaseInformation(new GetDatabaseInformationOptions.Builder()
                    .db(DB_NAME).build()).execute();
            CloudantClientWrapper discovered =
                    new CloudantClientWrapper(cloudant, DB_NAME, objectMapper);
            barrierClient = discovered;
            return discovered;
        } catch (NotFoundException absent) {
            return null; // the database does not exist: a verified absence, not a failure
        } catch (RuntimeException e) {
            throw new BarrierStorageException("barrier database lookup failed", e);
        }
    }

    private Map<String, Object> readBarrierDocStrict(String documentId) {
        CloudantClientWrapper client = discoverBarrierClientStrict();
        if (client == null) {
            return null;
        }
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc = client.getClient()
                    .getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder()
                            .db(client.getDatabaseName())
                            .docId(documentId)
                            .build())
                    .execute().getResult();
            Map<String, Object> props = new HashMap<>();
            if (doc.getId() != null) props.put("_id", doc.getId());
            if (doc.getRev() != null) props.put("_rev", doc.getRev());
            if (doc.getProperties() != null) props.putAll(doc.getProperties());
            return props;
        } catch (NotFoundException absent) {
            return null;
        } catch (RuntimeException e) {
            throw new BarrierStorageException("barrier read failed for '" + documentId + "'", e);
        }
    }

    /**
     * Writes one barrier-family document under its own {@code _rev}. An absent {@code _rev}
     * is a create-if-absent, so a 409 there means "someone else created it first" — an
     * ordinary CAS loss, not a failure.
     */
    private boolean casBarrierDoc(Map<String, Object> raw) {
        CloudantClientWrapper client = discoverBarrierClientStrict();
        if (client == null) {
            // The barrier lives beside the journal; without the database there is nothing to
            // CAS against, and creating it here would provision behind the operator's back.
            throw new BarrierStorageException("the lineage database does not exist — the"
                    + " barrier cannot be written before the journal itself is provisioned",
                    null);
        }
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            Map<String, Object> withoutMeta = new HashMap<>(raw);
            Object id = withoutMeta.remove("_id");
            Object rev = withoutMeta.remove("_rev");
            doc.setProperties(withoutMeta);
            doc.setId((String) id);
            doc.setRev((String) rev);
            client.getClient().putDocument(
                    new com.ibm.cloud.cloudant.v1.model.PutDocumentOptions.Builder()
                            .db(client.getDatabaseName())
                            .docId((String) id)
                            .document(doc)
                            .build())
                    .execute();
            return true;
        } catch (com.ibm.cloud.sdk.core.service.exception.ConflictException conflict) {
            return false;
        } catch (RuntimeException e) {
            throw new BarrierStorageException("barrier CAS failed for '" + raw.get("_id") + "'",
                    e);
        }
    }

    @Override
    public Map<String, Object> readBarrierRaw() {
        return readBarrierDocStrict(LineageWriteVersionBarrier.DOCUMENT_ID);
    }

    @Override
    public boolean casBarrier(Map<String, Object> raw) {
        return casBarrierDoc(raw);
    }

    @Override
    public Map<String, Object> readWitness() {
        return readBarrierDocStrict(LineageWriteVersionBarrier.WITNESS_DOCUMENT_ID);
    }

    @Override
    public boolean writeWitnessIfAbsent(long observedAtMs) {
        if (readWitness() != null) {
            return true;
        }
        Map<String, Object> witness = new LinkedHashMap<>();
        witness.put("_id", LineageWriteVersionBarrier.WITNESS_DOCUMENT_ID);
        witness.put("type", "lineage_barrier_witness");
        witness.put("observedAtMs", observedAtMs);
        // A 409 means a concurrent writer got there first, which is the outcome we wanted.
        return casBarrierDoc(witness) || readWitness() != null;
    }

    @Override
    public String readNodeId() {
        Map<String, Object> raw =
                readBarrierDocStrict(LineageWriteVersionBarrier.NODE_IDENTITY_DOCUMENT_ID);
        if (raw == null) {
            return null;
        }
        Object nodeId = raw.get("nodeId");
        if (!(nodeId instanceof String id) || id.isBlank()) {
            throw new BarrierStorageException("the node identity document has no usable"
                    + " nodeId — refusing to invent one", null);
        }
        return id;
    }

    @Override
    public String allocateNodeIdIfAbsent(String proposed, long allocatedAtMs) {
        String existing = readNodeId();
        if (existing != null) {
            return existing;
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("_id", LineageWriteVersionBarrier.NODE_IDENTITY_DOCUMENT_ID);
        identity.put("type", "lineage_node_identity");
        identity.put("nodeId", proposed);
        identity.put("allocatedAtMs", allocatedAtMs);
        if (casBarrierDoc(identity)) {
            return proposed;
        }
        // Someone allocated concurrently: THEIR id is the durable one. Returning our proposal
        // would leave two views of who this node is.
        String durable = readNodeId();
        if (durable == null) {
            throw new BarrierStorageException("node id allocation lost the CAS but no id is"
                    + " readable afterwards", null);
        }
        return durable;
    }
}
