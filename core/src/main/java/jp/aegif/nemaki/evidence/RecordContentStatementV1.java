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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What one version of one document's bytes were, at the moment they were recorded (E1).
 *
 * <p>Specified in {@code docs/design/evidence-profile-v1.md} §5.3. This is the document a
 * package ships as {@code record-content-statement.json}, and its canonical digest is what the
 * ledger entry commits to.
 *
 * <h2>Why the ledger hashes THIS and not the bytes</h2>
 *
 * <p>A digest of the bytes alone matches any document with the same content — it says "these
 * bytes existed", not "these bytes were the content of this version of this object". The
 * statement carries the version key, the length and the kind of commitment, so the entry
 * commits to a fact about a record rather than to an anonymous blob.
 *
 * <h2>What it does not say</h2>
 *
 * <ul>
 * <li><b>{@code OBSERVED} is not {@code CAPTURED}.</b> An observation says the bytes were there
 *     when someone looked; it says nothing about the time between receipt and that look. A
 *     reader must never render the two the same way.</li>
 * <li><b>{@code recordedAt} is when the ledger was written</b>, not when the source created
 *     anything. The source's own timestamp is its claim, not this repository's observation.</li>
 * </ul>
 */
public record RecordContentStatementV1(
        String repositoryId,
        String objectId,
        String versionObjectId,
        String contentStreamId,
        String contentDigest,
        long contentLength,
        CommitmentKind commitmentKind,
        String captureIntentId,
        String recordedAt) implements RecordStatement {

    /** How the bytes came to be recorded. The four are not interchangeable. */
    public enum CommitmentKind {
        /** Recorded as the content was first stored for this version. */
        CAPTURED,
        /** Recorded as the content of an existing version was replaced in place. */
        UPDATED,
        /**
         * Recorded by looking at what is stored now, some time after it was stored.
         *
         * <p>Covers "existed at this observation, and was anchored after it" and nothing
         * earlier. Backfilling history as {@code CAPTURED} would manufacture evidence.
         */
        OBSERVED,
        /**
         * Recorded as the content was put back from the archive (W11).
         *
         * <p>A fourth kind, not {@code CAPTURED} and not {@code UPDATED}: the first would read
         * as "these were the version's first bytes", the second as "the version's bytes were
         * replaced". Both are false of a restore — the bytes are the ones the archive held,
         * written back to a version that had none. The digest is taken on the bytes as they
         * are written back (one pass, ADR E1 decision 2), which is why a restore is a state
         * statement and not a {@link RecordContentTransitionV1}: there ARE received bytes.
         */
        RESTORED
    }

    @Override
    public EvidenceLedgerEntry.SubjectKind subjectKind() {
        return EvidenceLedgerEntry.SubjectKind.RECORD_CONTENT_STATE;
    }

    /** Lowercase hex, 64 characters. Case matters: a verifier compares the bytes. */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public RecordContentStatementV1 {
        requireText(repositoryId, "repositoryId");
        requireText(objectId, "objectId");
        // The version key is the whole point: a statement about "the latest version" is a
        // statement about whatever the repository decides later, which is not evidence.
        requireText(versionObjectId, "versionObjectId");
        requireText(recordedAt, "recordedAt");
        if (commitmentKind == null) {
            throw new IllegalArgumentException("a statement with no commitment kind cannot say "
                    + "whether it was captured, updated or merely observed, and those are not "
                    + "the same claim");
        }
        if (contentDigest == null || !SHA256_HEX.matcher(contentDigest).matches()) {
            throw new IllegalArgumentException("contentDigest must be lowercase hex SHA-256; "
                    + "got " + contentDigest + ". Upper case would hash to different canonical "
                    + "bytes than the same digest written the other way");
        }
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength " + contentLength + " is negative, "
                    + "which is not a length this statement can be about");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required: a statement missing it "
                    + "cannot be recomputed by a verifier, and a check that cannot be recomputed "
                    + "is NOT_PRESENT rather than passed");
        }
    }

    /**
     * The document a package ships, in the field order §5.3 lists.
     *
     * <p>The ORDER here is for a reader. It does not affect the digest — the canonical encoding
     * sorts map keys by UTF-8 bytes — and a change to it is therefore not a change to the
     * evidence.
     */
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("repositoryId", repositoryId);
        doc.put("objectId", objectId);
        doc.put("versionObjectId", versionObjectId);
        doc.put("contentStreamId", contentStreamId);
        doc.put("contentDigest", contentDigest);
        doc.put("contentLength", contentLength);
        doc.put("commitmentKind", commitmentKind.name());
        doc.put("captureIntentId", captureIntentId);
        doc.put("recordedAt", recordedAt);
        return doc;
    }

    /** The bytes a {@code .c14n} file holds for this statement. */
    public byte[] canonicalBytes() {
        return LineageCanonicalHash.canonicalBytes(toDocument());
    }

    /**
     * {@code hex(SHA-256(canonicalBytes()))} — what the ledger entry's {@code payloadDigest} is.
     *
     * <p>Computed from the map, not from serialised JSON, so the product never depends on which
     * JSON writer it used. A lock checks the other direction: that writing this document as
     * JSON and reading it back through {@link CanonicalJson} produces the same bytes. If that
     * ever stopped holding, the package would ship a statement whose digest no third party
     * could reproduce.
     */
    public String digest() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes());
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
