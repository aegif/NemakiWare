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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.rest.purview.journal.LineageCatalogEntityProbe.Presence;
import jp.aegif.nemaki.rest.purview.journal.LineageCatalogObligationStore.Claim;

/**
 * The obligation machine's moving parts, driven through every outcome an operator would only
 * meet in production: a contended claim, an expired lease, a worker that came back too late,
 * a catalog that answered nothing, and D-rest switched off.
 *
 * <p>The store is an in-memory implementation of the real contract rather than a mock, because
 * the properties under test are about what a <em>sequence</em> of CAS operations does. A mock
 * would let a claim succeed twice.
 */
public class LineageCatalogObligationServiceTest {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";
    private static final String QN = "nemaki://bedroom/folders/f-1/dataset";
    private static final EndpointKind KIND = EndpointKind.CMIS_FOLDER;

    private FakeStore store;
    private LineageDrestReadiness readiness;
    private LineageNodeIdentity identity;
    private AtomicLong clock;
    private Presence answer;
    private RuntimeException probeFailure;
    private int probeCalls;
    private final List<String> probedTargets = new ArrayList<>();

    /** An in-memory store honouring the CAS, token and lease rules of the real one. */
    private static final class FakeStore implements LineageCatalogObligationStore {
        final Map<String, LineageCatalogObligation> byKey = new LinkedHashMap<>();

        @Override
        public LineageCatalogObligation createIfAbsent(LineageCatalogObligation obligation) {
            LineageCatalogObligation existing = byKey.get(obligation.taskKey());
            if (existing != null) {
                if (!obligation.sameSubjectAs(existing)) {
                    throw new ObligationSubjectConflictException("different subject");
                }
                return existing;
            }
            byKey.put(obligation.taskKey(), obligation);
            return obligation;
        }

        @Override
        public Optional<LineageCatalogObligation> read(String taskKey) {
            return Optional.ofNullable(byKey.get(taskKey));
        }

        @Override
        public Optional<Claim> claim(String taskKey, String owner, Duration lease, long nowMs) {
            LineageCatalogObligation current = byKey.get(taskKey);
            if (current == null || current.terminal()) {
                return Optional.empty();
            }
            if (current.state() == LineageCatalogObligation.State.CLAIMED
                    && !current.leaseExpired(nowMs)) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            long until = nowMs + lease.toMillis();
            byKey.put(taskKey, withClaim(current, owner, token, until));
            return Optional.of(new Claim(taskKey, owner, token, until));
        }

        @Override
        public Optional<Claim> renew(Claim claim, Duration lease, long nowMs) {
            LineageCatalogObligation held = heldBy(claim);
            if (held == null) {
                return Optional.empty();
            }
            long until = nowMs + lease.toMillis();
            byKey.put(claim.taskKey(), withClaim(held, claim.owner(), claim.token(), until));
            return Optional.of(new Claim(claim.taskKey(), claim.owner(), claim.token(), until));
        }

        @Override
        public boolean resolve(Claim claim, LineageCatalogObligation.Outcome outcome,
                String reason, String evidence) {
            return finish(claim, LineageCatalogObligation.State.RESOLVED, outcome, reason,
                    evidence);
        }

        @Override
        public boolean giveUp(Claim claim, LineageCatalogObligation.Outcome outcome,
                String reason, String evidence) {
            if (outcome == LineageCatalogObligation.Outcome.SOURCE_ERROR) {
                throw new IllegalArgumentException("SOURCE_ERROR is retryable");
            }
            return finish(claim, LineageCatalogObligation.State.UNRESOLVED, outcome, reason,
                    evidence);
        }

        private boolean finish(Claim claim, LineageCatalogObligation.State state,
                LineageCatalogObligation.Outcome outcome, String reason, String evidence) {
            LineageCatalogObligation held = heldBy(claim);
            if (held == null) {
                return false;
            }
            byKey.put(claim.taskKey(), new LineageCatalogObligation(null, held.taskKey(),
                    held.target(), held.repositoryId(), held.endpointKind(),
                    held.catalogQualifiedName(), state, null, null, 0L, held.attempts(),
                    held.createdAtMs(), outcome, reason, evidence));
            return true;
        }

