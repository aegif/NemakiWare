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
package jp.aegif.nemaki.rest.purview.state;

/**
 * One repository's stored {@code cloud-metadata-snapshot} cursor, as a VERDICT (4b preflight).
 *
 * <p>The value never leaves the state layer. A check whose purpose is to find residual sharing
 * tokens must not be the thing that prints one — so this record carries counts and a verdict
 * and nothing that could contain a URL, and callers cannot reconstruct the value from it.
 *
 * <p>The verdict is <b>strict parsing</b>, not "does it equal its own normalization".
 * {@code CloudMetadataSnapshotFormat.normalize} deliberately passes a line that is not in the
 * five-field shape through untouched, so a cursor holding a URL in an unrecognized shape
 * compares equal to its normalization — exactly the case this check exists to catch. Every
 * non-blank line must therefore split into exactly five fields with the URL slot empty;
 * anything else is malformed and the repository fails.
 */
public record CloudMetadataCursorInspection(
        String repositoryId,
        PurviewStateStore.Presence presence,
        int lines,
        int malformedLines,
        int populatedUrlLines,
        boolean clean,
        /** Why it could not be established, as an exception CLASS name. Never a message. */
        String reasonClass) {

    /** The index of the field {@code CloudMetadataSnapshotFormat.entry} always writes empty. */
    private static final int URL_FIELD = 3;

    private static final int FIELD_COUNT = 5;

    /**
     * Every store's copy of the key, merged: clean only if ALL of them are.
     *
     * <p>A migration leaves the legacy document behind when the dedicated one already exists,
     * so checking only the value a reader would get lets a clean cursor mask a dirty one
     * underneath it.
     */
    public static CloudMetadataCursorInspection ofAll(String repositoryId,
            java.util.List<PurviewStateStore.RawEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new CloudMetadataCursorInspection(repositoryId,
                    PurviewStateStore.Presence.ERROR, 0, 0, 0, false, "NoStoreReported");
        }
        CloudMetadataCursorInspection merged = null;
        for (PurviewStateStore.RawEntry entry : entries) {
            CloudMetadataCursorInspection one = of(repositoryId, entry);
            if (merged == null) {
                merged = one;
                continue;
            }
            merged = new CloudMetadataCursorInspection(repositoryId,
                    // The most-present state wins, so a dirty legacy value is never hidden
                    // behind an absent dedicated one.
                    worse(merged.presence(), one.presence()),
                    merged.lines() + one.lines(),
                    merged.malformedLines() + one.malformedLines(),
                    merged.populatedUrlLines() + one.populatedUrlLines(),
                    merged.clean() && one.clean(),
                    merged.reasonClass() != null ? merged.reasonClass() : one.reasonClass());
        }
        return merged;
    }

    private static PurviewStateStore.Presence worse(PurviewStateStore.Presence a,
            PurviewStateStore.Presence b) {
        if (a == PurviewStateStore.Presence.ERROR || b == PurviewStateStore.Presence.ERROR) {
            return PurviewStateStore.Presence.ERROR;
        }
        if (a == PurviewStateStore.Presence.PRESENT_VALUE
                || b == PurviewStateStore.Presence.PRESENT_VALUE) {
            return PurviewStateStore.Presence.PRESENT_VALUE;
        }
        return a == PurviewStateStore.Presence.PRESENT_EMPTY ? a : b;
    }

    /**
     * @param raw the RAW stored value; it is read here and never retained
     */
    public static CloudMetadataCursorInspection of(String repositoryId,
            PurviewStateStore.RawEntry raw) {
        switch (raw.presence()) {
            case ERROR -> {
                // Fail closed: a cursor we could not read has not been checked.
                return new CloudMetadataCursorInspection(repositoryId,
                        PurviewStateStore.Presence.ERROR, 0, 0, 0, false, raw.reasonClass());
            }
            case ABSENT -> {
                return new CloudMetadataCursorInspection(repositoryId,
                        PurviewStateStore.Presence.ABSENT, 0, 0, 0, true, null);
            }
            case PRESENT_EMPTY -> {
                return new CloudMetadataCursorInspection(repositoryId,
                        PurviewStateStore.Presence.PRESENT_EMPTY, 0, 0, 0, true, null);
            }
            default -> {
                return parse(repositoryId, raw.value());
            }
        }
    }

    private static CloudMetadataCursorInspection parse(String repositoryId, String value) {
        int lines = 0;
        int malformed = 0;
        int populated = 0;
        for (String line : value.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            lines++;
            String[] parts = line.split("\\|", -1);
            if (parts.length != FIELD_COUNT) {
                // A shape nobody recognizes is not a shape anybody verified.
                malformed++;
            } else if (!parts[URL_FIELD].isEmpty()) {
                populated++;
            }
        }
        return new CloudMetadataCursorInspection(repositoryId,
                PurviewStateStore.Presence.PRESENT_VALUE, lines, malformed, populated,
                malformed == 0 && populated == 0, null);
    }
}
