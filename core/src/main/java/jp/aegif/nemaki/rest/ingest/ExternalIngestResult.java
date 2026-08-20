package jp.aegif.nemaki.rest.ingest;

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
                false, false, null, null, List.of(errorMessage), List.of());
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
                false, false, null, null, List.of(errorMessage),
                warnings == null ? List.of() : List.copyOf(warnings));
    }
}
