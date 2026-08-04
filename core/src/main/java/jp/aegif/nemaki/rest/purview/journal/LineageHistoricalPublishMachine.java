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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writing a historical entity, in a way a crash cannot leave silently wrong (§2, N-1.5D).
 *
 * <h2>The rule the whole machine is shaped by</h2>
 *
 * <p><b>The durable intent is written before the external side effect.</b> Otherwise this
 * happens and nothing is left to notice it:
 *
 * <pre>
 *   historical entity published → process dies → source restored → restart
 *   → the catalog holds the entity, the source exists, everything looks consistent
 * </pre>
 *
 * <p>With the intent recorded first, that state is {@code PLANNED} with a written entity, which
 * the recovery scanner finds, reads back, and drives to either {@code RESOLVED} or a
 * compensation.
 *
 * <h2>The operation digest is computed before publishing</h2>
 *
 * <p>Not taken from the receipt afterwards. A digest that only exists after the write cannot be
 * in the intent that precedes it, and the intent is what a compensation names. The publisher's
 * receipt must then <em>match</em> the planned digest; a publisher that wrote something else has
 * not done what was recorded, and the difference is not something to accept silently.
 *
 * <h2>What is never terminal</h2>
 *
 * <p>An unknown source, a catalog timeout, a lost lease, a CAS loss, a missing waiting event.
 * All of them leave the intent where it is and increment an attempt. The only terminal
 * conclusions are {@code RESOLVED} (source still purged after the write) and
 * {@code SNAPSHOT_INCOMPLETE} on the obligation, which requires an authoritative purge and a
 * snapshot that structurally lacks a mandatory attribute.
 */
public class LineageHistoricalPublishMachine {

    private static final Logger logger =
            LoggerFactory.getLogger(LineageHistoricalPublishMachine.class);

    /** The payload shape this build writes. In the intent id, so a change is a new plan. */
    static final int PAYLOAD_SCHEMA_VERSION = 1;

    /** Domain for the pre-computed operation digest. */
    static final String OPERATION_DOMAIN = "LINEAGE_HISTORICAL_OPERATION_V1";

    static final Duration INTENT_LEASE = Duration.ofMinutes(5);

    private final LineageHistoricalPublishIntentStore intents;
    private final LineageHistoricalCompensationStore compensations;
    private final LineageHistoricalPublisherRegistry publishers;
    private final LineageSourceDispositionResolver sources;
    private final LineageCurrentEntityRepublisher republisher;
    private final LineageNodeIdentity identity;
    private final LongSupplier clockMs;

    public LineageHistoricalPublishMachine(LineageHistoricalPublishIntentStore intents,
            LineageHistoricalCompensationStore compensations,
            LineageHistoricalPublisherRegistry publishers,
            LineageSourceDispositionResolver sources,
            LineageCurrentEntityRepublisher republisher,
            LineageNodeIdentity identity, LongSupplier clockMs) {
        this.intents = intents;
        this.compensations = compensations;
        this.publishers = publishers;
        this.sources = sources;
        this.republisher = republisher;
        this.identity = identity;
        this.clockMs = clockMs;
    }

    /** What one drive of the machine concluded. */
    public enum Verdict {
        /** The source was still purged after the write. The obligation may resolve. */
        RESOLVED_PURGED,
        /** Nothing was established or the step could not complete. Try again later. */
        RETRY,
        /** The source came back. A compensation exists and is being worked, not finished. */
        COMPENSATING,
        /** Both durable states agree the wrong entity has been replaced. */
        COMPENSATED,
        /** The snapshot structurally cannot rebuild the entity. Terminal on the obligation. */
        SNAPSHOT_INCOMPLETE
    }

