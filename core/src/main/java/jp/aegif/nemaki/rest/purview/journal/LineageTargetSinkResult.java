package jp.aegif.nemaki.rest.purview.journal;

/**
 * Result of a {@link LineageTargetSink#publish} operation.
 *
 * @param success     {@code true} if the publish succeeded
 * @param entityCount number of entities created/updated on the target
 * @param message     human-readable result or error description
 */
public record LineageTargetSinkResult(boolean success, int entityCount, String message) {

    public static LineageTargetSinkResult success(int entityCount, String msg) {
        return new LineageTargetSinkResult(true, entityCount, msg);
    }

    public static LineageTargetSinkResult failure(String msg) {
        return new LineageTargetSinkResult(false, 0, msg);
    }

    public static LineageTargetSinkResult skipped(String reason) {
        return new LineageTargetSinkResult(true, 0, "skipped: " + reason);
    }
}
