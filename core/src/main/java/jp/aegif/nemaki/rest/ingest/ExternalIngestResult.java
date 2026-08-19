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
     * <p>False is the conservative answer: it means "do not claim custody began now". Paths that
     * genuinely create an object say so explicitly.
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
}
