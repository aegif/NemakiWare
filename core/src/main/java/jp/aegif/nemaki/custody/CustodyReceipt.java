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
package jp.aegif.nemaki.custody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the receiving organisation sent back, and whether it is about our package (P3-4).
 *
 * <h2>An AIP checksum on its own proves nothing</h2>
 *
 * <p>The obvious receipt is "here is the checksum of the AIP we made". It establishes nothing
 * this repository can use: it is a hash of THEIR artefact, which we have never seen, so any
 * value at all satisfies it. What makes a receipt checkable is that it names <b>our</b> package
 * — {@link #sipDigest} — so the claim can be tied to something we hold.
 *
 * <p>Everything else follows from that. The submission id and AIP id are what a later
 * conversation about this record refers to; the agent is who is answerable; the verification
 * outcome is what they say they found. None of it is checkable without the SIP digest, and all
 * of it is worth having once there is one.
 *
 * <h2>The signature is carried, and not treated as verified</h2>
 *
 * <p>{@link #signature} travels because a submission agreement may require one and because
 * discarding it would make later verification impossible. <b>This class does not check it.</b>
 * A field called "signature" is routinely read as "signed and verified", so
 * {@link #signatureVerified} is separate and defaults to false — the honest state for a product
 * that has no key material for the far end.
 *
 * <h2>Why the receipt comes back later, on purpose</h2>
 *
 * <p>A bidirectional reference cannot exist at packaging time: when the SIP is built, the far
 * end's AIP id does not exist yet. So the SIP carries a chain excerpt outward, and the receipt
 * is appended to the evidence chain <b>after</b> the AIP is created and folded into the next
 * anchor. That makes later inconsistency detectable. It does not freeze anything.
 *
 * <p>Design: {@code docs/design/p3-4-custody-transfer.md}.
 */
public record CustodyReceipt(
        String submissionId,
        String aipId,
        String aipChecksum,
        String sipDigest,
        String verificationOutcome,
        String receivingAgent,
        String receivedAt,
        String signature,
        boolean signatureVerified) {

    /**
     * Refuses a receipt that cannot be tied to anything.
     *
     * <p>The compact constructor rejects a missing {@link #sipDigest} rather than letting one
     * through to be dealt with later: a receipt with no reference to our package is not a weak
     * receipt, it is a different object that happens to have the same fields, and admitting it
     * means every later reader has to remember to check.
     */
    public CustodyReceipt {
        if (sipDigest == null || sipDigest.isBlank()) {
            throw new IllegalArgumentException(
                    "a custody receipt must name the digest of the package it is about; without "
                            + "it the receipt cannot be tied to anything this repository holds, "
                            + "and an AIP checksum alone is a hash of an artefact we have never "
                            + "seen");
        }
        if (signatureVerified && (signature == null || signature.isBlank())) {
            throw new IllegalArgumentException(
                    "a receipt cannot be marked as having a verified signature when it carries "
                            + "no signature");
        }
    }

    /** Why a receipt was refused, or null when it is about the package we sent. */
    public String refusalReasonFor(String expectedSipDigest) {
        if (expectedSipDigest == null || expectedSipDigest.isBlank()) {
            return "this transfer has no recorded package digest, so no receipt can be checked "
                    + "against it. This is NOT a statement that the receipt is wrong.";
        }
        if (!expectedSipDigest.equalsIgnoreCase(sipDigest)) {
            return "the receipt is about package " + sipDigest + ", and this transfer sent "
                    + expectedSipDigest + ". A receipt for a different package says nothing "
                    + "about this one — including when it says everything went well.";
        }
        return null;
    }

    /**
     * Whether the far end says it accepted the package.
     *
     * <p>Unknown counts as NOT success. A receipt whose outcome is missing, or a word this
     * build does not recognise, is not evidence that things went well — and the state it would
     * unlock is the one before custody passes.
     *
     * <h3>What a real receiver actually says (RODA 6.3.0, measured 2026-08-27)</h3>
     *
     * <p><b>RODA has no resource that is recognisably a receipt</b> — none among its 26 v2 API
     * controllers. That is not the same as "RODA returns no receipt": a job report or an ingest
     * response could be pressed into the role, and that has not been ruled out. What is certain
     * is that a connector would have to assemble one, and the two fields it would assemble from
     * carry these vocabularies:
     *
     * <p>Both live on the SAME object — {@code org.roda.core.data.v2.jobs.Report}, the job
     * report ({@code JobReportController} is the endpoint; there is no type called
     * {@code JobReport}):
     *
     * <ul>
     *   <li>{@code Report.pluginState}: {@code SUCCESS}, {@code PARTIAL_SUCCESS},
     *       {@code FAILURE}, {@code RUNNING}, {@code SKIPPED}</li>
     *   <li>{@code Report.outcomeObjectState} ({@code AIPState}): {@code CREATED},
     *       {@code INGEST_PROCESSING}, {@code UNDER_APPRAISAL}, {@code ACTIVE},
     *       {@code DELETED}, …</li>
     * </ul>
     *
     * <p><b>Put {@code pluginState} in {@code verificationOutcome} and this list is right:</b>
     * {@code SUCCESS} passes, and {@code PARTIAL_SUCCESS} does not — which is what §1.4 of the
     * submission agreement decided independently.
     *
     * <p><b>Put {@code outcomeObjectState} in it and this list is wrong:</b> {@code ACTIVE} —
     * an AIP that completed appraisal — is not in the vocabulary, so a fully accepted deposit
     * would read as not-success. Since both fields arrive in one response body, that is a live
     * choice a connector makes, not a hypothetical.
     *
     * <p><b>And a RODA-derived receipt cannot satisfy the one field this class refuses to be
     * built without.</b> {@link #sipDigest} exists so a receipt names OUR package; RODA's
     * {@code TransferredResource} carries no checksum at all, and {@code Report} ties back by
     * {@code sourceObjectId} / {@code sourceObjectOriginalName} — <b>by name, not by content</b>.
     * So a connector built only from response FIELDS would have to fill {@code sipDigest} from
     * our own record, at which point {@link #refusalReasonFor} compares our value against
     * itself and establishes nothing — the shape §2 of the design doc exists to prevent.
     *
     * <p><b>That is a trap, not a dead end.</b> RODA does serve the bytes it holds:
     * {@code GET /api/v2/transfers/{uuid}/download} and {@code AIPController}'s
     * {@code download/submission}. A connector should fetch and hash those, so the digest is of
     * what the RECEIVER has. Unverified: whether those bytes are identical to what was sent, and
     * whether the transferred resource still exists when the receipt is assembled.
     *
     * <p>Archivematica's vocabulary is still unmeasured, and no receipt has yet been assembled
     * and verified end to end. Being wrong here costs a refusal of a genuine receipt, not an
     * acceptance of a bad one, which is the direction to be wrong in.
     */
    public boolean reportsSuccess() {
        if (verificationOutcome == null || verificationOutcome.isBlank()) {
            return false;
        }
        String outcome = verificationOutcome.trim().toUpperCase(java.util.Locale.ROOT);
        return outcome.equals("PASSED") || outcome.equals("PASS") || outcome.equals("VALID")
                || outcome.equals("SUCCESS") || outcome.equals("ACCEPTED") || outcome.equals("OK");
    }

    /**
     * The first identifying field this receipt does not have, or null when it has them all.
     *
     * <p>Separate from the compact constructor on purpose. A receipt object with gaps is worth
     * keeping — it is what arrived, and discarding it loses the fact that something arrived —
     * but it must not be enough to move to RECEIPT_VERIFIED. That state says "we checked", and
     * the next state along passes custody; passing custody to nobody in particular, at no
     * stated time, is not a handover anyone can later ask about.
     *
     * <p>The roadmap's list of receipt contents is what this checks: submission id, AIP id, AIP
     * checksum, target SIP digest (the constructor), verification outcome
     * ({@link #reportsSuccess}) and the receiving agent. <b>A signature is not required</b>,
     * because this product holds no key material for the far end and an unverified signature
     * string would be theatre. That gap is disclosed in {@link #limits()} on every receipt.
     */
    public String missingRequiredField() {
        if (submissionId == null || submissionId.isBlank()) {
            return "submissionId";
        }
        if (aipId == null || aipId.isBlank()) {
            return "aipId";
        }
        if (aipChecksum == null || aipChecksum.isBlank()) {
            return "aipChecksum";
        }
        if (receivingAgent == null || receivingAgent.isBlank()) {
            return "receivingAgent";
        }
        if (receivedAt == null || receivedAt.isBlank()) {
            return "receivedAt";
        }
        return null;
    }

    /** Whether this receipt refers to the package this transfer actually sent. */
    public boolean isAbout(String expectedSipDigest) {
        return refusalReasonFor(expectedSipDigest) == null;
    }

    /** What accepting this receipt does and does not establish. */
    public String limits() {
        StringBuilder limits = new StringBuilder(
                "A verified receipt establishes that the receiving system took in THIS package "
                        + "and reported this outcome. It does NOT establish that their copy is "
                        + "intact now, that they will keep it, or that their validation was "
                        + "thorough — those are their processes, reported by them.");
        if (signature == null || signature.isBlank()) {
            limits.append(" This receipt carries NO signature, so it is an unauthenticated "
                    + "statement: anything that could reach this endpoint could have sent it.");
        } else if (!signatureVerified) {
            limits.append(" This receipt carries a signature that has NOT been verified — this "
                    + "product holds no key material for the receiving agent — so it is stored "
                    + "for later checking and adds nothing today.");
        }
        return limits.toString();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("submissionId", submissionId);
        body.put("aipId", aipId);
        body.put("aipChecksum", aipChecksum);
        body.put("sipDigest", sipDigest);
        body.put("verificationOutcome", verificationOutcome);
        body.put("receivingAgent", receivingAgent);
        body.put("receivedAt", receivedAt);
        body.put("hasSignature", signature != null && !signature.isBlank());
        body.put("signatureVerified", signatureVerified);
        // Immediately after the two signature fields, so a reader cannot take them alone.
        body.put("limits", limits());
        return body;
    }
}
