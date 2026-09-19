package jp.aegif.nemaki.rest.ingest;

import java.util.List;

/**
 * Result of a fetch run (any adapter).
 *
 * <p><b>Counter semantics:</b>
 * <ul>
 *   <li>{@code fetched} — top-level source records retrieved from the external API</li>
 *   <li>{@code imported} — documents successfully created/versioned (includes child
 *       attachments in chat/mail/note adapters, so may exceed fetched)</li>
 *   <li>{@code skipped} — dedupe-skipped items (source-identity match, same-version etc.;
 *       includes both parent records and child attachments)</li>
 *   <li>{@code errors} — per-item error messages for failed imports</li>
 *   <li>{@code incompleteReads} — why this run did not see everything there was to see</li>
 * </ul>
 *
 * <p><b>Why {@code incompleteReads} is not just another error.</b> A listing that stopped at the
 * caller's limit is not a failure: nothing went wrong, items were imported, and the connector is
 * healthy. But the run did not see the whole source, and {@code IngestJobService.completeJob}
 * marked exactly that run {@code COMPLETED}. Putting it in {@code errors} instead would have had
 * the scheduler count a healthy large workspace towards opening the connector's circuit breaker
 * on every poll that imported nothing new — over-refusal in place of over-claiming. It is a third
 * thing and it gets a third field.
 */
public record FetchResult(int fetched, int imported, int skipped, List<String> errors,
        List<String> incompleteReads) {

    /** Convenience constructor for a run that saw everything it went looking for. */
    public FetchResult(int fetched, int imported, int skipped, List<String> errors) {
        this(fetched, imported, skipped, errors, List.of());
    }

    /** Convenience constructor without skipped (defaults to 0). */
    public FetchResult(int fetched, int imported, List<String> errors) {
        this(fetched, imported, 0, errors, List.of());
    }

    public boolean hasErrors() { return errors != null && !errors.isEmpty(); }

    /**
     * Whether this run saw the whole of what it was reading.
     *
     * <p>False does NOT mean something failed — see the class javadoc. It means the counters
     * above describe a part, and naming the run complete would overstate them.
     */
    public boolean sawEverything() {
        return incompleteReads == null || incompleteReads.isEmpty();
    }

    /**
     * The word a manual run reports to whoever pressed the button.
     *
     * <p>One place, because there were two and they agreed with each other and not with the
     * scheduled path: both keyed "success" off {@code hasErrors()} alone, so a run that stopped
     * at its limit told a person it had succeeded while the job record for the same shape of run
     * said PARTIAL (Codex review, P1). "success" is a claim about the SOURCE as well as about
     * whether anything threw.
     */
    public String runStatus() {
        return !hasErrors() && sawEverything() ? "success" : "partial";
    }
}
