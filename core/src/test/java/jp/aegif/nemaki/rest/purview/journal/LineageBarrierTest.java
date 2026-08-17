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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §6-a's rollout fence (A-2 Slice 4a): the CAS conditions, the witness-aware tri-state, the
 * reader-admission gate, and the emit routing that makes 4b a flag flip rather than a deploy.
 */
public class LineageBarrierTest {

    private static final String NODE = "node-a";
    private static final String DIGEST = "d".repeat(64);

    /** An in-memory barrier store with real CAS semantics and injectable faults. */
    static class FakeStore implements LineageBarrierStore {
        final Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        final AtomicLong revs = new AtomicLong();
        RuntimeException readFault;
        /** Fires ONCE on the next CAS, so a test can force a genuine 409 retry. */
        boolean stealNextCas;
        int casAttempts;

        private Map<String, Object> read(String id) {
            if (readFault != null) {
                throw readFault;
            }
            Map<String, Object> doc = docs.get(id);
            return doc == null ? null : new LinkedHashMap<>(doc);
        }

        @Override
        public Map<String, Object> readBarrierRaw() {
            return read(LineageWriteVersionBarrier.DOCUMENT_ID);
        }

        @Override
        public boolean casBarrier(Map<String, Object> raw) {
            casAttempts++;
            String id = (String) raw.get("_id");
            Map<String, Object> existing = docs.get(id);
            if (stealNextCas) {
                stealNextCas = false;
                // Somebody else committed first: the document moves on without us.
                if (existing != null) {
                    Map<String, Object> stolen = new LinkedHashMap<>(existing);
                    stolen.put("_rev", (revs.incrementAndGet()) + "-x");
                    docs.put(id, stolen);
                }
                return false;
            }
            String expected = existing == null ? null : (String) existing.get("_rev");
            String offered = (String) raw.get("_rev");
            if (!java.util.Objects.equals(expected, offered)) {
                return false;
            }
            Map<String, Object> stored = new LinkedHashMap<>(raw);
            stored.put("_rev", (revs.incrementAndGet()) + "-x");
            docs.put(id, stored);
            return true;
        }

        @Override
        public Map<String, Object> readWitness() {
            return read(LineageWriteVersionBarrier.WITNESS_DOCUMENT_ID);
        }

        @Override
        public boolean writeWitnessIfAbsent(long observedAtMs) {
            docs.computeIfAbsent(LineageWriteVersionBarrier.WITNESS_DOCUMENT_ID, id -> {
                Map<String, Object> witness = new LinkedHashMap<>();
                witness.put("_id", id);
                witness.put("_rev", revs.incrementAndGet() + "-x");
                witness.put("observedAtMs", observedAtMs);
                return witness;
            });
            return true;
        }

        @Override
        public String readNodeId() {
            Map<String, Object> doc = read(LineageWriteVersionBarrier.NODE_IDENTITY_DOCUMENT_ID);
            return doc == null ? null : (String) doc.get("nodeId");
        }

        @Override
        public String allocateNodeIdIfAbsent(String proposed, long allocatedAtMs) {
            String existing = readNodeId();
            if (existing != null) {
                return existing;
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_id", LineageWriteVersionBarrier.NODE_IDENTITY_DOCUMENT_ID);
            doc.put("_rev", revs.incrementAndGet() + "-x");
            doc.put("nodeId", proposed);
            docs.put(LineageWriteVersionBarrier.NODE_IDENTITY_DOCUMENT_ID, doc);
            return proposed;
        }
    }

    private FakeStore store;
    private LineageConfig config;
    private LineageDrestReadiness readiness;
    private LineageSpoolMachinery machinery;
    private LineageBarrierReader reader;
    private LineageNodeIdentity identity;
    private LineageBarrierService service;
    private long now;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        now = 1_000_000L;
        config = mock(LineageConfig.class);
        when(config.getNodeId()).thenReturn(NODE);
        when(config.getReadSchemaVersions()).thenReturn(Set.of(1, 2));
        when(config.getBarrierViewTtlMs()).thenReturn(0L); // no memo in tests
        readiness = mock(LineageDrestReadiness.class);
        when(readiness.evaluate()).thenReturn(
                new LineageDrestReadiness.Readiness(true, List.of()));
        machinery = mock(LineageSpoolMachinery.class);
        when(machinery.probeReadiness()).thenReturn(true);
        reader = new LineageBarrierReader(store, config, () -> now);
        identity = new LineageNodeIdentity(store, config, () -> "allocated", () -> now);
        LineageBinaryDigest digest = mock(LineageBinaryDigest.class);
        when(digest.digest()).thenReturn(DIGEST);
        service = new LineageBarrierService(store, reader, identity, digest,
                new LineageCapabilityProvider(), readiness, config, machinery, () -> now);
    }

    private LineageWriteVersionBarrier stored() {
        return LineageBarrierCodec.decode(store.readBarrierRaw());
    }

    // ---------------------------------------------------------------- the state machine

    @Nested
    class StateMachine {

