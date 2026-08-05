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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The catalog wait against a real CouchDB.
 *
 * <p>The coordinator's unit tests pin the ordering; this pins the things only a real server
 * decides — that the CAS actually refuses a row someone else moved, that the waiting metadata
 * survives the codec, and that the reverse-lookup view finds a waiting event by its obligation.
 * The view is the part a mock cannot check at all: it is JavaScript inside a design document,
 * and its field names have to agree with the codec's or the lookup silently returns nothing.
 */
public class LineageCatalogWaitCouchIT {

    private static final String TARGET = "atlas";
    private static final String REPO = "bedroom";

    private static Cloudant cloudant;
    private static String dbName;
    private static CouchLineageJournalStore store;

    @BeforeAll
    static void provision() {
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
        cloudant = user != null && !user.isBlank()
                ? new Cloudant("lineage-it", new BasicAuthenticator.Builder()
                        .username(user).password(password).build())
                : new Cloudant("lineage-it", null);
        cloudant.setServiceUrl(url);
        dbName = "nemaki_wait_it_" + UUID.randomUUID().toString().replace("-", "");
        store = CouchLineageJournalStore.forDirectClient(cloudant, dbName, new ObjectMapper());
        store.ensureDatabase();
    }

    @AfterAll
    static void dropDatabase() {
        if (cloudant != null && dbName != null) {
            try {
                cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(dbName).build())
                        .execute();
            } catch (Exception ignored) {
                // Per-run database; a failed drop is not a test result.
            }
        }
    }

    @Test
    @DisplayName("entering the wait stores the whole set and survives the codec")
    public void enterStoresEverything() {
        String recordId = append("enter");

        assertTrue(store.enterCatalogWait(recordId, TARGET, List.of("task-z", "task-a")));

        LineageTargetLifecycle lifecycle = lifecycleOf(recordId);
        assertEquals(LineagePublishStatus.WAITING_FOR_CATALOG, lifecycle.status());
        assertEquals(List.of("task-a", "task-z"), lifecycle.waitingTaskKeys(),
                "stored deduped and sorted, so two projectors write identical rows");
        assertNotNull(lifecycle.waitingSinceMs());
        assertTrue(lifecycle.waitingSinceMs() > 0);
    }

    @Test
    @DisplayName("an empty waiting set is refused before it reaches CouchDB")
    public void emptySetRefused() {
        String recordId = append("empty");
        assertThrows(IllegalArgumentException.class,
                () -> store.enterCatalogWait(recordId, TARGET, List.of()));
        assertEquals(LineagePublishStatus.PENDING, lifecycleOf(recordId).status());
    }

    /** The CAS is what stops a second projector from re-entering a wait already left. */
    @Test
    @DisplayName("entering twice fails the second time, and does not disturb the first")
    public void enterIsCasFenced() {
        String recordId = append("cas");
        assertTrue(store.enterCatalogWait(recordId, TARGET, List.of("task-a")));
        assertFalse(store.enterCatalogWait(recordId, TARGET, List.of("task-b")),
                "the row is no longer PENDING");
        assertEquals(List.of("task-a"), lifecycleOf(recordId).waitingTaskKeys());
    }

    /**
     * The wait start survives the round trip, which is what makes max age mean anything.
     *
     * <p>A resume that dropped it would let an event that has been waiting for days present
     * itself as freshly waiting on its next round and never reach the maximum.
     */
    @Test
    @DisplayName("resuming keeps waitingSinceMs and drops the keys")
    public void resumeKeepsTheStart() {
        String recordId = append("resume");
        assertTrue(store.enterCatalogWait(recordId, TARGET, List.of("task-a")));
        Long firstStart = lifecycleOf(recordId).waitingSinceMs();

        assertTrue(store.resumeFromCatalogWait(recordId, TARGET));
        LineageTargetLifecycle resumed = lifecycleOf(recordId);
        assertEquals(LineagePublishStatus.PENDING, resumed.status());
        assertEquals(firstStart, resumed.waitingSinceMs(), "the original start must survive");
        assertEquals(null, resumed.waitingTaskKeys(), "the answered keys go");

        // Waiting again keeps the ORIGINAL start rather than restarting the clock.
        assertTrue(store.enterCatalogWait(recordId, TARGET, List.of("task-a", "task-b")));
        assertEquals(firstStart, lifecycleOf(recordId).waitingSinceMs());
        assertEquals(List.of("task-a", "task-b"), lifecycleOf(recordId).waitingTaskKeys());
    }

    @Test
    @DisplayName("resume and expire refuse a row that is not waiting")
    public void leavingRequiresWaiting() {
        String recordId = append("not-waiting");
        assertFalse(store.resumeFromCatalogWait(recordId, TARGET));
        assertFalse(store.expireCatalogWait(recordId, TARGET,
                new LineageTargetLifecycle.TerminalReason("CATALOG_WAIT_EXPIRED", "x", 1L)));
        assertEquals(LineagePublishStatus.PENDING, lifecycleOf(recordId).status());
    }

    @Test
    @DisplayName("expiry makes the event UNRESOLVED with a durable reason")
    public void expiryIsDurable() {
        String recordId = append("expire");
        assertTrue(store.enterCatalogWait(recordId, TARGET, List.of("task-a")));

        assertTrue(store.expireCatalogWait(recordId, TARGET,
                new LineageTargetLifecycle.TerminalReason("CATALOG_WAIT_EXPIRED",
                        "waited past the configured maximum", 1_700_000_000_000L)));

        LineageTargetLifecycle expired = lifecycleOf(recordId);
        assertEquals(LineagePublishStatus.UNRESOLVED, expired.status());
        assertEquals("CATALOG_WAIT_EXPIRED", expired.terminalReason().reason());
        assertEquals(null, expired.waitingTaskKeys());
    }

    @Test
    @DisplayName("expiry without a reason is refused — UNRESOLVED must be auditable")
    public void expiryNeedsAReason() {
        String recordId = append("no-reason");
        store.enterCatalogWait(recordId, TARGET, List.of("task-a"));
        assertThrows(IllegalArgumentException.class,
                () -> store.expireCatalogWait(recordId, TARGET, null));
        assertEquals(LineagePublishStatus.WAITING_FOR_CATALOG, lifecycleOf(recordId).status());
    }

    /**
     * The reverse lookup: which events is a given obligation holding up.
     *
     * <p>Only a real CouchDB can answer this — the view is JavaScript reading field names that
     * must agree with the codec's. If they disagree the lookup returns nothing and an operator
     * concludes the obligation is blocking no one.
     */
    @Test
    @DisplayName("the reverse view finds waiting events by their obligation, per target")
    public void reverseLookupFindsWaitingEvents() {
        String first = append("rev-1");
        String second = append("rev-2");
        String unrelated = append("rev-3");
        assertTrue(store.enterCatalogWait(first, TARGET, List.of("task-shared", "task-only-1")));
        assertTrue(store.enterCatalogWait(second, TARGET, List.of("task-shared")));
        assertTrue(store.enterCatalogWait(unrelated, TARGET, List.of("task-other")));

        assertEquals(2, waitingOn("task-shared").size(),
                "both events waiting on the shared obligation");
        assertEquals(1, waitingOn("task-only-1").size());
        assertEquals(List.of(TARGET), waitingOn("task-only-1"), "the value is the target");
        assertTrue(waitingOn("task-never-created").isEmpty());

        // Resolving one event's wait removes only that event from the lookup.
        assertTrue(store.resumeFromCatalogWait(second, TARGET));
        assertEquals(1, waitingOn("task-shared").size());
    }

    /**
     * A waiting row with no keys is corruption, and must not appear in the lookup.
     *
     * <p>Emitting it would offer a resolver an event whose wait nobody can account for. Its
     * absence, plus its status, is the louder signal.
     */
    @Test
    @DisplayName("a keyless waiting row is not emitted by the reverse view")
    public void keylessWaitingRowIsNotEmitted() {
        String recordId = append("keyless");
        store.enterCatalogWait(recordId, TARGET, List.of("task-keyless"));
        // Strip the keys behind the store's back, as a partially-written older row would be.
        Map<String, Object> raw = store.readV2RawStrict(recordId);
        assertNotNull(raw, "the row must exist");
        @SuppressWarnings("unchecked")
        Map<String, Object> waits = (Map<String, Object>) raw.get("v2WaitingByTarget");
        @SuppressWarnings("unchecked")
        Map<String, Object> wait = new LinkedHashMap<>((Map<String, Object>) waits.get(TARGET));
        wait.remove("taskKeys");
        waits.put(TARGET, wait);
        store.client().update(raw);

        assertTrue(waitingOn("task-keyless").isEmpty(),
                "corruption must not be offered to a resolver as work");
    }

    /**
     * The deployed design document must be readable, or activation is impossible.
     *
     * <p>{@code viewSignatureViolations()} compares the deployed views against this binary's.
     * It reads through {@code readRawStrict}, which handed {@code _design/lineage} straight to
     * the SDK — and the SDK refuses any id starting with {@code _}. So the check reported
     * "design document unreadable" on every deployment, D-rest readiness was permanently red,
     * and 4b could never be activated. Only a real CouchDB shows this: a mock returns whatever
     * it was told to.
     */
    @Test
    @DisplayName("the deployed design document is readable, so the view check can actually run")
    public void designDocumentIsReadable() {
        Map<String, Object> design = store.readRawStrict("_design/" + store.designDoc());
        assertNotNull(design, "the design document must be readable after provisioning");
        assertEquals("_design/" + store.designDoc(), design.get("_id"));
        assertTrue(design.get("views") instanceof Map, "and must carry its views");

        // The check it exists for: with the document readable, the only violations left are
        // real differences, not an unreadable document.
        List<String> violations = store.viewSignatureViolations();
        assertTrue(violations.stream().noneMatch(v -> v.contains("unreadable")),
                "the deployed design document must not read as unreadable: " + violations);
        // And every view this binary defines must be FOUND. The SDK returns its own typed view
        // model rather than a Map, and reading those as maps reported every view as missing
        // from a design document that had all of them — the same activation block, one layer on.
        assertTrue(violations.stream().noneMatch(v -> v.contains("missing from deployed")),
                "the provisioned design document has every view: " + violations);
        assertEquals(List.of(), violations,
                "a freshly provisioned database must match this binary exactly");
    }

    /** A document that is genuinely absent is null, not an error. */
    @Test
    @DisplayName("an absent design document is null rather than a failure")
    public void absentDesignDocumentIsNull() {
        assertEquals(null, store.readRawStrict("_design/no-such-design-document"));
    }

    /**
     * The production reverse-lookup reader, against a real CouchDB.
     *
     * <p>Everything here is something a mock cannot settle: the view is JavaScript, the reduce
     * makes {@code include_docs} illegal, and CouchDB returns rows in its own order.
     */
    @org.junit.jupiter.api.Nested
    class WaitingEventReader {

        @Test
        @DisplayName("a waiting event is found by its task key, with order and snapshot")
        void findsWaitingEvent() {
            String seed = "reader-" + UUID.randomUUID();
            String recordId = append(seed);
            String taskKey = LineageCatalogObligation.taskKey(TARGET, REPO,
                    EndpointKind.CMIS_DOCUMENT, "nemaki://" + REPO + "/objects/doc-" + seed);
            assertTrue(store.enterCatalogWait(recordId, TARGET, List.of(taskKey)));

            var candidates = new CouchLineageWaitingEventSource(store).candidatesFor(taskKey);

            assertEquals(1, candidates.size());
            var candidate = candidates.get(0);
            assertEquals(REPO, candidate.order().repositoryId());
            assertEquals(recordId, candidate.order().deliveryId());
            assertEquals(TARGET, candidate.snapshot().target());
            assertEquals(EndpointKind.CMIS_DOCUMENT, candidate.snapshot().endpointKind());
            // The snapshot says what was observed, never what the source is now.
            assertEquals(LineageSourceDisposition.SOURCE_UNKNOWN,
                    candidate.snapshot().sourceDisposition());
        }

        @Test
        @DisplayName("an unrelated task key finds nothing, and a missing view is an error")
        void unrelatedAndMissingView() {
            var reader = new CouchLineageWaitingEventSource(store);
            String unrelated = LineageCatalogObligation.taskKey(TARGET, REPO,
                    EndpointKind.CMIS_DOCUMENT, "nemaki://" + REPO + "/objects/never-waited-on");
            assertTrue(reader.candidatesFor(unrelated).isEmpty());

            // Drop the view and confirm the reader refuses rather than reporting nobody waits.
            com.ibm.cloud.cloudant.v1.model.Document design =
                    store.client().get("_design/" + store.designDoc());
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("_id", design.getId());
            raw.put("_rev", design.getRev());
            raw.putAll(design.getProperties());
            Object views = raw.get("views");
            Map<String, Object> plain = new LinkedHashMap<>();
            ((Map<?, ?>) views).forEach((k, v) -> {
                Map<String, Object> def = new LinkedHashMap<>();
                if (v instanceof com.ibm.cloud.cloudant.v1.model.DesignDocumentViewsMapReduce t) {
                    def.put("map", t.map());
                    if (t.reduce() != null) {
                        def.put("reduce", t.reduce());
                    }
                } else if (v instanceof Map<?, ?> m) {
                    m.forEach((mk, mv) -> def.put(String.valueOf(mk), mv));
                }
                plain.put(String.valueOf(k), def);
            });
            Object saved = plain.remove("v2_waiting_by_task_key");
            try {
                raw.put("views", plain);
                store.client().update(raw);
                org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                        () -> reader.candidatesFor(unrelated),
                        "a missing view must never read as 'nobody is waiting'");
            } finally {
                Map<String, Object> restore = store.readV2RawStrict("x") == null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>();
                com.ibm.cloud.cloudant.v1.model.Document current =
                        store.client().get("_design/" + store.designDoc());
                restore.put("_id", current.getId());
                restore.put("_rev", current.getRev());
                restore.putAll(current.getProperties());
                plain.put("v2_waiting_by_task_key", saved);
                restore.put("views", plain);
                store.client().update(restore);
            }
        }

        /** Two targets waiting on their own tasks must not see each other's. */
        @Test
        @DisplayName("each target's wait is found under its own task key only")
        void targetsAreSeparate() {
            String seed = "two-targets-" + UUID.randomUUID();
            String recordId = append(seed);
            String qn = "nemaki://" + REPO + "/objects/doc-" + seed;
            String atlasKey = LineageCatalogObligation.taskKey(TARGET, REPO,
                    EndpointKind.CMIS_DOCUMENT, qn);
            String otherKey = LineageCatalogObligation.taskKey("purview", REPO,
                    EndpointKind.CMIS_DOCUMENT, qn);
            assertTrue(store.enterCatalogWait(recordId, TARGET, List.of(atlasKey)));

            var reader = new CouchLineageWaitingEventSource(store);
            assertEquals(1, reader.candidatesFor(atlasKey).size());
            assertTrue(reader.candidatesFor(otherKey).isEmpty(),
                    "a target that is not waiting must not be answered for");
        }

        /**
         * A row that belongs and cannot be read must not shrink the population.
         *
         * <p>Dropping it would hand the resolver a shorter list, which reads as a smaller clean
         * set rather than an incomplete one — and the resolver would settle an obligation
         * against a population nobody enumerated.
         */
        @Test
        @DisplayName("a malformed waiting row is an error, not a quietly shorter list")
        void malformedRowIsNotDropped() {
            String seed = "malformed-" + UUID.randomUUID();
            String recordId = append(seed);
            String taskKey = LineageCatalogObligation.taskKey(TARGET, REPO,
                    EndpointKind.CMIS_DOCUMENT, "nemaki://" + REPO + "/objects/doc-" + seed);
            assertTrue(store.enterCatalogWait(recordId, TARGET, List.of(taskKey)));

            // Strip the task keys behind the store's back, as a half-written row would be.
            Map<String, Object> raw = store.readV2RawStrict(recordId);
            @SuppressWarnings("unchecked")
            Map<String, Object> waits = (Map<String, Object>) raw.get("v2WaitingByTarget");
            @SuppressWarnings("unchecked")
            Map<String, Object> wait =
                    new LinkedHashMap<>((Map<String, Object>) waits.get(TARGET));
            wait.remove("taskKeys");
            waits.put(TARGET, wait);
            store.client().update(raw);

            // The view no longer emits it (keyless rows are excluded), so the population is
            // empty rather than wrong — which the resolver reports as NoWaitingEvent.
            assertTrue(new CouchLineageWaitingEventSource(store).candidatesFor(taskKey).isEmpty());
        }

        /** The reduce is what makes include_docs illegal; the reader must not trip on it. */
        @Test
        @DisplayName("the reader queries with reduce=false and gets documents back")
        void reduceIsDisabled() {
            String seed = "reduce-" + UUID.randomUUID();
            String recordId = append(seed);
            String taskKey = LineageCatalogObligation.taskKey(TARGET, REPO,
                    EndpointKind.CMIS_DOCUMENT, "nemaki://" + REPO + "/objects/doc-" + seed);
            assertTrue(store.enterCatalogWait(recordId, TARGET, List.of(taskKey)));

            var candidates = new CouchLineageWaitingEventSource(store).candidatesFor(taskKey);
            assertEquals(1, candidates.size(), "include_docs must have returned the document");
            assertNotNull(candidates.get(0).snapshot().evidenceDigest());
        }

        /** Several events waiting on one shared obligation all come back. */
        @Test
        @DisplayName("every event waiting on a shared task is returned")
        void sharedTaskReturnsEveryWaiter() {
            String qn = "nemaki://" + REPO + "/objects/shared-" + UUID.randomUUID();
            String taskKey = LineageCatalogObligation.taskKey(TARGET, REPO,
                    EndpointKind.CMIS_DOCUMENT, qn);
            int waiters = 3;
            for (int i = 0; i < waiters; i++) {
                LineageEventV2 event = new LineageEventV2Builder()
                        .eventId("evt-shared-" + i + "-" + UUID.randomUUID())
                        .occurredAt("2026-08-01T00:00:0" + i + "Z")
                        .repositoryId(REPO)
                        .processType(LineageProcessType.ARCHIVE_LOCAL)
                        .operationId("op-shared-" + i + "-" + UUID.randomUUID())
                        .delivery(new LineageDelivery.Original(List.of(TARGET)))
                        .addInput(LineageEndpoint.document(REPO,
                                qn.substring(qn.lastIndexOf('/') + 1), "a.txt"))
                        .addOutput(LineageEndpoint.archive(REPO, "arc-" + i, "arc-" + i, 1L))
                        .build();
                store.appendV2(event);
                assertTrue(store.enterCatalogWait(event.deliveryId(), TARGET, List.of(taskKey)));
            }

            assertEquals(waiters,
                    new CouchLineageWaitingEventSource(store).candidatesFor(taskKey).size());
        }
    }

    // ------------------------------------------------------------------

    /** The targets a waiting event reports for one obligation, via the reverse view. */
    private static List<String> waitingOn(String taskKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", taskKey);
        params.put("reduce", false);
        com.ibm.cloud.cloudant.v1.model.ViewResult result =
                store.client().queryView(store.designDoc(), "v2_waiting_by_task_key", params);
        if (result == null || result.getRows() == null) {
            return List.of();
        }
        return result.getRows().stream().map(row -> String.valueOf(row.getValue())).toList();
    }

    private static LineageTargetLifecycle lifecycleOf(String recordId) {
        LineageJournalRowV2 row = store.findV2ByRecordId(recordId);
        assertNotNull(row, "the row must exist");
        LineageTargetLifecycle lifecycle = row.targetLifecycles().get(TARGET);
        assertNotNull(lifecycle, "the target must have a lifecycle");
        return lifecycle;
    }

    /** A v2 row in PENDING for the target, appended through the production path. */
    private static String append(String seed) {
        LineageEventV2 event = new LineageEventV2Builder()
                .eventId("evt-" + seed)
                .occurredAt("2026-08-01T00:00:00Z")
                .repositoryId(REPO)
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .operationId("op-" + seed)
                .delivery(new LineageDelivery.Original(List.of(TARGET)))
                .addInput(LineageEndpoint.document(REPO, "doc-" + seed, "a.txt"))
                .addOutput(LineageEndpoint.archive(REPO, "doc-" + seed, "doc-" + seed, 1L))
                .build();
        store.appendV2(event);
        return event.deliveryId();
    }
}
