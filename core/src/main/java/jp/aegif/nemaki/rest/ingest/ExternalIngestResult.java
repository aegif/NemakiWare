package jp.aegif.nemaki.rest.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a canonical external ingest operation.
 */
public record ExternalIngestResult(
        String requestId,
        String objectId,
        String versionLabel,
        boolean isNewVersion,
        boolean dryRun,
        boolean skipped,
        String skipReason,
        String lineageEventId,
        List<String> errors,
        List<String> warnings,
        /**
         * Whether THIS operation created the object, as opposed to finding one already here.
         *
         * <p>Needed because "when did this deployment take custody" is only knowable for an
         * object we just made. A dedupe-skipped or updated object was here before, and neither
         * the clock nor {@code cmis:creationDate} says when we first held it — creation time
         * survives migration and archive restore, and a later version carries its own
         * (external review). Anything that cannot establish the answer must leave it unrecorded.
         */
        boolean createdObject) {

    /**
     * What a result says in place of a message it was handed as {@code null}: a failure whose
     * exception had no message, a warning added from a variable that held none.
     */
    public static final String NO_MESSAGE = "(no message was given)";

    /**
     * The lists are the result's own: copied when it is made, and not changeable through it.
     *
     * <p>A result used to keep the very list its maker had been adding to, and hand that list
     * out as it was: a wrapper could filter the mail's warnings in place —
     * {@code result.warnings().removeIf(…)} — and drop the warning that says an attachment is
     * missing, with every lock that reads the import's source still green, since nothing it reads
     * had changed (review, P1). Now a change made through a result fails when it runs, and a list
     * its maker changes after the result was made does not change the result.
     *
     * <p>No element of either list is {@code null}: a {@code null} message becomes
     * {@link #NO_MESSAGE}. Readers call string methods on the elements — the cloud-drive import
     * reads the first error with {@code contains}, the Notion and IMAP pollers read each warning —
     * and throw on a {@code null} one; the cloud-drive import met one as soon as the {@code error}
     * factories stopped throwing on a {@code null} message (review, P2). {@link #withWarnings} and
     * the {@code error} that carries warnings hand their list to this constructor: they copied it
     * with {@code List.copyOf}, which throws on a {@code null} element, so a result carrying one
     * could not be given the capture's warning; and both {@code error} factories put their message
     * in a list that takes {@code null}: with {@code List.of}, a failure whose exception had no
     * message threw instead of being reported as a failure (review, P2 ×2). An absent list stays
     * absent here; {@link #withWarnings} and the {@code error} that carries warnings make an absent
     * warning list empty, as they did before.
     */
    public ExternalIngestResult {
        errors = ownCopy(errors);
        warnings = ownCopy(warnings);
    }

    /** The list as the result keeps it: its own copy, no element {@code null}, not changeable. */
    private static List<String> ownCopy(List<String> messages) {
        if (messages == null) {
            return null;
        }
        List<String> copy = new ArrayList<>(messages);
        copy.replaceAll(message -> message == null ? NO_MESSAGE : message);
        return Collections.unmodifiableList(copy);
    }

    /**
     * Legacy arity, defaulting {@code createdObject} to false.
     *
     * <p>False is the conservative answer: it means "do not claim custody began now" — a wrong
     * false loses a fact, a wrong true asserts one. It exists so that error and skip results,
     * which never create anything, stay readable.
     *
     * <p>It is NOT a safe default for a wrapper that rebuilds a result: dropping the flag there
     * reported freshly created mail, note, record and chat objects as pre-existing (external
     * review). Every rebuild must carry the inner result's value forward explicitly.
     */
    /**
     * The same result with a different warning list.
     *
     * <p>Used where a warning becomes known only after the result is built — the capture
     * boundary can only report that the evidence was not recorded once the operation has
     * finished, by which point the content is already committed.
     */
    public ExternalIngestResult withWarnings(List<String> warnings) {
        return new ExternalIngestResult(requestId, objectId, versionLabel, isNewVersion, dryRun,
                skipped, skipReason, lineageEventId, errors,
                warnings == null ? List.of() : warnings, createdObject);
    }

    public ExternalIngestResult(String requestId, String objectId, String versionLabel,
                                boolean isNewVersion, boolean dryRun, boolean skipped,
                                String skipReason, String lineageEventId, List<String> errors,
                                List<String> warnings) {
        this(requestId, objectId, versionLabel, isNewVersion, dryRun, skipped, skipReason,
                lineageEventId, errors, warnings, false);
    }

    public boolean isSuccess() {
        return errors == null || errors.isEmpty();
    }

    public static ExternalIngestResult success(String requestId, String objectId,
                                               String versionLabel, boolean isNewVersion,
                                               String lineageEventId) {
        return new ExternalIngestResult(requestId, objectId, versionLabel, isNewVersion,
                false, false, null, lineageEventId, List.of(), List.of());
    }

    public static ExternalIngestResult skipped(String requestId, String reason) {
        return new ExternalIngestResult(requestId, null, null, false,
                false, true, reason, null, List.of(), List.of());
    }

    public static ExternalIngestResult skipped(String requestId, String existingObjectId, String reason) {
        return new ExternalIngestResult(requestId, existingObjectId, null, false,
                false, true, reason, null, List.of(), List.of());
    }

    public static ExternalIngestResult dryRun(String requestId, String objectId, boolean wouldBeNewVersion) {
        return new ExternalIngestResult(requestId, objectId, null, wouldBeNewVersion,
                true, false, null, null, List.of(), List.of());
    }

    public static ExternalIngestResult error(String requestId, String errorMessage) {
        return new ExternalIngestResult(requestId, null, null, false,
                false, false, null, null, Collections.singletonList(errorMessage), List.of());
    }

    /**
     * An error that still reports what the failed attempt had already done.
     *
     * <p>The two-argument form reports {@code objectId = null} and drops every accumulated
     * warning. On a path that fails AFTER committing a document, that tells the caller the
     * opposite of the truth: the object exists, and the warnings recorded along the way
     * (a replaced document that could not be deleted, provenance that was not recorded,
     * an ACL that was not applied) are exactly what an operator needs to clean up.
     *
     * @param objectId the object that WAS committed before the failure, or null if none was
     * @param warnings everything recorded before the failure; never discarded
     */
    public static ExternalIngestResult error(String requestId, String objectId,
                                             String errorMessage, List<String> warnings) {
        return new ExternalIngestResult(requestId, objectId, null, false,
                false, false, null, null, Collections.singletonList(errorMessage),
                warnings == null ? List.of() : warnings);
    }
}
