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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The ordering that makes a catalog wait resumable, and the states it must never enter.
 *
 * <p>Everything here is deterministic: a fake store that records what was written and a
 * collaborator whose answers a test sets. Crash boundaries are expressed by making the step
 * after the boundary fail, then running the whole thing again — which is exactly what a restart
 * does, and the only honest way to assert that the row is still usable.
 */
class LineageCatalogWaitCoordinatorTest {

    private static final String RECORD = "rec-1";
    private static final String TARGET = "atlas";
    private static final String REPO = "bedroom";
    private static final long HOUR = 3_600_000L;

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Records the writes, so the tests can assert what was stored and in what order. */
    private static final class RecordingTransitions implements LineageV2TransitionStore {
        final List<String> writes = new ArrayList<>();
        List<String> storedKeys;
        Long storedSince;
        LineagePublishStatus status = LineagePublishStatus.PENDING;
        boolean enterFails;
        boolean enterThrows;
        boolean resumeFails;
        boolean expireFails;
        long clock = 1_000L;

        @Override
        public boolean enterCatalogWait(String recordId, String target, List<String> taskKeys) {
            if (enterThrows) {
                throw new IllegalStateException("couch is down");
            }
            if (taskKeys == null || taskKeys.isEmpty()) {
                throw new IllegalArgumentException("empty waiting set");
            }
            writes.add("enter:" + String.join(",", taskKeys));
            if (enterFails) {
                return false;
            }
            storedKeys = List.copyOf(new java.util.TreeSet<>(taskKeys));
            // waitingSince is written only when absent — the invariant the resume path relies on.
            if (storedSince == null) {
                storedSince = clock;
            }
            status = LineagePublishStatus.WAITING_FOR_CATALOG;
            return true;
        }

        @Override
        public boolean resumeFromCatalogWait(String recordId, String target) {
            writes.add("resume");
            if (resumeFails) {
                return false;
            }
            storedKeys = null;
            status = LineagePublishStatus.PENDING;
            return true;
        }

        @Override
        public boolean expireCatalogWait(String recordId, String target,
                LineageTargetLifecycle.TerminalReason reason) {
            writes.add("expire:" + reason.reason());
            if (expireFails) {
                return false;
            }
            storedKeys = null;
            status = LineagePublishStatus.UNRESOLVED;
            return true;
        }

        LineageTargetLifecycle lifecycle() {
            return status == LineagePublishStatus.WAITING_FOR_CATALOG
                    ? new LineageTargetLifecycle(status, null, null, null, null, null, null,
                            storedKeys, storedSince)
                    : new LineageTargetLifecycle(status, null, null, null, null, null,
                            status == LineagePublishStatus.UNRESOLVED
                                    ? new LineageTargetLifecycle.TerminalReason("X", "y", 1L)
                                    : null,
                            null, storedSince);
        }

        // Unused by these tests.
        @Override public V2ClaimGrant claimForProjection(String r, String t,
                java.time.Duration l) { throw new UnsupportedOperationException(); }
        @Override public boolean transitionV2(String r, String t, LineagePublishStatus e,
                LineagePublishStatus n, String c, LineageTargetLifecycle.TerminalReason x) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean renewClaim(String r, String t, String c,
                java.time.Duration l) { throw new UnsupportedOperationException(); }
        @Override public boolean transitionV2Unclaimed(String r, String t,
                LineagePublishStatus e, LineagePublishStatus n,
                LineageTargetLifecycle.TerminalReason x) {
            throw new UnsupportedOperationException();
        }
        @Override public int reapExpiredClaims(String t, java.time.Instant c) {
            throw new UnsupportedOperationException();
        }
        @Override public List<LineageJournalRowV2> findV2ByRepositoryAndSequenceRange(
                String r, long f, int l) {
            throw new UnsupportedOperationException();
        }
        @Override public List<LineageJournalRow> findV1ByRepositoryAndSequenceRangeStrict(
                String r, long f, int l) {
            throw new UnsupportedOperationException();
        }
        @Override public LineageJournalRowV2 findV2ByRecordId(String r) {
            throw new UnsupportedOperationException();
        }
        @Override public List<String> findV2NonTerminalRepositoryIds(String t) {
            throw new UnsupportedOperationException();
        }
        @Override public List<String> findV2SequencedRepositoryIds(String t) {
            throw new UnsupportedOperationException();
        }
    }

