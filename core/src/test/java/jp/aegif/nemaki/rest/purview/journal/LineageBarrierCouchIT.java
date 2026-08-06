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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.cloudant.v1.model.DeleteDocumentOptions;
import com.ibm.cloud.cloudant.v1.model.PostAllDocsOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * §6-a's fence against a real CouchDB (A-2 Slice 4a).
 *
 * <p>The scripted tests drive a fake store with the right CAS semantics; what they cannot show
 * is whether the real one behaves that way. Two things here need the server: the barrier
 * lifecycle across genuine {@code _rev} conflicts, and the promise 4a rests on —
 *
 * <blockquote>a deployment with no barrier document persists exactly what it persisted before
 * 4a existed</blockquote>
 *
 * which is only meaningful as a DIFFERENCE. Asserting the final document set of one run proves
 * nothing; running the pre-4a construction and the 4a one against two independently empty
 * databases and comparing every document does.
 *
 * <p>Enabled by {@code NEMAKI_LINEAGE_IT_COUCHDB_URL} (with {@code ..._USER} /
 * {@code ..._PASSWORD}); {@code -Dlineage.it.required=true} makes a missing URL a failure
 * rather than a skip.
 */
public class LineageBarrierCouchIT {

    private static Cloudant cloudant;
    private static final List<String> databases = new ArrayList<>();
    private static Path spoolDir;

    @BeforeAll
    static void provision() throws Exception {
        String url = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_URL");
        if (url == null || url.isBlank()) {
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
            cloudant = new Cloudant("lineage-barrier-it", new BasicAuthenticator.Builder()
                    .username(user).password(password).build());
        } else {
            cloudant = new Cloudant("lineage-barrier-it", null);
        }
        cloudant.setServiceUrl(url);
        spoolDir = Files.createTempDirectory("lineage-barrier-it");
    }

