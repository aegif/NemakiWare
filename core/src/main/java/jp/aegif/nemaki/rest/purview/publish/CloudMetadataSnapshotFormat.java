package jp.aegif.nemaki.rest.purview.publish;

/**
 * The line format of the cloud-metadata change-detection snapshot — and the rule that the
 * cloud file URL is not in it.
 *
 * <h2>Why the URL had to leave</h2>
 *
 * <p>The snapshot is persisted verbatim as the {@code cloud-metadata-snapshot} stream's cursor
 * and returned verbatim by the admin cursor API. A cloud drive URL legitimately carries sharing
 * tokens in its query string — which is why increment A-1g stopped publishing it to the catalog
 * ({@code cloudFileUrl=null} in every payload). Keeping it in the cursor re-issued the same
 * secret past that boundary on every sync, into CouchDB and into every admin response.
 *
 * <p>Identity never needed it: the stable key is {@code {provider}:{fileId}} (§4), and both
 * fields stay. Change detection does not need it either — the catalog no longer carries the URL,
 * so a URL-only change produces no observable catalog difference, and re-publishing on one was
 * wasted work.
 *
 * <h2>The slot stays, empty</h2>
 *
 * <p>The format is positional ({@code id|provider|fileId|url|syncedAt}) and stored cursors are
 * full of the old shape. Keeping five fields with an always-empty third keeps every index stable,
 * and {@link #normalize} maps both shapes onto the new one — so an old cursor compares equal to a
 * fresh snapshot of unchanged documents instead of triggering a spurious full republish on the
 * first sync after upgrade.
 *
 * <h2>How stored cursors get clean</h2>
 *
 * <p>Every successful sync cycle stores the freshly built (URL-free) snapshot, so persistence
 * scrubs itself one cycle after deployment. Until that cycle happens — or if sync keeps failing —
 * the admin API normalizes on the way out, so the stored residue is never served.
 *
 * <h2>Which is why {@link #normalize} cannot be used to CHECK a stored cursor</h2>
 *
 * <p>{@code normalizeLine} passes a line that is not in the five-field shape through untouched,
 * on purpose — guessing at an unrecognized shape is worse than leaving it. The consequence is
 * that {@code stored.equals(normalize(stored))} is true for a cursor holding a URL in any shape
 * this class does not recognize, which is exactly the case an acceptance check is looking for.
 * The 4b preflight therefore parses strictly instead
 * ({@code CloudMetadataCursorInspection}): five fields, URL slot empty, anything else fails.
 */
public final class CloudMetadataSnapshotFormat {

    /** The cursor stream this format belongs to, as the admin controller needs to spot it. */
    public static final String STREAM_KIND = "cloud-metadata-snapshot";

    private static final int URL_FIELD = 3;
    private static final int FIELD_COUNT = 5;

    private CloudMetadataSnapshotFormat() {
    }

    /** One snapshot line. No URL parameter on purpose: there is nowhere safe to put one. */
    public static String entry(String objectId, String provider, String fileId,
                               String lastSyncedAt) {
        return String.join("|",
                nullToEmpty(objectId),
                nullToEmpty(provider),
                nullToEmpty(fileId),
                "",
                nullToEmpty(lastSyncedAt));
    }

    /**
     * Maps a snapshot of either shape onto the URL-free one. Idempotent; lines that are not in
     * the five-field format pass through untouched rather than being guessed at.
     */
    public static String normalize(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return snapshot == null ? "" : snapshot;
        }
        StringBuilder normalized = new StringBuilder(snapshot.length());
        boolean first = true;
        for (String line : snapshot.split("\\R", -1)) {
            if (!first) {
                normalized.append('\n');
            }
            first = false;
            normalized.append(normalizeLine(line));
        }
        return normalized.toString();
    }

    /** One line of {@link #normalize}; public because the parser works line-wise. */
    public static String normalizeLineForCompare(String line) {
        return normalizeLine(line);
    }

    private static String normalizeLine(String line) {
        if (line == null || line.isBlank()) {
            return line;
        }
        String[] parts = line.split("\\|", -1);
        if (parts.length != FIELD_COUNT || parts[URL_FIELD].isEmpty()) {
            return line;
        }
        parts[URL_FIELD] = "";
        return String.join("|", parts);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
