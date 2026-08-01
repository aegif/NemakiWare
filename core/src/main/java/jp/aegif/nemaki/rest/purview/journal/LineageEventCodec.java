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

import java.util.Map;

/**
 * One entry point from a stored journal document to a {@link LineageJournalEntry}, whichever
 * version wrote it.
 *
 * <p>This is the seam Slice 2d-2 threads through the store's read paths. Nothing in production
 * calls it yet; §6-a's {@code read:v2} capability is claimed only when the whole path — this
 * codec, the store, the projector, the sinks, the failure handling — demonstrably carries a v2
 * row, not when this class merely exists.
 *
 * <h2>Version dispatch</h2>
 *
 * <table>
 *   <tr><th>{@code schemaVersion}</th><th>path</th></tr>
 *   <tr><td>2</td><td>{@link CouchLineageEventV2#fromMap} — strict; re-verifies identity</td></tr>
 *   <tr><td>1, 0 or absent</td><td>the v1 codec, exactly as today — rows predating the field are
 *       v1 by definition, because every v2 row carries a 2</td></tr>
 *   <tr><td>anything greater</td><td>throw: the row was written by a newer binary, and reading it
 *       leniently is how §6-a's stopping condition gets silently violated in reverse</td></tr>
 * </table>
 */
public final class LineageEventCodec {

    private LineageEventCodec() {
    }

    /**
     * The total form of {@link #decode}: a failed row comes back as a value, not an exception.
     *
     * <p>This is what the store's row loops call in Slice 2d-2. Throwing per row would leave the
     * loop the choice between failing the whole batch (one corrupt row hides every row) and
     * catch-and-skip (the ordered read exposes rows behind the broken one, and the cursor walks
     * past it — see {@link LineageJournalRow}). Neither is acceptable; a union is.
     */
    public static LineageJournalRow decodeRow(Map<String, Object> doc) {
        try {
            return new LineageJournalRow.Decoded(decode(doc));
        } catch (RuntimeException e) {
            String id = doc != null && doc.get("_id") instanceof String s ? s : null;
            String type = doc != null && doc.get("type") instanceof String s ? s : null;
            int schemaVersion = doc != null && doc.get("schemaVersion") instanceof Number n
                    ? n.intValue() : 0;
            return new LineageJournalRow.Undecodable(id, type, schemaVersion,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * @throws IllegalArgumentException if the document is malformed, of an unsupported version,
     *                                  or fails v2's stored-identity verification. Callers route
     *                                  this to the quarantine path (Slice 2c) — the row, not the
     *                                  batch, is what failed.
     */
    public static LineageJournalEntry decode(Map<String, Object> doc) {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        int schemaVersion = doc.get("schemaVersion") instanceof Number n ? n.intValue() : 0;
        if (schemaVersion > LineageEventV2.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion " + schemaVersion + " was written"
                    + " by a newer binary than this one reads (max "
                    + LineageEventV2.CURRENT_SCHEMA_VERSION + ")");
        }
        if (schemaVersion == LineageEventV2.CURRENT_SCHEMA_VERSION) {
            return LineageJournalEntry.ofV2(CouchLineageEventV2.fromMap(doc));
        }
        return LineageJournalEntry.ofV1(new CouchLineageEvent(doc).toLineageEvent());
    }
}