    /** A collaborator whose probe answers and durability a test controls. */
    private static final class FakeCollaborator
            implements LineageObligationProjectorCollaborator {
        final Map<String, String> owedByQualifiedName = new LinkedHashMap<>();
        final java.util.Set<String> durable = new java.util.LinkedHashSet<>();
        final List<String> probed = new ArrayList<>();
        LineageCatalogObligationService.Verdict verdict;
        RuntimeException probeThrows;
        String probeThrowsFor;

        @Override
        public LineageCatalogObligationService service() {
            return null;
        }

        @Override
        public boolean isDurable(String taskKey) {
            return durable.contains(taskKey);
        }

        @Override
        public Optional<String> requireCatalogEntity(String target, String repositoryId,
                EndpointKind kind, String catalogQualifiedName) {
            probed.add(catalogQualifiedName);
            if (probeThrows != null && (probeThrowsFor == null
                    || probeThrowsFor.equals(catalogQualifiedName))) {
                throw probeThrows;
            }
            return Optional.ofNullable(owedByQualifiedName.get(catalogQualifiedName));
        }

        @Override
        public LineageCatalogObligationService.Verdict verdictFor(List<String> taskKeys) {
            return verdict;
        }
    }

    /**
     * The coordinator asks the collaborator's service for the read-back, which the fake does
     * not have. This subclass routes it to the fake's durable set instead.
     */
    private static LineageCatalogWaitCoordinator coordinator(FakeCollaborator collaborator,
            RecordingTransitions transitions, long maxAgeMs) {
        return new LineageCatalogWaitCoordinator(collaborator, transitions,
                () -> transitions.clock, maxAgeMs);
    }

    private static LineageEndpoint endpoint(String objectId) {
        return LineageEndpoint.document(REPO, objectId, objectId);
    }

    // ------------------------------------------------------------------

    @Nested
    class EnteringTheWait {

        @Test
        @DisplayName("nothing owed proceeds, and every endpoint was still probed")
        void nothingOwed() {
            FakeCollaborator collaborator = new FakeCollaborator();
            RecordingTransitions transitions = new RecordingTransitions();
            var decision = coordinator(collaborator, transitions, 24 * HOUR)
                    .beforePublish(RECORD, TARGET, REPO,
                            List.of(endpoint("a"), endpoint("b"), endpoint("c")));

            assertInstanceOf(LineageCatalogWaitCoordinator.Decision.Proceed.class, decision);
            assertEquals(3, collaborator.probed.size());
            assertTrue(transitions.writes.isEmpty(), "nothing owed writes nothing");
        }

        /**
         * The set must be complete, so probing cannot stop at the first miss.
         *
         * <p>A row that waited only on its first missing endpoint would resume as soon as that
         * one arrived and publish an event whose other endpoints still have no entity.
         */
        @Test
        @DisplayName("every endpoint is probed even after the first one owes an obligation")
        void probesAllEndpoints() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.owedByQualifiedName.put(qn("a"), "task-a");
            collaborator.owedByQualifiedName.put(qn("c"), "task-c");
            collaborator.durable.addAll(List.of("task-a", "task-c"));
            RecordingTransitions transitions = new RecordingTransitions();

            var decision = durableCoordinator(collaborator, transitions, 24 * HOUR)
                    .beforePublish(RECORD, TARGET, REPO,
                            List.of(endpoint("a"), endpoint("b"), endpoint("c")));

            var waiting =
                    assertInstanceOf(LineageCatalogWaitCoordinator.Decision.Waiting.class,
                            decision);
            assertEquals(List.of("task-a", "task-c"), waiting.taskKeys());
            assertEquals(3, collaborator.probed.size(), "all three, not just up to the first");
        }

