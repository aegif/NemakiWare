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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.DeleteDatabaseOptions;
import com.ibm.cloud.sdk.core.security.BasicAuthenticator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The historical machine's production stores, against a real CouchDB.
 *
 * <h2>Why a mock is not enough here</h2>
 *
 * <p>Every guarantee these stores make is a property of CouchDB's own semantics: {@code _rev}
 * conflict on a concurrent update, 409 on a duplicate {@code _id}, what a view emits, what a
 * missing document returns. An in-memory fake asserts that the <em>test</em> implements those
 * rules, which is exactly the thing that cannot be assumed. The unit tests prove the machine's
 * logic given a correct store; this proves the store.
 *
 * <p>Skipped locally without {@code NEMAKI_LINEAGE_IT_COUCHDB_URL}; a dedicated CI job sets
 * {@code -Dlineage.it.required=true}, where a missing URL is a failure rather than a silent
 * green with zero tests run.
 */
public class LineageHistoricalStoreCouchIT {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";
    private static final EndpointKind KIND = EndpointKind.CMIS_DOCUMENT;

    private static Cloudant cloudant;
    private static String dbName;
    private static CouchLineageJournalStore journal;
    private static CouchLineageHistoricalPublishIntentStore intents;
    private static CouchLineageHistoricalCompensationStore compensations;

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
        dbName = "nemaki_hist_it_" + UUID.randomUUID().toString().replace("-", "");
        journal = CouchLineageJournalStore.forDirectClient(cloudant, dbName, new ObjectMapper());
        journal.ensureDatabase();
        intents = new CouchLineageHistoricalPublishIntentStore(journal);
        compensations = new CouchLineageHistoricalCompensationStore(journal);
    }

    @AfterAll
    static void dropDatabase() {
        if (cloudant != null && dbName != null) {
            try {
                cloudant.deleteDatabase(new DeleteDatabaseOptions.Builder().db(dbName).build())
                        .execute();
            } catch (Exception ignored) {
                // The database is per-run; a failure to drop it is not a test result.
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static String digest(String seed) {
        return LineageCanonicalHash.hash("IT", seed);
    }

    private static LineageHistoricalPublishIntent intent(String seed, long observationSequence,
            String observationDeliveryId) {
        String subject = digest("subject-" + seed);
        String snapshotEvidence = digest("snapshot-" + seed);
        String sourceEvidence = digest("source-" + seed);
        String operation = digest("operation-" + seed);
        String taskKey = digest("task-" + seed);
        String intentId = LineageHistoricalPublishIntent.intentId(taskKey, TARGET, REPO, KIND,
                subject, snapshotEvidence, sourceEvidence, operation, 1, observationSequence,
                observationDeliveryId);
        return new LineageHistoricalPublishIntent(null, intentId, taskKey, TARGET, REPO, KIND,
                subject, snapshotEvidence, sourceEvidence, operation, 1, observationSequence,
                observationDeliveryId, null, LineageHistoricalPublishIntent.State.PLANNED,
                null, null, 0L, 0, 1000L, null);
    }

    /** Two intents for ONE subject, differing only in the observation they were taken at. */
    private static LineageHistoricalPublishIntent sameSubjectIntent(String seed,
            LineageHistoricalPublishIntent sibling, long observationSequence,
            String observationDeliveryId) {
        String snapshotEvidence = digest("snapshot-" + seed);
        String sourceEvidence = digest("source-" + seed);
        String operation = digest("operation-" + seed);
        String intentId = LineageHistoricalPublishIntent.intentId(sibling.taskKey(), TARGET,
                REPO, KIND, sibling.subjectDigest(), snapshotEvidence, sourceEvidence, operation,
                1, observationSequence, observationDeliveryId);
        return new LineageHistoricalPublishIntent(null, intentId, sibling.taskKey(), TARGET,
                REPO, KIND, sibling.subjectDigest(), snapshotEvidence, sourceEvidence, operation,
                1, observationSequence, observationDeliveryId, null,
                LineageHistoricalPublishIntent.State.PLANNED, null, null, 0L, 0, 1000L, null);
    }

    private static LineageHistoricalCompensation compensation(String seed) {
        String subject = digest("subject-" + seed);
        String operation = digest("operation-" + seed);
        return new LineageHistoricalCompensation(null,
                LineageHistoricalCompensation.taskId(TARGET, REPO, KIND, subject, operation),
                TARGET, REPO, KIND, subject, operation, digest("published-" + seed),
                digest("observed-" + seed),
                LineageHistoricalCompensation.Reason.SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH,
                1000L, LineageHistoricalCompensation.State.PENDING);
    }

    // ------------------------------------------------------------------ intents

    @Nested
    @DisplayName("intent create-if-absent")
    class Creation {

        @Test
        @DisplayName("the same plan twice leaves one document")
        void idempotent() {
            LineageHistoricalPublishIntent plan = intent("idem", 10L, "d-1");

            LineageHistoricalPublishIntent first = intents.createIfAbsent(plan);
            LineageHistoricalPublishIntent second = intents.createIfAbsent(plan);

            assertEquals(first.intentId(), second.intentId());
            assertEquals(LineageHistoricalPublishIntent.State.PLANNED, second.state());
        }

        /** The id is derived from the plan, so this is corruption rather than a race. */
        @Test
        @DisplayName("a different plan under one id is refused")
        void differentPlanIsRefused() {
            LineageHistoricalPublishIntent plan = intent("conflict", 10L, "d-1");
            intents.createIfAbsent(plan);

            LineageHistoricalPublishIntent impostor = new LineageHistoricalPublishIntent(null,
                    plan.intentId(), plan.taskKey(), TARGET, REPO, KIND, plan.subjectDigest(),
                    digest("other-snapshot"), plan.sourceEvidenceDigest(),
                    plan.plannedOperationDigest(), 1, plan.observationSequence(),
                    plan.observationDeliveryId(), null,
                    LineageHistoricalPublishIntent.State.PLANNED, null, null, 0L, 0, 1L, null);

            assertThrows(LineageHistoricalPublishIntentStore.IntentPlanConflictException.class,
                    () -> intents.createIfAbsent(impostor));
        }

        /** A missing document is an ordinary answer; it must not look like a failure. */
        @Test
        @DisplayName("reading an absent intent is empty, not an error")
        void absentIsEmpty() {
            assertTrue(intents.read(digest("never-created")).isEmpty());
            assertTrue(intents.read(null).isEmpty());
            assertTrue(intents.read("  ").isEmpty());
        }

        /** A document of another type must not decode as an intent. */
        @Test
        @DisplayName("a foreign document is refused, not partly understood")
        void foreignDocumentIsRefused() {
            java.util.Map<String, Object> foreign = new java.util.LinkedHashMap<>();
            foreign.put("_id", LineageHistoricalPublishIntent.DOCUMENT_ID_PREFIX
                    + digest("foreign"));
            foreign.put("type", "lineage_event_v2");
            foreign.put("state", "PLANNED");
            journal.client().create(foreign);

            assertThrows(LineageHistoricalPublishIntentStore.IntentStorageException.class,
                    () -> intents.read(digest("foreign")));
        }
    }

    @Nested
    @DisplayName("claim, lease and fencing")
    class Claiming {

        @Test
        @DisplayName("one claim wins and the state at the CAS is returned")
        void claimReturnsStateAtomically() {
            LineageHistoricalPublishIntent plan = intents.createIfAbsent(
                    intent("claim", 10L, "d-1"));

            Optional<LineageHistoricalPublishIntentStore.IntentClaim> first =
                    intents.claim(plan.intentId(), "node-1", Duration.ofMinutes(5), 1000L);
            Optional<LineageHistoricalPublishIntentStore.IntentClaim> second =
                    intents.claim(plan.intentId(), "node-2", Duration.ofMinutes(5), 1000L);

            assertTrue(first.isPresent());
            assertTrue(second.isEmpty(), "two workers held one intent at once");
            assertEquals(LineageHistoricalPublishIntent.State.PLANNED,
                    first.get().stateAtClaim());
        }

        @Test
        @DisplayName("an expired lease is reclaimable, and the old token stops working")
        void expiredLeaseIsReclaimed() {
            LineageHistoricalPublishIntent plan = intents.createIfAbsent(
                    intent("reclaim", 10L, "d-1"));
            LineageHistoricalPublishIntentStore.IntentClaim stale = intents
                    .claim(plan.intentId(), "node-1", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();

            long later = 1000L + Duration.ofMinutes(6).toMillis();
            LineageHistoricalPublishIntentStore.IntentClaim fresh = intents
                    .claim(plan.intentId(), "node-2", Duration.ofMinutes(5), later)
                    .orElseThrow();

            assertNotEquals(stale.token(), fresh.token());
            assertTrue(intents.renew(stale, Duration.ofMinutes(5), later).isEmpty(),
                    "a stale claimant renewed a claim someone else holds");
            assertFalse(intents.transition(stale,
                    LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "stale"));
            assertTrue(intents.transition(fresh,
                    LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "fresh"));
        }

        /** A transition already applied must not be re-applied by a slow worker. */
        @Test
        @DisplayName("a transition from the wrong state is refused")
        void wrongFromStateIsRefused() {
            LineageHistoricalPublishIntent plan = intents.createIfAbsent(
                    intent("from-state", 10L, "d-1"));
            LineageHistoricalPublishIntentStore.IntentClaim claim = intents
                    .claim(plan.intentId(), "node-1", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();

            assertTrue(intents.transition(claim, LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "ok"));
            assertFalse(intents.transition(claim, LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "again"));
        }

        @Test
        @DisplayName("recording an attempt releases the hold so the next pass can take it")
        void recordAttemptReleases() {
            LineageHistoricalPublishIntent plan = intents.createIfAbsent(
                    intent("attempt", 10L, "d-1"));
            LineageHistoricalPublishIntentStore.IntentClaim claim = intents
                    .claim(plan.intentId(), "node-1", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();

            assertTrue(intents.recordAttempt(claim, "transient"));
            assertEquals(1, intents.read(plan.intentId()).orElseThrow().attempts());
            assertTrue(intents.claim(plan.intentId(), "node-2", Duration.ofMinutes(5), 1000L)
                    .isPresent());
        }
    }

    @Nested
    @DisplayName("the subject fence")
    class Fence {

        @Test
        @DisplayName("one intent holds the subject at a time")
        void exclusive() {
            String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(TARGET, REPO,
                    KIND, digest("fence-subject"));

            assertTrue(intents.acquireSubjectFence(subjectKey, "intent-a",
                    Duration.ofMinutes(5), 1000L).isPresent());
            assertTrue(intents.acquireSubjectFence(subjectKey, "intent-b",
                    Duration.ofMinutes(5), 1000L).isEmpty(),
                    "two intents held one subject at once");
        }

        /** A source lookup and a catalog write both take time; the fence must outlive them. */
        @Test
        @DisplayName("a live fence renews and the renewed token still releases")
        void renewAndRelease() {
            String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(TARGET, REPO,
                    KIND, digest("renew-subject"));
            LineageHistoricalPublishIntentStore.SubjectFence held = intents
                    .acquireSubjectFence(subjectKey, "intent-a", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();

            LineageHistoricalPublishIntentStore.SubjectFence renewed = intents
                    .renewSubjectFence(held, Duration.ofMinutes(5), 2000L).orElseThrow();

            assertTrue(renewed.leaseUntilMs() > held.leaseUntilMs());
            assertTrue(intents.releaseSubjectFence(renewed));
        }

        /** Worker A stalls, the fence expires, B takes it, A comes back. */
        @Test
        @DisplayName("a returning worker cannot renew or release the new holder's fence")
        void staleHolderIsRefused() {
            String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(TARGET, REPO,
                    KIND, digest("stale-subject"));
            LineageHistoricalPublishIntentStore.SubjectFence stale = intents
                    .acquireSubjectFence(subjectKey, "intent-a", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();

            long later = 1000L + Duration.ofMinutes(6).toMillis();
            intents.acquireSubjectFence(subjectKey, "intent-b", Duration.ofMinutes(5), later)
                    .orElseThrow();

            assertTrue(intents.renewSubjectFence(stale, Duration.ofMinutes(5), later).isEmpty());
            assertFalse(intents.releaseSubjectFence(stale),
                    "a stale holder released the new holder's fence");
        }

        @Test
        @DisplayName("a released fence is immediately available")
        void releaseFrees() {
            String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(TARGET, REPO,
                    KIND, digest("release-subject"));
            LineageHistoricalPublishIntentStore.SubjectFence held = intents
                    .acquireSubjectFence(subjectKey, "intent-a", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();
            intents.releaseSubjectFence(held);

            assertTrue(intents.acquireSubjectFence(subjectKey, "intent-b",
                    Duration.ofMinutes(5), 1000L).isPresent());
        }
    }

    @Nested
    @DisplayName("arbitration and views")
    class Views {

        @Test
        @DisplayName("state view returns only that state, and only intents")
        void byStateIsScoped() {
            LineageHistoricalPublishIntent planned = intents.createIfAbsent(
                    intent("view-planned", 10L, "d-1"));
            LineageHistoricalPublishIntent published = intents.createIfAbsent(
                    intent("view-published", 10L, "d-1"));
            LineageHistoricalPublishIntentStore.IntentClaim claim = intents
                    .claim(published.intentId(), "node-1", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();
            intents.transition(claim, LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "for the view");
            compensations.createIfAbsent(compensation("view-not-an-intent"));

            List<String> plannedIds = intents
                    .findByState(LineageHistoricalPublishIntent.State.PLANNED, 100)
                    .stream().map(LineageHistoricalPublishIntent::intentId).toList();
            List<String> publishedIds = intents
                    .findByState(LineageHistoricalPublishIntent.State.PUBLISHED, 100)
                    .stream().map(LineageHistoricalPublishIntent::intentId).toList();

            assertTrue(plannedIds.contains(planned.intentId()));
            assertFalse(plannedIds.contains(published.intentId()));
            assertTrue(publishedIds.contains(published.intentId()));
        }

        /**
         * The arbitration index must include intents that already wrote: an older observation
         * must not publish over a newer one merely because the newer finished first.
         */
        @Test
        @DisplayName("arbitration sees every claim on a subject except SUPERSEDED")
        void arbitrationScope() {
            LineageHistoricalPublishIntent older = intents.createIfAbsent(
                    intent("arb", 10L, "d-1"));
            LineageHistoricalPublishIntent newer = intents.createIfAbsent(
                    sameSubjectIntent("arb-newer", older, 20L, "d-2"));
            String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(TARGET, REPO,
                    KIND, older.subjectDigest());

            List<String> contenders = intents.findContendingForSubject(subjectKey, 100)
                    .stream().map(LineageHistoricalPublishIntent::intentId).toList();
            assertTrue(contenders.contains(older.intentId()));
            assertTrue(contenders.contains(newer.intentId()));

            // Settle the loser; it must drop out of arbitration and stop being rescanned.
            LineageHistoricalPublishIntentStore.IntentClaim claim = intents
                    .claim(older.intentId(), "node-1", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();
            assertTrue(intents.markSuperseded(claim, newer.plannedOperationDigest(), "lost"));

            List<String> after = intents.findContendingForSubject(subjectKey, 100)
                    .stream().map(LineageHistoricalPublishIntent::intentId).toList();
            assertFalse(after.contains(older.intentId()));
            assertTrue(after.contains(newer.intentId()));
            assertEquals(newer.plannedOperationDigest(),
                    intents.read(older.intentId()).orElseThrow().supersededByDigest());
        }

        /** A written intent may not be settled as if it had never been written. */
        @Test
        @DisplayName("only a PLANNED intent can be superseded")
        void supersedeOnlyFromPlanned() {
            LineageHistoricalPublishIntent plan = intents.createIfAbsent(
                    intent("supersede-guard", 10L, "d-1"));
            LineageHistoricalPublishIntentStore.IntentClaim claim = intents
                    .claim(plan.intentId(), "node-1", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();
            intents.transition(claim, LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "written");

            assertFalse(intents.markSuperseded(claim, digest("winner"), "too late"));
        }
    }

    @Nested
    @DisplayName("compensations")
    class Compensations {

        @Test
        @DisplayName("create-if-absent is idempotent per publish operation")
        void idempotent() {
            LineageHistoricalCompensation request = compensation("comp-idem");

            assertEquals(compensations.createIfAbsent(request).taskId(),
                    compensations.createIfAbsent(request).taskId());
            assertEquals(1, compensations
                    .findByState(LineageHistoricalCompensation.State.PENDING, 100).stream()
                    .filter(one -> one.taskId().equals(request.taskId())).count());
        }

        @Test
        @DisplayName("markResolved moves it out of PENDING")
        void markResolved() {
            LineageHistoricalCompensation stored =
                    compensations.createIfAbsent(compensation("comp-resolve"));

            assertTrue(compensations.markResolved(stored, "the current entity was re-published"));
            assertEquals(LineageHistoricalCompensation.State.RESOLVED,
                    compensations.read(stored.taskId()).orElseThrow().state());
            assertFalse(compensations
                    .findByState(LineageHistoricalCompensation.State.PENDING, 100).stream()
                    .anyMatch(one -> one.taskId().equals(stored.taskId())));
        }

        @Test
        @DisplayName("an absent compensation is empty, and marking one is false not an error")
        void absent() {
            assertTrue(compensations.read(digest("no-such-compensation")).isEmpty());
            assertFalse(compensations.markResolved(compensation("never-stored"), "x"));
        }
    }

    /**
     * The two ways these views are queried must describe the same documents.
     *
     * <h2>The production bug this pins</h2>
     *
     * <p>Every one of these views carries a {@code _count} reduce for the status routes, and
     * CouchDB rejects {@code include_docs} on a reduce query outright. So every document-listing
     * call — the recovery scanner's, arbitration's, the admin status route's — failed against a
     * real server while every mocked test passed. Only {@code reduce=false} makes the listing
     * legal, and only a real CouchDB can tell us so.
     *
     * <p>Counting rows of the listing and reading the reduce are two independent code paths over
     * one population. If they disagree, one of them is lying to a preflight.
     */
    @Nested
    class ReduceRegression {

        @Test
        @DisplayName("intents: the reduce count and the document listing agree")
        void intentCountMatchesListing() {
            for (int i = 0; i < 5; i++) {
                intents.createIfAbsent(intent("reduce-intent-" + i, 100L + i, "d-" + i));
            }
            List<LineageHistoricalPublishIntent> listed = intents
                    .findByState(LineageHistoricalPublishIntent.State.PLANNED, 1_000);
            long counted = listed.stream()
                    .filter(one -> one.intentId().length() == 64).count();
            assertEquals(listed.size(), counted, "every listed row must decode");
            assertTrue(listed.size() >= 5,
                    "reduce=false listing must return documents, not fail the query");
        }

        @Test
        @DisplayName("compensations: the reduce count and the document listing agree")
        void compensationCountMatchesListing() {
            for (int i = 0; i < 3; i++) {
                compensations.createIfAbsent(compensation("reduce-comp-" + i));
            }
            assertTrue(compensations
                    .findByState(LineageHistoricalCompensation.State.PENDING, 1_000).size() >= 3);
        }

        /**
         * The obligation store has the same views, the same reduce, and had the same bug.
         *
         * <p>Its count route is the one a preflight reads, so its two paths are compared
         * directly: a listing that fails while the reduce succeeds would leave the preflight
         * reporting a backlog nobody can enumerate.
         */
        @Test
        @DisplayName("obligations: reduce=true count equals the reduce=false listing")
        void obligationCountEqualsListing() {
            CouchLineageCatalogObligationStore store =
                    new CouchLineageCatalogObligationStore(journal);
            for (int i = 0; i < 4; i++) {
                store.createIfAbsent(obligation("reduce-obl-" + i));
            }

            List<LineageCatalogObligation> listed =
                    store.findByState(LineageCatalogObligation.State.PENDING, 10_000);
            LineageCatalogObligationStore.StateCount count =
                    store.countByState().get(LineageCatalogObligation.State.PENDING);

            assertFalse(count.truncated(),
                    "a real reduce must answer exactly, not as a lower bound");
            assertEquals(listed.size(), count.count(),
                    "the reduce and the listing must describe one population");
        }

        /**
         * A count that cannot be read is not zero.
         *
         * <p>Zero is the one answer that looks green, so a malformed reduce value must degrade
         * to a lower bound instead. Forced here by pointing the store at a view whose reduce
         * emits a non-numeric value.
         */
        @Test
        @DisplayName("a malformed reduce value degrades to a lower bound, never to a clean zero")
        void malformedReduceIsNotZero() {
            Map<String, Object> original = viewDefinition("obligationsByState");
            try {
                // A reduce that answers with a string. The store must not read it as a number,
                // and must not substitute the number it would like to see.
                Map<String, Object> broken = new LinkedHashMap<>(original);
                broken.put("reduce", "function(k, v, r) { return 'not-a-number'; }");
                replaceView("obligationsByState", broken);

                CouchLineageCatalogObligationStore store =
                        new CouchLineageCatalogObligationStore(journal);
                store.createIfAbsent(obligation("malformed-reduce"));
                LineageCatalogObligationStore.StateCount count =
                        store.countByState().get(LineageCatalogObligation.State.PENDING);
                assertTrue(count.truncated(),
                        "an unreadable reduce must be reported as a lower bound");
            } finally {
                // Put the real view back; later tests in this class depend on it.
                replaceView("obligationsByState", original);
            }
        }

        /**
         * A view that is not there is not an empty result set.
         *
         * <p>Collapsing a query failure into {@code List.of()} tells a scanner there is no work
         * and a preflight there is no backlog. Both are the reading that lets a broken
         * deployment look finished.
         */
        @Test
        @DisplayName("a query failure propagates instead of collapsing to an empty list")
        void queryFailureIsNotEmpty() {
            Map<String, Object> original = viewDefinition("historicalIntentsByState");
            try {
                removeView("historicalIntentsByState");
                org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                        () -> intents.findByState(
                                LineageHistoricalPublishIntent.State.PLANNED, 100),
                        "a missing view must be an error, not 'there is nothing to do'");
            } finally {
                replaceView("historicalIntentsByState", original);
            }
        }

        /**
         * The arbitration view is the one that must never fail quietly.
         *
         * <p>"No contenders" is the answer that lets every intent conclude it holds the subject
         * alone. Two nodes then publish over each other — precisely what the fence exists to
         * prevent — and nothing in the machine would notice.
         */
        @Test
        @DisplayName("a missing arbitration view is an error, not 'nobody else holds the subject'")
        void missingArbitrationViewIsNotSoleOwnership() {
            LineageHistoricalPublishIntent stored =
                    intents.createIfAbsent(intent("arbitration-view", 50L, "d-arb"));
            String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(stored.target(),
                    stored.repositoryId(), stored.endpointKind(), stored.subjectDigest());
            // With the view present, the intent finds itself contending.
            assertFalse(intents.findContendingForSubject(subjectKey, 100).isEmpty());

            Map<String, Object> original =
                    viewDefinition("historicalIntentsContendingBySubject");
            try {
                removeView("historicalIntentsContendingBySubject");
                org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                        () -> intents.findContendingForSubject(subjectKey, 100),
                        "sole ownership must never be inferred from a broken query");
            } finally {
                replaceView("historicalIntentsContendingBySubject", original);
            }
        }

        private Map<String, Object> viewDefinition(String viewName) {
            Map<String, Object> found = plainViews().get(viewName);
            if (found == null) {
                throw new IllegalStateException("no such view: " + viewName);
            }
            return found;
        }

        private void replaceView(String viewName, Map<String, Object> definition) {
            mutateViews(views -> views.put(viewName, definition));
        }

        private void removeView(String viewName) {
            mutateViews(views -> views.remove(viewName));
        }

        /**
         * The design document's views as plain maps.
         *
         * <p>The SDK deserialises them into its own {@code DesignDocumentViewsMapReduce}, which
         * is not a {@code Map} and cannot be edited in place. Flattening them keeps the write
         * path a plain JSON round trip.
         */
        private Map<String, Map<String, Object>> plainViews() {
            com.ibm.cloud.cloudant.v1.model.Document design =
                    journal.client().get("_design/" + journal.designDoc());
            Object rawViews = design.get("views");
            Map<String, Map<String, Object>> plain = new LinkedHashMap<>();
            if (!(rawViews instanceof Map<?, ?> byName)) {
                return plain;
            }
            for (Map.Entry<?, ?> entry : byName.entrySet()) {
                Map<String, Object> definition = new LinkedHashMap<>();
                Object value = entry.getValue();
                if (value instanceof com.ibm.cloud.cloudant.v1.model.DesignDocumentViewsMapReduce
                        typed) {
                    definition.put("map", typed.map());
                    if (typed.reduce() != null) {
                        definition.put("reduce", typed.reduce());
                    }
                } else if (value instanceof Map<?, ?> asMap) {
                    asMap.forEach((k, v) -> definition.put(String.valueOf(k), v));
                }
                plain.put(String.valueOf(entry.getKey()), definition);
            }
            return plain;
        }

        private void mutateViews(
                java.util.function.Consumer<Map<String, Map<String, Object>>> mutation) {
            com.ibm.cloud.cloudant.v1.model.Document design =
                    journal.client().get("_design/" + journal.designDoc());
            Map<String, Map<String, Object>> views = plainViews();
            mutation.accept(views);
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("_id", design.getId());
            raw.put("_rev", design.getRev());
            if (design.getProperties() != null) {
                raw.putAll(design.getProperties());
            }
            raw.put("views", views);
            journal.client().update(raw);
        }

        /**
         * Every view the machine needs must exist after provisioning.
         *
         * <p>Provisioning is idempotent and runs on every start, so this also covers the
         * upgrade path: a store built against a database whose design document predates these
         * views must end up with them rather than failing on first use.
         */
        @Test
        @DisplayName("every required view is queryable before and after re-provisioning")
        void requiredViewsSurviveReprovisioning() {
            List<String> required = List.of("obligationsByState", "v2_waiting_by_task_key",
                    "historicalIntentsByState", "historicalCompensationsByState",
                    "historicalIntentsContendingBySubject");
            assertQueryable(required);

            // Re-run provisioning over the existing design document.
            journal.ensureDatabase();
            assertQueryable(required);
        }

        private void assertQueryable(List<String> viewNames) {
            for (String view : viewNames) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("reduce", false);
                params.put("limit", 1);
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                        () -> journal.client().queryView(journal.designDoc(), view, params),
                        view + " must be queryable");
            }
        }
    }

    /**
     * The enum field and the human note are different fields, and must stay that way.
     *
     * <h2>The production bug this pins</h2>
     *
     * <p>{@code markResolved} wrote its free-text explanation into {@code reason}, which holds
     * the {@code Reason} enum. Every document it touched became undecodable — a compensation
     * that had been acted on could never be read again, so nothing could report on it. Mocks do
     * not go through the codec, so nothing caught it until a real round trip did.
     */
    @Test
    @DisplayName("the compensation reason enum and its outcome note stay independent")
    public void reasonAndOutcomeNoteAreIndependent() {
        LineageHistoricalCompensation stored =
                compensations.createIfAbsent(compensation("reason-independence"));
        assertEquals(LineageHistoricalCompensation.Reason
                .SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH, stored.reason());

        assertTrue(compensations.markResolved(stored,
                "the current authoritative entity was re-published over the historical one"));

        LineageHistoricalCompensation reread = compensations.read(stored.taskId()).orElseThrow();
        assertEquals(LineageHistoricalCompensation.Reason
                .SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH, reread.reason(),
                "the note must not have overwritten the enum");
        assertEquals(LineageHistoricalCompensation.State.RESOLVED, reread.state());
        // And the note itself survived, in its own field.
        com.ibm.cloud.cloudant.v1.model.Document raw = cloudant.getDocument(
                new com.ibm.cloud.cloudant.v1.model.GetDocumentOptions.Builder().db(dbName)
                        .docId(LineageHistoricalCompensation.DOCUMENT_ID_PREFIX + stored.taskId())
                        .build()).execute().getResult();
        assertEquals("SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH", raw.get("reason"));
        assertTrue(String.valueOf(raw.get("outcomeNote")).contains("re-published"));
    }

    /** A PENDING obligation, deterministic per seed. */
    private static LineageCatalogObligation obligation(String seed) {
        String qualifiedName = "nemaki://" + REPO + "/" + seed;
        return new LineageCatalogObligation(null,
                LineageCatalogObligation.taskKey(TARGET, REPO, KIND, qualifiedName),
                TARGET, REPO, KIND, qualifiedName, LineageCatalogObligation.State.PENDING,
                null, null, 0L, 0L, 0, 1_000L, LineageCatalogObligation.Outcome.NONE, null, null);
    }

    /**
     * Resuming from each durable boundary, using only what is in CouchDB.
     *
     * <p>The unit tests drive the machine across these; this proves the states they depend on
     * actually survive a round trip through the real store.
     */
    @Test
    @DisplayName("every intent state round-trips through CouchDB")
    public void everyStateRoundTrips() {
        for (LineageHistoricalPublishIntent.State state
                : LineageHistoricalPublishIntent.State.values()) {
            LineageHistoricalPublishIntent plan = intents.createIfAbsent(
                    intent("roundtrip-" + state, 10L, "d-1"));
            LineageHistoricalPublishIntentStore.IntentClaim claim = intents
                    .claim(plan.intentId(), "node-1", Duration.ofMinutes(5), 1000L)
                    .orElseThrow();
            if (state != LineageHistoricalPublishIntent.State.PLANNED) {
                boolean moved = state == LineageHistoricalPublishIntent.State.SUPERSEDED
                        ? intents.markSuperseded(claim, digest("winner"), "lost")
                        : intents.transition(claim,
                                LineageHistoricalPublishIntent.State.PLANNED, state, "moved");
                assertTrue(moved, "could not reach " + state);
            }

            LineageHistoricalPublishIntent read = intents.read(plan.intentId()).orElseThrow();
            assertEquals(state, read.state());
            assertEquals(plan.observationSequence(), read.observationSequence());
            assertEquals(plan.observationDeliveryId(), read.observationDeliveryId());
            assertEquals(plan.plannedOperationDigest(), read.plannedOperationDigest());
            assertTrue(plan.samePlanAs(read), state + " did not round-trip its plan");
        }
    }
}
