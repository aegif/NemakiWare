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

/**
 * What reading one journal row produced: an entry, or the fact that it could not be one.
 *
 * <h2>Why "could not decode" is a value and not a log line</h2>
 *
 * <p>The store's ordered read ({@code findByRepositoryAndSequenceRange}) feeds a cursor that must
 * never pass an unprocessed event. A row that fails to decode and is silently dropped from the
 * result makes the rows <em>behind</em> it visible; the projector processes them, advances the
 * cursor, and the broken row is now permanently behind a cursor that claims it was handled —
 * ordered delivery violated by a {@code catch} block. The failure has to travel to the caller as
 * data, so the projector can stop the ordered stream at it and quarantine it durably, and the
 * admin API can render it as a diagnostic row instead of a gap.
 *
 * <p>{@link LineageJournalEntry} itself cannot represent this: its constructor demands a valid,
 * verified pairing of projection and envelope, which is precisely what a broken row does not
 * have. Hence a union over the two outcomes.
 *
 * <h2>What an {@link Undecodable} may carry</h2>
 *
 * <p>The document id, the raw type/version markers and the failure message — never the payload.
 * The payload of a v2 row can contain external stable keys inside qualified names, and a row
 * that failed verification is the last thing whose contents should be copied around.
 */
public sealed interface LineageJournalRow
        permits LineageJournalRow.Decoded, LineageJournalRow.Undecodable {

    /** A row that decoded, verified and projected. */
    record Decoded(LineageJournalEntry entry) implements LineageJournalRow {
        public Decoded {
            if (entry == null) {
                throw new IllegalArgumentException("entry must not be null");
            }
        }
    }

    /**
     * A row that could not become an entry.
     *
     * @param documentId the CouchDB {@code _id}, so an operator can find the row; may be null if
     *                   even that was unreadable
     * @param documentType the raw {@code type} field, or null
     * @param schemaVersion the raw {@code schemaVersion}, or 0 when absent or non-numeric
     * @param reason the decode failure, in words; never the payload
     */
    record Undecodable(String documentId, String documentType, int schemaVersion, String reason)
            implements LineageJournalRow {

        public Undecodable {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be null or blank — an"
                        + " undecodable row nobody can explain cannot be acted on");
            }
        }

        @Override
        public String toString() {
            // The reason comes from codec exceptions, which never include payload values.
            return "Undecodable[id=" + documentId + ", type=" + documentType
                    + ", schemaVersion=" + schemaVersion + ", reason=" + reason + "]";
        }
    }
}
