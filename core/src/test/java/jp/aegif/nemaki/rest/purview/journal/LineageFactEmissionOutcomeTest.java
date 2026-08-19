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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six outcomes {@code emitSafely} collapses into one {@code false}, told apart.
 *
 * <p>Three of them are failures — the activity check threw, the fact could not be built,
 * {@code emit()} threw — and three are not: lineage is off, there is no emitter, the fact was
 * null. A caller that only sees {@code false} cannot distinguish a deployment that chose not to
 * record lineage from one that just lost evidence for a document it has already committed.
 *
 * <p>These tests exercise the real seam. An earlier attempt at this (PR #506) tested against an
 * unwired emitter that threw during argument validation — <em>before</em> the fail-open boundary
 * — so it passed while the actual failure paths stayed invisible. Each test here drives a fake
 * emitter through one specific branch inside the boundary.
 */
class LineageFactEmissionOutcomeTest {

    /** Minimal emitter whose behaviour each test chooses. */
    private static final class FakeEmitter implements LineageEmitter {
        private final boolean active;
        private final RuntimeException activeThrows;
        private final RuntimeException emitThrows;
        private int emitCount;

        FakeEmitter(boolean active, RuntimeException activeThrows, RuntimeException emitThrows) {
            this.active = active;
            this.activeThrows = activeThrows;
            this.emitThrows = emitThrows;
        }

        @Override
        public boolean isActive() {
            if (activeThrows != null) {
                throw activeThrows;
            }
            return active;
        }

        @Override
        public void emit(LineageEvent event) {
            // The legacy v1 entry point; the fact-based overload below is what this class uses.
            throw new UnsupportedOperationException("not used by emitReporting");
        }

        @Override
        public void emit(LineageFact fact) {
            emitCount++;
            if (emitThrows != null) {
                throw emitThrows;
            }
        }
    }

    private static LineageFact anyFact() {
        // The outcome logic never inspects the fact, so a null-returning supplier and a
        // non-null one are the only two shapes that matter; this stands in for "non-null".
        return org.mockito.Mockito.mock(LineageFact.class);
    }

    @Test
    @DisplayName("emit() throwing is a FAILURE, not silence")
    void emitThrowIsAFailure() {
        FakeEmitter emitter = new FakeEmitter(true, null, new IllegalStateException("journal down"));

        LineageFactEmission.EmissionOutcome outcome =
                LineageFactEmission.emitReporting(emitter, LineageFactEmissionOutcomeTest::anyFact, "test");

        assertFalse(outcome.handedOff());
        assertTrue(outcome.failed(),
                "the document is already committed; losing its provenance is not silence");
        assertTrue(outcome.failureReason().startsWith("emit:"), outcome.failureReason());
        assertTrue(outcome.failureReason().contains("journal down"), outcome.failureReason());
        assertEquals(1, emitter.emitCount, "the branch under test must actually have been reached");
    }

    @Test
    @DisplayName("the activity check throwing is a FAILURE")
    void activityCheckThrowIsAFailure() {
        FakeEmitter emitter = new FakeEmitter(true, new IllegalStateException("config unreadable"), null);

        LineageFactEmission.EmissionOutcome outcome =
                LineageFactEmission.emitReporting(emitter, LineageFactEmissionOutcomeTest::anyFact, "test");

        assertTrue(outcome.failed());
        assertTrue(outcome.failureReason().startsWith("activity check:"), outcome.failureReason());
    }

    @Test
    @DisplayName("fact construction throwing is a FAILURE")
    void factConstructionThrowIsAFailure() {
        FakeEmitter emitter = new FakeEmitter(true, null, null);

        LineageFactEmission.EmissionOutcome outcome = LineageFactEmission.emitReporting(
                emitter, () -> { throw new IllegalArgumentException("sourceSystem must not be blank"); },
                "test");

        assertTrue(outcome.failed());
        assertTrue(outcome.failureReason().startsWith("fact construction:"), outcome.failureReason());
        assertEquals(0, emitter.emitCount, "nothing should have been handed to the emitter");
    }

    @Test
    @DisplayName("lineage switched off is NOT a failure")
    void inactiveIsNotAFailure() {
        LineageFactEmission.EmissionOutcome outcome = LineageFactEmission.emitReporting(
                new FakeEmitter(false, null, null), LineageFactEmissionOutcomeTest::anyFact, "test");

        assertFalse(outcome.handedOff());
        assertFalse(outcome.failed(),
                "warning on every import in a deployment that chose not to record lineage would "
                        + "train operators to ignore the warning that matters");
        assertNull(outcome.failureReason());
    }

    @Test
    @DisplayName("no emitter and a null fact are NOT failures either")
    void absentEmitterAndNullFactAreNotFailures() {
        assertFalse(LineageFactEmission.emitReporting(null, LineageFactEmissionOutcomeTest::anyFact, "t")
                .failed());
        assertFalse(LineageFactEmission.emitReporting(new FakeEmitter(true, null, null), () -> null, "t")
                .failed());
    }

    @Test
    @DisplayName("a successful emission reports success and no reason")
    void successHasNoReason() {
        LineageFactEmission.EmissionOutcome outcome = LineageFactEmission.emitReporting(
                new FakeEmitter(true, null, null), LineageFactEmissionOutcomeTest::anyFact, "test");

        assertTrue(outcome.handedOff());
        assertFalse(outcome.failed());
    }

    @Test
    @DisplayName("an emitter that SWALLOWS its own failure is still reported as a loss")
    void swallowedLossIsReported() {
        // The failure mode that actually happens in production: JournaledLineageEmitter catches
        // a journal write failure, dead-letters, and returns normally. Nothing throws, so an
        // implementation that only watches for exceptions reports success and hands back an
        // event id pointing at a journal row that does not exist (external review, P1-1).
        LineageEmitter swallowing = new LineageEmitter() {
            @Override public void emit(LineageEvent event) { }
            @Override public void emit(LineageFact fact) { }
            @Override public boolean isActive() { return true; }
            @Override public String emitReportingLoss(LineageFact fact) {
                return "dead-lettered: journal unreachable";
            }
        };

        LineageFactEmission.EmissionOutcome outcome = LineageFactEmission.emitReporting(
                swallowing, LineageFactEmissionOutcomeTest::anyFact, "test");

        assertFalse(outcome.handedOff(),
                "the fact was not recorded, so no event id may be reported for it");
        assertTrue(outcome.failed());
        assertTrue(outcome.failureReason().contains("dead-lettered"), outcome.failureReason());
    }

    @Test
    @DisplayName("a REAL JournaledLineageEmitter whose store throws reports the loss")
    void realJournaledEmitterReportsStoreFailure() {
        // The fake above proves only that LineageFactEmission consumes a loss string — delete
        // JournaledLineageEmitter's override and it still passes. This drives the real emitter
        // with a store that fails the way CouchDB does, which is the seam that matters
        // (external review, P1-1).
        LineageJournalStore failingStore = org.mockito.Mockito.mock(LineageJournalStore.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("couchdb unreachable"))
                .when(failingStore).append(org.mockito.ArgumentMatchers.any(LineageEvent.class));
        LineageConfig config = org.mockito.Mockito.mock(LineageConfig.class);

        JournaledLineageEmitter emitter = new JournaledLineageEmitter(failingStore, config);

        String loss = emitter.emitReportingLoss(realFact());

        assertNotNull(loss, "the journal has no row for this fact, so the caller must be told");
        assertTrue(loss.contains("dead-lettered") || loss.contains("dropped"), loss);
        assertTrue(loss.contains("couchdb unreachable"), loss);
    }

    @Test
    @DisplayName("plain emit() leaves no retained state for the next caller")
    void plainEmitRetainsNothing() {
        LineageJournalStore failingStore = org.mockito.Mockito.mock(LineageJournalStore.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("couchdb unreachable"))
                .when(failingStore).append(org.mockito.ArgumentMatchers.any(LineageEvent.class));
        JournaledLineageEmitter emitter = new JournaledLineageEmitter(
                failingStore, org.mockito.Mockito.mock(LineageConfig.class));

        // A caller that never asked for a loss report must not leave one parked on the thread.
        emitter.emit(realFact());

        LineageJournalStore okStore = org.mockito.Mockito.mock(LineageJournalStore.class);
        JournaledLineageEmitter healthy = new JournaledLineageEmitter(
                okStore, org.mockito.Mockito.mock(LineageConfig.class));
        assertNull(healthy.emitReportingLoss(realFact()),
                "a previous failure on this thread must not be reported against a later success");
    }

    /** A fact the real emitter can actually convert to a v1 event. */
    private static LineageFact realFact() {
        return new LineageFact("bedroom", LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD, "op-1",
                java.time.Instant.now().toString(),
                java.util.List.of(LineageEndpoint.externalAsset("bedroom", "src://x", "slack")),
                java.util.List.of(LineageEndpoint.document("bedroom", "obj-1", "doc.txt")),
                java.util.List.of(), null,
                new LineageFact.LegacyV1Projection(
                        LineageProcessType.FILE_SHARE_SYNC_DOWNLOAD,
                        java.util.List.of("src://x"), java.util.List.of("bedroom/obj-1"),
                        new java.util.LinkedHashMap<>(), java.util.UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("an emitter that reports no loss is still a success")
    void defaultLossReportingKeepsSuccess() {
        // The interface default delegates to emit() and reports nothing, so emitters with
        // nothing to add must not suddenly be treated as failing.
        LineageFactEmission.EmissionOutcome outcome = LineageFactEmission.emitReporting(
                new FakeEmitter(true, null, null), LineageFactEmissionOutcomeTest::anyFact, "test");
        assertTrue(outcome.handedOff());
        assertFalse(outcome.failed());
    }

    @Test
    @DisplayName("emitSafely keeps its old contract for the callers that still use it")
    void emitSafelyStillReturnsBoolean() {
        assertTrue(LineageFactEmission.emitSafely(
                new FakeEmitter(true, null, null), LineageFactEmissionOutcomeTest::anyFact, "t"));
        assertFalse(LineageFactEmission.emitSafely(
                new FakeEmitter(true, null, new IllegalStateException("x")),
                LineageFactEmissionOutcomeTest::anyFact, "t"));
        assertNotNull(LineageFactEmission.class);
    }
}
