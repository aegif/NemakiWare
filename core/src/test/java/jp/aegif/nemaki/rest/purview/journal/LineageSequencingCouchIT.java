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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseGrant;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.LeaseMissingException;
import jp.aegif.nemaki.rest.purview.journal.LineageSequencingStore.SequenceCounterException;

/**
 * The real-CouchDB half of increment D's gate: the same {@link LineageSequencingStore}
 * contract the scripted tests drive, exercised against actual CouchDB CAS semantics
 * (revisions, update conflicts, view ordering).
 *
 * <p>Enabled by {@code NEMAKI_LINEAGE_IT_COUCHDB_URL} (with
 * {@code NEMAKI_LINEAGE_IT_COUCHDB_USER} / {@code NEMAKI_LINEAGE_IT_COUCHDB_PASSWORD}) — set
 * locally against the dev stack, and set unconditionally by the dedicated CI job, where this
 * class MUST run: a skipped IT does not satisfy the gate. Each run provisions its own
 * database and deletes it afterwards.
 */
public class LineageSequencingCouchIT {

    private static final String REPO = "bedroom";

    private static Cloudant cloudant;
    private static String dbName;
    private static CouchLineageJournalStore store;

    @BeforeAll
    static void provision() {
        String url = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_URL");
        if (url == null || url.isBlank()) {
            // The dedicated CI job sets -Dlineage.it.required=true: there, a missing URL is a
            // FAILURE, never a silent skip — an @Enabled condition alone reports green with
            // zero tests executed on a misspelled variable. Locally (no flag) the class opts
            // out via an assumption.
            if (Boolean.getBoolean("lineage.it.required")) {
                throw new IllegalStateException("lineage.it.required=true but"
                        + " NEMAKI_LINEAGE_IT_COUCHDB_URL is not set — the CI gate must run");
            }
            org.junit.jupiter.api.Assumptions.abort(
                    "NEMAKI_LINEAGE_IT_COUCHDB_URL not set — real-CouchDB IT skipped locally");
        }
        String user = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_USER");
        String password = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_PASSWORD");
        if (user != null && !user.isBlank()) {
            cloudant = new Cloudant("lineage-it", new BasicAuthenticator.Builder()
                    .username(user).password(password).build());
        } else {
            cloudant = new Cloudant("lineage-it", null);
        }
        cloudant.setServiceUrl(url);
        dbName = "nemaki_lineage_it_" + UUID.randomUUID().toString().replace("-", "");
        store = CouchLineageJournalStore.forDirectClient(cloudant, dbName, new ObjectMapper());
    }

