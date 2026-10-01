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

import jp.aegif.nemaki.rest.purview.journal.LineageCanonicalHash;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What happened to one version of one document's bytes (Phase 3's remaining paths).
 *
 * <p>Specified in {@code docs/design/evidence-profile-v1.md} §5.3; designed in
 * {@code docs/design/record-content-transition.md}. Shipped under the same file name as a
 * {@link RecordContentStatementV1} — the ledger entry's {@code subjectKind} says which one a
 * reader is holding.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a state statement with the digest left out. A statement of state says "the bytes are
 * these"; this says "this happened to the bytes". The two have different subjects, and a type
 * that held both would have a required field that means nothing on half its values. It has
 * <b>no {@code contentDigest}</b>, and a lock holds that it never gains one.
 *
 * <h2>{@code priorContentDigest} is copied, never computed</h2>
 *
 * <p>The digest of the bytes BEFORE the transition, copied from the statement the ledger already
 * holds for this version — together with {@link #priorStatementEntrySequence() the entry it
 * came from}, so a reader can check the copy against its source. It is never taken by reading
 * the bytes at transition time: for a destroy or a content removal there is nothing left to
 * read, and reading just before would be a different observation ("what was there when we
 * looked") passed off as the earlier statement. When the ledger has no prior statement, both
 * fields are null — "not known", which is not "none".
 *
 * <h2>{@code bytesNow}</h2>
 *
 * <p>Where the bytes are AFTER the transition. {@code UNKNOWN} is a value: the destroy path
 * does not ask cold storage whether a blob survived it ({@code LongTermStorageAdapter.delete}
 * is never called), so a destroy of a cold-moved archive says UNKNOWN rather than COLD — a
 * COLD written there would become a lie the day the destroy path changed.
 */
public record RecordContentTransitionV1(
        String repositoryId,
        String objectId,
        String versionObjectId,
        Transition transition,
        BytesNow bytesNow,
        String priorContentDigest,
        Long priorStatementEntrySequence,
        String recordedAt) implements RecordStatement {

    /**
     * What happened. One value per path that produces it; a restore (W11) is NOT here because
     * a restore receives bytes and is recorded as a {@link RecordContentStatementV1} with
     * {@code RESTORED}.
     */
    public enum Transition {
        /** W10: the bytes were copied to the archive database as the version was deleted. */
        ARCHIVED,
        /** W12 COPY: the bytes were written to cold storage and kept locally. */
        COPIED_TO_COLD,
        /** W12 MOVE: the bytes were written to cold storage and the local copy deleted. */
        MOVED_TO_COLD,
        /** W13: the archived bytes were destroyed. */
        ARCHIVE_DESTROYED,
        /**
         * W14: the archive row of a cold-moved version was destroyed. The cold blob is not
         * touched by that path, and not checked either — hence {@link BytesNow#UNKNOWN}.
         */
        ARCHIVE_DESTROYED_LEAVING_COLD_BLOB,
        /** deleteContentStream: the version no longer has content (the bytes went to W10). */
        CONTENT_REMOVED
    }

    /** Where the bytes are after the transition. */
    public enum BytesNow {
        /** In the archive database (and, after {@code COPIED_TO_COLD}, in cold storage too). */
        ARCHIVE_DB,
        /** In cold storage only. */
        COLD,
        /** Nowhere this repository knows of. */
        NONE,
        /** Not checked by the path that recorded this. Not "none" and not "somewhere". */
        UNKNOWN
    }

    /** Lowercase hex, 64 characters. Case matters: a verifier compares the bytes. */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public RecordContentTransitionV1 {
        requireText(repositoryId, "repositoryId");
        requireText(objectId, "objectId");
        requireText(versionObjectId, "versionObjectId");
        requireText(recordedAt, "recordedAt");
        if (transition == null) {
            throw new IllegalArgumentException("a transition statement with no transition says "
                    + "nothing happened, which is not a thing to put in a ledger");
        }
        if (bytesNow == null) {
            throw new IllegalArgumentException("bytesNow is required. A statement that does not "
                    + "say where the bytes are is read by each reader as whatever they expect; "
                    + "UNKNOWN is the value for 'not checked'");
        }
        if (priorContentDigest != null && !SHA256_HEX.matcher(priorContentDigest).matches()) {
            throw new IllegalArgumentException("priorContentDigest must be lowercase hex "
                    + "SHA-256 or null; got " + priorContentDigest);
        }
        // Paired: a digest with no source is a value nobody can check against anything, and a
        // source with no digest names an entry for no reason.
        if ((priorContentDigest == null) != (priorStatementEntrySequence == null)) {
            throw new IllegalArgumentException("priorContentDigest and "
                    + "priorStatementEntrySequence are set together or not at all: the digest "
                    + "is a copy, and a copy without its source cannot be checked");
        }
        if (priorStatementEntrySequence != null && priorStatementEntrySequence < 0) {
            throw new IllegalArgumentException("priorStatementEntrySequence "
                    + priorStatementEntrySequence + " is not a ledger sequence");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required: a statement missing it "
                    + "cannot be recomputed by a verifier, and a check that cannot be recomputed "
                    + "is NOT_PRESENT rather than passed");
        }
    }

    @Override
    public EvidenceLedgerEntry.SubjectKind subjectKind() {
        return EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_TRANSITION;
    }

    /**
     * The document a package ships, in the field order §5.3 lists.
     *
     * <p>Null fields are WRITTEN as null, not omitted: the canonical form has a tag for null
     * (§3.1), and a reader that finds the key absent could not tell "not known" from "an older
     * writer that did not have the field".
     */
    @Override
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("repositoryId", repositoryId);
        doc.put("objectId", objectId);
        doc.put("versionObjectId", versionObjectId);
        doc.put("transition", transition.name());
        doc.put("bytesNow", bytesNow.name());
        doc.put("priorContentDigest", priorContentDigest);
        doc.put("priorStatementEntrySequence", priorStatementEntrySequence);
        doc.put("recordedAt", recordedAt);
        return doc;
    }

    @Override
    public byte[] canonicalBytes() {
        return LineageCanonicalHash.canonicalBytes(toDocument());
    }

    @Override
    public String digest() {
        return RecordStatement.hexSha256(canonicalBytes());
    }
}