    @AfterAll
    static void cleanUp() {
        for (String db : databases) {
            try {
                cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(db).build())
                        .execute();
            } catch (Exception ignored) {
            }
        }
        if (spoolDir != null) {
            try (var walk = Files.walk(spoolDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                    }
                });
            } catch (java.io.IOException ignored) {
            }
        }
    }

    private static CouchLineageJournalStore freshStore() {
        String db = "nemaki_lineage_it_" + UUID.randomUUID().toString().replace("-", "");
        databases.add(db);
        return CouchLineageJournalStore.forDirectClient(cloudant, db, ObjectMapperFactory.createDefaultObjectMapper());
    }

    private static String dbOf(CouchLineageJournalStore store) {
        return databases.get(databases.size() - 1);
    }

    private static LineageConfig config(String nodeId) {
        LineageConfig config = mock(LineageConfig.class);
        when(config.getNodeId()).thenReturn(nodeId);
        when(config.getReadSchemaVersions()).thenReturn(Set.of(1, 2));
        when(config.getBarrierViewTtlMs()).thenReturn(0L);
        when(config.getTargets()).thenReturn(List.of("atlas"));
        return config;
    }

    private static LineageBarrierService serviceFor(CouchLineageJournalStore store,
            LineageConfig config, LineageBarrierReader reader) {
        LineageDrestReadiness readiness = mock(LineageDrestReadiness.class);
        when(readiness.evaluate()).thenReturn(
                new LineageDrestReadiness.Readiness(true, List.of()));
        LineageSpoolMachinery machinery = mock(LineageSpoolMachinery.class);
        when(machinery.probeReadiness()).thenReturn(true);
        LineageBinaryDigest digest = mock(LineageBinaryDigest.class);
        when(digest.digest()).thenReturn("d".repeat(64));
        LineageNodeIdentity identity = new LineageNodeIdentity(store, config,
                () -> UUID.randomUUID().toString(), System::currentTimeMillis);
        return new LineageBarrierService(store, reader, identity, digest,
                new LineageCapabilityProvider(), readiness, config, machinery,
                System::currentTimeMillis);
    }

    /** The whole lifecycle against real {@code _rev} CAS. */
    @Test
    public void theLifecycleRunsOnRealRevisions() {
        CouchLineageJournalStore store = freshStore();
        // The barrier lives beside the journal, so the journal must exist first — writing one
        // event is also how a real deployment gets there.
        store.append(v1Event("op-" + UUID.randomUUID()));
        LineageConfig config = config("node-it");
        LineageBarrierReader reader = new LineageBarrierReader(store, config,
                System::currentTimeMillis);
        LineageBarrierService service = serviceFor(store, config, reader);

        assertTrue(reader.viewUncached() instanceof LineageBarrierReader.BarrierView.Pristine);

        assertTrue(service.prepare(null, null).applied());
        assertNotNull(store.readWitness(), "the witness precedes the barrier");
        assertTrue(reader.viewUncached() instanceof LineageBarrierReader.BarrierView.Present);

        assertFalse(service.activate().applied(), "no ACK yet");
        assertTrue(service.ack().applied());
        assertTrue(service.activate().applied());
        LineageWriteVersionBarrier active = service.readBarrier();
        assertEquals(2, active.writeSchemaVersion());
        assertEquals(2, active.minReaderSchemaVersion());

        assertTrue(service.rollback().applied());
        assertEquals(1, service.readBarrier().writeSchemaVersion());
        assertEquals(2, service.readBarrier().minReaderSchemaVersion(),
                "the reader floor never comes back down");

        assertTrue(service.prepare(null, null).applied());
        LineageWriteVersionBarrier rearmed = service.readBarrier();
        assertEquals(2L, rearmed.generation());
        assertTrue(rearmed.acks().isEmpty());
        assertFalse(service.activate().applied(), "the re-arm needs a fresh ACK");
    }

    /** Deleting the barrier does not restore pristine semantics. */
    @Test
    public void aDeletedBarrierBecomesIndeterminateNotPristine() {
        CouchLineageJournalStore store = freshStore();
        store.append(v1Event("op-" + UUID.randomUUID()));
        LineageConfig config = config("node-vanish");
        LineageBarrierReader reader = new LineageBarrierReader(store, config,
                System::currentTimeMillis);
        serviceFor(store, config, reader).prepare(null, null);
        assertTrue(reader.viewUncached() instanceof LineageBarrierReader.BarrierView.Present);

        Map<String, Object> raw = store.readBarrierRaw();
        cloudant.deleteDocument(new DeleteDocumentOptions.Builder()
                .db(dbOf(store))
                .docId(LineageWriteVersionBarrier.DOCUMENT_ID)
                .rev((String) raw.get("_rev"))
                .build()).execute();

        var view = reader.viewUncached();
        assertTrue(view instanceof LineageBarrierReader.BarrierView.Indeterminate);
        assertEquals(LineageBarrierReader.BARRIER_VANISHED,
                ((LineageBarrierReader.BarrierView.Indeterminate) view).reasonClass());
    }

    /**
     * The promise 4a rests on, measured as a difference: the pre-4a emitter and the 4a emitter
     * on a barrier-less deployment must leave the SAME persistence behind.
     */
    @Test
    public void aBarrierlessDeploymentPersistsExactlyWhatThePre4aBuildDid() throws Exception {
        LineageFact fact = fact();
        Set<String> databasesBefore = allDatabases();

        CouchLineageJournalStore legacyStore = freshStore();
        String legacyDb = databases.get(databases.size() - 1);
        LineageConfig legacyConfig = config("node-legacy");
        new JournaledLineageEmitter(legacyStore, legacyConfig).emit(fact);

        CouchLineageJournalStore barrierStore = freshStore();
        String barrierDb = databases.get(databases.size() - 1);
        LineageConfig barrierConfig = config("node-barrier");
        when(barrierConfig.getSpoolDir()).thenReturn(spoolDir.toString());
        LineageBarrierReader reader = new LineageBarrierReader(barrierStore, barrierConfig,
                System::currentTimeMillis);
        LineageSpoolMachinery machinery = new LineageSpoolMachinery(barrierConfig, null,
                barrierStore);
        new JournaledLineageEmitter(barrierStore, barrierConfig, reader, machinery, null)
                .emit(fact);

        // Not just the two primary databases: 4a must not create an auxiliary one either.
        Set<String> databasesAfter = allDatabases();
        assertTrue(databasesAfter.contains(legacyDb) && databasesAfter.contains(barrierDb));
        Set<String> unexpected = new java.util.LinkedHashSet<>(databasesAfter);
        unexpected.removeAll(databasesBefore);
        unexpected.remove(legacyDb);
        unexpected.remove(barrierDb);
        assertEquals(Set.of(), unexpected,
                "4a created a database beyond the journal's own");

        Map<String, Map<String, Object>> legacy = snapshot(legacyDb);
        Map<String, Map<String, Object>> barrier = snapshot(barrierDb);
        assertEquals(legacy.keySet(), barrier.keySet(),
                "4a added a document to a deployment that has no barrier");
        for (String id : legacy.keySet()) {
            assertEquals(legacy.get(id), barrier.get(id),
                    "document '" + id + "' differs between the pre-4a and 4a paths");
        }
        assertNull(barrierStore.readBarrierRaw(), "no barrier was created");
        assertNull(barrierStore.readWitness(), "no witness was created");
        assertNull(barrierStore.readNodeId(), "no node identity was allocated");
        try (var walk = Files.walk(spoolDir)) {
            assertEquals(0L, walk.filter(Files::isRegularFile).count(),
                    "nothing was spooled");
        }
    }

    private static Set<String> allDatabases() {
        return new java.util.LinkedHashSet<>(cloudant.getAllDbs(
                new com.ibm.cloud.cloudant.v1.model.GetAllDbsOptions.Builder().build())
                .execute().getResult());
    }

    /**
     * Every document in a database, with the per-run values normalized away.
     *
     * <p>Three fields cannot match across two runs and are normalized rather than hidden: the
     * audit {@code eventId} (a fresh UUID per emit BY DESIGN, which also appears in the
     * document id), {@code _rev} (a hash of a body containing that id), and the write
     * timestamps. Each is asserted PRESENT in both snapshots first, so "normalized" can never
     * quietly mean "one side did not write it".
     */
    private static Map<String, Map<String, Object>> snapshot(String db) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        var result = cloudant.postAllDocs(new PostAllDocsOptions.Builder()
                .db(db).includeDocs(true).build()).execute().getResult();
        for (var row : result.getRows()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            if (row.getDoc() != null && row.getDoc().getProperties() != null) {
                doc.putAll(row.getDoc().getProperties());
            }
            // Identity and timestamps are per-run by construction; what must match is the
            // SHAPE and the content the journal keys on.
            doc.remove("_rev");
            for (String perRun : List.of("eventId", "createdAt", "updatedAt")) {
                if (doc.containsKey(perRun)) {
                    // Present on both sides or the comparison below fails on the key set.
                    doc.put(perRun, "{normalized:" + perRun + "}");
                }
            }
            // The audit id is a fresh UUID per emit BY DESIGN, so it differs between the two
            // runs and appears in the document id. Everything else must be identical.
            byId.put(row.getId().replaceFirst(
                    "^lineage:[0-9a-f-]{36}$", "lineage:{eventId}"), doc);
        }
        return byId;
    }

    private static LineageFact fact() {
        return new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED, "op-fixed",
                "2026-08-01T00:00:00Z",
                List.of(LineageEndpoint.importArtifact("bedroom", "op-fixed", "zip", Map.of())),
                List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                List.of("atlas"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                        List.of("upload://zip"), List.of("nemaki://bedroom/objects/doc-1"),
                        Map.of(), null));
    }

    private static LineageEvent v1Event(String operationId) {
        return new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED, operationId,
                "2026-08-01T00:00:00Z",
                List.of(LineageEndpoint.importArtifact("bedroom", operationId, "zip", Map.of())),
                List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                List.of("atlas"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                        List.of("upload://" + operationId),
                        List.of("nemaki://bedroom/objects/doc-1"), Map.of(), null))
                .toV1Event();
    }
}