    /**
     * Plans and performs the write, or resumes one already planned.
     *
     * <p>Idempotent by construction: the intent id is derived from the plan, so a repeat of the
     * same decision finds the same intent and continues from wherever it got to.
     */
    public Verdict publish(LineageCatalogObligation obligation,
            HistoricalEntitySnapshot historical, List<String> mandatoryAttributes) {
        if (obligation == null || historical == null) {
            return Verdict.RETRY;
        }
        if (!historical.snapshot().hasAll(mandatoryAttributes)) {
            // The only structural verdict. Reached only because the caller already established
            // an authoritative purge — otherwise this would terminate an event over a catalog
            // outage that happened to arrive while a field was missing.
            return Verdict.SNAPSHOT_INCOMPLETE;
        }
        LineageHistoricalEntityPublisher publisher =
                publishers == null ? null : publishers.publisherFor(historical.target());
        if (publisher == null) {
            // Unwired, not unpublishable. Readiness names this; the machine waits.
            return Verdict.RETRY;
        }

        Map<String, Object> payload = canonicalPayload(historical);
        String plannedOperationDigest = operationDigest(historical, payload);
        LineageHistoricalPublishIntent planned = plan(obligation, historical,
                plannedOperationDigest);

        LineageHistoricalPublishIntent stored;
        try {
            stored = intents.createIfAbsent(planned);
        } catch (LineageHistoricalPublishIntentStore.IntentPlanConflictException conflict) {
            // A different plan under this id cannot happen — the id is derived from the plan —
            // so this is corruption or tampering, and retrying will not fix it. Still not
            // terminal on the obligation: nothing has been established about the snapshot.
            logger.error("A historical publish intent describes a different plan: {}",
                    conflict.getClass().getSimpleName());
            return Verdict.RETRY;
        } catch (RuntimeException e) {
            logger.warn("Could not record a historical publish intent: {}",
                    e.getClass().getSimpleName());
            return Verdict.RETRY;
        }
        return drive(stored, historical, publisher, payload, plannedOperationDigest);
    }

    /**
     * Drives one intent from wherever it is. The recovery scanner calls this too.
     *
     * @param historical may be {@code null} when resuming — a resumed intent already has
     *        everything it needs recorded, except the payload, which is rebuilt by the caller
     */
    public Verdict drive(LineageHistoricalPublishIntent intent,
            HistoricalEntitySnapshot historical, LineageHistoricalEntityPublisher publisher,
            Map<String, Object> payload, String plannedOperationDigest) {
        long now = clockMs.getAsLong();
        Optional<LineageHistoricalPublishIntentStore.IntentClaim> claim =
                intents.claim(intent.intentId(), identity.nodeId(), INTENT_LEASE, now);
        if (claim.isEmpty()) {
            // Someone else holds it. Ordinary, not a failure.
            return Verdict.RETRY;
        }
        LineageHistoricalPublishIntentStore.IntentClaim held = claim.get();

        // The state AT THE CLAIM, not the one on the object handed in. That object was read
        // before the claim, and the window between is exactly where another worker moves it.
        return switch (held.stateAtClaim()) {
            case PLANNED -> fromPlanned(intent, held, historical, publisher, payload,
                    plannedOperationDigest);
            case PUBLISHED -> fromPublished(intent, held, historical, publisher,
                    plannedOperationDigest);
            case COMPENSATION_REQUIRED -> compensate(intent, held);
            case RESOLVED -> Verdict.RESOLVED_PURGED;
            case COMPENSATED -> Verdict.COMPENSATED;
        };
    }

    /**
     * PLANNED: the entity may or may not be written. Read back before writing again.
     *
     * <p>The read-back comes first because a crash between the external write and the intent
     * update leaves exactly this state, and publishing again without looking would be a second
     * write of something that may already be there.
     */
    private Verdict fromPlanned(LineageHistoricalPublishIntent intent,
            LineageHistoricalPublishIntentStore.IntentClaim held,
            HistoricalEntitySnapshot historical, LineageHistoricalEntityPublisher publisher,
            Map<String, Object> payload, String plannedOperationDigest) {
        if (historical == null) {
            // Resuming without material. The scanner rebuilds it; until then, nothing to do.
            return Verdict.RETRY;
        }

        // Read back FIRST, and bound to THIS plan. A crash between the external write and this
        // state update leaves exactly PLANNED-with-the-entity-written, and re-checking the
        // source instead would find it restored and walk away from a tombstone nobody comes
        // back for. "Something is present" is not this plan's write — see LineageHistoricalReadBack.
        LineageHistoricalReadBack readBack =
                readBack(publisher, historical, plannedOperationDigest);
        switch (readBack) {
            case UNKNOWN -> {
                // Publishing now might be a second write; skipping might abandon a first one.
                intents.recordAttempt(held, "the planned entity could not be read back");
                return Verdict.RETRY;
            }
            case CONFLICT -> {
                // Something else owns this qualified name — the authoritative entity, or
                // another intent's. Overwriting it is the failure the subject fence exists to
                // prevent, so this plan stops and stays visible.
                logger.warn("A historical plan found a conflicting entity in its place");
                intents.recordAttempt(held, "the catalog holds an entity this plan did not write");
                return Verdict.RETRY;
            }
            case MATCH -> {
                // The write happened before the crash. Record it and go on to the source
                // re-check, which is where a restore during the gap is caught.
                if (!intents.transition(held, LineageHistoricalPublishIntent.State.PLANNED,
                        LineageHistoricalPublishIntent.State.PUBLISHED,
                        "the entity was already written before this attempt")) {
                    return Verdict.RETRY;
                }
                return fromPublished(intent, held, historical, publisher,
                        plannedOperationDigest);
            }
            case ABSENT -> { /* fall through to publishing */ }
        }

        // One writer per catalog entity. Intent ids differ per evidence, which does not stop
        // two observations of one object from publishing concurrently.
        String subjectKey = LineageHistoricalPublishIntentStore.subjectKey(intent.target(),
                intent.repositoryId(), intent.endpointKind(), intent.subjectDigest());
        Optional<LineageHistoricalPublishIntentStore.SubjectFence> fence =
                intents.acquireSubjectFence(subjectKey, intent.intentId(), INTENT_LEASE,
                        clockMs.getAsLong());
        if (fence.isEmpty()) {
            // Another intent is writing this entity. Waiting is the whole point.
            intents.recordAttempt(held, "another intent holds this subject");
            return Verdict.RETRY;
        }
        try {
            return publishUnderFence(intent, held, historical, publisher,
                    plannedOperationDigest);
        } finally {
            intents.releaseSubjectFence(fence.get());
        }
    }