    @AfterAll
    static void dropDatabase() {
        if (cloudant != null && dbName != null) {
            try {
                cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(dbName).build())
                        .execute();
            } catch (Exception ignored) {
            }
        }
    }

    private static LineageEventV2 event(String repositoryId, String operationId,
            String occurredAt) {
        return new LineageEventV2Builder()
                .eventId("evt-" + operationId)
                .occurredAt(occurredAt)
                .repositoryId(repositoryId)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId(operationId)
                .delivery(new LineageDelivery.Original(List.of("purview")))
                .addInput(LineageEndpoint.document(repositoryId, "doc-" + operationId,
                        "a.txt"))
                .addOutput(LineageEndpoint.archive(repositoryId, "doc-" + operationId,
                        "doc-" + operationId, 1L))
                .build();
    }

    /** The bootstrap patch's job, done directly: the lease document with generation 0. */
    private static void bootstrapLease(String repositoryId) {
        Map<String, Object> lease = new HashMap<>();
        lease.put("_id", "lineage_sequencer_lease:" + repositoryId);
        lease.put("type", "lineage_sequencer_lease");
        lease.put("generation", 0L);
        lease.put("owner", null);
        lease.put("expiresAt", java.time.Instant.EPOCH.toString());
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            lease.forEach(doc::put);
            doc.setId((String) lease.get("_id"));
            cloudant.postDocument(new com.ibm.cloud.cloudant.v1.model.PostDocumentOptions
                    .Builder().db(dbName).document(doc).build()).execute();
        } catch (Exception alreadyThere) {
            // idempotent for reruns within the class
        }
    }

    private static void bootstrapCounter(String repositoryId, long seq) {
        try {
            com.ibm.cloud.cloudant.v1.model.Document doc =
                    new com.ibm.cloud.cloudant.v1.model.Document();
            doc.setId("lineage_seq:" + repositoryId);
            doc.put("type", "lineage_sequence");
            doc.put("repositoryId", repositoryId);
            doc.put("seq", seq);
            cloudant.postDocument(new com.ibm.cloud.cloudant.v1.model.PostDocumentOptions
                    .Builder().db(dbName).document(doc).build()).execute();
        } catch (Exception alreadyThere) {
        }
    }

    @Test
    public void theWholeFencedPathRunsAgainstRealCouch() {
        // Lease is bootstrap-only: before it exists, acquire must fail closed, never create.
        // A repository nothing bootstraps keeps this assertion order-independent.
        assertThrows(LeaseMissingException.class,
                () -> store.acquireSequencerLease("never-bootstrapped",
                        "node-a", Duration.ofMinutes(5)));

        bootstrapLease(REPO);
        bootstrapCounter(REPO, 0L);

        // Durable-first: three unsequenced rows land via the production appendV2.
        store.appendV2(event(REPO, "op-b", "2026-08-01T00:00:02Z"));
        store.appendV2(event(REPO, "op-a", "2026-08-01T00:00:01Z"));
        store.appendV2(event(REPO, "op-c", "2026-08-01T00:00:03Z"));

        List<LineageJournalRowV2> backlog = store.findUnsequencedV2(REPO, 10);
        assertEquals(3, backlog.size());
        assertEquals("op-a", backlog.get(0).event().operationId(),
                "the claim scan is occurredAt-ordered on the real view");

        LineageFencedSequencer sequencer = new LineageFencedSequencer(store,
                new LineageMetrics(), "node-a", Duration.ofMinutes(5), 10, 100);
        LineageFencedSequencer.RunSummary summary = sequencer.runOnce(REPO);

        assertEquals(3, summary.finalized());
        assertEquals(LineageSequencingStore.SequencerHealth.FENCED_OK, summary.health());
        assertTrue(store.findUnsequencedV2(REPO, 10).isEmpty());
        assertTrue(store.findSequencingV2(REPO, 10).isEmpty());
        assertEquals(3, store.sequenceHighWatermark(REPO),
                "the watermark view reduces to the max finalized sequence");

        // Idempotent re-run: nothing left, lease re-acquired and released cleanly.
        assertEquals(0, sequencer.runOnce(REPO).finalized());
    }

    @Test
    public void staleRevisionsLoseTheRealCas() {
        String casRepo = "cas-repo";
        bootstrapLease(casRepo);
        bootstrapCounter(casRepo, 100L);
        store.appendV2(event(casRepo, "op-cas", "2026-08-01T00:01:00Z"));

        LineageJournalRowV2 row = store.findUnsequencedV2(casRepo, 50).stream()
                .filter(r -> "op-cas".equals(r.event().operationId()))
                .findFirst().orElseThrow();

        Optional<LeaseGrant> grant = store.acquireSequencerLease(casRepo, "node-cas",
                Duration.ofMinutes(5));
        assertTrue(grant.isPresent());
        try {
            assertTrue(store.claimForSequencing(row, grant.get().generation(),
                    grant.get().sequencerLeaseToken()));
            assertFalse(store.claimForSequencing(row, grant.get().generation(),
                            grant.get().sequencerLeaseToken()),
                    "the same stale-rev row must lose the second CAS on real CouchDB");
        } finally {
            store.releaseSequencerLease(grant.get());
        }
    }

    /** A corrupt row at the queue head must surface as STOPPED, never as an empty backlog. */
    @Test
    public void aCorruptHeadRowStopsTheSequencerLoudly() throws Exception {
        String repo = "corrupt-head-repo";
        bootstrapLease(repo);
        bootstrapCounter(repo, 0L);
        store.appendV2(event(repo, "op-head", "2026-08-01T00:00:01Z"));

        // Corrupt the stored row directly: garbage state makes the strict envelope reject it.
        var doc = cloudant.getDocument(new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions
                .Builder().db(dbName)
                .docId(CouchLineageEventV2.documentId(
                        event(repo, "op-head", "2026-08-01T00:00:01Z").deliveryId()))
                .build()).execute().getResult();
        // Tamper an immutable field: the row STAYS in the backlog view (state unchanged)
        // but the strict decode's digest re-verification rejects it — the barrier case.
        doc.put("operationId", "op-TAMPERED");
        cloudant.postDocument(new com.ibm.cloud.cloudant.v1.model.PostDocumentOptions
                .Builder().db(dbName).document(doc).build()).execute();

        LineageFencedSequencer sequencer = new LineageFencedSequencer(store,
                new LineageMetrics(), "node-a", Duration.ofMinutes(5), 10, 100);
        LineageFencedSequencer.RunSummary summary = sequencer.runOnce(repo);
        assertEquals(LineageSequencingStore.SequencerHealth.STOPPED, summary.health(),
                "a blocked queue head is an outage, not an empty backlog");
    }

    /** The watermark covers v1 history too — a v1 sequence bounds the shared counter. */
    @Test
    public void theWatermarkSeesV1History() {
        String v1Repo = "v1-history-repo";
        LineageEvent v1 = new LineageEventBuilder()
                .repositoryId(v1Repo)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject(v1Repo, "doc-1")
                .addOutput("nemaki://" + v1Repo + "/archives/doc-1")
                .targets(List.of("purview"))
                .build();
        store.append(v1); // the v1 path assigns its eager sequence (auto-seeded counter)
        assertTrue(store.sequenceHighWatermark(v1Repo) >= 1,
                "v1 sequences bound the shared counter's rewind check");
    }

    @Test
    public void theFencedAllocatorFailsClosedOnAMissingCounter() {
        String otherRepo = "canopy";
        assertThrows(SequenceCounterException.class,
                () -> store.allocateSequenceFenced(otherRepo),
                "no auto-seed: a missing counter is a bootstrap gap, not a zero");
    }
}
