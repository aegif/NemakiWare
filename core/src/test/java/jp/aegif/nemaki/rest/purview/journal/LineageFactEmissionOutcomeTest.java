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
