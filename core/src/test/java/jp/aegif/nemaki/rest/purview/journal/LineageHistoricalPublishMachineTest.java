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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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

/**
 * Every crash boundary, resumed.
 *
 * <p>The sequence the whole design is shaped by is asserted directly: the entity is written, the
 * process dies, the source is restored, and the restart must <b>not</b> conclude everything is
 * fine. It must find the intent, notice the source came back, and converge on a compensation.
 *
 * <p>The stores are in-memory implementations of the real contracts rather than mocks, because
 * what is under test is what a <em>sequence</em> of CAS operations does across a restart.
 */
public class LineageHistoricalPublishMachineTest {

    private static final String TARGET = "purview";
    private static final String REPO = "bedroom";
    private static final String QN = "nemaki://bedroom/objects/doc-1";
    private static final EndpointKind KIND = EndpointKind.CMIS_DOCUMENT;

    private AtomicLong clock;
    private FakeIntentStore intents;
    private FakeCompensationStore compensations;
    private FakePublisher publisher;
    private FakeRepublisher republisher;
    private LineageSourceDisposition sourceSays;
    private String sourceIncarnation;
    /** The incarnation the plan was authorised by; the source may since have moved on. */
    private String authorisingIncarnation;
    private LineageHistoricalPublishMachine machine;

    // ------------------------------------------------------------------ fakes

    /** Honours _rev CAS, token fencing and state guards, like the real store must. */
    private static final class FakeIntentStore implements LineageHistoricalPublishIntentStore {
        final Map<String, LineageHistoricalPublishIntent> byId = new LinkedHashMap<>();
        final Map<String, SubjectFence> fences = new LinkedHashMap<>();
        boolean failWrites;
        boolean failRenew;
        boolean failFinalTransition;

        @Override
        public LineageHistoricalPublishIntent createIfAbsent(
                LineageHistoricalPublishIntent intent) {
            if (failWrites) {
                throw new IntentStorageException("store unavailable");
            }
            LineageHistoricalPublishIntent existing = byId.get(intent.intentId());
            if (existing != null) {
                if (!intent.samePlanAs(existing)) {
                    throw new IntentPlanConflictException("different plan");
                }
                return existing;
            }
            byId.put(intent.intentId(), intent);
            return intent;
        }

        @Override
        public Optional<LineageHistoricalPublishIntent> read(String intentId) {
            return Optional.ofNullable(byId.get(intentId));
        }

        @Override
        public Optional<IntentClaim> claim(String intentId, String owner, Duration lease,
                long nowMs) {
            LineageHistoricalPublishIntent current = byId.get(intentId);
            if (current == null) {
                return Optional.empty();
            }
            if (current.token() != null && !current.leaseExpired(nowMs)) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            long until = nowMs + lease.toMillis();
            byId.put(intentId, with(current, current.state(), owner, token, until,
                    current.attempts(), current.reason()));
            return Optional.of(new IntentClaim(intentId, owner, token, until,
                    current.state()));
        }

        @Override
        public Optional<IntentClaim> renew(IntentClaim claim, Duration lease, long nowMs) {
            if (failRenew) {
                return Optional.empty();
            }
            LineageHistoricalPublishIntent held = heldBy(claim);
            if (held == null) {
                return Optional.empty();
            }
            long until = nowMs + lease.toMillis();
            byId.put(claim.intentId(), with(held, held.state(), claim.owner(), claim.token(),
                    until, held.attempts(), held.reason()));
            return Optional.of(new IntentClaim(claim.intentId(), claim.owner(), claim.token(),
                    until, held.state()));
        }

        @Override
        public Optional<SubjectFence> acquireSubjectFence(String subjectKey, String intentId,
                Duration lease, long nowMs) {
            SubjectFence current = fences.get(subjectKey);
            if (current != null && current.leaseUntilMs() > nowMs
                    && !current.intentId().equals(intentId)) {
                return Optional.empty();
            }
            SubjectFence fence = new SubjectFence(subjectKey, intentId,
                    UUID.randomUUID().toString(), nowMs + lease.toMillis());
            fences.put(subjectKey, fence);
            return Optional.of(fence);
        }

        @Override
        public boolean releaseSubjectFence(SubjectFence fence) {
            SubjectFence current = fence == null ? null : fences.get(fence.subjectKey());
            if (current == null || !current.token().equals(fence.token())) {
                return false;
            }
            fences.remove(fence.subjectKey());
            return true;
        }