    /** The external write, with the subject fence held. */
    private Verdict publishUnderFence(LineageHistoricalPublishIntent intent,
            LineageHistoricalPublishIntentStore.IntentClaim held,
            HistoricalEntitySnapshot historical, LineageHistoricalEntityPublisher publisher,
            String plannedOperationDigest) {
        // Re-check the source immediately before the external write: the gap between planning
        // and publishing is exactly where a restore does the most damage.
        LineageSourceDispositionResolver.SourceEvidence before = sources.dispositionOf(
                historical.repositoryId(), historical.endpointKind(),
                historical.catalogQualifiedName());
        if (!historical.stillAuthorised(before)) {
            // Restored, or unknown, before anything was written. Nothing to compensate.
            intents.recordAttempt(held, "the source changed before publishing");
            return Verdict.RETRY;
        }

        // The renew is the AUTHORISATION for the external side effect, not a courtesy. If the
        // lease has gone — expired, or reclaimed by another worker — this process no longer
        // speaks for the intent, and a write it made would be one nothing is tracking.
        Optional<LineageHistoricalPublishIntentStore.IntentClaim> renewed =
                intents.renew(held, INTENT_LEASE, clockMs.getAsLong());
        if (renewed.isEmpty()) {
            logger.warn("Lost the intent lease before publishing; not writing");
            return Verdict.RETRY;
        }
        LineageHistoricalPublishIntentStore.IntentClaim live = renewed.get();

        LineageHistoricalPublishReceipt receipt;
        try {
            receipt = publisher.publishHistorical(historical);
        } catch (RuntimeException e) {
            logger.warn("Historical publish failed: {}", e.getClass().getSimpleName());
            intents.recordAttempt(live, "the publish attempt failed");
            return Verdict.RETRY;
        }
        if (receipt == null
                || receipt.outcome() != LineageHistoricalEntityPublisher.Outcome.PUBLISHED) {
            intents.recordAttempt(live, "the publish did not complete");
            return Verdict.RETRY;
        }
        if (!plannedOperationDigest.equals(receipt.operationDigest())) {
            // The publisher wrote something other than what was planned. The intent — and any
            // compensation derived from it — names the planned write, so accepting this would
            // leave an entity nothing can identify later.
            logger.error("A historical publish wrote a different operation than was planned");
            intents.recordAttempt(live, "the publisher's operation does not match the plan");
            return Verdict.RETRY;
        }
        if (!intents.transition(live, LineageHistoricalPublishIntent.State.PLANNED,
                LineageHistoricalPublishIntent.State.PUBLISHED, "read-back confirmed")) {
            // The entity is written and the state is not updated. Harmless: the next pass finds
            // PLANNED, reads back, sees a MATCH, and moves on.
            return Verdict.RETRY;
        }
        return fromPublished(intent, live, historical, publisher, plannedOperationDigest);
    }

