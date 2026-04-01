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
        List<String> warnings) {

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

    public static ExternalIngestResult dryRun(String requestId, String objectId, boolean wouldBeNewVersion) {
        return new ExternalIngestResult(requestId, objectId, null, wouldBeNewVersion,
                true, false, null, null, List.of(), List.of());
    }

    public static ExternalIngestResult error(String requestId, String errorMessage) {
        return new ExternalIngestResult(requestId, null, null, false,
                false, false, null, null, List.of(errorMessage), List.of());
    }
}