        @Override
        public boolean release(Claim claim, String reason) {
            LineageCatalogObligation held = heldBy(claim);
            if (held == null) {
                return false;
            }
            byKey.put(claim.taskKey(), new LineageCatalogObligation(null, held.taskKey(),
                    held.target(), held.repositoryId(), held.endpointKind(),
                    held.catalogQualifiedName(), LineageCatalogObligation.State.PENDING,
                    null, null, 0L, held.attempts() + 1, held.createdAtMs(),
                    LineageCatalogObligation.Outcome.NONE, reason, null));
            return true;
        }

        @Override
        public int reclaimExpired(int limit, long nowMs) {
            int reclaimed = 0;
            for (Map.Entry<String, LineageCatalogObligation> entry : byKey.entrySet()) {
                LineageCatalogObligation o = entry.getValue();
                if (o.leaseExpired(nowMs) && reclaimed < limit) {
                    entry.setValue(new LineageCatalogObligation(null, o.taskKey(), o.target(),
                            o.repositoryId(), o.endpointKind(), o.catalogQualifiedName(),
                            LineageCatalogObligation.State.PENDING, null, null, 0L,
                            o.attempts() + 1, o.createdAtMs(),
                            LineageCatalogObligation.Outcome.NONE, "lease expired", null));
                    reclaimed++;
                }
            }
            return reclaimed;
        }

        @Override
        public List<LineageCatalogObligation> findByState(LineageCatalogObligation.State state,
                int limit) {
            List<LineageCatalogObligation> found = new ArrayList<>();
            for (LineageCatalogObligation o : byKey.values()) {
                if (o.state() == state && found.size() < limit) {
                    found.add(o);
                }
            }
            return found;
        }

        @Override
        public Map<LineageCatalogObligation.State, Long> countByState() {
            Map<LineageCatalogObligation.State, Long> counts = new LinkedHashMap<>();
            for (LineageCatalogObligation.State state : LineageCatalogObligation.State.values()) {
                counts.put(state, (long) findByState(state, Integer.MAX_VALUE).size());
            }
            return counts;
        }

        /** The document, but only if the claim's token still holds it. The fence. */
        private LineageCatalogObligation heldBy(Claim claim) {
            LineageCatalogObligation current = byKey.get(claim.taskKey());
            if (current == null
                    || current.state() != LineageCatalogObligation.State.CLAIMED
                    || !claim.token().equals(current.token())) {
                return null;
            }
            return current;
        }

        private static LineageCatalogObligation withClaim(LineageCatalogObligation o,
                String owner, String token, long until) {
            return new LineageCatalogObligation(null, o.taskKey(), o.target(), o.repositoryId(),
                    o.endpointKind(), o.catalogQualifiedName(),
                    LineageCatalogObligation.State.CLAIMED, owner, token, until, o.attempts(),
                    o.createdAtMs(), LineageCatalogObligation.Outcome.NONE, null, null);
        }
    }

    private LineageCatalogObligationService service(boolean ready) {
        when(readiness.evaluate()).thenReturn(
                new LineageDrestReadiness.Readiness(ready, ready ? List.of()
                        : List.of("lineage.drest.enabled is false")));
        return new LineageCatalogObligationService(store, this::probe, readiness, identity,
                clock::get);
    }