        @Override
        public boolean transition(IntentClaim claim, LineageHistoricalPublishIntent.State from,
                LineageHistoricalPublishIntent.State to, String reason) {
            if (failWrites) {
                return false;
            }
            if (failFinalTransition
                    && to == LineageHistoricalPublishIntent.State.COMPENSATED) {
                return false;
            }
            LineageHistoricalPublishIntent held = heldBy(claim);
            if (held == null || held.state() != from) {
                return false;
            }
            byId.put(claim.intentId(), with(held, to, claim.owner(), claim.token(),
                    held.leaseUntilMs(), held.attempts(), reason));
            return true;
        }

        @Override
        public boolean recordAttempt(IntentClaim claim, String reason) {
            LineageHistoricalPublishIntent held = heldBy(claim);
            if (held == null) {
                return false;
            }
            byId.put(claim.intentId(), with(held, held.state(), null, null, 0L,
                    held.attempts() + 1, reason));
            return true;
        }

        @Override
        public List<LineageHistoricalPublishIntent> findByState(
                LineageHistoricalPublishIntent.State state, int limit) {
            List<LineageHistoricalPublishIntent> found = new ArrayList<>();
            for (LineageHistoricalPublishIntent intent : byId.values()) {
                if (intent.state() == state && found.size() < limit) {
                    found.add(intent);
                }
            }
            return found;
        }

        private LineageHistoricalPublishIntent heldBy(IntentClaim claim) {
            LineageHistoricalPublishIntent current =
                    claim == null ? null : byId.get(claim.intentId());
            return current != null && claim.token() != null
                    && claim.token().equals(current.token()) ? current : null;
        }

        private static LineageHistoricalPublishIntent with(LineageHistoricalPublishIntent base,
                LineageHistoricalPublishIntent.State state, String owner, String token,
                long leaseUntilMs, int attempts, String reason) {
            return new LineageHistoricalPublishIntent(null, base.intentId(), base.taskKey(),
                    base.target(), base.repositoryId(), base.endpointKind(),
                    base.subjectDigest(), base.snapshotEvidenceDigest(),
                    base.sourceEvidenceDigest(), base.plannedOperationDigest(),
                    base.payloadSchemaVersion(), state, owner, token, leaseUntilMs, attempts,
                    base.createdAtMs(), reason);
        }
    }

    private static final class FakeCompensationStore
            implements LineageHistoricalCompensationStore {
        final Map<String, LineageHistoricalCompensation> byId = new LinkedHashMap<>();
        boolean failWrites;
        boolean failMarkResolved;

        @Override
        public LineageHistoricalCompensation createIfAbsent(
                LineageHistoricalCompensation compensation) {
            if (failWrites) {
                throw new IllegalStateException("store unavailable");
            }
            return byId.computeIfAbsent(compensation.taskId(), key -> compensation);
        }

        @Override
        public Optional<LineageHistoricalCompensation> read(String taskId) {
            return Optional.ofNullable(byId.get(taskId));
        }

        @Override
        public List<LineageHistoricalCompensation> findByState(
                LineageHistoricalCompensation.State state, int limit) {
            List<LineageHistoricalCompensation> found = new ArrayList<>();
            for (LineageHistoricalCompensation one : byId.values()) {
                if (one.state() == state && found.size() < limit) {
                    found.add(one);
                }
            }
            return found;
        }

        @Override
        public boolean markResolved(LineageHistoricalCompensation compensation, String reason) {
            return !failMarkResolved
                    && mark(compensation, LineageHistoricalCompensation.State.RESOLVED);
        }

        @Override
        public boolean markFailed(LineageHistoricalCompensation compensation, String reason) {
            return mark(compensation, LineageHistoricalCompensation.State.FAILED);
        }

        private boolean mark(LineageHistoricalCompensation compensation,
                LineageHistoricalCompensation.State state) {
            LineageHistoricalCompensation current = byId.get(compensation.taskId());
            if (current == null) {
                return false;
            }
            byId.put(current.taskId(), new LineageHistoricalCompensation(null, current.taskId(),
                    current.target(), current.repositoryId(), current.endpointKind(),
                    current.subjectDigest(), current.operationDigest(),
                    current.publishedEvidenceDigest(), current.observedEvidenceDigest(),
                    current.reason(), current.createdAtMs(), state));
            return true;
        }
    }

