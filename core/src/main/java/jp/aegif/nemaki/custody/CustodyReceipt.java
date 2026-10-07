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
 * A statement that arrived about one handover, and whether it is about our package (P3-4).
 *
 * <p><b>Not "what the receiving organisation sent back", which is what this said.</b> A receipt
 * arrives on a REST endpoint; nothing establishes who wrote it unless its signature could be
 * checked, and neither measured receiver signs anything. {@link #limits()}, {@link CustodyState}
 * and the endpoint's own limits were all corrected for this — and the class contract, which is
 * what a developer reads before any of them, kept the stronger version.
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
 * outcome is what the receipt reports as the finding. None of it is checkable without the SIP digest, and all
 * of it is worth having once there is one.
 *
 * <h2>The signature is carried, and not treated as verified</h2>
 *
 * <p>{@link #signature} travels because a submission agreement may require one and because
 * discarding it would make later verification impossible. <b>This class does not check it.</b>
 * A field called "signature" is routinely read as "signed and verified", so
 * {@link #signatureVerified} is separate and defaults to false. <b>False does not mean "no key
 * was configured"</b>: it is also what a check that RAN and FAILED leaves behind, and one
 * boolean cannot tell an operator which. {@code ReceiptSignatureVerifier.Checked} carries that
 * distinction and the service puts it on the response.
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
        String reportedOutcome,
        String receivingAgent,
        String receivedAt,
        String signature,
        boolean signatureVerified) {

    /**
     * For a receiver whose own word this product already judges — no mapping, nothing to keep
     * separately.
     *
     * <p>RODA is such a receiver on the field the connector reads: {@code pluginState=SUCCESS}
     * is already this product's word, so nothing is mapped and this constructor is the honest
     * one. Archivematica is not — {@code COMPLETE} has to be translated, and the other
     * constructor is what keeps its own word. (Both receivers also carry fields this product
     * deliberately does NOT read — RODA's {@code outcomeObjectState=ACTIVE}, the Storage
     * Service's {@code UPLOADED} — which is a different decision, made in
     * {@link jp.aegif.nemaki.custody.connector.ReceivingSystem}.) See {@link #reportedOutcome}.
     */
    public CustodyReceipt(String submissionId, String aipId, String aipChecksum,
            String sipDigest, String verificationOutcome, String receivingAgent,
            String receivedAt, String signature, boolean signatureVerified) {
        this(submissionId, aipId, aipChecksum, sipDigest, verificationOutcome, null,
                receivingAgent, receivedAt, signature, signatureVerified);
    }

    /**
     * The word the RECEIVER used, when it is not the word this product judges.
     *
     * <p>{@link #verificationOutcome} is read by the state machine — {@code verifyReceipt} calls
     * {@link #reportsSuccess()} on it and refuses the receipt when it does not pass. So a
     * connector for a receiver whose vocabulary differs has to put the MAPPED word there, or a
     * genuine acceptance stops the handover. Measured: Archivematica says {@code COMPLETE},
     * which is not in the accepted list (design §13.1).
     *
     * <p>That leaves the receiver's own word homeless, and losing it means never being able to
     * answer "what did they actually say?". It goes here. <b>Null means no mapping was applied
     * </b> — the receiver's word IS {@link #verificationOutcome}.
     *
     * <p><b>The signature covers this one, not the mapped one.</b> The far end signs what it
     * produced; it has never seen our vocabulary. {@link ReceiptSignatureVerifier#canonicalForm}
     * therefore uses {@code reportedOutcome} when it is present. The consequence is worth saying
     * plainly: <b>the mapped word is not covered by the far end's signature</b>. A reader who
     * wants to check the mapping has the raw word — signed — beside it, and can re-derive.
     * This product's own ledger digest commits to both.
     */
    public String reportedOutcome() {
        return reportedOutcome;
    }

    /** The word the receiver used, mapped or not. What a later conversation quotes. */
    public String asReported() {
        return reportedOutcome == null ? verificationOutcome : reportedOutcome;
    }

    /**
     * Refuses a receipt that cannot be tied to anything.
     *
     * <p>The compact constructor rejects a missing {@link #sipDigest} rather than letting one
     * through to be dealt with later: a receipt with no reference to our package is not a weak
     * receipt, it is a different object that happens to have the same fields, and admitting it
     * means every later reader has to remember to check.
     */
    public CustodyReceipt {
        // ONE representation of "nothing was mapped". A blank-but-present reportedOutcome is
        // the same hole as a forged one, one notch weaker: it reads as "the receiver said
        // something" while carrying nothing a signature or a re-derivation could work on, and
        // isDerivableMapping would wave it through as an absent mapping.
        reportedOutcome = reportedOutcome == null || reportedOutcome.isBlank()
                ? null
                : reportedOutcome;
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

    /**
     * Why the mapped word cannot be believed, or null when it can.
     *
     * <p><b>The hole this closes.</b> Splitting the outcome — the mapped word where the state
     * machine reads it, the receiver's word where the far end's signature covers it — makes
     * {@code verificationOutcome=SUCCESS} with {@code reportedOutcome=FAILED} internally
     * consistent. The signature verifies (it is over {@code FAILED}); {@link #reportsSuccess()}
     * passes (it reads {@code SUCCESS}); nothing in the receipt contradicts either. So the pair
     * has to be re-derivable from a mapping this product actually performs.
     *
     * <p>It is a method HERE, beside {@link #missingRequiredField()} and
     * {@link #refusalReasonFor(String)}, so that every path which checks a receipt checks this
     * too — including {@code restore()}, which re-applies the same rules to a row read back out
     * of the database. A row is exactly where a forged pair would be planted.
     *
     * <p><b>Wider than "catches forgeries", in one direction.</b> The rule refuses any pair it
     * cannot re-derive, and that includes an honest receipt that fills both slots with the same
     * word — {@code (SUCCESS, SUCCESS)} is refused, because when nothing was translated the
     * second slot must be empty. Two representations of "nothing was mapped" is the shape a
     * forged pair hides in, so there is exactly one. The refusal message says that rather than
     * calling it a forgery.
     *
     * <p><b>Looser than that, in the other direction:</b> the pair is checked against ALL
     * receivers this product knows, not against the one this transfer is going to. {@code COMPLETE → SUCCESS} is
     * Archivematica's mapping and would be accepted on a RODA transfer. Binding it would mean
     * matching {@code CustodyTransfer.receivingSystem} — a free-form string an operator types —
     * to the enum, which is a guess. Recorded rather than guessed at: today no receiver signs
     * anything, so a forger has no reason to prefer this route over {@code SUCCESS} with no
     * mapping at all.
     */
    public String mappingRefusalReason() {
        if (jp.aegif.nemaki.custody.connector.ReceivingSystem
                .isDerivableMapping(verificationOutcome, reportedOutcome)) {
            return null;
        }
        if (java.util.Objects.equals(verificationOutcome, reportedOutcome)) {
            // The honest-but-redundant case, diagnosed as itself rather than as a forgery.
            return "this receipt puts the same word ('" + verificationOutcome + "') in both the "
                    + "judged and the reported slot. When nothing was translated, the reported "
                    + "slot must be empty: two ways of saying 'nothing was mapped' is the shape "
                    + "a forged pair hides in.";
        }
        return "this receipt carries the reported word '" + reportedOutcome
                + "' and claims it means '" + verificationOutcome + "'. No receiver this "
                + "product knows maps those to each other. Only the first of the two can be "
                + "covered by a signature, so a mapping that cannot be re-derived is a way to "
                + "be judged on a word nobody signed.";
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
     * Whether THIS RECEIPT reports an outcome this product accepts.
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
     * what the RECEIVER has. <b>Measured</b> (design §16): the bytes came back identical to the
     * ones submitted, and the transferred resource was still there after ingest — observed
     * within one job, so how long it survives is still unknown.
     *
     * <p><b>Archivematica has the same trap with a different shape.</b> The digest nearest to
     * hand — the pointer file's PREMIS {@code messageDigest} — is of the AIP's 7z, an artefact
     * this product has never seen. Putting it here would be exactly the "AIP checksum only"
     * receipt §2 refuses. What survives of ours is the shipped bag's
     * {@code manifest-sha256.txt}, filed inside the AIP under
     * {@code data/objects/metadata/transfers/{name}-{TRANSFER uuid}/} — <b>not</b> the AIP's
     * uuid, and <b>not</b> the same-named manifest at the AIP root, which is Archivematica's own.
     * Design §13.2, measured in §17.
     *
     * <p>Archivematica 1.18.0's live {@code status} strings are {@code COMPLETE} and
     * {@code FAILED} (transfer/SIP); the stored AIP is {@code UPLOADED}; {@code check_fixity}
     * returns a boolean {@code success}. None of those are in this list, so a connector that
     * copies them here will refuse a genuine ingest — the same trap as RODA's
     * {@code outcomeObjectState=ACTIVE}. Map, or the list is wrong for that receiver.
     *
     * <p><b>Map in the connector, do not widen this list</b> (decided 2026-08-27 — design §13.1).
     * Adding {@code ACTIVE} or {@code UPLOADED} would tip the failure direction: a third
     * receiver using either word differently would be ACCEPTED rather than refused.
     *
     * <p>The MAPPED word is stored here, because this method is what {@code verifyReceipt}
     * calls; the receiver's own word goes in {@link #reportedOutcome()} beside it, so a later
     * reader can still see what was actually said. Putting them the other way round stops a
     * genuine acceptance, which is the whole reason the mapping exists.
     * {@link jp.aegif.nemaki.custody.connector.ReceivingSystem} is where the per-receiver rules
     * live.
     *
     * <p>A receipt HAS now been assembled and verified end to end against both live receivers
     * (design §16 RODA, §17 Archivematica) — RODA's {@code SUCCESS} passes unmapped, and
     * Archivematica's {@code COMPLETE} passes mapped, with the raw word kept. What is still
     * missing is the sending path, not the verification one. Being wrong in this list costs a
     * refusal of a genuine receipt, not an acceptance of a bad one, which is the direction to
     * be wrong in.
     */
    public boolean reportsSuccess() {
        return wouldReportSuccess(verificationOutcome);
    }

    /**
     * The vocabulary itself, without a receipt around it.
     *
     * <p>A connector has to ask "is this word one we accept?" while deciding what to put in the
     * receipt — before there is a receipt. It used to do that by building a throwaway one, which
     * closed a loop: {@code ReceivingSystem} built a {@code CustodyReceipt}, and
     * {@link #mappingRefusalReason()} calls back into {@code ReceivingSystem}. That worked only
     * because the throwaway had no mapping to check. Moving the forgery rule into the compact
     * constructor — the natural next step, and this rule has already moved twice — would have
     * turned it into a {@code StackOverflowError}.
     */
    public static boolean wouldReportSuccess(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return false;
        }
        String word = outcome.trim().toUpperCase(java.util.Locale.ROOT);
        return word.equals("PASSED") || word.equals("PASS") || word.equals("VALID")
                || word.equals("SUCCESS") || word.equals("ACCEPTED") || word.equals("OK");
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
     * <p>The roadmap's list of receipt contents is what this checks: submission id, AIP id,
     * target SIP digest (the constructor), verification outcome ({@link #reportsSuccess}) and
     * the receiving agent. <b>A signature is not required</b>, because this product holds no key
     * material for the far end and an unverified signature string would be theatre. That gap is
     * disclosed in {@link #limits()} on every receipt.
     *
     * <h2>{@code aipChecksum} is NOT required, and that was measured</h2>
     *
     * <p>It was, because the roadmap lists it. Running the connector against a live RODA 6.3.0
     * (P3-4 §16) showed what that costs: <b>nothing that run returned carried a checksum of
     * RODA's own AIP</b> — not the ingest report, not the AIP record — so every receipt
     * assembled from that genuine, successful ingest was refused here. Stated as what was looked
     * at, not as "RODA has none anywhere": §10 walked the analogous claim back from "RODA returns
     * no receipt" to "no receipt-shaped resource among its 26 v2 controllers", and this one is
     * load-bearing enough — it is the whole reason a required field was dropped — to deserve the
     * same discipline. A required field the receiver cannot supply is
     * not a protection; it is this product declining to finish a handover it documents.
     *
     * <p>The two alternatives were worse. Refusing to assemble in the connector says the same
     * thing one layer earlier. Hashing {@code downloadAipSubmission} and calling the result an
     * AIP checksum would be the substitution this product refuses everywhere else: those bytes
     * are the SUBMISSION, so the "AIP checksum" would be the SIP digest wearing another name,
     * and the receipt would carry the same value twice while appearing to carry two facts.
     *
     * <p><b>What IS lost, stated exactly.</b> No comparison is lost: verification is against
     * {@link #sipDigest} — what a receiver gives back about OUR package — and {@code aipChecksum} is the
     * far end's statement about its own artefact, which this product cannot recompute and never
     * compares against anything. But the field is not inert. It is inside
     * {@link ReceiptSignatureVerifier#canonicalForm}, so when a key IS available, altering it
     * breaks verification; and {@link CustodyLedgerRecorder} commits it to the ledger digest when
     * the handover is eventually recorded.
     *
     * <p><b>What this change gives up, stated without inflating it: presence.</b> Every accepted
     * receipt used to CARRY a value in this field. It does not follow that the value was signed
     * or in the ledger — {@code verifyReceipt} accepts unsigned receipts, and the ledger entry is
     * written later, by {@code passCustody}. So the guarantee lost is that the field is there;
     * whether anything covers it was always conditional on a key existing and on custody actually
     * passing.
     *
     * <p>Two earlier versions of this javadoc were wrong in opposite directions. The first said
     * "nothing here ever checked it" and "nothing is lost" — too weak. The correction said the
     * lost guarantee was of a "signed, ledger-committed statement" — too strong, and wrong in the
     * same shape it was written to fix. Both are recorded rather than quietly replaced, because
     * the second is the more instructive failure: <b>a correction can overshoot, and an
     * overshooting correction reads as extra rigour.</b> {@link #limits()} says on the receipt
     * itself when there is no checksum.
     */
    public String missingRequiredField() {
        if (submissionId == null || submissionId.isBlank()) {
            return "submissionId";
        }
        if (aipId == null || aipId.isBlank()) {
            return "aipId";
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

    /**
     * What accepting this receipt does and does not establish.
     *
     * <h2>Why the opening sentence is weaker than it could be for one receiver</h2>
     *
     * <p>It used to say the receiving system "took in THIS package". That is what was measured of
     * RODA (design §16: the bytes it returned were byte-identical to the ones submitted). It is
     * <b>stronger than what the Archivematica route supports</b>: there the value is recovered
     * from a line in {@code manifest-sha256.txt} — a file this product WROTE and the receiver
     * merely stored (§17). That the receiver kept our manifest shows they took in a bag carrying
     * it; that the payload inside still hashes to what the manifest says rests on the receiver
     * having run {@code Verify bag}, which it did on the one configuration measured and which
     * this receipt does not record.
     *
     * <p>One sentence covering both routes has to state the weaker one, so it does. The stronger
     * RODA statement is not made here. Making it would mean carrying the recovery route on the
     * receipt — a component the ledger digest and the signed form would both have to cover —
     * which is a change worth making when a route is actually wired to a caller, and not before.
     */
    public String limits() {
        StringBuilder limits = new StringBuilder(
                "A verified receipt establishes that THIS RECEIPT reports this outcome, and "
                        + "that this repository could tie it to the package it sent. Who wrote "
                        + "it is a separate question, answered only by a signature this product "
                        + "could check. It does NOT establish that any copy is intact now, that "
                        + "it will be kept, or that anyone's validation was thorough.");
        if (aipChecksum == null || aipChecksum.isBlank()) {
            // A fact about the RECEIPT, not about the receiver. The first version said "the
            // receiving system reported NO checksum", which this object cannot know: the field
            // arrives from whoever built the receipt, and it is blank when the receiver gave
            // none OR when a REST caller left it out -- and since it stopped being required,
            // that second case is ordinary. Archivematica does publish an AIP checksum (the
            // pointer file's PREMIS messageDigest), so "the receiver reported none" is not even
            // reliably true of the measured receivers.
            limits.append(" This receipt carries NO checksum of the receiver's own copy, so it "
                    + "says nothing about the integrity of what they hold. What it does say is "
                    + "only what the opening sentence says.");
        }
        if (signature == null || signature.isBlank()) {
            limits.append(" This receipt carries NO signature, so it is an unauthenticated "
                    + "statement: anything that could reach this endpoint could have sent it.");
        } else if (!signatureVerified) {
            // NOT "this product holds no key material", which is what this said. That is ONE of
            // three ways signatureVerified stays false, and ReceiptSignatureVerifier was written
            // to keep them apart: no key ("a statement about this deployment"), a check that RAN
            // and did NOT match ("this receipt is not from the holder of that key — or it has
            // been altered since it was signed"), and an unreadable signature or algorithm.
            //
            // A record cannot see which happened -- it has one boolean -- so it must not name a
            // cause. Naming the harmless one made a FAILED check read as an unconfigured
            // deployment, which is the strongest of the three read as the weakest. The finding
            // itself travels on the response beside this receipt (CustodyTransferService puts
            // signatureCheck there); this sentence says only what the receipt knows.
            limits.append(" This receipt carries a signature that is NOT marked as verified. "
                    + "That does NOT distinguish 'no key was configured for this agent' from 'a "
                    + "check ran and the signature did not match' — the receipt holds one "
                    + "boolean. See signatureCheck on the response that carried it.");
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
        body.put("reportedOutcome", reportedOutcome);
        body.put("receivingAgent", receivingAgent);
        body.put("receivedAt", receivedAt);
        body.put("hasSignature", signature != null && !signature.isBlank());
        body.put("signatureVerified", signatureVerified);
        // Immediately after the two signature fields, so a reader cannot take them alone.
        body.put("limits", limits());
        return body;
    }
}
