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
 * An id collision: create-if-absent found a document under this record's key whose immutable
 * payload is <em>different</em>.
 *
 * <h2>Why a 409 alone is not success and not failure</h2>
 *
 * <p>The journal's idempotency is "same {@code deliveryId} + same {@code creationPayloadDigest} =
 * the same record, already stored" (§3). A conflict where the digests match is a retry arriving
 * after its first attempt succeeded — idempotent success, no exception. A conflict where they
 * differ means two different events computed one identity: the identity rules were broken, the
 * clock was re-read on a rebuild ({@code occurredAt} moves the digest but not the id — §3 makes
 * that combination this exception on purpose), or someone tampered. None of those may be
 * absorbed silently.
 *
 * <h2>Who catches this, and what they do (§3's table)</h2>
 *
 * <table>
 *   <tr><th>path</th><th>behaviour</th></tr>
 *   <tr><td>normal emit (Slice 4's v2 emitter)</td>
 *       <td>never a 500 to the business caller (fail-open): route to the spool with metric
 *           {@code lineage.digest.mismatch}</td></tr>
 *   <tr><td>admin replay / repair</td>
 *       <td>500 — an operator is the caller, and an operator should see it</td></tr>
 * </table>
 *
 * <p>Carries the two digests. SHA-256 hex is not reversible, so the message is safe for logs —
 * unlike the payloads it summarises, which can hold external stable keys.
 */
public class LineageIntegrityException extends RuntimeException {

    private final String recordId;
    private final String expectedDigest;
    private final String storedDigest;

    public LineageIntegrityException(String recordId, String expectedDigest, String storedDigest) {
        super("id collision on journal record '" + recordId + "': stored creationPayloadDigest "
                + storedDigest + " does not match this event's " + expectedDigest
                + " — same identity, different content");
        this.recordId = recordId;
        this.expectedDigest = expectedDigest;
        this.storedDigest = storedDigest;
    }

    public String recordId() {
        return recordId;
    }

    public String expectedDigest() {
        return expectedDigest;
    }

    /** {@code null} when the stored document carries no digest at all (e.g. a v1 row). */
    public String storedDigest() {
        return storedDigest;
    }
}
