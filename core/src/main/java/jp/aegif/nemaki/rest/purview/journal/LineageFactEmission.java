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

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fail-open boundary for fact construction — the one place a producer touches lineage from a
 * business method.
 *
 * <h2>Why construction needs its own boundary</h2>
 *
 * <p>{@code emit()} already never throws, but {@link LineageFact} is <em>constructed</em> before
 * it is emitted, and construction is deliberately strict: typed endpoint factories validate
 * stable keys, the shape table rejects malformed connections, A-1's scope rules run. The old v1
 * builder accepted nearly anything, so producers grew up building events inside the same broad
 * {@code try} that owns the business response — with the new strictness, a lineage validation
 * bug there would report failure for an import that in fact succeeded. ECM availability takes
 * strict priority over lineage capture, including over lineage's own input validation.
 *
 * <p>So the supplier runs inside this boundary, and nothing escapes it. A construction failure
 * is logged with the operation coordinates — never rethrown, and there is nothing durable to
 * write yet (no event exists; the file dead-letter records events). The loss is bounded to the
 * one fact, and the log line names it.
 */
public final class LineageFactEmission {

    private static final Logger logger = LoggerFactory.getLogger(LineageFactEmission.class);

    private LineageFactEmission() {
    }

    /**
     * Builds the fact and emits it; never throws.
     *
     * @param emitter       the active emitter; null is treated as lineage-off
     * @param factSupplier  runs entirely inside the fail-open boundary
     * @param whatHappened  operation coordinates for the failure log (repository, operation id,
     *                      process type — never payload values)
     * @return whether the fact was handed to the emitter — {@code false} when lineage is off,
     *         construction failed, or the supplier returned null. Producers that report an
     *         event id to their caller use this to keep the old "id or null" contract: an id
     *         for an emission that never happened resolves to nothing. Handed-off is the
     *         honest boundary — {@code emit()} is itself fail-open (journal failures
     *         dead-letter and replay, preserving the id), so "accepted" cannot mean "durable".
     */
    public static boolean emitSafely(LineageEmitter emitter, Supplier<LineageFact> factSupplier,
                                     String whatHappened) {
        return emitReporting(emitter, factSupplier, whatHappened).handedOff();
    }

    /**
     * Why nothing was emitted, when nothing was.
     *
     * <p>{@link #emitSafely} collapses six different outcomes into one {@code false}: lineage is
     * switched off, the emitter could not be resolved, the activity check threw, the fact could
     * not be built, there was nothing to record, or {@code emit()} threw. Three of those are
     * failures and three are not, and a caller that only sees {@code false} cannot tell a
     * deliberate configuration from evidence it just lost.
     *
     * <p>That distinction is the whole point for ingest: the document is already committed by
     * the time this runs, so a swallowed failure leaves content stored with no provenance while
     * the import still reports success. {@code failureReason} is non-null only for the three
     * genuine failures — never for "lineage is off" — so a caller can warn without crying wolf
     * on every deployment that chose not to enable lineage.
     */
    public record EmissionOutcome(boolean handedOff, String failureReason) {
        public boolean failed() {
            return failureReason != null;
        }

        static EmissionOutcome ok() {
            return new EmissionOutcome(true, null);
        }

        /** Nothing to record, and nothing wrong: lineage off, no emitter, or a null fact. */
        static EmissionOutcome nothingToEmit() {
            return new EmissionOutcome(false, null);
        }

        static EmissionOutcome failure(String stage, RuntimeException e) {
            return new EmissionOutcome(false,
                    stage + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** {@link #emitSafely} with the reason preserved. Same fail-open behaviour, more truth. */
    public static EmissionOutcome emitReporting(LineageEmitter emitter,
                                                Supplier<LineageFact> factSupplier,
                                                String whatHappened) {
        if (emitter == null) {
            return EmissionOutcome.nothingToEmit();
        }
        try {
            // isActive() is an interface call into arbitrary emitter code, so it sits inside
            // the boundary like everything else — an activity check that throws must not be
            // able to fail the business response either.
            if (!emitter.isActive()) {
                return EmissionOutcome.nothingToEmit();
            }
        } catch (RuntimeException e) {
            logger.warn("Lineage activity check failed (business operation unaffected): {} — {}",
                    whatHappened, e.getMessage());
            return EmissionOutcome.failure("activity check", e);
        }
        LineageFact fact;
        try {
            fact = factSupplier.get();
        } catch (RuntimeException e) {
            logger.warn("Lineage fact could not be constructed (business operation unaffected):"
                    + " {} — {}", whatHappened, e.getMessage());
            return EmissionOutcome.failure("fact construction", e);
        }
        if (fact == null) {
            return EmissionOutcome.nothingToEmit();
        }
        try {
            emitter.emit(fact);
            return EmissionOutcome.ok();
        } catch (RuntimeException e) {
            // emit() contracts to never throw; this catch is the boundary keeping that contract
            // even against an implementation that breaks it.
            logger.warn("Lineage emission failed (business operation unaffected): {} — {}",
                    whatHappened, e.getMessage());
            return EmissionOutcome.failure("emit", e);
        }
    }
}
