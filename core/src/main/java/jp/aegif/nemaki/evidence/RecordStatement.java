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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * A statement about one version of one document that the record-content ledger commits to.
 *
 * <p>Two shapes implement it: {@link RecordContentStatementV1} ("this version's bytes are
 * these") and {@link RecordContentTransitionV1} ("this happened to this version's bytes").
 * They share the parts the ledger and the journal need — the version key, the time of
 * recording, the document a package ships and its canonical digest — and the
 * {@link #subjectKind() kind} that tells a reader which shape it is holding. The kind is the
 * discriminator; nothing in the document itself is, which is why the two are separate types
 * rather than one with nullable fields (see the kind's javadoc).
 */
public sealed interface RecordStatement permits RecordContentStatementV1, RecordContentTransitionV1 {

    /** The immutable version key the statement is about. */
    String versionObjectId();

    /** When the ledger was written, not when anything happened to the source. */
    String recordedAt();

    /** The kind the ledger entry carries for this statement. */
    EvidenceLedgerEntry.SubjectKind subjectKind();

    /** The document a package ships, in the field order the specification lists. */
    Map<String, Object> toDocument();

    /** The bytes a {@code .c14n} file holds for this statement. */
    byte[] canonicalBytes();

    /** {@code hex(SHA-256(canonicalBytes()))} — what the ledger entry's {@code payloadDigest} is. */
    String digest();

    /** Lowercase hex SHA-256 of {@code bytes}: the only digest form the profile uses (§2). */
    static String hexSha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
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
