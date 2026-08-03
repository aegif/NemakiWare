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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

/**
 * v2.3.22 D1 and v2.3.24 F1, measured against a real CouchDB rather than a mocked verdict.
 *
 * <p>The unit tests inject {@code DocumentTooLargeException} directly, which proves the
 * materializer's reaction but not the classification: whether a genuine over-limit write
 * actually arrives as 413 / {@code document_too_large} rather than as some other status is a
 * fact about the server, and only the server can answer it. This IT lowers CouchDB's own
 * {@code max_document_size} for the duration of the test and drives the real path:
 *
 * <ul>
 *   <li>a fact whose FIRST chunk is refused parks with nothing written (D1);</li>
 *   <li>a fact whose LATER chunk is refused has its already-written rows made
 *       non-projectable BEFORE it parks (F1) — the case the mid-plan defect left behind.</li>
 * </ul>
 *
 * <p>Enabled by {@code NEMAKI_LINEAGE_IT_COUCHDB_URL} (with
 * {@code NEMAKI_LINEAGE_IT_COUCHDB_USER} / {@code NEMAKI_LINEAGE_IT_COUCHDB_PASSWORD}), the
 * same variables as {@link LineageSequencingCouchIT}; {@code -Dlineage.it.required=true}
 * makes a missing URL a failure instead of a skip.
 *
 * <p><b>It changes a node-global server setting</b> ({@code max_document_size}) and restores
 * the original value in {@link #restore()}, which runs even when a test fails. The value is
 * captured before the first change, so a re-run after a crashed run still restores whatever
 * the server has now rather than a hard-coded default.
 */
public class LineageParkingCouchIT {

    private static final String REPO = "bedroom";
    /** Small enough that a handful of endpoints exceed it, large enough for a lone anchor. */
    private static final String TEST_MAX_DOCUMENT_SIZE = "6000";

    private static Cloudant cloudant;
    private static HttpClient http;
    private static String baseUrl;
    private static String authHeader;
    private static String dbName;
    private static String originalMaxDocumentSize;
    private static boolean restoreNeeded;
    private static CouchLineageJournalStore store;
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
        baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String user = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_USER");
        String password = System.getenv("NEMAKI_LINEAGE_IT_COUCHDB_PASSWORD");
        if (user != null && !user.isBlank()) {
            cloudant = new Cloudant("lineage-parking-it", new BasicAuthenticator.Builder()
                    .username(user).password(password).build());
            authHeader = "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        } else {
            cloudant = new Cloudant("lineage-parking-it", null);
        }
        cloudant.setServiceUrl(baseUrl);
        http = HttpClient.newHttpClient();
        dbName = "nemaki_lineage_it_" + UUID.randomUUID().toString().replace("-", "");
        store = CouchLineageJournalStore.forDirectClient(cloudant, dbName, new ObjectMapper());
        spoolDir = Files.createTempDirectory("lineage-parking-it");