    /** Read-back, with every failure collapsing to UNKNOWN rather than to a guess. */
    private LineageHistoricalReadBack readBack(LineageHistoricalEntityPublisher publisher,
            HistoricalEntitySnapshot historical, String plannedOperationDigest) {
        try {
            LineageHistoricalReadBack verdict =
                    publisher.readBackHistorical(historical, plannedOperationDigest);
            return verdict == null ? LineageHistoricalReadBack.UNKNOWN : verdict;
        } catch (RuntimeException e) {
            logger.warn("Could not read back a planned historical entity: {}",
                    e.getClass().getSimpleName());
            return LineageHistoricalReadBack.UNKNOWN;
        }
    }

    /** PUBLISHED: the entity is there. Does the source still say it should be? */
    private Verdict fromPublished(LineageHistoricalPublishIntent intent,
            LineageHistoricalPublishIntentStore.IntentClaim held,
            HistoricalEntitySnapshot historical, LineageHistoricalEntityPublisher publisher,
            String plannedOperationDigest) {
        if (historical == null) {
            return Verdict.RETRY;
        }
        LineageSourceDispositionResolver.SourceEvidence after = sources.dispositionOf(
                historical.repositoryId(), historical.endpointKind(),
                historical.catalogQualifiedName());
        if (historical.stillAuthorised(after)) {
            if (!intents.transition(held, LineageHistoricalPublishIntent.State.PUBLISHED,
                    LineageHistoricalPublishIntent.State.RESOLVED,
                    "the source was still purged after publishing")) {
                return Verdict.RETRY;
            }
            return Verdict.RESOLVED_PURGED;
        }
        if (after == null
                || after.disposition() == LineageSourceDisposition.SOURCE_UNKNOWN) {
            // Not established. Advancing on this would either resolve wrongly or compensate a
            // publish that may have been correct.
            intents.recordAttempt(held, "the source could not be re-checked after publishing");
            return Verdict.RETRY;
        }
        // The source came back, or is a different incarnation. The written entity is wrong.
        if (!intents.transition(held, LineageHistoricalPublishIntent.State.PUBLISHED,
                LineageHistoricalPublishIntent.State.COMPENSATION_REQUIRED,
                "the source changed during the historical publish")) {
            return Verdict.RETRY;
        }
        return compensate(intent, held);
    }

    /**
     * COMPENSATION_REQUIRED: record the durable request, then converge on the current entity.
     *
     * <p>Re-publishing the authoritative entity rather than deleting the historical one:
     * deletion semantics differ between catalog backends and a delete that silently does
     * nothing would leave the wrong entity in place looking compensated.
     *
     * <h2>The convergence order, and why every step's result is checked</h2>
     *
     * <pre>
     *   1. re-publish the authoritative current entity
     *   2. read it back
     *   3. CAS the compensation to RESOLVED
     *   4. CAS the intent to COMPENSATED
     * </pre>
     *
     * <p>A stop at any point leaves a state the next scan finishes: the compensation is still
     * PENDING, or the intent is still COMPENSATION_REQUIRED, and both are found by
     * {@code findByState}. What must never happen is reporting completion with either durable
     * state unconfirmed — so no CAS result is discarded, and {@code COMPENSATING} is returned
     * unless <em>both</em> transitions succeeded.
     */
    private Verdict compensate(LineageHistoricalPublishIntent intent,
            LineageHistoricalPublishIntentStore.IntentClaim held) {
        LineageHistoricalCompensation request = new LineageHistoricalCompensation(null,
                LineageHistoricalCompensation.taskId(intent.target(), intent.repositoryId(),
                        intent.endpointKind(), intent.subjectDigest(),
                        intent.plannedOperationDigest()),
                intent.target(), intent.repositoryId(), intent.endpointKind(),
                intent.subjectDigest(), intent.plannedOperationDigest(),
                intent.sourceEvidenceDigest(), null,
                LineageHistoricalCompensation.Reason.SOURCE_CHANGED_DURING_HISTORICAL_PUBLISH,
                clockMs.getAsLong(), LineageHistoricalCompensation.State.PENDING);

        LineageHistoricalCompensation stored;
        try {
            stored = compensations.createIfAbsent(request);
        } catch (RuntimeException e) {
            // Without a durable request nothing will come back for the wrong entity, so the
            // intent stays where it is and the scanner tries again.
            logger.error("Could not record a historical compensation: {}",
                    e.getClass().getSimpleName());
            intents.recordAttempt(held, "the compensation could not be recorded");
            return Verdict.RETRY;
        }
        if (stored == null) {
            intents.recordAttempt(held, "the compensation could not be recorded");
            return Verdict.RETRY;
        }
        if (stored.state() == LineageHistoricalCompensation.State.RESOLVED) {
            // Step 3 already done by an earlier attempt; only step 4 is left.
            return finishCompensation(held, stored);
        }

        if (republisher == null) {
            // Unwired. The request is durable, so nothing is lost — but nothing converges
            // either, and readiness names this.
            return Verdict.COMPENSATING;
        }
        LineageCurrentEntityRepublisher.Outcome outcome;
        try {
            outcome = republisher.republishCurrent(intent.target(), intent.repositoryId(),
                    intent.endpointKind(), intent.subjectDigest());
        } catch (RuntimeException e) {
            logger.warn("Could not re-publish the current entity: {}",
                    e.getClass().getSimpleName());
            return Verdict.COMPENSATING;
        }
        if (outcome != LineageCurrentEntityRepublisher.Outcome.REPUBLISHED) {
            // Including SOURCE_UNKNOWN: a compensation is not finished until the current
            // entity has actually been read back in place of the historical one.
            return Verdict.COMPENSATING;
        }
        if (!compensations.markResolved(stored, "the current entity was re-published")) {
            // The republish happened; the record of it did not. The next scan finds the
            // compensation still PENDING and repeats an idempotent republish.
            logger.warn("Could not mark a historical compensation resolved");
            return Verdict.COMPENSATING;
        }
        return finishCompensation(held, stored);
    }