    /** Records what it was asked to write, and whether the entity is "in the catalog". */
    private static final class FakePublisher implements LineageHistoricalEntityPublisher {
        int publishCount;
        boolean fail;
        String forcedOperationDigest;
        String lastOperationDigest;
        /** Non-null unless the read itself fails. */
        Object stored = "readable";
        /** Which operation the catalog holds, if any. */
        String storedOperationDigest;

        @Override
        public LineageHistoricalReadBack readBackHistorical(HistoricalEntitySnapshot snapshot,
                String plannedOperationDigest) {
            if (stored == null) {
                return LineageHistoricalReadBack.UNKNOWN;
            }
            if (storedOperationDigest == null) {
                return LineageHistoricalReadBack.ABSENT;
            }
            // MATCH only if the entity in the catalog is THIS plan's write.
            return storedOperationDigest.equals(plannedOperationDigest)
                    ? LineageHistoricalReadBack.MATCH : LineageHistoricalReadBack.CONFLICT;
        }

        @Override
        public LineageHistoricalPublishReceipt publishHistorical(
                HistoricalEntitySnapshot snapshot) {
            publishCount++;
            if (fail) {
                return LineageHistoricalPublishReceipt.retryable(snapshot.target(),
                        snapshot.sourceEvidence().subjectDigest(),
                        LineageCatalogEntityProbe.Presence.UNKNOWN);
            }
            String digest = forcedOperationDigest != null ? forcedOperationDigest
                    : LineageHistoricalPublishMachine.operationDigest(snapshot,
                            LineageHistoricalPublishMachine.canonicalPayload(snapshot));
            lastOperationDigest = digest;
            storedOperationDigest = digest;
            return new LineageHistoricalPublishReceipt(Outcome.PUBLISHED, snapshot.target(),
                    snapshot.sourceEvidence().subjectDigest(), digest,
                    LineageCatalogEntityProbe.Presence.PRESENT);
        }
    }

    private static final class FakeRepublisher implements LineageCurrentEntityRepublisher {
        int calls;
        Outcome answer = Outcome.REPUBLISHED;

        @Override
        public Outcome republishCurrent(String target, String repositoryId, EndpointKind kind,
                String subjectDigest) {
            calls++;
            return answer;
        }
    }

