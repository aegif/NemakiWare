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
    private final boolean independentlyVerifiable;
    private final AnchorKind.TimeSemantics timeSemantics;

    private AnchorReceipt(AnchorKind kind, AnchorStatus status, String anchoredDigest,
                          Instant attemptedAt, Instant anchoredAt, byte[] proof, String proofDigest,
                          Map<String, String> attributes, String failureReason,
                          boolean independentlyVerifiable, AnchorKind.TimeSemantics timeSemantics) {
        this.independentlyVerifiable = independentlyVerifiable;
        this.timeSemantics = timeSemantics;
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

    /**
     * A proof that is complete and checkable.
     *
     * @param independentlyVerifiable whether a third party can check this proof WITHOUT trusting
     *        this deployment. Not implied by the destination: a self-hosted or untrusted RFC 3161
     *        service is as operator-controlled as the catalog is, so the target establishes this
     *        and says so here rather than the enum deciding for it (external review, 3.4).
     * @param timeSemantics what the anchor's time may be read as. Per receipt because an RFC 3161
     *        token that omits {@code accuracy} does not support the bidirectional claim its kind
     *        normally would.
     */
    public static AnchorReceipt confirmed(AnchorKind kind, String anchoredDigest, Instant attemptedAt,
                                          Instant anchoredAt, byte[] proof, String proofDigest,
                                          Map<String, String> attributes,
                                          boolean independentlyVerifiable,
                                          AnchorKind.TimeSemantics timeSemantics) {
        if (proof == null || proof.length == 0) {
            // A confirmed receipt with nothing to check is a contradiction — and exactly the
            // shape an evidence report would happily render as "confirmed".
            throw new IllegalArgumentException("a CONFIRMED receipt requires a non-empty proof");
        }
        return new AnchorReceipt(kind, AnchorStatus.CONFIRMED, anchoredDigest, attemptedAt,
                anchoredAt, proof, proofDigest, attributes, null,
                independentlyVerifiable, timeSemantics);
    }

    /**
     * Accepted but not yet verifiable. {@code anchoredAt} is deliberately absent: the anchor
     * time is not known until the commitment confirms, and filling in "now" would state a time
     * the proof does not support.
     */
    public static AnchorReceipt pending(AnchorKind kind, String anchoredDigest, Instant attemptedAt,
                                        byte[] proof, String proofDigest, Map<String, String> attributes) {
        return new AnchorReceipt(kind, AnchorStatus.PENDING, anchoredDigest, attemptedAt,
                null, proof, proofDigest, attributes, null, false, AnchorKind.TimeSemantics.NOT_A_TIME_PROOF);
    }

    public static AnchorReceipt failed(AnchorKind kind, String anchoredDigest, Instant attemptedAt,
                                       String failureReason) {
        return new AnchorReceipt(kind, AnchorStatus.FAILED, anchoredDigest, attemptedAt,
                null, null, null, Map.of(), failureReason, false, AnchorKind.TimeSemantics.NOT_A_TIME_PROOF);
    }

    public static AnchorReceipt notConfigured(AnchorKind kind, String anchoredDigest) {
        return new AnchorReceipt(kind, AnchorStatus.NOT_CONFIGURED, anchoredDigest, null,
                null, null, null, Map.of(), null, false, AnchorKind.TimeSemantics.NOT_A_TIME_PROOF);
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
     * What this anchor's time may be read as. Usually the kind's default, but an RFC 3161 token
     * without {@code accuracy} is downgraded to {@link AnchorKind.TimeSemantics#UPPER_BOUND_ONLY}
     * rather than claiming a precision the token never stated.
     */
    public AnchorKind.TimeSemantics timeSemantics() {
        // Non-confirmed receipts carry NOT_A_TIME_PROOF regardless of kind: a failed or pending
        // RFC 3161 receipt has no token, no time and no accuracy, so reporting its kind's usual
        // BIDIRECTIONAL_WITHIN_ACCURACY would describe a proof that does not exist
        // (external review, 3.4).
        return timeSemantics;
    }

    /**
     * Whether we hold something a third party could check <em>without our cooperation</em>.
     *
     * <h3>Why this is not called "independent"</h3>
     *
     * <p>Three review rounds all landed on the same wall: this system cannot establish that an
     * anchor is organizationally independent of the operator, and every attempt to compute it
     * was derivable by the operator. Verify a certificate chain and an operator can run their
     * own TSA and configure its certificate as the anchor. Require a declared accreditation and
     * the operator writes the declaration. Trust the sidecar's verification and the operator
     * runs the sidecar. Each fix moved the assumption without removing it.
     *
     * <p>So the claim is abandoned rather than relabelled. What this deployment can honestly
     * assert is not "this is independent" but "we kept the artifact by which YOU can decide":
     * a complete OpenTimestamps proof that verifies against Bitcoin block headers, or a
     * time-stamp token carrying the certificate needed to check its signature. Whether the
     * issuer is a genuine third party is then the reader's judgement about the world, made with
     * evidence in hand — which is exactly what an auditor is for, and is the one thing no
     * amount of code here can do on their behalf.
     *
     * <p>The evidence report renders this as a preserved artifact plus the verification
     * procedure, never as a boolean called "independent".
     */
    public boolean preservesIndependentlyCheckableArtifact() {
        return kind.independentOfOperator()
                && proof != null && proof.length > 0
                && (status == AnchorStatus.CONFIRMED || "true".equals(attributes.get("proofComplete")));
    }

    @Override
    public String toString() {
        return "AnchorReceipt[" + kind + " " + status
                + (anchoredAt != null ? " at " + anchoredAt : "")
                + (failureReason != null ? " (" + failureReason + ")" : "") + "]";
    }
}