        @Test
        @DisplayName("the stored key set is deduped and sorted")
        void keysAreNormalised() {
            FakeCollaborator collaborator = new FakeCollaborator();
            // Two endpoints resolving to one catalog entity — one obligation, not two.
            collaborator.owedByQualifiedName.put(qn("z"), "task-z");
            collaborator.owedByQualifiedName.put(qn("a"), "task-a");
            collaborator.owedByQualifiedName.put(qn("m"), "task-a");
            collaborator.durable.addAll(List.of("task-a", "task-z"));
            RecordingTransitions transitions = new RecordingTransitions();

            durableCoordinator(collaborator, transitions, 24 * HOUR).beforePublish(RECORD,
                    TARGET, REPO, List.of(endpoint("z"), endpoint("a"), endpoint("m")));

            assertEquals(List.of("task-a", "task-z"), transitions.storedKeys);
        }

        /**
         * The boundary between creating the obligations and storing the wait.
         *
         * <p>A crash here must leave the row PENDING, not WAITING with a partial set.
         */
        @Test
        @DisplayName("an unconfirmed obligation stops before the CAS, leaving the row PENDING")
        void partialCreationNeverEntersTheWait() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.owedByQualifiedName.put(qn("a"), "task-a");
            collaborator.owedByQualifiedName.put(qn("b"), "task-b");
            collaborator.durable.add("task-a");    // task-b did not survive
            RecordingTransitions transitions = new RecordingTransitions();

            var decision = durableCoordinator(collaborator, transitions, 24 * HOUR)
                    .beforePublish(RECORD, TARGET, REPO,
                            List.of(endpoint("a"), endpoint("b")));

            assertInstanceOf(LineageCatalogWaitCoordinator.Decision.Halt.class, decision);
            assertTrue(transitions.writes.isEmpty(), "no CAS may be attempted");
            assertEquals(LineagePublishStatus.PENDING, transitions.status);