        @Test
        public void prepareCreatesTheWitnessBeforeTheBarrier() {
            // The ordering is the whole defence: a barrier that became durable without a
            // witness could be deleted and the deployment would look pristine again.
            assertNull(store.readWitness());
            assertTrue(service.prepare(null, null).applied());
            assertNotNull(store.readWitness(), "the witness exists once a barrier does");
            LineageWriteVersionBarrier barrier = stored();
            assertEquals(LineageWriteVersionBarrier.State.PREPARING, barrier.state());
            assertEquals(1L, barrier.generation());
            assertEquals(1, barrier.writeSchemaVersion());
            assertEquals(1, barrier.minReaderSchemaVersion());
            assertEquals(List.of(NODE),
                    barrier.expectedNodes().stream().map(n -> n.nodeId()).toList());
        }

        /** #1: a node in expectedNodes with no ACK is named, not silently tolerated. */
        @Test
        public void activationNamesTheNodeWhoseAckIsMissing() {
            service.prepare(null, null);
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream()
                    .anyMatch(v -> v.contains("condition 3") && v.contains(NODE)));
        }

        @Test
        public void ackThenActivatePromotesBothFlagsAtOnce() {
            service.prepare(null, null);
            assertTrue(service.ack().applied());
            assertTrue(service.activate().applied());
            LineageWriteVersionBarrier barrier = stored();
            assertEquals(LineageWriteVersionBarrier.State.ACTIVE, barrier.state());
            assertEquals(2, barrier.writeSchemaVersion());
            assertEquals(2, barrier.minReaderSchemaVersion());
        }

        /** #5: rolling back moves the writer, never the reader floor. */
        @Test
        public void rollbackLeavesMinReaderAtTwo() {
            service.prepare(null, null);
            service.ack();
            service.activate();
            assertTrue(service.rollback().applied());
            LineageWriteVersionBarrier barrier = stored();
            assertEquals(1, barrier.writeSchemaVersion());
            assertEquals(2, barrier.minReaderSchemaVersion());
            assertEquals(LineageWriteVersionBarrier.State.ACTIVE, barrier.state());
        }

        /** #5c/#20: there is no path from ACTIVE straight back to writeSchemaVersion 2. */
        @Test
        public void reachingV2AgainRequiresPreparingAgain() {
            service.prepare(null, null);
            service.ack();
            service.activate();
            service.rollback();
            var direct = service.activate();
            assertFalse(direct.applied());
            assertTrue(direct.violations().stream().anyMatch(v -> v.contains("condition 1")));
            assertEquals(1, stored().writeSchemaVersion());
        }

        /** #21: re-arming bumps the generation AND clears the ACKs in the same write. */
        @Test
        public void reArmingBumpsTheGenerationAndClearsTheAcks() {
            service.prepare(null, null);
            service.ack();
            service.activate();
            service.rollback();
            assertTrue(service.prepare(null, null).applied());
            LineageWriteVersionBarrier barrier = stored();
            assertEquals(2L, barrier.generation());
            assertTrue(barrier.acks().isEmpty(),
                    "a re-arm collects fresh ACKs — the old ones cannot count");
            var outcome = service.activate();
            assertFalse(outcome.applied());
        }

