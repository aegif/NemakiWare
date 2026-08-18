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
package jp.aegif.nemaki.rest.purview.anchor;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What came back from anchoring one digest, in a form that is still useful years later.
 *
 * <p>This is deliberately more than "it worked". Long-term verification fails for boring
 * reasons — the proof was kept but the certificate chain was not, or revocation data was never
 * captured and cannot be reconstructed after the fact — so the receipt carries the material
 * needed to check the anchor later, not merely the fact that it was made.
 *
 * <p>{@code proof} is the opaque bytes a verifier needs (a {@code .ots} file, a DER-encoded
 * time-stamp token). It is kept as bytes rather than parsed into fields because the verifier of
 * record is the external tool, not us: re-serializing our own interpretation would substitute
 * our reading of the proof for the proof itself.
 */
public final class AnchorReceipt {

    private final AnchorKind kind;
    private final AnchorStatus status;
    private final String anchoredDigest;
    private final Instant attemptedAt;
    private final Instant anchoredAt;
    private final byte[] proof;
    private final String proofDigest;
    private final Map<String, String> attributes;
    private final String failureReason;

    private AnchorReceipt(AnchorKind kind, AnchorStatus status, String anchoredDigest,
                          Instant attemptedAt, Instant anchoredAt, byte[] proof, String proofDigest,
                          Map<String, String> attributes, String failureReason) {
        this.kind = kind;
        this.status = status;
        this.anchoredDigest = anchoredDigest;
        this.attemptedAt = attemptedAt;
        this.anchoredAt = anchoredAt;
        this.proof = proof == null ? null : proof.clone();
        this.proofDigest = proofDigest;
        this.attributes = attributes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.failureReason = failureReason;
    }

    public static AnchorReceipt confirmed(AnchorKind kind, String anchoredDigest, Instant attemptedAt,
                                          Instant anchoredAt, byte[] proof, String proofDigest,
                                          Map<String, String> attributes) {
        return new AnchorReceipt(kind, AnchorStatus.CONFIRMED, anchoredDigest, attemptedAt,
                anchoredAt, proof, proofDigest, attributes, null);
    }

    /**
     * Accepted but not yet verifiable. {@code anchoredAt} is deliberately absent: the anchor
     * time is not known until the commitment confirms, and filling in "now" would state a time
     * the proof does not support.
     */
    public static AnchorReceipt pending(AnchorKind kind, String anchoredDigest, Instant attemptedAt,
                                        byte[] proof, String proofDigest, Map<String, String> attributes) {
        return new AnchorReceipt(kind, AnchorStatus.PENDING, anchoredDigest, attemptedAt,
                null, proof, proofDigest, attributes, null);
    }

    public static AnchorReceipt failed(AnchorKind kind, String anchoredDigest, Instant attemptedAt,
                                       String failureReason) {
        return new AnchorReceipt(kind, AnchorStatus.FAILED, anchoredDigest, attemptedAt,
                null, null, null, Map.of(), failureReason);
    }

    public static AnchorReceipt notConfigured(AnchorKind kind, String anchoredDigest) {
        return new AnchorReceipt(kind, AnchorStatus.NOT_CONFIGURED, anchoredDigest, null,
                null, null, null, Map.of(), null);
    }

    public AnchorKind kind() {
        return kind;
    }

    public AnchorStatus status() {
        return status;
    }

    /** The digest that was anchored, lowercase hex. */
    public String anchoredDigest() {
        return anchoredDigest;
    }

    /** When this deployment made the attempt. Its own clock — evidence of nothing by itself. */
    public Instant attemptedAt() {
        return attemptedAt;
    }

    /**
     * The time the ANCHOR attests, present only once {@link AnchorStatus#CONFIRMED}. Read it
     * together with {@link AnchorKind#timeSemantics()}: for OpenTimestamps this is an upper
     * bound, not the moment of anchoring.
     */
    public Instant anchoredAt() {
        return anchoredAt;
    }

    /** The verifier's input (.ots file, DER time-stamp token), or null when there is none. */
    public byte[] proof() {
        return proof == null ? null : proof.clone();
    }

    /** SHA-256 of {@link #proof()}, so a report can reference the proof without embedding it. */
    public String proofDigest() {
        return proofDigest;
    }

    /**
     * Everything a verifier needs beyond the proof bytes — TSA policy OID, accuracy, serial,
     * whether the certificate chain came with the token, when revocation data was captured,
     * which calendars were used. Kept as a string map rather than typed per kind because the
     * evidence report is the consumer and it stores them as strings anyway.
     */
    public Map<String, String> attributes() {
        return attributes;
    }

    /** Why it failed, for {@link AnchorStatus#FAILED}. Null otherwise. */
    public String failureReason() {
        return failureReason;
    }

    /**
     * Whether this receipt supports a claim of independence from the operator. Requires BOTH an
     * independent target and a confirmed proof: a pending OpenTimestamps commitment is not yet
     * evidence of anything, however independent its destination.
     */
    public boolean supportsIndependenceClaim() {
        return kind.independentOfOperator() && status == AnchorStatus.CONFIRMED;
    }

    @Override
    public String toString() {
        return "AnchorReceipt[" + kind + " " + status
                + (anchoredAt != null ? " at " + anchoredAt : "")
                + (failureReason != null ? " (" + failureReason + ")" : "") + "]";
    }
}