            // Re-running after the restart: the keys are deterministic, so the same set is
            // recomputed and the wait completes with nothing lost.
            collaborator.durable.add("task-b");
            var second = durableCoordinator(collaborator, transitions, 24 * HOUR)
                    .beforePublish(RECORD, TARGET, REPO,
                            List.of(endpoint("a"), endpoint("b")));
            assertInstanceOf(LineageCatalogWaitCoordinator.Decision.Waiting.class, second);
            assertEquals(List.of("task-a", "task-b"), transitions.storedKeys);
        }

        /** An endpoint nobody could ask about is not an endpoint that is present. */
        @Test
        @DisplayName("a probe that throws halts instead of publishing or waiting partially")
        void unprobedEndpointHalts() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.owedByQualifiedName.put(qn("a"), "task-a");
            collaborator.durable.add("task-a");
            collaborator.probeThrows = new IllegalStateException("catalog unreachable");
            collaborator.probeThrowsFor = qn("b");
            RecordingTransitions transitions = new RecordingTransitions();

            var decision = durableCoordinator(collaborator, transitions, 24 * HOUR)
                    .beforePublish(RECORD, TARGET, REPO,
                            List.of(endpoint("a"), endpoint("b")));

            var halt = assertInstanceOf(LineageCatalogWaitCoordinator.Decision.Halt.class,
                    decision);
            assertTrue(transitions.writes.isEmpty());
            assertFalse(halt.why().contains(qn("b")), "a qualified name is not log-safe");
        }

        @Test
        @DisplayName("a lost CAS is not a wait")
        void lostCasIsNotAWait() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.owedByQualifiedName.put(qn("a"), "task-a");
            collaborator.durable.add("task-a");
            RecordingTransitions transitions = new RecordingTransitions();
            transitions.enterFails = true;

            assertInstanceOf(LineageCatalogWaitCoordinator.Decision.Halt.class,
                    durableCoordinator(collaborator, transitions, 24 * HOUR)
                            .beforePublish(RECORD, TARGET, REPO, List.of(endpoint("a"))));
            assertEquals(LineagePublishStatus.PENDING, transitions.status);
        }

        @Test
        @DisplayName("a store that throws halts without changing anything")
        void storeFailureHalts() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.owedByQualifiedName.put(qn("a"), "task-a");
            collaborator.durable.add("task-a");
            RecordingTransitions transitions = new RecordingTransitions();
            transitions.enterThrows = true;

            assertInstanceOf(LineageCatalogWaitCoordinator.Decision.Halt.class,
                    durableCoordinator(collaborator, transitions, 24 * HOUR)
                            .beforePublish(RECORD, TARGET, REPO, List.of(endpoint("a"))));
            assertEquals(LineagePublishStatus.PENDING, transitions.status);
        }

        @Test
        @DisplayName("no collaborator halts rather than publishing unprobed edges")
        void noCollaboratorHalts() {
            RecordingTransitions transitions = new RecordingTransitions();
            assertInstanceOf(LineageCatalogWaitCoordinator.Decision.Halt.class,
                    new LineageCatalogWaitCoordinator(null, transitions, () -> 0L, HOUR)
                            .beforePublish(RECORD, TARGET, REPO, List.of(endpoint("a"))));
        }
    }

    @Nested
    class WhileWaiting {

        @Test
        @DisplayName("only ALL_RESOLVED returns the row to PENDING")
        void onlyAllResolvedResumes() {
            for (LineageCatalogObligationService.VerdictKind kind
                    : LineageCatalogObligationService.VerdictKind.values()) {
                FakeCollaborator collaborator = new FakeCollaborator();
                collaborator.verdict = new LineageCatalogObligationService.Verdict(kind, "r", 2);
                RecordingTransitions transitions = waiting(List.of("task-a", "task-b"), 1_000L);

                var outcome = coordinator(collaborator, transitions, 24 * HOUR)
                        .whileWaiting(RECORD, TARGET, transitions.lifecycle());

                if (kind == LineageCatalogObligationService.VerdictKind.ALL_RESOLVED) {
                    assertInstanceOf(LineageCatalogWaitCoordinator.WaitOutcome.Resumed.class,
                            outcome);
                    assertEquals(LineagePublishStatus.PENDING, transitions.status);
                } else if (kind
                        == LineageCatalogObligationService.VerdictKind.TERMINAL_UNRESOLVED) {
                    assertInstanceOf(LineageCatalogWaitCoordinator.WaitOutcome.Terminal.class,
                            outcome);
                } else {
                    assertInstanceOf(LineageCatalogWaitCoordinator.WaitOutcome.Holding.class,
                            outcome, kind + " must not resume");
                    assertEquals(LineagePublishStatus.WAITING_FOR_CATALOG, transitions.status);
                }
            }
        }

        /** INDETERMINATE is not a slow WAITING — it must not age into a terminal verdict. */
        @Test
        @DisplayName("INDETERMINATE changes nothing, even past the maximum age")
        void indeterminateNeverExpires() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.verdict = new LineageCatalogObligationService.Verdict(
                    LineageCatalogObligationService.VerdictKind.INDETERMINATE, "unreadable", 1);
            RecordingTransitions transitions = waiting(List.of("task-a"), 1_000L);
            transitions.clock = 1_000L + 1_000 * HOUR;

            var outcome = coordinator(collaborator, transitions, HOUR)
                    .whileWaiting(RECORD, TARGET, transitions.lifecycle());

            assertInstanceOf(LineageCatalogWaitCoordinator.WaitOutcome.Holding.class, outcome);
            assertTrue(transitions.writes.isEmpty(), "an unreadable store is not a verdict");
            assertEquals(LineagePublishStatus.WAITING_FOR_CATALOG, transitions.status);
        }

        @Test
        @DisplayName("the maximum age boundary: just under holds, exactly at expires")
        void maxAgeBoundary() {
            for (long elapsed : new long[] {HOUR - 1, HOUR}) {
                FakeCollaborator collaborator = new FakeCollaborator();
                collaborator.verdict = new LineageCatalogObligationService.Verdict(
                        LineageCatalogObligationService.VerdictKind.WAITING, "pending", 1);
                RecordingTransitions transitions = waiting(List.of("task-a"), 1_000L);
                transitions.clock = 1_000L + elapsed;

                var outcome = coordinator(collaborator, transitions, HOUR)
                        .whileWaiting(RECORD, TARGET, transitions.lifecycle());

                if (elapsed < HOUR) {
                    assertInstanceOf(LineageCatalogWaitCoordinator.WaitOutcome.Holding.class,
                            outcome);
                } else {
                    var terminal = assertInstanceOf(
                            LineageCatalogWaitCoordinator.WaitOutcome.Terminal.class, outcome);
                    assertEquals("CATALOG_WAIT_EXPIRED", terminal.reason());
                }
            }
        }

        @Test
        @DisplayName("a zero maximum age disables expiry rather than expiring immediately")
        void zeroMaxAgeDisablesExpiry() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.verdict = new LineageCatalogObligationService.Verdict(
                    LineageCatalogObligationService.VerdictKind.WAITING, "pending", 1);
            RecordingTransitions transitions = waiting(List.of("task-a"), 1_000L);
            transitions.clock = 1_000L + 10_000 * HOUR;

            assertInstanceOf(LineageCatalogWaitCoordinator.WaitOutcome.Holding.class,
                    coordinator(collaborator, transitions, 0L)
                            .whileWaiting(RECORD, TARGET, transitions.lifecycle()));
        }

        /**
         * Expiry ends the event's wait and nothing else.
         *
         * <p>The obligation is shared with every other event waiting on the same catalog
         * entity; resolving or burning it here would decide for all of them.
         */
        @Test
        @DisplayName("expiry touches the event only, never the shared obligation")
        void expiryLeavesTheObligationAlone() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.verdict = new LineageCatalogObligationService.Verdict(
                    LineageCatalogObligationService.VerdictKind.WAITING, "pending", 1);
            RecordingTransitions transitions = waiting(List.of("task-a"), 1_000L);
            transitions.clock = 1_000L + 2 * HOUR;

            coordinator(collaborator, transitions, HOUR)
                    .whileWaiting(RECORD, TARGET, transitions.lifecycle());

            assertEquals(List.of("expire:CATALOG_WAIT_EXPIRED"), transitions.writes,
                    "only the event's row is written");
        }

        @Test
        @DisplayName("a lost CAS on resume or expiry holds instead of claiming success")
        void lostCasHolds() {
            FakeCollaborator resolved = new FakeCollaborator();
            resolved.verdict = new LineageCatalogObligationService.Verdict(
                    LineageCatalogObligationService.VerdictKind.ALL_RESOLVED, "ok", 1);
            RecordingTransitions t1 = waiting(List.of("task-a"), 1_000L);
            t1.resumeFails = true;
            assertInstanceOf(LineageCatalogWaitCoordinator.WaitOutcome.Holding.class,
                    coordinator(resolved, t1, HOUR)
                            .whileWaiting(RECORD, TARGET, t1.lifecycle()));

            FakeCollaborator stuck = new FakeCollaborator();
            stuck.verdict = new LineageCatalogObligationService.Verdict(
                    LineageCatalogObligationService.VerdictKind.WAITING, "pending", 1);
            RecordingTransitions t2 = waiting(List.of("task-a"), 1_000L);
            t2.expireFails = true;
            t2.clock = 1_000L + 2 * HOUR;
            assertInstanceOf(LineageCatalogWaitCoordinator.WaitOutcome.Holding.class,
                    coordinator(stuck, t2, HOUR).whileWaiting(RECORD, TARGET, t2.lifecycle()));
        }
    }

    /**
     * The wait start must survive a resume, or a row that waits repeatedly never ages.
     */
    @Nested
    class WaitingSince {

        @Test
        @DisplayName("re-waiting keeps the original start, so max age still bites")
        void reWaitingKeepsTheOriginalStart() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.owedByQualifiedName.put(qn("a"), "task-a");
            collaborator.durable.add("task-a");
            RecordingTransitions transitions = new RecordingTransitions();
            transitions.clock = 1_000L;

            durableCoordinator(collaborator, transitions, 24 * HOUR)
                    .beforePublish(RECORD, TARGET, REPO, List.of(endpoint("a")));
            assertEquals(1_000L, transitions.storedSince);

            // Resolved, resumed...
            collaborator.verdict = new LineageCatalogObligationService.Verdict(
                    LineageCatalogObligationService.VerdictKind.ALL_RESOLVED, "ok", 1);
            coordinator(collaborator, transitions, 24 * HOUR)
                    .whileWaiting(RECORD, TARGET, transitions.lifecycle());
            assertEquals(LineagePublishStatus.PENDING, transitions.status);

            // ...and waiting again, much later.
            transitions.clock = 1_000L + 20 * HOUR;
            durableCoordinator(collaborator, transitions, 24 * HOUR)
                    .beforePublish(RECORD, TARGET, REPO, List.of(endpoint("a")));
            assertEquals(1_000L, transitions.storedSince,
                    "a row that keeps re-waiting must not keep restarting its own clock");
        }
    }

    /** Two targets wait and resume independently. */
    @Nested
    class MultipleTargets {

        @Test
        @DisplayName("one target's wait does not enter or leave another's")
        void targetsAreIndependent() {
            FakeCollaborator collaborator = new FakeCollaborator();
            collaborator.owedByQualifiedName.put(qn("a"), "task-a");
            collaborator.durable.add("task-a");
            RecordingTransitions atlas = new RecordingTransitions();
            RecordingTransitions purview = new RecordingTransitions();

            durableCoordinator(collaborator, atlas, 24 * HOUR)
                    .beforePublish(RECORD, "atlas", REPO, List.of(endpoint("a")));
            assertEquals(LineagePublishStatus.WAITING_FOR_CATALOG, atlas.status);
            assertEquals(LineagePublishStatus.PENDING, purview.status,
                    "the other target was not touched");

            collaborator.verdict = new LineageCatalogObligationService.Verdict(
                    LineageCatalogObligationService.VerdictKind.ALL_RESOLVED, "ok", 1);
            coordinator(collaborator, atlas, 24 * HOUR)
                    .whileWaiting(RECORD, "atlas", atlas.lifecycle());
            assertEquals(LineagePublishStatus.PENDING, atlas.status);
            assertTrue(purview.writes.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Record-level invariants
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a waiting lifecycle cannot exist without both keys and a start")
    void waitingRequiresBoth() {
        assertThrows(IllegalArgumentException.class, () -> new LineageTargetLifecycle(
                LineagePublishStatus.WAITING_FOR_CATALOG, null, null, null, null, null, null,
                null, 1_000L), "no keys");
        assertThrows(IllegalArgumentException.class, () -> new LineageTargetLifecycle(
                LineagePublishStatus.WAITING_FOR_CATALOG, null, null, null, null, null, null,
                List.of("k"), null), "no start");
        assertThrows(IllegalArgumentException.class, () -> new LineageTargetLifecycle(
                LineagePublishStatus.WAITING_FOR_CATALOG, null, null, null, null, null, null,
                List.of(), 1_000L), "an empty set waits for nothing and can never resolve");
    }

    @Test
    @DisplayName("the stored key set is immutable, deduped and sorted")
    void keySetIsNormalisedAtConstruction() {
        List<String> mutable = new ArrayList<>(List.of("z", "a", "z"));
        LineageTargetLifecycle lifecycle = new LineageTargetLifecycle(
                LineagePublishStatus.WAITING_FOR_CATALOG, null, null, null, null, null, null,
                mutable, 1_000L);
        assertEquals(List.of("a", "z"), lifecycle.waitingTaskKeys());
        mutable.add("m");
        assertEquals(List.of("a", "z"), lifecycle.waitingTaskKeys(), "not a live view");
        assertThrows(UnsupportedOperationException.class,
                () -> lifecycle.waitingTaskKeys().add("x"));
    }

    @Test
    @DisplayName("PENDING keeps waitingSince but never the keys")
    void pendingKeepsOnlyTheStart() {
        assertThrows(IllegalArgumentException.class, () -> new LineageTargetLifecycle(
                LineagePublishStatus.PENDING, null, null, null, null, null, null,
                List.of("k"), 1_000L));
        // The start alone is legal, and is what makes max age survive a resume.
        assertEquals(1_000L, new LineageTargetLifecycle(LineagePublishStatus.PENDING, null, null,
                null, null, null, null, null, 1_000L).waitingSinceMs());
    }

    // ------------------------------------------------------------------

    private static String qn(String objectId) {
        return endpoint(objectId).catalogQualifiedName();
    }

    private static RecordingTransitions waiting(List<String> keys, long since) {
        RecordingTransitions transitions = new RecordingTransitions();
        transitions.storedKeys = List.copyOf(new java.util.TreeSet<>(keys));
        transitions.storedSince = since;
        transitions.status = LineagePublishStatus.WAITING_FOR_CATALOG;
        return transitions;
    }

    /** Same as {@link #coordinator}; kept for the tests that speak about durability. */
    private static LineageCatalogWaitCoordinator durableCoordinator(
            FakeCollaborator collaborator, RecordingTransitions transitions, long maxAgeMs) {
        return coordinator(collaborator, transitions, maxAgeMs);
    }
}