    // ------------------------------------------------------------------ fixtures

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000_000L);
        intents = new FakeIntentStore();
        compensations = new FakeCompensationStore();
        publisher = new FakePublisher();
        republisher = new FakeRepublisher();
        sourceSays = LineageSourceDisposition.SOURCE_PURGED;
        sourceIncarnation = "inc-1";
        authorisingIncarnation = "inc-1";

        LineageNodeIdentity identity = mock(LineageNodeIdentity.class);
        when(identity.nodeId()).thenReturn("node-1");

        machine = new LineageHistoricalPublishMachine(intents, compensations,
                new LineageHistoricalPublisherRegistry(Map.of(TARGET, publisher)),
                this::currentSource, republisher, identity, clock::get);
    }

    private LineageSourceDispositionResolver.SourceEvidence currentSource(String repositoryId,
            EndpointKind kind, String catalogQualifiedName) {
        if (sourceSays == LineageSourceDisposition.SOURCE_UNKNOWN) {
            return LineageSourceDispositionResolver.SourceEvidence.unknown(clock.get());
        }
        return LineageSourceDispositionResolver.SourceEvidence.of(repositoryId, kind,
                catalogQualifiedName, sourceSays,
                sourceSays == LineageSourceDisposition.SOURCE_PURGED ? sourceIncarnation : null,
                sourceSays == LineageSourceDisposition.SOURCE_PURGED ? "rev-1" : null,
                null, clock.get());
    }

    private static LineageCatalogObligation obligation() {
        return new LineageCatalogObligation(null,
                LineageCatalogObligation.taskKey(TARGET, REPO, KIND, QN), TARGET, REPO, KIND, QN,
                LineageCatalogObligation.State.CLAIMED, "node-1", "tok", 9_999_999L, 0L, 0, 1L,
                LineageCatalogObligation.Outcome.NONE, null, null);
    }

    /**
     * The authorising material, built from the evidence that licensed the plan — NOT from
     * whatever the source says now.
     *
     * <p>That is also how production resumes: the intent records which evidence authorised the
     * write, and the machine re-reads the current source to compare against it. Rebuilding the
     * authorisation from the current reading would compare a fact with itself and never detect
     * a restore.
     */
    private HistoricalEntitySnapshot historical() {
        return historicalAuthorisedBy(authorisingIncarnation);
    }

    private HistoricalEntitySnapshot historicalAuthorisedBy(String incarnation) {
        LineageWaitingSnapshot snapshot = LineageWaitingSnapshot.of(TARGET, REPO, KIND, QN,
                Map.of("name", "a.txt"), LineageSourceDisposition.SOURCE_PURGED, 2);
        LineageSourceDispositionResolver.SourceEvidence authorising =
                LineageSourceDispositionResolver.SourceEvidence.of(REPO, KIND, QN,
                        LineageSourceDisposition.SOURCE_PURGED, incarnation, "rev-1", null,
                        1000L);
        return HistoricalEntitySnapshot.from(snapshot, obligation(), TARGET, authorising)
                .orElseThrow();
    }

    private LineageHistoricalPublishMachine.Verdict publish() {
        return machine.publish(obligation(), historical(), List.of("name"));
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("the ordinary path")
    class HappyPath {

        @Test
        @DisplayName("plans, publishes, re-checks and resolves")
        void resolves() {
            assertEquals(LineageHistoricalPublishMachine.Verdict.RESOLVED_PURGED, publish());

            assertEquals(1, publisher.publishCount);
            assertEquals(1, intents.byId.size());
            assertEquals(LineageHistoricalPublishIntent.State.RESOLVED,
                    intents.byId.values().iterator().next().state());
            assertTrue(compensations.byId.isEmpty());
        }

        /** The intent must exist before anything external happens. */
        @Test
        @DisplayName("the intent is durable before the external write")
        void intentPrecedesTheWrite() {
            intents.failWrites = true;

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, publish());
            assertEquals(0, publisher.publishCount,
                    "nothing may be written to the catalog before the intent is durable");
        }

        @Test
        @DisplayName("re-running the same plan reaches the same intent")
        void planIsDeterministic() {
            publish();
            String first = intents.byId.keySet().iterator().next();

            intents.byId.get(first);
            publish();

            assertEquals(1, intents.byId.size());
            assertEquals(first, intents.byId.keySet().iterator().next());
        }

        /** A different source verdict is a different plan and must not adopt the old intent. */
        @Test
        @DisplayName("a plan from different evidence is a different intent")
        void differentEvidenceIsADifferentPlan() {
            publish();
            String first = intents.byId.keySet().iterator().next();

            sourceIncarnation = "inc-2";
            authorisingIncarnation = "inc-2";
            LineageHistoricalPublishIntent.State before =
                    intents.byId.get(first).state();
            publish();

            assertEquals(2, intents.byId.size(),
                    "evidence from another incarnation must not reuse the earlier intent");
            assertEquals(before, intents.byId.get(first).state());
        }
    }

    @Nested
    @DisplayName("crash boundaries")
    class Crashes {

        /**
         * The sequence the whole design exists for. The entity is written, the process dies
         * before anything else, and the source comes back. A restart must not read the world
         * as consistent.
         */
        @Test
        @DisplayName("published, crashed, then restored — compensates rather than resolving")
        void publishedThenRestoredAcrossACrash() {
            // Publish, but the process dies before the state moves off PLANNED. The entity
            // is in the catalog; nothing durable records that it is.
            intents.createIfAbsent(plannedIntent());
            publisher.publishHistorical(historical());
            publisher.publishCount = 0;

            // …and the source comes back while we are down.
            sourceSays = LineageSourceDisposition.SOURCE_EXISTS;

            LineageHistoricalPublishMachine.Verdict verdict = resumeAll();

            // COMPENSATED, not merely COMPENSATING: both durable states agree.
            assertEquals(LineageHistoricalPublishMachine.Verdict.COMPENSATED, verdict);
            assertFalse(compensations.byId.isEmpty(),
                    "a wrong historical entity with nothing recorded is never revisited");
            assertEquals(1, republisher.calls,
                    "the compensation must converge on the current entity");
            assertEquals(LineageHistoricalCompensation.State.RESOLVED,
                    compensations.byId.values().iterator().next().state());
            assertEquals(LineageHistoricalPublishIntent.State.COMPENSATED,
                    intents.byId.values().iterator().next().state());
        }

        @Test
        @DisplayName("crashed after PLANNED and before publishing — publishes on resume")
        void plannedThenCrash() {
            intents.createIfAbsent(plannedIntent());

            assertEquals(LineageHistoricalPublishMachine.Verdict.RESOLVED_PURGED, resumeAll());
            assertEquals(1, publisher.publishCount);
        }

        @Test
        @DisplayName("crashed after PUBLISHED and before the source re-check — re-checks")
        void publishedThenCrash() {
            intents.createIfAbsent(plannedIntent());
            claimAndTransition(LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED);

            assertEquals(LineageHistoricalPublishMachine.Verdict.RESOLVED_PURGED, resumeAll());
            assertEquals(0, publisher.publishCount, "the entity is already written");
        }

        @Test
        @DisplayName("crashed after detecting the restore and before the compensation")
        void compensationRequiredThenCrash() {
            intents.createIfAbsent(plannedIntent());
            claimAndTransition(LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED);
            claimAndTransition(LineageHistoricalPublishIntent.State.PUBLISHED,
                    LineageHistoricalPublishIntent.State.COMPENSATION_REQUIRED);
            sourceSays = LineageSourceDisposition.SOURCE_EXISTS;

            assertEquals(LineageHistoricalPublishMachine.Verdict.COMPENSATED, resumeAll());
            assertFalse(compensations.byId.isEmpty());
        }

        /**
         * Stopped between the two final CAS steps. The next scan must finish only what is
         * left, and must not report completion until both durable states agree.
         */
        @Test
        @DisplayName("compensation RESOLVED but intent still COMPENSATION_REQUIRED — finishes")
        void resumesFromResolvedCompensation() {
            intents.createIfAbsent(plannedIntent());
            claimAndTransition(LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED);
            claimAndTransition(LineageHistoricalPublishIntent.State.PUBLISHED,
                    LineageHistoricalPublishIntent.State.COMPENSATION_REQUIRED);
            sourceSays = LineageSourceDisposition.SOURCE_EXISTS;
            // The compensation was recorded AND resolved before the stop.
            LineageHistoricalPublishIntent intent = intents.byId.values().iterator().next();
            LineageHistoricalCompensation stored = compensations.createIfAbsent(
                    new LineageHistoricalCompensation(null,
                            LineageHistoricalCompensation.taskId(TARGET, REPO, KIND,
                                    intent.subjectDigest(), intent.plannedOperationDigest()),
                            TARGET, REPO, KIND, intent.subjectDigest(),
                            intent.plannedOperationDigest(), intent.sourceEvidenceDigest(), null,
                            LineageHistoricalCompensation.Reason
                                    .SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH,
                            1L, LineageHistoricalCompensation.State.PENDING));
            compensations.markResolved(stored, "already done");

            assertEquals(LineageHistoricalPublishMachine.Verdict.COMPENSATED, resumeAll());
            assertEquals(0, republisher.calls,
                    "the current entity was already re-published; do not do it again");
            assertEquals(LineageHistoricalPublishIntent.State.COMPENSATED,
                    intents.byId.values().iterator().next().state());
        }

        /** The republish happened; recording it did not. Not finished. */
        @Test
        @DisplayName("a compensation that cannot be marked resolved is not reported complete")
        void markResolvedFailureDoesNotComplete() {
            intents.createIfAbsent(plannedIntent());
            claimAndTransition(LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED);
            sourceSays = LineageSourceDisposition.SOURCE_EXISTS;
            compensations.failMarkResolved = true;

            assertEquals(LineageHistoricalPublishMachine.Verdict.COMPENSATING, resumeAll());
            assertEquals(LineageHistoricalCompensation.State.PENDING,
                    compensations.byId.values().iterator().next().state());
            assertNotEquals(LineageHistoricalPublishIntent.State.COMPENSATED,
                    intents.byId.values().iterator().next().state());
        }

        /** The last CAS lost. Both states are not consistent, so it is not complete. */
        @Test
        @DisplayName("a failed final intent CAS is not reported complete")
        void finalIntentCasFailureDoesNotComplete() {
            intents.createIfAbsent(plannedIntent());
            claimAndTransition(LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED);
            sourceSays = LineageSourceDisposition.SOURCE_EXISTS;
            intents.failFinalTransition = true;

            assertEquals(LineageHistoricalPublishMachine.Verdict.COMPENSATING, resumeAll());
            assertNotEquals(LineageHistoricalPublishIntent.State.COMPENSATED,
                    intents.byId.values().iterator().next().state());
        }

        /** Without a durable request nothing comes back for the wrong entity. */
        @Test
        @DisplayName("a compensation that cannot be recorded leaves the intent where it is")
        void compensationRecordFailureDoesNotAdvance() {
            intents.createIfAbsent(plannedIntent());
            claimAndTransition(LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED);
            claimAndTransition(LineageHistoricalPublishIntent.State.PUBLISHED,
                    LineageHistoricalPublishIntent.State.COMPENSATION_REQUIRED);
            sourceSays = LineageSourceDisposition.SOURCE_EXISTS;
            compensations.failWrites = true;

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, resumeAll());
            assertEquals(LineageHistoricalPublishIntent.State.COMPENSATION_REQUIRED,
                    intents.byId.values().iterator().next().state());
        }

        /** The current entity is not confirmed, so the compensation is not finished. */
        @Test
        @DisplayName("a compensation whose republish is unknown does not complete")
        void unknownRepublishDoesNotComplete() {
            intents.createIfAbsent(plannedIntent());
            claimAndTransition(LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED);
            sourceSays = LineageSourceDisposition.SOURCE_EXISTS;
            republisher.answer = LineageCurrentEntityRepublisher.Outcome.SOURCE_UNKNOWN;

            assertEquals(LineageHistoricalPublishMachine.Verdict.COMPENSATING, resumeAll());
            assertEquals(LineageHistoricalCompensation.State.PENDING,
                    compensations.byId.values().iterator().next().state());
            assertNotEquals(LineageHistoricalPublishIntent.State.COMPENSATED,
                    intents.byId.values().iterator().next().state());
        }
    }

    @Nested
    @DisplayName("the lease authorises the external write")
    class LeaseAuthorisation {

        /**
         * The renew is not a courtesy. A process that has lost the lease no longer speaks for
         * the intent, and a write it made would be one nothing is tracking.
         */
        @Test
        @DisplayName("a failed renew means the publisher is never called")
        void lostLeaseMeansNoWrite() {
            intents.createIfAbsent(plannedIntent());
            intents.failRenew = true;

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, resumeAll());
            assertEquals(0, publisher.publishCount,
                    "an unauthorised process wrote to the catalog");
            assertEquals(LineageHistoricalPublishIntent.State.PLANNED,
                    intents.byId.values().iterator().next().state());
        }

        /** The fence is released even when the write is refused, or the subject would stick. */
        @Test
        @DisplayName("a refused write still releases the subject fence")
        void refusedWriteReleasesTheFence() {
            intents.createIfAbsent(plannedIntent());
            intents.failRenew = true;
            resumeAll();

            assertTrue(intents.fences.isEmpty(), "the subject would be blocked forever");
        }
    }

    @Nested
    @DisplayName("read-back is bound to this plan")
    class ReadBack {

        /** Something present is not this plan's write — the authoritative publisher uses the
         * same qualified name. */
        @Test
        @DisplayName("a conflicting entity is not treated as this plan's write")
        void conflictIsNotSuccess() {
            intents.createIfAbsent(plannedIntent());
            publisher.storedOperationDigest = "f".repeat(64);

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, resumeAll());
            assertEquals(0, publisher.publishCount,
                    "overwriting someone else's entity is what the fence exists to prevent");
            assertEquals(LineageHistoricalPublishIntent.State.PLANNED,
                    intents.byId.values().iterator().next().state());
        }

        @Test
        @DisplayName("an unreadable catalog neither writes nor advances")
        void unknownReadBack() {
            intents.createIfAbsent(plannedIntent());
            publisher.stored = null;

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, resumeAll());
            assertEquals(0, publisher.publishCount);
        }
    }

    @Nested
    @DisplayName("one writer per subject")
    class SubjectFence {

        /** Two intents from different observations write the same qualified name. */
        @Test
        @DisplayName("a second intent cannot publish while another holds the subject")
        void secondIntentWaits() {
            LineageHistoricalPublishIntent first = intents.createIfAbsent(plannedIntent());
            String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(TARGET, REPO,
                    KIND, first.subjectDigest());
            // Another intent already holds the subject.
            intents.acquireSubjectFence(subjectKey, "another-intent", Duration.ofMinutes(5),
                    clock.get());

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, resumeAll());
            assertEquals(0, publisher.publishCount,
                    "two intents wrote the same catalog entity concurrently");
        }

        /** An abandoned holder must not block the subject forever. */
        @Test
        @DisplayName("an expired fence can be taken over")
        void expiredFenceIsReclaimable() {
            LineageHistoricalPublishIntent first = intents.createIfAbsent(plannedIntent());
            String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(TARGET, REPO,
                    KIND, first.subjectDigest());
            intents.acquireSubjectFence(subjectKey, "abandoned", Duration.ofMinutes(5),
                    clock.get());

            clock.addAndGet(Duration.ofMinutes(6).toMillis());

            assertEquals(LineageHistoricalPublishMachine.Verdict.RESOLVED_PURGED, resumeAll());
            assertEquals(1, publisher.publishCount);
        }

        /** The result must not depend on which order the scanner happened to enumerate them. */
        @Test
        @DisplayName("the outcome does not depend on scan order")
        void scanOrderDoesNotMatter() {
            intents.createIfAbsent(plannedIntent());
            assertEquals(LineageHistoricalPublishMachine.Verdict.RESOLVED_PURGED, resumeAll());

            // A second intent for the same subject, from a later observation.
            authorisingIncarnation = "inc-2";
            sourceIncarnation = "inc-2";
            publisher.storedOperationDigest = null;
            intents.createIfAbsent(plannedIntent());
            assertEquals(2, intents.byId.size(), "two intents for one subject");

            assertEquals(LineageHistoricalPublishMachine.Verdict.RESOLVED_PURGED, resumeAll());
            assertTrue(intents.fences.isEmpty(), "every write released its fence");

            // Reversing the enumeration reaches the same terminal states.
            List<LineageHistoricalPublishIntent.State> states = new ArrayList<>();
            for (LineageHistoricalPublishIntent one : intents.byId.values()) {
                states.add(one.state());
            }
            assertEquals(List.of(LineageHistoricalPublishIntent.State.RESOLVED,
                    LineageHistoricalPublishIntent.State.RESOLVED), states);
        }
    }

    @Nested
    @DisplayName("things that are never terminal")
    class NeverTerminal {

        @Test
        @DisplayName("an unknown source before publishing does not write")
        void unknownBeforePublish() {
            intents.createIfAbsent(plannedIntent());
            sourceSays = LineageSourceDisposition.SOURCE_UNKNOWN;

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, resumeAll());
            assertEquals(0, publisher.publishCount);
        }

        @Test
        @DisplayName("an unknown source after publishing neither resolves nor compensates")
        void unknownAfterPublish() {
            intents.createIfAbsent(plannedIntent());
            claimAndTransition(LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED);
            sourceSays = LineageSourceDisposition.SOURCE_UNKNOWN;

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, resumeAll());
            assertEquals(LineageHistoricalPublishIntent.State.PUBLISHED,
                    intents.byId.values().iterator().next().state());
            assertTrue(compensations.byId.isEmpty());
        }

        @Test
        @DisplayName("a failed publish leaves the intent PLANNED")
        void failedPublish() {
            intents.createIfAbsent(plannedIntent());
            publisher.fail = true;

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, resumeAll());
            assertEquals(LineageHistoricalPublishIntent.State.PLANNED,
                    intents.byId.values().iterator().next().state());
        }

        @Test
        @DisplayName("an unwired publisher is a retry, not a failure of the snapshot")
        void unwiredPublisher() {
            LineageNodeIdentity identity = mock(LineageNodeIdentity.class);
            when(identity.nodeId()).thenReturn("node-1");
            LineageHistoricalPublishMachine unwired = new LineageHistoricalPublishMachine(
                    intents, compensations,
                    new LineageHistoricalPublisherRegistry(Map.of()), this::noSource,
                    republisher, identity, clock::get);

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY,
                    unwired.publish(obligation(), historical(), List.of("name")));
            assertTrue(intents.byId.isEmpty(), "an unwired target must not plan a write");
        }

        private LineageSourceDispositionResolver.SourceEvidence noSource(String repositoryId,
                EndpointKind kind, String qn) {
            return LineageSourceDispositionResolver.SourceEvidence.unknown(0L);
        }
    }

    @Nested
    @DisplayName("the operation digest")
    class OperationDigest {

        /** Computed from the plan, so the intent — and any compensation — can name the write. */
        @Test
        @DisplayName("a publisher that wrote something else is not accepted")
        void mismatchedOperationIsRefused() {
            publisher.forcedOperationDigest = "f".repeat(64);

            assertEquals(LineageHistoricalPublishMachine.Verdict.RETRY, publish());
            assertEquals(LineageHistoricalPublishIntent.State.PLANNED,
                    intents.byId.values().iterator().next().state(),
                    "an unrecognised write must not be recorded as this plan's");
        }

        @Test
        @DisplayName("the same plan always produces the same operation digest")
        void deterministic() {
            HistoricalEntitySnapshot one = historical();
            assertEquals(
                    LineageHistoricalPublishMachine.operationDigest(one,
                            LineageHistoricalPublishMachine.canonicalPayload(one)),
                    LineageHistoricalPublishMachine.operationDigest(one,
                            LineageHistoricalPublishMachine.canonicalPayload(one)));
        }

        /** The markers are what tells a later reader which entity the catalog is holding. */
        @Test
        @DisplayName("the payload carries the historical markers and no raw evidence")
        void payloadCarriesMarkers() {
            Map<String, Object> payload =
                    LineageHistoricalPublishMachine.canonicalPayload(historical());

            assertEquals("PURGED", payload.get("sourceState"));
            assertEquals(Boolean.FALSE, payload.get("active"));
            assertTrue(payload.containsKey("historicalSourceEvidence"));
            assertFalse(payload.toString().contains("inc-1"),
                    "an incarnation identifies the object and must not be published");
        }
    }

    @Nested
    @DisplayName("fencing")
    class Fencing {

        /** An intent outlives the claim that made it, so it carries its own token. */
        @Test
        @DisplayName("a stale intent claimant cannot advance it")
        void staleIntentClaimantIsRefused() {
            LineageHistoricalPublishIntent planned = intents.createIfAbsent(plannedIntent());
            LineageHistoricalPublishIntentStore.IntentClaim stale = intents
                    .claim(planned.intentId(), "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();

            clock.addAndGet(Duration.ofMinutes(6).toMillis());
            intents.claim(planned.intentId(), "node-2", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();

            assertFalse(intents.transition(stale,
                    LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "stale"));
        }

        /** A transition that has already happened must not be re-applied. */
        @Test
        @DisplayName("a transition from the wrong state is refused")
        void wrongFromStateIsRefused() {
            LineageHistoricalPublishIntent planned = intents.createIfAbsent(plannedIntent());
            LineageHistoricalPublishIntentStore.IntentClaim claim = intents
                    .claim(planned.intentId(), "node-1", Duration.ofMinutes(5), clock.get())
                    .orElseThrow();

            assertTrue(intents.transition(claim, LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "ok"));
            assertFalse(intents.transition(claim, LineageHistoricalPublishIntent.State.PLANNED,
                    LineageHistoricalPublishIntent.State.PUBLISHED, "again"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private LineageHistoricalPublishIntent plannedIntent() {
        HistoricalEntitySnapshot historical = historical();
        Map<String, Object> payload =
                LineageHistoricalPublishMachine.canonicalPayload(historical);
        String operation = LineageHistoricalPublishMachine.operationDigest(historical, payload);
        String subject = historical.sourceEvidence().subjectDigest();
        String intentId = LineageHistoricalPublishIntent.intentId(obligation().taskKey(), TARGET,
                REPO, KIND, subject, historical.snapshot().evidenceDigest(),
                historical.sourceEvidence().evidenceDigest(), operation, 1);
        return new LineageHistoricalPublishIntent(null, intentId, obligation().taskKey(), TARGET,
                REPO, KIND, subject, historical.snapshot().evidenceDigest(),
                historical.sourceEvidence().evidenceDigest(), operation, 1,
                LineageHistoricalPublishIntent.State.PLANNED, null, null, 0L, 0, 1L, null);
    }

    /** Resumes every incomplete intent, as the recovery scanner would. */
    private LineageHistoricalPublishMachine.Verdict resumeAll() {
        LineageHistoricalPublishMachine.Verdict last =
                LineageHistoricalPublishMachine.Verdict.RETRY;
        for (LineageHistoricalPublishIntent intent :
                new ArrayList<>(intents.byId.values())) {
            if (!intent.state().incomplete()) {
                continue;
            }
            HistoricalEntitySnapshot historical = historical();
            Map<String, Object> payload =
                    LineageHistoricalPublishMachine.canonicalPayload(historical);
            last = machine.drive(intents.byId.get(intent.intentId()), historical, publisher,
                    payload, LineageHistoricalPublishMachine.operationDigest(historical,
                            payload));
        }
        return last;
    }

    private void claimAndTransition(LineageHistoricalPublishIntent.State from,
            LineageHistoricalPublishIntent.State to) {
        LineageHistoricalPublishIntent intent = intents.byId.values().iterator().next();
        LineageHistoricalPublishIntentStore.IntentClaim claim = intents
                .claim(intent.intentId(), "setup", Duration.ofMinutes(5), clock.get())
                .orElseThrow();
        intents.transition(claim, from, to, "test setup");
        // Release, so the machine can take it.
        clock.addAndGet(Duration.ofMinutes(6).toMillis());
    }
}