    /**
     * The last CAS. Split out so the resume path — compensation already RESOLVED, intent still
     * COMPENSATION_REQUIRED — reaches exactly the same code.
     */
    private Verdict finishCompensation(LineageHistoricalPublishIntentStore.IntentClaim held,
            LineageHistoricalCompensation stored) {
        if (!intents.transition(held, LineageHistoricalPublishIntent.State.COMPENSATION_REQUIRED,
                LineageHistoricalPublishIntent.State.COMPENSATED,
                "the current entity was re-published")) {
            // Both durable states are not yet consistent, so this is not finished.
            return Verdict.COMPENSATING;
        }
        return Verdict.COMPENSATED;
    }

    // ------------------------------------------------------------------

    private LineageHistoricalPublishIntent plan(LineageCatalogObligation obligation,
            HistoricalEntitySnapshot historical, String plannedOperationDigest) {
        String subjectDigest = historical.sourceEvidence().subjectDigest();
        String intentId = LineageHistoricalPublishIntent.intentId(obligation.taskKey(),
                historical.target(), historical.repositoryId(), historical.endpointKind(),
                subjectDigest, historical.snapshot().evidenceDigest(),
                historical.sourceEvidence().evidenceDigest(), plannedOperationDigest,
                PAYLOAD_SCHEMA_VERSION);
        return new LineageHistoricalPublishIntent(null, intentId, obligation.taskKey(),
                historical.target(), historical.repositoryId(), historical.endpointKind(),
                subjectDigest, historical.snapshot().evidenceDigest(),
                historical.sourceEvidence().evidenceDigest(), plannedOperationDigest,
                PAYLOAD_SCHEMA_VERSION, LineageHistoricalPublishIntent.State.PLANNED,
                null, null, 0L, 0, clockMs.getAsLong(), null);
    }

    /**
     * The entity as it will be written — snapshot attributes plus the historical markers.
     *
     * <p>The markers are what lets a later reader tell this entity from the authoritative one:
     * a catalog answering PRESENT does not say which of the two it is holding.
     */
    static Map<String, Object> canonicalPayload(HistoricalEntitySnapshot historical) {
        Map<String, Object> payload = new TreeMap<>(historical.snapshot().attributes());
        payload.put("sourceState", "PURGED");
        payload.put("active", Boolean.FALSE);
        // Digests, never the values they stand for.
        payload.put("historicalSourceEvidence",
                historical.sourceEvidence().evidenceDigest());
        return payload;
    }

    /**
     * The operation digest, computed from the plan rather than reported by the publisher.
     *
     * <p>A digest that only came into existence after the write could not be in the intent that
     * precedes it, and the intent is what a compensation names.
     */
    static String operationDigest(HistoricalEntitySnapshot historical,
            Map<String, Object> payload) {
        return LineageCanonicalHash.hash(OPERATION_DOMAIN, historical.target(),
                historical.repositoryId(), historical.endpointKind().name(),
                historical.sourceEvidence().subjectDigest(),
                historical.snapshot().evidenceDigest(),
                historical.sourceEvidence().evidenceDigest(),
                (long) PAYLOAD_SCHEMA_VERSION, new TreeMap<>(payload));
    }
}