    private Presence probe(String target, String repositoryId, EndpointKind kind,
            String qualifiedName) {
        probeCalls++;
        probedTargets.add(target);
        if (probeFailure != null) {
            throw probeFailure;
        }
        return answer;
    }

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        readiness = mock(LineageDrestReadiness.class);
        identity = mock(LineageNodeIdentity.class);
        when(identity.nodeId()).thenReturn("node-1");
        clock = new AtomicLong(1_000_000L);
        answer = Presence.ABSENT;
        probeFailure = null;
        probeCalls = 0;
        probedTargets.clear();
    }

    @Nested
    @DisplayName("while D-rest is off")
    class Inert {

        /** The whole point of distributing this inactive: it must do nothing at all. */
        @Test
        @DisplayName("nothing is created, claimed, scanned or reclaimed")
        void completelyInert() {
            LineageCatalogObligationService inactive = service(false);

            assertFalse(inactive.active());
            assertEquals(Optional.empty(),
                    inactive.requireCatalogEntity(TARGET, REPO, KIND, QN));
            assertEquals(LineageCatalogObligationService.Pass.INERT, inactive.runOnce(10));
            assertEquals(Map.of(), inactive.status());

            assertTrue(store.byKey.isEmpty(), "an inert machine wrote a document");
            assertEquals(0, probeCalls, "an inert machine asked the catalog");
        }

        /** An inert machine cannot answer a resume question either — it parked nothing. */
        @Test
        @DisplayName("nothing is reported as resumable")
        void nothingResumes() {
            assertFalse(service(false).allResolved(List.of("some-key")));
            // An empty wait list is still trivially satisfied: there is nothing to wait for.
            assertTrue(service(false).allResolved(List.of()));
        }

        @Test
        @DisplayName("a terminal verdict cannot be recorded")
        void cannotGiveUp() {
            assertFalse(service(false).giveUp(new Claim("k", "node-1", "tok", 0L), "why"));
        }

        @Test
        @DisplayName("an unwired machine is inert even if readiness says green")
        void unwiredIsInert() {
            when(readiness.evaluate())
                    .thenReturn(new LineageDrestReadiness.Readiness(true, List.of()));
            assertFalse(new LineageCatalogObligationService(
                    null, LineageCatalogObligationServiceTest.this::probe, readiness, identity,
                    clock::get).active());
        }
    }

    @Nested
    @DisplayName("the producer")
    class Producer {

        @Test
        @DisplayName("lets a present entity through without writing anything")
        void presentProceeds() {
            answer = Presence.PRESENT;

            assertEquals(Optional.empty(),
                    service(true).requireCatalogEntity(TARGET, REPO, KIND, QN));
            assertTrue(store.byKey.isEmpty());
        }

        @Test
        @DisplayName("owes an obligation for an absent entity")
        void absentOwes() {
            answer = Presence.ABSENT;

            Optional<String> taskKey =
                    service(true).requireCatalogEntity(TARGET, REPO, KIND, QN);

            assertTrue(taskKey.isPresent());
            assertEquals(LineageCatalogObligation.taskKey(TARGET, REPO, KIND, QN), taskKey.get());
            assertEquals(LineageCatalogObligation.State.PENDING,
                    store.byKey.get(taskKey.get()).state());
        }

        /** Silence is not permission. A catalog that did not answer confirmed nothing. */
        @Test
        @DisplayName("owes an obligation when the catalog did not answer")
        void unknownOwes() {
            answer = Presence.UNKNOWN;
            assertTrue(service(true).requireCatalogEntity(TARGET, REPO, KIND, QN).isPresent());
        }

        @Test
        @DisplayName("owes an obligation when the probe throws")
        void probeFailureOwes() {
            probeFailure = new IllegalStateException("connection refused");
            assertTrue(service(true).requireCatalogEntity(TARGET, REPO, KIND, QN).isPresent());
        }

        /** A restart, a replay and a duplicate delivery converge on one document. */
        @Test
        @DisplayName("creating twice for the same subject leaves one obligation")
        void createIsIdempotent() {
            LineageCatalogObligationService active = service(true);

            String first = active.requireCatalogEntity(TARGET, REPO, KIND, QN).orElseThrow();
            String second = active.requireCatalogEntity(TARGET, REPO, KIND, QN).orElseThrow();

            assertEquals(first, second);
            assertEquals(1, store.byKey.size());
        }

        /** Two different subjects colliding on one key is a bug, not "already done". */
        @Test
        @DisplayName("a different subject under the same key is refused")
        void collidingSubjectIsRefused() {
            String taskKey = LineageCatalogObligation.taskKey(TARGET, REPO, KIND, QN);
            store.byKey.put(taskKey, new LineageCatalogObligation(null, taskKey, TARGET, REPO,
                    EndpointKind.CMIS_DOCUMENT, QN, LineageCatalogObligation.State.PENDING,
                    null, null, 0L, 0, 1L, LineageCatalogObligation.Outcome.NONE, null, null));

            assertThrows(LineageCatalogObligationStore.ObligationSubjectConflictException.class,
                    () -> service(true).requireCatalogEntity(TARGET, REPO, KIND, QN));
        }

        /** Parking a projection whose obligation is already resolved would stall it for nothing. */
        @Test
        @DisplayName("an already-resolved obligation does not park the projection again")
        void resolvedDoesNotPark() {
            String taskKey = LineageCatalogObligation.taskKey(TARGET, REPO, KIND, QN);
            store.byKey.put(taskKey, new LineageCatalogObligation(null, taskKey, TARGET, REPO,
                    KIND, QN, LineageCatalogObligation.State.RESOLVED, null, null, 0L, 1, 1L,
                    LineageCatalogObligation.Outcome.SOURCE_EXISTS, "already there", null));

            assertEquals(Optional.empty(),
                    service(true).requireCatalogEntity(TARGET, REPO, KIND, QN));
        }
    }

    /**
     * The task key names a target, and the verdict behind it must come from that target.
     *
     * <p>Otherwise the key is a label rather than an identity: a projection to Purview would
     * proceed because Atlas happened to hold the entity.
     */
    @Test
    @DisplayName("the probe is asked about the obligation's own target")
    public void probeRoutingMatchesTheTaskKey() {
        answer = Presence.ABSENT;
        LineageCatalogObligationService active = service(true);
        active.requireCatalogEntity("atlas", REPO, KIND, QN);
        active.requireCatalogEntity("purview", REPO, KIND, QN);

        assertEquals(List.of("atlas", "purview"), probedTargets);
        // Two targets, one qualified name, two obligations: each waits independently.
        assertEquals(2, store.byKey.size());
        assertFalse(LineageCatalogObligation.taskKey("atlas", REPO, KIND, QN)
                .equals(LineageCatalogObligation.taskKey("purview", REPO, KIND, QN)));

        // And the consumer asks the catalog each obligation names, not whichever came first.
        probedTargets.clear();
        answer = Presence.PRESENT;
        active.runOnce(10);
        assertEquals(2, probedTargets.size());
        assertTrue(probedTargets.contains("atlas"));
        assertTrue(probedTargets.contains("purview"));
    }

    @Nested
    @DisplayName("claim, lease and fencing")
    class Claiming {

        private String owe() {
            answer = Presence.ABSENT;
            return service(true).requireCatalogEntity(TARGET, REPO, KIND, QN).orElseThrow();
        }

        @Test
        @DisplayName("two workers contend and exactly one wins")
        void claimContention() {
            String taskKey = owe();

            Optional<Claim> first =
                    store.claim(taskKey, "node-1", Duration.ofMinutes(5), clock.get());
            Optional<Claim> second =
                    store.claim(taskKey, "node-2", Duration.ofMinutes(5), clock.get());

            assertTrue(first.isPresent());
            assertTrue(second.isEmpty(), "two workers held the same obligation at once");
        }

        @Test
        @DisplayName("an expired lease is reclaimed and the obligation is claimable again")
        void leaseExpiryReclaims() {
            String taskKey = owe();
            Claim held = store.claim(taskKey, "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();

            clock.addAndGet(Duration.ofMinutes(6).toMillis());
            assertEquals(1, store.reclaimExpired(10, clock.get()));

            assertEquals(LineageCatalogObligation.State.PENDING,
                    store.byKey.get(taskKey).state());
            assertTrue(store.claim(taskKey, "node-2", Duration.ofMinutes(5), clock.get())
                    .isPresent());
            assertEquals(held.taskKey(), taskKey);
        }

        /**
         * The fence. A worker that stalled past its lease has no way to know it did, so the
         * new token is what stops it from finishing on top of the worker that took over.
         */
        @Test
        @DisplayName("a stale claimant cannot write after someone else took over")
        void staleClaimantIsRefused() {
            String taskKey = owe();
            Claim stale = store.claim(taskKey, "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();

            clock.addAndGet(Duration.ofMinutes(6).toMillis());
            store.reclaimExpired(10, clock.get());
            Claim fresh = store.claim(taskKey, "node-2", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();

            assertFalse(store.resolve(stale, LineageCatalogObligation.Outcome.SOURCE_EXISTS,
                    "stale worker came back", null));
            assertFalse(store.release(stale, "stale"));
            assertTrue(store.renew(stale, Duration.ofMinutes(5), clock.get()).isEmpty());

            // And the worker that actually holds it still can.
            assertTrue(store.resolve(fresh, LineageCatalogObligation.Outcome.SOURCE_EXISTS,
                    "the catalog holds it", null));
        }

        @Test
        @DisplayName("a live claim is not reclaimed")
        void liveClaimSurvives() {
            String taskKey = owe();
            store.claim(taskKey, "node-1", Duration.ofMinutes(5), clock.get());

            clock.addAndGet(Duration.ofMinutes(1).toMillis());

            assertEquals(0, store.reclaimExpired(10, clock.get()));
            assertEquals(LineageCatalogObligation.State.CLAIMED,
                    store.byKey.get(taskKey).state());
        }

        @Test
        @DisplayName("a terminal obligation cannot be claimed again")
        void terminalIsNotClaimable() {
            String taskKey = owe();
            Claim claim = store.claim(taskKey, "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();
            store.resolve(claim, LineageCatalogObligation.Outcome.SOURCE_EXISTS, "done", null);

            assertTrue(store.claim(taskKey, "node-2", Duration.ofMinutes(5), clock.get())
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("the consumer")
    class Consumer {

        private String owe() {
            answer = Presence.ABSENT;
            return service(true).requireCatalogEntity(TARGET, REPO, KIND, QN).orElseThrow();
        }

        @Test
        @DisplayName("resolves an obligation once the entity appears")
        void resolvesWhenPresent() {
            String taskKey = owe();
            answer = Presence.PRESENT;

            LineageCatalogObligationService.Pass pass = service(true).runOnce(10);

            assertEquals(1, pass.claimed());
            assertEquals(1, pass.resolved());
            assertEquals(LineageCatalogObligation.State.RESOLVED,
                    store.byKey.get(taskKey).state());
            assertEquals(LineageCatalogObligation.Outcome.SOURCE_EXISTS,
                    store.byKey.get(taskKey).outcome());
        }

        /** A five-minute outage must not become a permanently unprojectable event. */
        @Test
        @DisplayName("a catalog that did not answer is retried, never terminated")
        void unknownIsRetried() {
            String taskKey = owe();
            answer = Presence.UNKNOWN;

            LineageCatalogObligationService.Pass pass = service(true).runOnce(10);

            assertEquals(1, pass.released());
            assertEquals(0, pass.gaveUp());
            assertEquals(LineageCatalogObligation.State.PENDING,
                    store.byKey.get(taskKey).state());
            assertTrue(store.byKey.get(taskKey).attempts() > 0, "the attempt was not counted");
        }

        @Test
        @DisplayName("a still-absent entity is retried too")
        void absentIsRetried() {
            String taskKey = owe();

            assertEquals(1, service(true).runOnce(10).released());
            assertEquals(LineageCatalogObligation.State.PENDING,
                    store.byKey.get(taskKey).state());
        }

        /** The retryable failure converges once the authoritative publisher catches up. */
        @Test
        @DisplayName("a retryable failure is re-claimed and eventually resolves")
        void retryableConverges() {
            String taskKey = owe();
            LineageCatalogObligationService active = service(true);

            answer = Presence.UNKNOWN;
            assertEquals(1, active.runOnce(10).released());

            answer = Presence.PRESENT;
            assertEquals(1, active.runOnce(10).resolved());
            assertEquals(LineageCatalogObligation.State.RESOLVED,
                    store.byKey.get(taskKey).state());
        }

        @Test
        @DisplayName("a terminal verdict is bound to its reason")
        void terminalNeedsAReason() {
            String taskKey = owe();
            Claim claim = store.claim(taskKey, "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();

            assertTrue(service(true).giveUp(claim, "the snapshot cannot rebuild the entity"));
            assertEquals(LineageCatalogObligation.State.UNRESOLVED,
                    store.byKey.get(taskKey).state());
            assertEquals(LineageCatalogObligation.Outcome.SNAPSHOT_INCOMPLETE,
                    store.byKey.get(taskKey).outcome());
        }

        @Test
        @DisplayName("the pass is bounded")
        void bounded() {
            answer = Presence.ABSENT;
            LineageCatalogObligationService active = service(true);
            for (int i = 0; i < 5; i++) {
                active.requireCatalogEntity(TARGET, REPO, KIND, QN + "-" + i);
            }

            assertEquals(2, active.runOnce(2).claimed());
        }

        /** Evidence is read back on admin routes, so it must not be the name itself. */
        @Test
        @DisplayName("the recorded evidence is a digest, not the qualified name")
        void evidenceIsRedacted() {
            String taskKey = owe();
            answer = Presence.PRESENT;
            service(true).runOnce(10);

            String evidence = store.byKey.get(taskKey).evidence();
            assertFalse(evidence.contains(QN));
            assertEquals(LineageEndpoint.shortDigest(QN), evidence);
        }
    }

    @Nested
    @DisplayName("the resume condition")
    class Resume {

        /** §2: ALL of them. One resolved obligation says nothing about the others. */
        @Test
        @DisplayName("one resolved obligation does not resume an event waiting on two")
        void allNotAny() {
            answer = Presence.ABSENT;
            LineageCatalogObligationService active = service(true);
            String first = active.requireCatalogEntity(TARGET, REPO, KIND, QN).orElseThrow();
            String second =
                    active.requireCatalogEntity(TARGET, REPO, KIND, QN + "-2").orElseThrow();

            Claim claim = store.claim(first, "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();
            store.resolve(claim, LineageCatalogObligation.Outcome.SOURCE_EXISTS, "there", null);

            assertFalse(active.allResolved(List.of(first, second)));

            Claim other = store.claim(second, "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();
            store.resolve(other, LineageCatalogObligation.Outcome.SOURCE_EXISTS, "there", null);

            assertTrue(active.allResolved(List.of(first, second)));
        }

        /** A task key nobody knows is not resolved — it is unaccounted for. */
        @Test
        @DisplayName("an unknown task key does not resume anything")
        void unknownKeyDoesNotResume() {
            assertFalse(service(true).allResolved(List.of("nonexistent")));
        }

        @Test
        @DisplayName("an UNRESOLVED obligation does not resume the event either")
        void unresolvedDoesNotResume() {
            answer = Presence.ABSENT;
            LineageCatalogObligationService active = service(true);
            String taskKey = active.requireCatalogEntity(TARGET, REPO, KIND, QN).orElseThrow();
            Claim claim = store.claim(taskKey, "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();
            active.giveUp(claim, "the snapshot cannot rebuild it");

            assertFalse(active.allResolved(List.of(taskKey)));
        }
    }
}
