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
package jp.aegif.nemaki.evidence;

import java.util.List;

/**
 * Durable rows saying "content was written and its statement is not in the ledger yet" (E1).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>The content lives in CouchDB and the ledger lives in the evidence database, and there is no
 * transaction across the two. So between writing bytes and recording what they were, there is a
 * window, and a crash inside it leaves content with no statement. The plan's requirement
 * (§8) is not that the window be closed — it cannot be — but that <b>a gap after a successful
 * write be permanently observable</b>. An open row here IS that gap.
 *
 * <h2>Why a row and not a marker on the document (案 A)</h2>
 *
 * <p>Both designs were considered; see {@code docs/design/adr-e1-durable-commitment.md}. The
 * marker-on-the-document design has to be CLEARED after the statement is recorded, and that
 * clear is a second write with exactly the same window — so a crash there leaves a document
 * marked pending forever, indistinguishable from a real gap. An open row, by contrast, is
 * closed inside the same store that holds the ledger, so "recorded" and "resolved" are one
 * store's concern.
 *
 * <h2>What an unreachable journal means</h2>
 *
 * <p><b>It does not mean the write is refused.</b> Turning a journal outage into a failed
 * check-in would convert an evidence problem into a business failure, which the plan forbids.
 * It also does not mean the gap is invisible: with no statement recorded, no package for that
 * version can satisfy P1, and the verifier answers {@code NOT_PRESENT} rather than passing.
 * What is lost is the ability to LIST the gap, and that loss is itself reported by
 * {@link #isActive()} rather than being inferred from an empty list.
 */
public interface ContentWriteJournal {

    /** Which of the enumerated write paths opened the row. Free text is refused by the store. */
    enum WriteKind {
        /** W1 createDocument. */ CREATE_DOCUMENT,
        /** W2 createDocumentWithNewStream. */ NEW_VERSION_WITH_STREAM,
        /** W3 updateDocumentWithNewStream — in place. */ UPDATE_IN_PLACE,
        /** W4 checkIn. */ CHECK_IN,
        /** W5 updateWithoutCheckInOut. */ UPDATE_WITHOUT_CHECKOUT,
        /** W6 createDocumentFromSource. */ COPY_FROM_SOURCE,
        /** W7 appendContentStream — one statement per call; which is last is not known. */ APPEND,
        /** W8 checkOut — the PWC copy. */ CHECK_OUT_PWC,
        /** W9 replacePwc — in place. */ REPLACE_PWC,
        /** W10 archive on delete. */ ARCHIVE,
        /** W11 restore from archive. */ RESTORE,
        /** W12 move or copy to cold storage. */ COLD_TRANSFER,
        /** W13 destroy an archived row. */ DESTROY_ARCHIVE,
        /** W14 destroy a row whose bytes are in cold storage — the blob is left behind. */
        DESTROY_ARCHIVE_LEAVING_COLD_BLOB,
        /** deleteContentStream — the transition to "there is no content". */ CONTENT_REMOVED
    }

    /**
     * Opens a row BEFORE the bytes are written.
     *
     * <p>Before, not after: a row opened afterwards is not written at all when the crash lands
     * between the two, which is precisely the case it exists to record.
     *
     * @return the intent id, which is the idempotency key for {@link #close}
     * @throws ContentWriteJournalUnavailable when the row could not be written. The caller
     *         catches this and proceeds with the business operation — see the class javadoc.
     */
    String open(String repositoryId, String objectId, String versionObjectId, WriteKind kind,
            String openedAt);

    /** What {@link #close} did. Four values because three of them are not success. */
    enum CloseOutcome {
        /** The row is closed and names the entry that resolved it. */
        CLOSED,
        /**
         * The row was already closed, and by the same statement.
         *
         * <p>A retry that arrives after a successful close. Not an error, and NOT a second
         * ledger entry — recording the same statement twice would put two facts in the chain
         * for one write.
         */
        ALREADY_CLOSED,
        /**
         * The row names a different version than the one being closed with.
         *
         * <p>The case the plan names explicitly: an old intent must not be closed by new bytes.
         * Between opening and closing, a check-in can have moved the document on, and closing
         * then would attach this version's statement to the previous version's row.
         */
        WRONG_VERSION,
        /** The store could not be asked. The row, if any, stays open. */
        UNAVAILABLE
    }

    CloseOutcome close(String intentId, String versionObjectId, String statementDigest,
            long entrySequence);

    /** A write whose statement never reached the ledger. */
    record Unresolved(String intentId, String repositoryId, String objectId,
                      String versionObjectId, WriteKind kind, String openedAt) {
    }

    /**
     * Open rows, oldest first.
     *
     * <p>Callers must not read an empty list from a store that could not be asked — check
     * {@link #isActive()} first. That is the same confusion the ledger's
     * {@code UNAVAILABLE} outcome exists to prevent, one layer out.
     */
    List<Unresolved> unresolved(int limit);

    /** Whether the backing store is reachable. */
    boolean isActive();

    /**
     * Rows the most recent read could not decode.
     *
     * <p>An undecodable row is not an absent gap. A caller that reported "no unresolved writes"
     * over a row it could not read would be answering a question it did not ask.
     */
    default int unreadableCount() {
        return 0;
    }

    /** The journal could not be written. Its own type so a caller cannot catch it by accident. */
    class ContentWriteJournalUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ContentWriteJournalUnavailable(String message, Throwable cause) {
            super(message, cause);
        }

        public ContentWriteJournalUnavailable(String message) {
            super(message);
        }
    }
}