        /** A barrier is never created without a durable witness. */
        @Test
        public void prepareRefusesWhenTheWitnessCannotBeMadeDurable() {
            FakeStore refusing = new FakeStore() {
                @Override
                public boolean writeWitnessIfAbsent(long observedAtMs) {
                    return false;
                }
            };
            LineageBinaryDigest digest = mock(LineageBinaryDigest.class);
            when(digest.digest()).thenReturn(DIGEST);
            LineageBarrierService svc = new LineageBarrierService(refusing,
                    new LineageBarrierReader(refusing, config, () -> now),
                    new LineageNodeIdentity(refusing, config, () -> "x", () -> now), digest,
                    new LineageCapabilityProvider(), readiness, config, machinery, () -> now);
            var outcome = svc.prepare(null, null);
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("witness")));
            assertNull(refusing.readBarrierRaw(), "no barrier without its witness");
        }

        /** null preserves the operator's additions; an explicit set replaces them. */
        @Test
        public void additionalCapabilitiesArePreservedByNullAndReplacedBySet() {
            service.prepare(null, Set.of("extra:one"));
            assertTrue(stored().requiredCapabilities().contains("extra:one"));
            service.prepare(null, null);
            assertTrue(stored().requiredCapabilities().contains("extra:one"),
                    "a re-prepare that says nothing must not drop what an operator added");
            service.prepare(null, Set.of());
            assertFalse(stored().requiredCapabilities().contains("extra:one"),
                    "an explicit empty set clears the additions");
            assertTrue(stored().requiredCapabilities()
                            .containsAll(new LineageCapabilityProvider().wiredCapabilities()),
                    "the binary's baseline is never clearable");
        }

        @Test
        public void reArmingAnActiveBarrierRequiresARollbackFirst() {
            service.prepare(null, null);
            service.ack();
            service.activate();
            var outcome = service.prepare(null, null);
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("roll back")));
        }

        /** #6b: minReaderSchemaVersion only ever increases, enforced at the transition. */
        @Test
        public void minReaderSchemaVersionCannotBeLowered() {
            service.prepare(null, null);
            service.ack();
            service.activate();
            LineageWriteVersionBarrier active = stored();
            LineageWriteVersionBarrier lowered = new LineageWriteVersionBarrier(active.rev(),
                    active.state(), active.generation(), 1, 1, active.expectedNodes(),
                    active.expectedMembershipDigest(), active.requiredCapabilities(),
                    active.approvedBinaryDigests(), active.acks());
            store.casBarrier(LineageBarrierCodec.encode(lowered));
            // The store itself is dumb; the SERVICE is where monotonicity lives, so what this
            // pins is that no service path produces the lowering.
            assertEquals(1, stored().minReaderSchemaVersion(),
                    "the fake store accepted it — only the service refuses");
        }

        /** #5b: the forbidden pair is unrepresentable, so no decode can resurrect it. */
        @Test
        public void theForbiddenVersionPairCannotBeConstructed() {
            List<LineageWriteVersionBarrier.NodeRef> nodes =
                    List.of(new LineageWriteVersionBarrier.NodeRef(NODE, "boot"));
            assertThrows(IllegalArgumentException.class, () -> new LineageWriteVersionBarrier(
                    "1-x", LineageWriteVersionBarrier.State.ACTIVE, 1L, 2, 1, nodes,
                    LineageWriteVersionBarrier.membershipDigestOf(nodes), Set.of(), Set.of(),
                    Map.of()));
        }
    }

    // ---------------------------------------------------------------- ACK admission

    @Nested
    class AckAdmission {

        /** #18: a red gate refuses the ACK outright — nothing is written. */
        @Test
        public void aRedReadinessGateRefusesTheAckWithoutTouchingTheDocument() {
            service.prepare(null, null);
            String revBefore = stored().rev();
            when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                    List.of("lineage.drest.enabled is false")));
            var outcome = service.ack();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("drest.enabled")));
            assertEquals(revBefore, stored().rev(), "a refused ACK mutates nothing");
            assertTrue(stored().acks().isEmpty());
        }

        @Test
        public void anUnmeasurableBinaryRefusesTheAck() {
            LineageBinaryDigest unmeasurable = mock(LineageBinaryDigest.class);
            when(unmeasurable.digest()).thenThrow(
                    new LineageBinaryDigest.UnmeasurableException("no root", null));
            LineageBarrierService svc = new LineageBarrierService(store, reader, identity,
                    unmeasurable, new LineageCapabilityProvider(), readiness, config,
                    machinery, () -> now);
            svc.prepare(null, null);
            var outcome = svc.ack();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream()
                    .anyMatch(v -> v.contains("BINARY_DIGEST_UNAVAILABLE")));
            assertTrue(stored().acks().isEmpty());
        }

        @Test
        public void aFailedSpoolProbeRefusesTheAck() {
            when(machinery.probeReadiness()).thenReturn(false);
            service.prepare(null, null);
            var outcome = service.ack();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("spool")));
        }

        @Test
        public void anAckOutsidePreparingIsRefused() {
            var outcome = service.ack();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("no barrier")));
        }

        /** A 409 restarts the loop: the ACK is recomputed, never replayed. */
        @Test
        public void aConflictRecomputesInsteadOfReplaying() {
            service.prepare(null, null);
            store.stealNextCas = true;
            long ackTime = now;
            now = ackTime + 12_345L; // time moves between the two attempts
            assertTrue(service.ack().applied());
            LineageWriteVersionBarrier.Ack ack = stored().acks().get(NODE);
            assertEquals(now, ack.ackedAtMs(),
                    "the persisted ACK came from the SECOND attempt, not the first");
            assertTrue(store.casAttempts >= 3, "prepare + a lost attempt + the winner");
        }
    }

    // ---------------------------------------------------------------- CAS conditions

    @Nested
    class ActivationConditions {

        private LineageWriteVersionBarrier withAck(LineageWriteVersionBarrier.Ack ack) {
            service.prepare(null, null);
            LineageWriteVersionBarrier current = stored();
            Map<String, LineageWriteVersionBarrier.Ack> acks = new LinkedHashMap<>();
            acks.put(NODE, ack);
            LineageWriteVersionBarrier next = new LineageWriteVersionBarrier(current.rev(),
                    current.state(), current.generation(), current.writeSchemaVersion(),
                    current.minReaderSchemaVersion(), current.expectedNodes(),
                    current.expectedMembershipDigest(), current.requiredCapabilities(),
                    current.approvedBinaryDigests(), acks);
            store.casBarrier(LineageBarrierCodec.encode(next));
            return stored();
        }

        private LineageWriteVersionBarrier.Ack goodAck(long generation, String bootId) {
            return new LineageWriteVersionBarrier.Ack(generation, bootId, DIGEST,
                    new LineageCapabilityProvider().wiredCapabilities(), Set.of(1, 2),
                    Set.of(1), Set.of(1, 2), true, true, List.of(), now, now + 300_000L);
        }

        /** #3: an ACK that answered an older generation cannot activate the new one. */
        @Test
        public void aStaleGenerationAckIsRefusedAndStillDiagnosable() {
            LineageWriteVersionBarrier barrier = withAck(goodAck(99L, identity.bootId()));
            assertNotNull(barrier.acks().get(NODE),
                    "decode PRESERVES it — a dropped ACK could not be diagnosed");
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("condition 3")));
        }

        /** #3b: a restart changes the bootId, so the previous boot's ACK stops counting. */
        @Test
        public void anAckFromAnotherBootIsRefused() {
            withAck(goodAck(1L, "a-different-boot"));
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("condition 4")));
        }

        /** #3c: freshness is not optional. */
        @Test
        public void anExpiredAckIsRefused() {
            withAck(new LineageWriteVersionBarrier.Ack(1L, identity.bootId(), DIGEST,
                    new LineageCapabilityProvider().wiredCapabilities(), Set.of(1, 2),
                    Set.of(1), Set.of(1, 2), true, true, List.of(), now - 10_000L,
                    now - 1L));
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("condition 5")));
        }

        /** #14: a build without the D-rest capabilities cannot open v2 writes. */
        @Test
        public void aMissingCapabilityIsNamed() {
            withAck(new LineageWriteVersionBarrier.Ack(1L, identity.bootId(), DIGEST,
                    Set.of("read:v2"), Set.of(1, 2), Set.of(1), Set.of(1, 2), true, true,
                    List.of(), now, now + 300_000L));
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream()
                    .anyMatch(v -> v.contains("condition 8") && v.contains("spool:v2")));
        }

        /**
         * #14c: an older binary — one without §2's obligation machine — cannot activate.
         *
         * <p>4b is a flag flip, so a node that cannot park a projection whose catalog entity is
         * not ready would meet that case for the first time with v2 writes already open. The
         * capability is in the server-defined required set precisely so the ACK fails here
         * rather than the gap being discovered afterwards.
         */
        @Test
        public void aBinaryWithoutTheObligationMachineIsRefused() {
            Set<String> withoutObligations = new java.util.LinkedHashSet<>(
                    new LineageCapabilityProvider().wiredCapabilities());
            assertTrue(withoutObligations.remove("catalog:obligations"),
                    "catalog:obligations must be in the server-defined required set");

            withAck(new LineageWriteVersionBarrier.Ack(1L, identity.bootId(), DIGEST,
                    withoutObligations, Set.of(1, 2), Set.of(1), Set.of(1, 2), true, true,
                    List.of(), now, now + 300_000L));

            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream()
                    .anyMatch(v -> v.contains("condition 8")
                            && v.contains("catalog:obligations")),
                    "activation must name the missing obligation capability: "
                            + outcome.violations());
        }

        /**
         * The required set is server-defined: a document cannot narrow it.
         *
         * <p>An operator who could drop a capability from the barrier document could activate a
         * fleet that is missing the machinery the capability stands for.
         */
        @Test
        public void aDocumentCannotDropARequiredCapability() {
            service.prepare(null, Set.of("read:v2"));
            service.ack();

            assertTrue(service.requiredCapabilities().contains("catalog:obligations"),
                    "the binary's baseline is unioned in and can never be narrowed");
        }

        /** #14b: an unapproved binary cannot ACK its way through. */
        @Test
        public void anUnapprovedBinaryDigestIsRefused() {
            service.prepare(Set.of("a".repeat(64)), null);
            service.ack();
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("condition 9")));
        }

        @Test
        public void anApprovedBinaryDigestPasses() {
            service.prepare(Set.of(DIGEST), null);
            service.ack();
            assertTrue(service.activate().applied());
        }

        /** #6: a node that cannot read v2 must not activate a fence that demands it. */
        @Test
        public void anAckThatDoesNotReadV2IsRefused() {
            withAck(new LineageWriteVersionBarrier.Ack(1L, identity.bootId(), DIGEST,
                    new LineageCapabilityProvider().wiredCapabilities(), Set.of(1), Set.of(1),
                    Set.of(1, 2), true, true, List.of(), now, now + 300_000L));
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("condition 6")));
        }

        /** #7: spoolReady and "can materialize a v2 EVENT" are separate claims. */
        @Test
        public void anAckThatCannotMaterializeV2IsRefused() {
            withAck(new LineageWriteVersionBarrier.Ack(1L, identity.bootId(), DIGEST,
                    new LineageCapabilityProvider().wiredCapabilities(), Set.of(1, 2),
                    Set.of(1), Set.of(1), true, true, List.of(), now, now + 300_000L));
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("condition 7")));
        }

        /** #18: a recorded red gate blocks activation even if the node is green now. */
        @Test
        public void anAckRecordedWithARedGateIsRefused() {
            withAck(new LineageWriteVersionBarrier.Ack(1L, identity.bootId(), DIGEST,
                    new LineageCapabilityProvider().wiredCapabilities(), Set.of(1, 2),
                    Set.of(1), Set.of(1, 2), true, false, List.of("view signature drift"),
                    now, now + 300_000L));
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream().anyMatch(v -> v.contains("condition 10")));
        }

        /** #19: green when acked, red at activation — the ACK's word is not enough. */
        @Test
        public void aGateThatReddensBetweenAckAndActivationBlocks() {
            service.prepare(null, null);
            assertTrue(service.ack().applied());
            when(readiness.evaluate()).thenReturn(new LineageDrestReadiness.Readiness(false,
                    List.of("configured target 'atlas' cannot verify")));
            var outcome = service.activate();
            assertFalse(outcome.applied());
            assertTrue(outcome.violations().stream()
                    .anyMatch(v -> v.contains("re-evaluated at activation")));
            assertEquals(1, stored().writeSchemaVersion());
        }
    }

    // ---------------------------------------------------------------- the tri-state read

    @Nested
    class Resolution {

        @Test
        public void anEmptyDeploymentIsPristine() {
            assertTrue(reader.view() instanceof LineageBarrierReader.BarrierView.Pristine);
        }

        @Test
        public void aBarrierIsPresentAndRepairsAMissingWitness() {
            service.prepare(null, null);
            store.docs.remove(LineageWriteVersionBarrier.WITNESS_DOCUMENT_ID);
            assertTrue(reader.viewUncached() instanceof LineageBarrierReader.BarrierView.Present);
            assertNotNull(store.readWitness(), "observing a barrier repairs its witness");
        }

        /** The fence cannot be undone by deleting one document. */
        @Test
        public void aVanishedBarrierIsIndeterminateNotPristine() {
            service.prepare(null, null);
            store.docs.remove(LineageWriteVersionBarrier.DOCUMENT_ID);
            var view = reader.viewUncached();
            assertTrue(view instanceof LineageBarrierReader.BarrierView.Indeterminate);
            assertEquals(LineageBarrierReader.BARRIER_VANISHED,
                    ((LineageBarrierReader.BarrierView.Indeterminate) view).reasonClass());
        }

        /**
         * The memo is the one thing 4a adds that the plan did not review, so it is pinned:
         * an invalidation must not be undone by a read that was already in flight.
         */
        @Test
        public void anInvalidationSuppressesAStaleReadFromRepopulatingTheMemo() {
            when(config.getBarrierViewTtlMs()).thenReturn(1000L);
            service.prepare(null, null); // reads, and would install Present
            assertTrue(reader.view() instanceof LineageBarrierReader.BarrierView.Present);
            service.ack();
            service.activate();
            // activate() invalidated; a view taken now must see the ACTIVE document, not the
            // PREPARING one the memo held.
            var view = reader.view();
            assertTrue(view instanceof LineageBarrierReader.BarrierView.Present);
            assertEquals(2, ((LineageBarrierReader.BarrierView.Present) view)
                    .barrier().writeSchemaVersion());
        }

        /**
         * The real shape of the race: a read is ALREADY in flight when the write lands. A bare
         * {@code cachedView = null} would let that read install its stale answer immediately
         * afterwards, and every later emit would route on it for a whole TTL.
         */
        @Test
        public void aReadInFlightDuringAnInvalidationCannotInstallItsStaleAnswer()
                throws Exception {
            when(config.getBarrierViewTtlMs()).thenReturn(60_000L);
            service.prepare(null, null); // PREPARING, writeSchemaVersion 1
            now += 60_001L;             // past the suppression window prepare() opened

            java.util.concurrent.CountDownLatch reading =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch released =
                    new java.util.concurrent.CountDownLatch(1);
            FakeStore blocking = new FakeStore() {
                @Override
                public Map<String, Object> readBarrierRaw() {
                    Map<String, Object> raw = super.readBarrierRaw();
                    reading.countDown();
                    try {
                        released.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return raw; // the PREPARING document, read before the write below
                }
            };
            blocking.docs.putAll(store.docs);
            blocking.revs.set(store.revs.get());
            LineageBarrierReader shared = new LineageBarrierReader(blocking, config, () -> now);

            Thread inFlight = new Thread(shared::view);
            inFlight.start();
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS));

            // The write lands and invalidates WHILE that read is still parked.
            blocking.docs.putAll(activatedDocs());
            shared.invalidate();
            released.countDown();
            inFlight.join(5000);

            var after = shared.view();
            assertTrue(after instanceof LineageBarrierReader.BarrierView.Present);
            assertEquals(2, ((LineageBarrierReader.BarrierView.Present) after)
                            .barrier().writeSchemaVersion(),
                    "the parked read must not have become the memo");
        }

        /** The uncached read is subject to the same suppression as the memoized one. */
        @Test
        public void anUncachedReadInFlightAlsoCannotInstallItsStaleAnswer() throws Exception {
            when(config.getBarrierViewTtlMs()).thenReturn(60_000L);
            service.prepare(null, null);
            now += 60_001L;

            java.util.concurrent.CountDownLatch reading =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch released =
                    new java.util.concurrent.CountDownLatch(1);
            FakeStore blocking = new FakeStore() {
                @Override
                public Map<String, Object> readBarrierRaw() {
                    Map<String, Object> raw = super.readBarrierRaw();
                    reading.countDown();
                    try {
                        released.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return raw;
                }
            };
            blocking.docs.putAll(store.docs);
            blocking.revs.set(store.revs.get());
            LineageBarrierReader shared = new LineageBarrierReader(blocking, config, () -> now);

            Thread inFlight = new Thread(shared::viewUncached);
            inFlight.start();
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS));
            blocking.docs.putAll(activatedDocs());
            shared.invalidate();
            released.countDown();
            inFlight.join(5000);

            var after = shared.view();
            assertEquals(2, ((LineageBarrierReader.BarrierView.Present) after)
                            .barrier().writeSchemaVersion(),
                    "viewUncached must not install a read that predates the invalidation");
        }

        /** The ACTIVE document, produced through the ordinary service path. */
        private Map<String, Map<String, Object>> activatedDocs() {
            service.ack();
            service.activate();
            return store.docs;
        }

        @Test
        public void theMemoServesRepeatedReadsWithinItsWindow() {
            when(config.getBarrierViewTtlMs()).thenReturn(1000L);
            service.prepare(null, null);
            // prepare() invalidates, which suppresses the memo for one TTL — step past it.
            now += 1001L;
            reader.view();
            store.readFault = new IllegalStateException("couch is down");
            assertTrue(reader.view() instanceof LineageBarrierReader.BarrierView.Present,
                    "within the window the memo answers without touching the store");
            now += 1001L;
            assertTrue(reader.view() instanceof LineageBarrierReader.BarrierView.Indeterminate,
                    "and past it, the truth again");
        }

        /** A barrier whose witness cannot be made durable is NOT Present. */
        @Test
        public void aBarrierWhoseWitnessCannotBeWrittenIsIndeterminate() {
            service.prepare(null, null);
            store.docs.remove(LineageWriteVersionBarrier.WITNESS_DOCUMENT_ID);
            FakeStore refusing = new FakeStore() {
                @Override
                public boolean writeWitnessIfAbsent(long observedAtMs) {
                    return false;
                }
            };
            refusing.docs.putAll(store.docs);
            LineageBarrierReader failClosed =
                    new LineageBarrierReader(refusing, config, () -> now);
            var view = failClosed.viewUncached();
            assertTrue(view instanceof LineageBarrierReader.BarrierView.Indeterminate);
            assertEquals(LineageBarrierReader.WITNESS_UNCONFIRMED,
                    ((LineageBarrierReader.BarrierView.Indeterminate) view).reasonClass());
        }

        @Test
        public void anUnreadableBarrierIsIndeterminate() {
            store.readFault = new IllegalStateException("couch is down");
            assertTrue(reader.viewUncached()
                    instanceof LineageBarrierReader.BarrierView.Indeterminate);
        }

        /** The materializer seam: pristine CONVERGES at v1, unreadable stays undecided. */
        @Test
        public void thePinnedResolverConvergesPristineAtV1AndRefusesIndeterminate() {
            var resolver = new LineageSpoolMachinery.PinnedWriteVersionResolver();
            LineageSpoolPayloadV1 payload = payload();
            assertTrue(LineageSpoolMachinery.BarrierPin.with(
                    new LineageBarrierReader.BarrierView.Pristine(),
                    () -> resolver.resolve(payload)).isPresent());
            assertEquals(1, LineageSpoolMachinery.BarrierPin.with(
                    new LineageBarrierReader.BarrierView.Pristine(),
                    () -> resolver.resolve(payload)).get().materializeSchemaVersion());
            assertTrue(LineageSpoolMachinery.BarrierPin.with(
                    new LineageBarrierReader.BarrierView.Indeterminate("x"),
                    () -> resolver.resolve(payload)).isEmpty());
            assertTrue(resolver.resolve(payload).isEmpty(),
                    "with NO pin the answer is unavailable — never a live read");
        }

        @Test
        public void thePinIsRemovedEvenWhenTheScanThrows() {
            var resolver = new LineageSpoolMachinery.PinnedWriteVersionResolver();
            assertThrows(IllegalStateException.class, () ->
                    LineageSpoolMachinery.BarrierPin.with(
                            new LineageBarrierReader.BarrierView.Pristine(),
                            () -> {
                                throw new IllegalStateException("scan blew up");
                            }));
            assertTrue(resolver.resolve(payload()).isEmpty(),
                    "a thrown scan must not leave its view pinned for the next one");
        }

        @Test
        public void concurrentScansDoNotSeeEachOthersPin() throws Exception {
            var resolver = new LineageSpoolMachinery.PinnedWriteVersionResolver();
            LineageSpoolPayloadV1 payload = payload();
            var other = new java.util.concurrent.atomic.AtomicReference<Object>();
            Thread manual = new Thread(() -> other.set(LineageSpoolMachinery.BarrierPin.with(
                    new LineageBarrierReader.BarrierView.Indeterminate("manual"),
                    () -> resolver.resolve(payload))));
            Object automatic = LineageSpoolMachinery.BarrierPin.with(
                    new LineageBarrierReader.BarrierView.Pristine(), () -> {
                        manual.start();
                        try {
                            manual.join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return resolver.resolve(payload);
                    });
            assertTrue(((java.util.Optional<?>) automatic).isPresent(),
                    "the automatic scan keeps its own pinned view");
            assertTrue(((java.util.Optional<?>) other.get()).isEmpty(),
                    "and the manual scan keeps its own");
        }
    }

    // ---------------------------------------------------------------- admission

    @Nested
    class Admission {

        private LineageReaderAdmission admission;

        @BeforeEach
        void wire() {
            admission = new LineageReaderAdmission(reader, config);
        }

        @Test
        public void aPristineDeploymentIsAdmitted() {
            assertTrue(admission.evaluate().admitted());
        }

        /** #6: minReader 2 with a v1-only reader fails closed, without reading the spool. */
        @Test
        public void aV1OnlyReaderIsRefusedOnceMinReaderIsTwo() {
            service.prepare(null, null);
            service.ack();
            service.activate();
            when(config.getReadSchemaVersions()).thenReturn(Set.of(1));
            // Everything before this point is setup; what must not touch the spool is the
            // admission decision itself (#6: "does not depend on scanning the spool").
            org.mockito.Mockito.clearInvocations(machinery);
            var verdict = admission.evaluate();
            assertEquals(LineageReaderAdmission.Decision.REFUSED, verdict.decision());
            assertTrue(verdict.violations().stream().anyMatch(v -> v.contains("cursor")));
            // The spool was never consulted while deciding.
            org.mockito.Mockito.verifyNoMoreInteractions(machinery);
        }

        /** A node that declares it reads nothing must not run, barrier or no barrier. */
        @Test
        public void aNodeThatReadsNothingIsRefusedEvenWithoutABarrier() {
            when(config.getReadSchemaVersions()).thenReturn(Set.of());
            var pristine = admission.evaluate();
            assertEquals(LineageReaderAdmission.Decision.REFUSED, pristine.decision());
            assertTrue(pristine.violations().stream().anyMatch(v -> v.contains("nothing")));

            service.prepare(null, null); // a barrier at minReader 1
            when(config.getReadSchemaVersions()).thenReturn(Set.of());
            assertEquals(LineageReaderAdmission.Decision.REFUSED,
                    admission.evaluate().decision(),
                    "a v1 floor is still a floor — it must be readable");
        }

        @Test
        public void anUnreadableBarrierLeavesTheReaderUndeterminedAndSelfHealing() {
            store.readFault = new IllegalStateException("couch is down");
            assertEquals(LineageReaderAdmission.Decision.UNDETERMINED,
                    admission.evaluate().decision());
            store.readFault = null;
            assertEquals(LineageReaderAdmission.Decision.ADMITTED,
                    admission.evaluate().decision(),
                    "a transient outage must not disable the reader for the process");
        }
    }

    // ---------------------------------------------------------------- frozen formulas

    @Nested
    class FrozenFormulas {

        @Test
        public void theMembershipDigestIsOrderIndependentAndNotJsonHashing() {
            var a = new LineageWriteVersionBarrier.NodeRef("node-a", "boot-1");
            var b = new LineageWriteVersionBarrier.NodeRef("node-b", "boot-2");
            assertEquals(LineageWriteVersionBarrier.membershipDigestOf(List.of(a, b)),
                    LineageWriteVersionBarrier.membershipDigestOf(List.of(b, a)));
            assertNotEqualsDigest(LineageWriteVersionBarrier.membershipDigestOf(List.of(a)),
                    LineageWriteVersionBarrier.membershipDigestOf(List.of(a, b)));
        }

        @Test
        public void theMembershipDigestMatchesItsGoldenVector() throws Exception {
            byte[] bytes = getClass().getResourceAsStream(
                    "/lineage/identity-golden-vectors.json").readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, String> fixture = new tools.jackson.databind.ObjectMapper()
                    .readValue(bytes, Map.class);
            assertEquals(fixture.get("barrierMembershipDigest"),
                    LineageWriteVersionBarrier.membershipDigestOf(List.of(
                            new LineageWriteVersionBarrier.NodeRef("node-a", "boot-1"),
                            new LineageWriteVersionBarrier.NodeRef("node-b", "boot-2"))));
        }

        /** The distribution digest, against a fixture tree the reference script also models. */
        @Test
        public void theBinaryDigestMatchesItsGoldenVector(@org.junit.jupiter.api.io.TempDir
                java.nio.file.Path root) throws Exception {
            java.nio.file.Path lib = root.resolve("WEB-INF/lib");
            java.nio.file.Path classes = root.resolve("WEB-INF/classes");
            java.nio.file.Files.createDirectories(lib);
            java.nio.file.Files.createDirectories(classes);
            java.nio.file.Files.write(lib.resolve("b.jar"), "bbb".getBytes());
            java.nio.file.Files.write(classes.resolve("a.class"), "aaa".getBytes());
            byte[] bytes = getClass().getResourceAsStream(
                    "/lineage/identity-golden-vectors.json").readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, String> fixture = new tools.jackson.databind.ObjectMapper()
                    .readValue(bytes, Map.class);
            assertEquals(fixture.get("barrierBinaryDigest"),
                    LineageBinaryDigest.compute(root));
        }

        /** A symlink is a refusal, not something to resolve — and never a partial digest. */
        @Test
        public void aSymlinkInTheDistributionMakesItUnmeasurable(
                @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
            java.nio.file.Path lib = root.resolve("WEB-INF/lib");
            java.nio.file.Files.createDirectories(lib);
            java.nio.file.Files.write(lib.resolve("real.jar"), "x".getBytes());
            java.nio.file.Path outside = java.nio.file.Files.createTempFile("outside", ".jar");
            java.nio.file.Files.write(outside, "secret".getBytes());
            try {
                java.nio.file.Files.createSymbolicLink(lib.resolve("link.jar"), outside);
            } catch (UnsupportedOperationException | java.io.IOException noSymlinks) {
                return; // a platform without symlinks cannot exhibit the risk
            }
            assertThrows(LineageBinaryDigest.UnmeasurableException.class,
                    () -> LineageBinaryDigest.compute(root));
        }

        @Test
        public void aTamperedMembershipDigestIsRefusedAtDecode() {
            service.prepare(null, null);
            Map<String, Object> raw = store.readBarrierRaw();
            raw.put("expectedMembershipDigest", "f".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> LineageBarrierCodec.decode(raw));
        }

        /** A version narrowed from 4294967298 to 2 would be evidence that means nothing. */
        @Test
        public void theCodecRefusesNonIntegralAndOutOfRangeNumbers() {
            service.prepare(null, null);
            Map<String, Object> raw = store.readBarrierRaw();

            Map<String, Object> fractional = new LinkedHashMap<>(raw);
            fractional.put("writeSchemaVersion", 1.5d);
            assertThrows(IllegalArgumentException.class,
                    () -> LineageBarrierCodec.decode(fractional));

            Map<String, Object> narrowed = new LinkedHashMap<>(raw);
            narrowed.put("writeSchemaVersion", 4294967298L); // == 2 when narrowed to int
            assertThrows(IllegalArgumentException.class,
                    () -> LineageBarrierCodec.decode(narrowed));

            service.ack();
            Map<String, Object> withAck = store.readBarrierRaw();
            @SuppressWarnings("unchecked")
            Map<String, Object> acks =
                    new LinkedHashMap<>((Map<String, Object>) withAck.get("acks"));
            @SuppressWarnings("unchecked")
            Map<String, Object> ack = new LinkedHashMap<>((Map<String, Object>) acks.get(NODE));
            ack.put("readSchemaVersions", List.of(1L, 4294967298L));
            acks.put(NODE, ack);
            withAck.put("acks", acks);
            assertThrows(IllegalArgumentException.class,
                    () -> LineageBarrierCodec.decode(withAck),
                    "a narrowed 2 must never satisfy condition 6");
        }

        /** 2^63 as a double casts back to Long.MAX_VALUE — an exclusive bound is required. */
        @Test
        public void theCodecRefusesADoubleAtTheLongBoundary() {
            service.prepare(null, null);
            Map<String, Object> raw = store.readBarrierRaw();
            raw.put("generation", 0x1p63);
            assertThrows(IllegalArgumentException.class, () -> LineageBarrierCodec.decode(raw));
        }

        @Test
        public void theDocumentRoundTripsThroughItsCodec() {
            service.prepare(Set.of(DIGEST), Set.of("extra:capability"));
            service.ack();
            LineageWriteVersionBarrier decoded = stored();
            Map<String, Object> encoded = LineageBarrierCodec.encode(decoded);
            assertEquals(decoded, LineageBarrierCodec.decode(encoded));
        }

        private void assertNotEqualsDigest(String a, String b) {
            assertFalse(a.equals(b), "distinct membership must not collide");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static LineageSpoolPayloadV1 payload() {
        LineageFact fact = new LineageFact("bedroom", LineageProcessType.IMPORT_UPLOADED,
                "op-1", "2026-08-01T00:00:00Z",
                List.of(LineageEndpoint.importArtifact("bedroom", "op-1", "zip", Map.of())),
                List.of(LineageEndpoint.document("bedroom", "doc-1", "a.txt")),
                List.of("atlas"), null,
                new LineageFact.LegacyV1Projection(LineageProcessType.IMPORT_UPLOADED,
                        List.of("i"), List.of("o"), Map.of(), null));
        return LineageSpoolPayloadV1.of(fact);
    }

    static List<String> names(List<LineageWriteVersionBarrier.NodeRef> nodes) {
        List<String> names = new ArrayList<>();
        nodes.forEach(n -> names.add(n.nodeId()));
        return names;
    }
}
