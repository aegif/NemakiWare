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
package jp.aegif.nemaki.rest.ingest.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One ingest attempt's capture boundary: opened before the first mutation, completed by its owner.
 *
 * <h2>Why the scope, and not the intent, is what gets passed around</h2>
 *
 * <p>The entry points update the same document AFTER {@code execute()} returns — metadata,
 * capture window, {@code chatCapturedAt}, relationships — so completing inside {@code execute}
 * would snapshot something that is not the final state. But opening at the entry point is wrong
 * too: dry run, skipped empty attachments, dedupe and idempotency skips all finish normally
 * having changed nothing, and an intent opened there is a row with nowhere to go — it cannot be
 * completed (there is no object) and leaving it makes the sweeper call it unresolved, which is a
 * lie, because the thing that ran knows perfectly well that it changed nothing.
 *
 * <p>So the wrapper creates this object <b>un-opened</b> and hands it down; {@code execute} calls
 * {@link #ensureIntentOpened} immediately before its first mutation; and the owner — the wrapper
 * for a root scope, the child operation for a child scope — completes it (design §4).
 *
 * <h2>What stops a capture from completing</h2>
 *
 * <p>Every tracked mutation records a {@link MutationOutcome}. {@code CAPTURED} means "every
 * tracked mutation succeeded", not "every mutation was attempted": the ingest path has real
 * mutations whose failure becomes a warning string rather than an exception, so "attempted"
 * would manufacture evidence. A single {@link MutationOutcome#INDETERMINATE} is enough to stop
 * it — not knowing is not the same as succeeding.
 *
 * <h2>When this does nothing at all</h2>
 *
 * <p>With no store wired, every method is inert and {@link #ensureIntentOpened} does not fail.
 * That is the condition the fail-closed behaviour hangs on: the collaborator being present. It
 * is not a test-only flag — it is the same shape as the existing {@code ingestLineageEmitter !=
 * null} guard — but it does mean unit tests that never wire a store are unaffected, which
 * matters because an absent application context is a situation that arises in tests far more
 * than in production (design §6.8-1-3).
 *
 * <h2>What this does NOT establish</h2>
 *
 * <p>A completed scope says the mutations this ingest tracked succeeded and that the intent
 * preceded them. It does not say the lineage event was delivered anywhere — publication remains
 * fail-open and belongs to the existing outbox — and it does not say the document still exists
 * or is unmodified.
 *
 * <p>Not thread-safe: one scope belongs to one ingest attempt on one thread.
 */
public final class CaptureScope {

    private static final Logger logger = LoggerFactory.getLogger(CaptureScope.class);

    private final CaptureIntentStore store;
    private CaptureIntent intent;

    private boolean opened;
    private final Map<String, Object> passFacts = new LinkedHashMap<>();
    private boolean completed;
    private String operationFailure;
    /** Latched once the repository is found not to run the boundary. */
    private boolean notApplicable;
    /** Latched when an intent write was refused, so a swallowed exception still shows up. */
    private boolean openRefused;
    /** Set when the boundary could not be told to apply or not. Reported, not fatal. */
    private String undeterminedReason;
    private final List<Recorded> mutations = new ArrayList<>();

    /** One tracked mutation and what we know about it. */
    public record Recorded(String operation, MutationOutcome outcome, String detail) {}

    /**
     * @param store  the write seam, or {@code null} for an inert scope
     * @param intent what would be written; not written until {@link #ensureIntentOpened}
     */
    public CaptureScope(CaptureIntentStore store, CaptureIntent intent) {
        this.store = store;
        this.intent = intent;
    }

    /** An inert scope, for paths that have no capture boundary wired. */
    public static CaptureScope inactive() {
        return new CaptureScope(null, null);
    }

    /**
     * Whether this scope has anything to record against.
     *
     * <p>Deliberately does NOT ask the store whether it is reachable: that question provisions
     * the lineage database, and asking it on every {@code record} would provision it for
     * repositories the boundary does not even cover. Reachability is decided once, in
     * {@link #ensureIntentOpened}, where a negative answer is a refusal rather than silence.
     */
    public boolean isActive() {
        return store != null && intent != null;
    }

    public CaptureIntent intent() {
        return intent;
    }

    /**
     * Notes a fact about this pass for the completion evidence. Overwrites the key: callers
     * accumulate locally and set once, so partial lists cannot be read as complete ones.
     */
    public void notePassFact(String key, Object value) {
        passFacts.put(key, value);
    }

    public boolean isOpened() {
        return opened;
    }

    /**
     * Whether an intent write was refused during this operation.
     *
     * <p>Independent of whether the exception reached the caller. A swallowed
     * {@link CaptureIntentFailedException} must still make the operation fail, or fail-closed
     * quietly becomes a warning.
     */
    public boolean wasOpenRefused() {
        return openRefused;
    }

    public List<Recorded> recorded() {
        return List.copyOf(mutations);
    }

    /**
     * Opens the intent if it is not open yet. Call immediately before the first mutation.
     *
     * <p>Idempotent: the second and later calls do nothing, so every mutation site may call it
     * without knowing whether it is the first.
     *
     * @throws CaptureIntentFailedException when the intent could not be written and this scope
     *         is active. This is the fail-closed point: the ingest must not proceed to change
     *         anything, because a change with no preceding intent is exactly the state the
     *         boundary exists to prevent. The store reports a verdict rather than throwing,
     *         because the underlying client swallows failures and returns null.
     */
    public void ensureIntentOpened() {
        if (opened || notApplicable || store == null || intent == null) {
            return;
        }
        // The MODE is asked first, before anything touches the database. Asking isActive() first
        // provisioned nemaki_lineage — creating the database and deploying its design documents —
        // on deployments whose every repository is disabled, which is a behaviour change where
        // the design promised none. It also made a provisioning failure silently disable the
        // whole boundary: an inert scope opens nothing, records nothing and warns about nothing,
        // so the document was created and the ingest reported success (external review).
        CaptureIntentStore.Applicability applicability = store.appliesTo(intent.repositoryId());
        if (applicability == CaptureIntentStore.Applicability.NOT_APPLICABLE) {
            // Latched, so the answer cannot change mid-operation and is not asked again per
            // mutation.
            notApplicable = true;
            return;
        }
        if (applicability == CaptureIntentStore.Applicability.UNDETERMINED) {
            // NOT a refusal. Design §6.8-1 decision 2 already chose to record this as a hole in
            // the guarantee rather than close it, and the sibling emitter path reports it as a
            // warning — making capture refuse where the emitter warns would fail every ingest in
            // every deployment that has never enabled lineage, the moment nemaki_conf blinks.
            // Guarantee 3 says disabled and direct behave exactly as today, and "we could not
            // tell whether it is disabled" is inside that.
            //
            // What changes is that it is no longer SILENT: the caller is told the answer is not
            // evidence of a deliberate configuration (external review).
            notApplicable = true;
            undeterminedReason = "Whether the capture boundary covers repository "
                    + intent.repositoryId() + " could not be determined, because the "
                    + "configuration could not be read. No capture intent was recorded for this "
                    + "ingest. This does NOT establish that lineage is switched off.";
            return;
        }
        if (!store.isActive()) {
            // The boundary DOES apply here and the store cannot be reached. That is a refusal,
            // not a reason to carry on quietly: an inert answer here is exactly how a change
            // ends up with no evidence.
            openRefused = true;
            throw new CaptureIntentFailedException(
                    "The capture boundary applies to repository " + intent.repositoryId()
                            + " but the lineage store is not available, so the ingest must not "
                            + "change anything.");
        }
        if (!store.openIntent(intent)) {
            // Latched as well as thrown. The ingest path is full of catch (Exception) blocks
            // that turn anything into a warning string, and one that is missed would put the
            // refusal in the margin of a successful-looking import. The owner reads this when
            // it builds the result, so the report is safe even if the throw is swallowed
            // somewhere (external review).
            openRefused = true;
            throw new CaptureIntentFailedException(
                    "The capture intent could not be written, so the ingest must not change "
                            + "anything: a change with no preceding intent leaves a document "
                            + "whose origin nothing records. intentId=" + intent.intentId()
                            + ", repositoryId=" + intent.repositoryId());
        }
        opened = true;
    }

    /**
     * Fills in what was not knowable when the scope was created.
     *
     * <p>The connector and the archetype are resolved inside {@code execute}, after the wrapper
     * has already built this object. Because nothing is written until the first mutation, they
     * can still reach the stored row — but only until then, so this refuses once the row exists
     * rather than pretending to change it.
     *
     * @throws IllegalStateException if the intent has already been written
     */
    public void describe(String sourceSystem, String processType) {
        describe(sourceSystem, processType, null, null);
    }

    /**
     * As above, additionally CONFIRMING the execution attribution.
     *
     * <p>{@code newCaptureScope} runs before the profile is resolved, so the scope opens with a
     * provisional actor (the raw context username). The resolver's verdict — one resolution,
     * shared verbatim with the lineage event — lands here, before anything is written, which is
     * what makes "outbox actor == event actor" structural rather than coincidental (P1-1(e) D7,
     * AC9). The confirmation OVERWRITES both actor fields: a resolved {@code onBehalfOf} of
     * null means "no separate authority", and keeping a provisional value under it would let
     * the two records diverge — the exact defect (Codex H2/L2 residual). The 2-arg overload
     * (no attribution yet) keeps the provisional values, keyed on {@code executedBy == null}.
     */
    public void describe(String sourceSystem, String processType,
            String executedBy, String onBehalfOf) {
        if (intent == null) {
            return;
        }
        if (opened) {
            throw new IllegalStateException(
                    "The capture intent has already been written; describing it now would change "
                            + "this object without changing the stored row");
        }
        boolean confirming = executedBy != null;
        intent = new CaptureIntent(intent.documentId(), intent.intentId(),
                intent.intentOpenedAtMs(), intent.repositoryId(), intent.connectorId(),
                sourceSystem == null ? intent.sourceSystem() : sourceSystem,
                intent.sourceObjectType(), intent.sourceObjectId(), intent.requestId(),
                processType == null ? intent.processType() : processType,
                confirming ? executedBy : intent.executedBy(),
                confirming ? onBehalfOf : intent.onBehalfOf());
    }

    /**
     * Records what we know about one tracked mutation.
     *
     * <p>Only mutations that were actually attempted belong here. An operation that was skipped
     * or disabled is not {@link MutationOutcome#INDETERMINATE} — it is absent.
     */
    public void record(String operation, MutationOutcome outcome) {
        record(operation, outcome, null);
    }

    public void record(String operation, MutationOutcome outcome, String detail) {
        if (!isActive() || notApplicable) {
            // Not applicable is not a broken guarantee. Without this, every ingest into a
            // repository the boundary does not cover logged a warning per mutation saying the
            // intent no longer precedes every change — false, and on the default configuration
            // it is every ingest (external review).
            return;
        }
        if (!opened) {
            // A mutation recorded before the intent was opened means a mutation happened outside
            // the boundary. Opening it now does not repair the ordering, but leaving it silent
            // would hide that the guarantee was broken.
            logger.warn("Mutation '{}' recorded on an unopened capture scope (intentId={}); the "
                    + "intent no longer precedes every change", operation,
                    intent == null ? "none" : intent.intentId());
        }
        mutations.add(new Recorded(operation, outcome, detail));
    }

    /**
     * Records that the business operation did not run to the end.
     *
     * <p>Distinct from any individual mutation failing. Every tracked change can have succeeded
     * and the operation still have failed afterwards — a later step throws, or the entry point
     * returns errors. {@code CAPTURED} means the operation finished, so this has to be able to
     * stop it on its own.
     */
    public void operationFailed(String reason) {
        if (!isActive()) {
            return;
        }
        operationFailure = reason == null ? "the ingest did not complete" : reason;
    }

    /** Whether every tracked mutation is known to have succeeded. */
    public boolean allMutationsSucceeded() {
        return mutations.stream().map(Recorded::outcome).allMatch(MutationOutcome::permitsCapture);
    }

    /**
     * Completes the capture, if it can be completed. Only the scope's owner calls this.
     *
     * <p>An un-opened scope completes to {@link CaptureResult#notOpened()} — the normal ending
     * for a path that changed nothing. A scope with a failed or indeterminate mutation is left
     * as {@code CAPTURE_INTENT} deliberately: the sweeper will call it unresolved, which is the
     * truthful answer, and it is what puts the attempt in front of an operator.
     */
    public CaptureResult complete(Map<String, Object> evidence) {
        if (undeterminedReason != null) {
            // Nothing was recorded, and the caller is told why. Silence here is what let a
            // journaled repository be ingested into with no evidence while the configuration was
            // unreadable.
            return CaptureResult.notEstablished(undeterminedReason);
        }
        if (!isActive() || !opened) {
            return CaptureResult.notOpened();
        }
        if (completed) {
            return CaptureResult.alreadyCompleted();
        }
        if (operationFailure != null) {
            return CaptureResult.notEstablished(
                    "The capture was not recorded as complete because the ingest did not run to "
                            + "the end: " + operationFailure
                            + ". The attempt remains open and will be listed as unresolved.");
        }
        if (!allMutationsSucceeded()) {
            List<String> unmet = mutations.stream()
                    .filter(r -> !r.outcome().permitsCapture())
                    .map(r -> r.operation() + "=" + r.outcome()
                            + (r.detail() == null ? "" : " (" + r.detail() + ")"))
                    .toList();
            return CaptureResult.notEstablished(
                    "The capture was not recorded as complete because these tracked changes did "
                            + "not succeed: " + String.join(", ", unmet)
                            + ". The attempt remains open and will be listed as unresolved.");
        }

        Map<String, Object> body = new LinkedHashMap<>(evidence == null ? Map.of() : evidence);
        // Facts the wrapper noted about THIS pass — what it decided NOT to take, chiefly. A
        // 0-byte or pseudo-file attachment produces no document, no aspect and no event, so the
        // completed row is the only durable place "we saw it and did not ingest it, and why"
        // can live (design p1-1d-evidence-data-model.md D5). Recorded only on passes that open
        // a row at all: a re-poll whose parent dedupe-skips records nothing, which is the same
        // anti-flood rule as the re-import event — the fact was recorded at capture time.
        body.putAll(passFacts);
        body.put("mutations", mutations.stream()
                .map(r -> Map.of("operation", r.operation(), "outcome", r.outcome().name()))
                .toList());

        CaptureIntentStore.CaptureCompletion outcome = store.completeIntent(intent, body);
        completed = outcome == CaptureIntentStore.CaptureCompletion.COMPLETED;
        return switch (outcome) {
            case COMPLETED -> CaptureResult.success();
            case ROW_UNREADABLE -> CaptureResult.notEstablished(
                    // Deliberately an observation with no cause attached: the layers below
                    // collapse a missing document, a transient failure and a write that never
                    // landed into the same answer (design §6.8-6).
                    "The evidence row for this ingest (intent id: " + intent.intentId() + ") "
                            + "could not be read when completing it. Possible causes include "
                            + "deletion by retention, a transient failure of the lineage "
                            + "database, or the intent write never having taken effect.");
            case NOT_COMPLETABLE -> CaptureResult.notEstablished(
                    "The evidence row for this ingest (intent id: " + intent.intentId() + ") "
                            + "was not in a state that could be completed.");
            case CONTENDED -> CaptureResult.notEstablished(
                    "The evidence row for this ingest (intent id: " + intent.intentId() + ") "
                            + "could not be written because another writer kept winning. The row "
                            + "remained completable each time it was read, so this is contention "
                            + "rather than a state problem.");
        };
    }

    /** The outcome of completing, and anything the caller has to be told. */
    public record CaptureResult(boolean captured, String warning) {

        static CaptureResult success() {
            return new CaptureResult(true, null);
        }

        /** Nothing was changed, so there was nothing to capture. Not a problem. */
        static CaptureResult notOpened() {
            return new CaptureResult(false, null);
        }

        static CaptureResult alreadyCompleted() {
            return new CaptureResult(true, null);
        }

        static CaptureResult notEstablished(String warning) {
            return new CaptureResult(false, warning);
        }
    }

    /** Raised when the intent could not be written, to stop the ingest before it changes anything. */
    public static class CaptureIntentFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CaptureIntentFailedException(String message) {
            super(message);
        }
    }
}