        // Absent means "CouchDB's built-in default", which is the usual state — restoring it
        // is a DELETE, not a PUT of some value we invented.
        originalMaxDocumentSize = config("GET", null);
        restoreNeeded = true;
        config("PUT", TEST_MAX_DOCUMENT_SIZE);
    }

    @AfterAll
    static void restore() {
        if (http != null && restoreNeeded) {
            try {
                config(originalMaxDocumentSize == null ? "DELETE" : "PUT",
                        originalMaxDocumentSize);
            } catch (Exception e) {
                throw new IllegalStateException("FAILED TO RESTORE CouchDB max_document_size to "
                        + (originalMaxDocumentSize == null ? "its built-in default"
                                : originalMaxDocumentSize)
                        + " — restore it by hand before using this server again", e);
            }
        }
        if (cloudant != null && dbName != null) {
            try {
                cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(dbName).build())
                        .execute();
            } catch (Exception ignored) {
            }
        }
        if (spoolDir != null) {
            try (var walk = Files.walk(spoolDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Reads, writes or clears {@code couchdb/max_document_size} on the local node.
     *
     * @return the value for GET, or {@code null} when the key is unset (CouchDB answers 404
     *         {@code unknown_config_value} while still enforcing its built-in default)
     */
    private static String config(String method, String value) throws Exception {
        String target = baseUrl + "/_node/_local/_config/couchdb/max_document_size";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        switch (method) {
            case "PUT" -> builder.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("\"" + value + "\""));
            case "DELETE" -> builder.DELETE();
            default -> builder.GET();
        }
        HttpResponse<String> response =
                http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404 && !"PUT".equals(method)) {
            return null; // unset — for GET the default is in force, for DELETE already clear
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("max_document_size " + method + " returned "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body().trim().replace("\"", "");
    }

    /**
     * An export fact of {@code documents} inputs, each name padded to {@code padding} chars so
     * the caller controls which chunk breaches the server's ceiling.
     */
    private static LineageSpoolPayloadV1 exportFact(String operationId, int documents,
            int padding) {
        List<LineageEndpoint> inputs = new ArrayList<>();
        for (int i = 0; i < documents; i++) {
            String name = String.format("doc-%03d-", i) + "x".repeat(padding) + ".txt";
            inputs.add(LineageEndpoint.document(REPO, String.format("doc-%03d", i), name));
        }
        LineageFact fact = new LineageFact(REPO, LineageProcessType.EXPORT_SELECTED_OBJECTS,
                operationId, "2026-08-01T00:00:00Z", inputs,
                List.of(LineageEndpoint.exportArtifact(REPO, operationId, "ZIP", "out.zip", 1L)),
                List.of("atlas"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                        List.of("i"), List.of("o"), Map.of(), null));
        return LineageSpoolPayloadV1.of(fact);
    }

    private static Path spool(LineageFactSpool spool, LineageSpoolPayloadV1 payload)
            throws IOException {
        assertEquals(LineageFactSpool.AppendOutcome.APPENDED, spool.append(payload));
        try (var walk = Files.walk(spoolDir)) {
            return walk.filter(f -> f.getFileName().toString()
                            .equals("fact-" + payload.spoolRecordId() + ".json"))
                    .findFirst().orElseThrow();
        }
    }

    private static LineageSpoolMaterializer materializer(LineageFactSpool factSpool,
            LineageChunkPlanner.ChunkLimits limits) {
        return new LineageSpoolMaterializer(store, store, store,
                p -> java.util.Optional.of(new WriteVersionResolver.ResolvedWrite(2, 0L)),
                factSpool, null, () -> UUID.randomUUID().toString(),
                System::currentTimeMillis, limits,
                // Our own ceiling stays generous: the point is that the SERVER refuses, not
                // that the pre-write ruler caught it.
                4L * 1024 * 1024);
    }

    /**
     * D1 against the real server: the first chunk is already over the node's limit, so the
     * fact parks with no journal row at all.
     */
    @Test
    public void aFactTheServerRefusesOutrightParksWithNothingWritten() throws Exception {
        LineageFactSpool factSpool = new LineageFactSpool(spoolDir, null);
        LineageSpoolPayloadV1 payload = exportFact("op-refused-" + UUID.randomUUID(), 1, 20000);
        Path factFile = spool(factSpool, payload);

        LineageSpoolMaterializer materializer = materializer(factSpool,
                new LineageChunkPlanner.ChunkLimits(1000L, 1024L * 1024L));
        LineageSpoolMaterializer.MaterializeResult result =
                materializer.materialize(payload, factFile);

        assertEquals(LineageSpoolMaterializer.Outcome.ALREADY_ACKED, result.outcome(),
                "CouchDB's own verdict parks the fact rather than retrying forever");
        assertTrue(factSpool.readOversizeMarker(factFile) instanceof LineageFactSpool.AckBytes,
                "the parking marker is the durable evidence");
        LineageMaterializationDecision decision = store.readDecision(payload.spoolRecordId());
        assertNotNull(decision, "the decision is frozen before the write is attempted");
        for (LineageMaterializationDecision.PlanEntry entry : decision.planEntries()) {
            assertNull(store.findV2ByRecordId(
                            ((LineageMaterializationDecision.V2Entry) entry).deliveryId()),
                    "nothing was written, so nothing can be projected");
        }
    }

    /**
     * F1 against the real server: chunk 0 fits and is written, a later chunk does not. The
     * already-written row must be non-projectable before the fact parks.
     */
    @Test
    public void aRefusalAtALaterChunkTerminalizesWhatWasAlreadyWritten() throws Exception {
        LineageFactSpool factSpool = new LineageFactSpool(spoolDir, null);
        // Two endpoints per chunk; the second document's name is what breaches the ceiling,
        // and canonical order puts doc-000 (short) before doc-001 (long).
        List<LineageEndpoint> inputs = List.of(
                LineageEndpoint.document(REPO, "doc-000", "small.txt"),
                LineageEndpoint.document(REPO, "doc-001", "x".repeat(20000) + ".txt"));
        String operationId = "op-midplan-" + UUID.randomUUID();
        LineageFact fact = new LineageFact(REPO, LineageProcessType.EXPORT_SELECTED_OBJECTS,
                operationId, "2026-08-01T00:00:00Z", inputs,
                List.of(LineageEndpoint.exportArtifact(REPO, operationId, "ZIP", "out.zip", 1L)),
                List.of("atlas"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.EXPORT_SELECTED_OBJECTS,
                        List.of("i"), List.of("o"), Map.of(), null));
        LineageSpoolPayloadV1 payload = LineageSpoolPayloadV1.of(fact);
        Path factFile = spool(factSpool, payload);

        // One payload endpoint per chunk (plus the replicated anchor) → two chunks.
        var limits = new LineageChunkPlanner.ChunkLimits(2L, 1024L * 1024L);
        List<LineageChunkPlanner.ChunkSlice> slices =
                LineageChunkPlanner.partition(payload, limits, "00000000-0000-4000-8000-"
                        + "000000000000");
        assertEquals(2, slices.size(), "the fixture must produce a first chunk that fits and"
                + " a later chunk that does not");

        LineageSpoolMaterializer.MaterializeResult result =
                materializer(factSpool, limits).materialize(payload, factFile);

        assertEquals(LineageSpoolMaterializer.Outcome.ALREADY_ACKED, result.outcome());
        assertTrue(factSpool.readOversizeMarker(factFile) instanceof LineageFactSpool.AckBytes);
        LineageMaterializationDecision decision = store.readDecision(payload.spoolRecordId());
        assertNotNull(decision);
        int found = 0;
        for (LineageMaterializationDecision.PlanEntry entry : decision.planEntries()) {
            String deliveryId = ((LineageMaterializationDecision.V2Entry) entry).deliveryId();
            LineageJournalRowV2 row = store.findV2ByRecordId(deliveryId);
            if (row == null) {
                continue;
            }
            found++;
            for (Map.Entry<String, LineageTargetLifecycle> e
                    : row.targetLifecycles().entrySet()) {
                assertEquals(LineagePublishStatus.UNRESOLVED, e.getValue().status(),
                        "row " + deliveryId + " target '" + e.getKey() + "' survived the park"
                                + " in a projectable state — K-1 of K chunks would publish as"
                                + " if they were the whole fact");
            }
        }
        assertTrue(found > 0, "the fixture must actually write a row before the refusal —"
                + " otherwise this test degenerates into the outright-refusal case");
    }
}
