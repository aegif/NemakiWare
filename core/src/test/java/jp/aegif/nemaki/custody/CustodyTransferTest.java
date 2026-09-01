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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custody does not pass because the other end said so.
 *
 * <h2>The failure this is built against</h2>
 *
 * <p>Not a lost package — that one is visible. The quiet one is a transfer marked complete on a
 * receipt nobody checked: a positive-sounding document about a different submission, or an
 * AIP checksum that satisfies nothing because it hashes an artefact this repository has never
 * seen. Either way the record leaves and a state machine says everything went well.
 */
class CustodyTransferTest {

    private static final String SIP_DIGEST = "a".repeat(64);

    private static CustodyTransfer transfer() {
        return new CustodyTransfer("t-1", "bedroom", "doc-1", SIP_DIGEST, "RODA",
                "2026-08-26T00:00:00Z");
    }

    private static CustodyReceipt receiptFor(String sipDigest) {
        return new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), sipDigest, "PASSED",
                "roda-agent", "2026-08-26T01:00:00Z", null, false);
    }

    private static CustodyTransfer atAipCreated() {
        CustodyTransfer transfer = transfer();
        for (CustodyState next : List.of(CustodyState.SENT, CustodyState.RECEIVED,
                CustodyState.VALIDATED, CustodyState.INGEST_ACCEPTED,
                CustodyState.AIP_CREATED)) {
            CustodyTransfer.Moved moved = transfer.advance(next, "2026-08-26T00:10:00Z", "step");
            assertTrue(moved.accepted(), moved.refusedReason());
        }
        return transfer;
    }

    // ---- the receipt is the gate ----

    @Test
    @DisplayName("a mapped word nobody could derive is refused BY THE TYPE")
    void aForgedMappingIsRefusedByTheType() {
        // Deliberately at the type, not through the service. The rule was written in the
        // service first, and a service-level test could not tell the difference -- it stays
        // green whichever layer holds the check. This is the one that measures the placement,
        // and the placement is the point: restore() re-applies the type's checks to a row read
        // out of the database, so a rule living one layer up would leave that open.
        CustodyTransfer transfer = atAipCreated();

        // Internally consistent: the signature (were there one) covers FAILED, reportsSuccess()
        // reads SUCCESS, and nothing else in the receipt contradicts either.
        CustodyTransfer.Moved moved = transfer.verifyReceipt(
                new CustodyReceipt("sub-1", "aip-1", "c".repeat(64), SIP_DIGEST, "SUCCESS",
                        "FAILED", "am-agent", "2026-08-27T00:00:00Z", null, false),
                "2026-08-27T00:00:00Z");

        assertFalse(moved.accepted(),
                "the type accepted a mapping no receiver performs, so a receipt could be judged "
                        + "on a word the far end never said");
        assertEquals(CustodyState.AIP_CREATED, transfer.state());
    }

    @Test
    @DisplayName("a genuine mapping is accepted BY THE TYPE")
    void aGenuineMappingIsAcceptedByTheType() {
        CustodyTransfer transfer = atAipCreated();

        CustodyTransfer.Moved moved = transfer.verifyReceipt(
                new CustodyReceipt("sub-1", "aip-1", "c".repeat(64), SIP_DIGEST, "SUCCESS",
                        "COMPLETE", "am-agent", "2026-08-27T00:00:00Z", null, false),
                "2026-08-27T00:00:00Z");

        assertTrue(moved.accepted(), moved.refusedReason());
        assertEquals(CustodyState.RECEIPT_VERIFIED, transfer.state());
    }

    @Test
    @DisplayName("a receipt about a DIFFERENT package is refused, however positive it is")
    void aReceiptForAnotherPackageIsRefused() {
        // The whole protection. "Everything went well" about somebody else's record moves
        // custody off this repository on the strength of a document about a different thing.
        CustodyTransfer transfer = atAipCreated();

        CustodyTransfer.Moved moved = transfer.verifyReceipt(
                receiptFor("c".repeat(64)), "2026-08-26T02:00:00Z");

        assertFalse(moved.accepted(),
                "a receipt about another package was accepted, so custody would pass on a "
                        + "document that says nothing about this record");
        assertEquals(CustodyState.AIP_CREATED, transfer.state());
        assertTrue(moved.refusedReason().contains("says nothing about this one"),
                moved.refusedReason());
    }

    @Test
    @DisplayName("the package digest is compared without regard to hex case")
    void theDigestComparisonIgnoresHexCase() {
        // Deliberate: the same SHA-256 written upper- and lower-case is the same digest, and a
        // receipt that spelled it the other way would otherwise be refused as being about a
        // different package. Pinned because the alternative — a case-sensitive compare — looks
        // equally reasonable in review and would reject correct receipts.
        CustodyTransfer transfer = atAipCreated();

        CustodyTransfer.Moved moved = transfer.verifyReceipt(
                receiptFor(SIP_DIGEST.toUpperCase(java.util.Locale.ROOT)),
                "2026-08-26T02:00:00Z");

        assertTrue(moved.accepted(),
                "a receipt naming the same digest in upper case was refused as being about "
                        + "another package: " + moved.refusedReason());
    }

    @Test
    @DisplayName("a receipt about THIS package is accepted — the control")
    void aReceiptForThisPackageIsAccepted() {
        // Without this, refusing everything would pass the test above and no transfer could
        // ever complete.
        CustodyTransfer transfer = atAipCreated();

        CustodyTransfer.Moved moved = transfer.verifyReceipt(
                receiptFor(SIP_DIGEST), "2026-08-26T02:00:00Z");

        assertTrue(moved.accepted(), moved.refusedReason());
        assertEquals(CustodyState.RECEIPT_VERIFIED, transfer.state());
        assertNotNull(transfer.receipt());
    }

    @Test
    @DisplayName("a receipt that names no package cannot be built at all")
    void aReceiptWithoutASipDigestIsRefusedAtConstruction() {
        // An AIP checksum alone is a hash of an artefact we have never seen, so ANY value
        // satisfies it. Admitting such a receipt means every later reader has to remember to
        // check; refusing it at construction means none of them does.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), null, "PASSED",
                        "roda-agent", "2026-08-26T01:00:00Z", null, false));

        assertTrue(refused.getMessage().contains("never seen"), refused.getMessage());
    }

    @Test
    @DisplayName("RECEIPT_VERIFIED cannot be walked into — only verifyReceipt reaches it")
    void receiptVerifiedIsNotAnOrdinaryMove() {
        // The state means "we CHECKED". advance() checks nothing, so a transfer that walked
        // into it would be named "verified" with no receipt in it — the machine whose whole
        // claim is that the state you are stuck in IS the diagnosis, giving a false one.
        // Custody would still be blocked one step later, so the damage is a lying state rather
        // than a lost record; the natural way to drive a sequence is to advance through it.
        CustodyTransfer transfer = atAipCreated();

        CustodyTransfer.Moved moved = transfer.advance(CustodyState.RECEIPT_VERIFIED,
                "2026-08-26T02:00:00Z", "assume it is fine");

        assertFalse(moved.accepted(),
                "a transfer was advanced into RECEIPT_VERIFIED with nothing checked");
        assertEquals(CustodyState.AIP_CREATED, transfer.state());
        assertNull(transfer.receipt());
        assertFalse(CustodyState.AIP_CREATED.allowedNext()
                        .contains(CustodyState.RECEIPT_VERIFIED),
                "the state machine still offers RECEIPT_VERIFIED as an ordinary move");
        assertTrue(CustodyState.RECEIPT_VERIFIED.isReachableFrom(CustodyState.AIP_CREATED),
                "verifyReceipt has no route either, so the transfer can never complete");
    }

    @Test
    @DisplayName("a receipt reporting REJECTED does not become 'verified'")
    void aNegativeReceiptIsNotVerification() {
        // It is about our package, so the digest check passes. It says the far end did not
        // accept it. Calling that "verified" names a check that came back negative as a step
        // towards custody passing.
        CustodyTransfer transfer = atAipCreated();

        CustodyTransfer.Moved moved = transfer.verifyReceipt(
                new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), SIP_DIGEST, "REJECTED",
                        "roda-agent", "2026-08-26T01:00:00Z", null, false),
                "2026-08-26T02:00:00Z");

        assertFalse(moved.accepted(), "a rejection was accepted as a verification");
        assertEquals(CustodyState.AIP_CREATED, transfer.state());
        assertTrue(moved.refusedReason().contains("REJECTED"), moved.refusedReason());
    }

    @Test
    @DisplayName("an outcome this build does not recognise is not success")
    void anUnknownOutcomeIsNotSuccess() {
        // "We do not know what they said" must not unlock the state before custody passes.
        CustodyTransfer transfer = atAipCreated();

        assertFalse(transfer.verifyReceipt(
                new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), SIP_DIGEST, "WEIRD",
                        "roda-agent", "t", null, false), "t").accepted());
        assertFalse(transfer.verifyReceipt(
                new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), SIP_DIGEST, null,
                        "roda-agent", "t", null, false), "t").accepted(),
                "a receipt with no outcome at all was treated as a pass");
    }

    @Test
    @DisplayName("a receipt arriving after custody passed does not rewrite the handover")
    void aLateReceiptDoesNotRewriteTheHandover() {
        CustodyTransfer transfer = atAipCreated();
        transfer.verifyReceipt(receiptFor(SIP_DIGEST), "t");
        // passCustody, not advance: advance refuses this state so that the ledger cannot be
        // skipped, and a fixture using it would be exercising a path the product refuses.
        assertTrue(transfer.passCustody("t", "receipt verified").accepted());

        CustodyTransfer.Moved late = transfer.verifyReceipt(
                new CustodyReceipt("sub-2", "aip-9", "c".repeat(64), SIP_DIGEST, "PASSED",
                        "roda-agent", "t", null, false), "t");

        assertFalse(late.accepted(), "a later receipt replaced the one custody passed on");
        assertEquals("aip-1", transfer.receipt().aipId());
        // On the REASON, because the state machine refuses this anyway — RECEIPT_VERIFIED is
        // not reachable from CUSTODY_TRANSFERRED — and would refuse it with "this transfer is
        // at CUSTODY_TRANSFERRED", which reads as a sequencing slip. It is not one. Somebody
        // is presenting a receipt for a handover that is over, and the operator needs to be
        // told that rather than left to work out why the step is out of order.
        assertTrue(late.refusedReason().contains("custody has already passed"),
                "the refusal is right but describes it as a wrong-order step: " + late.refusedReason());
    }

    @Test
    @DisplayName("a SECOND receipt before custody passes is refused too")
    void aReplacementReceiptIsRefusedBeforeCustodyPasses() {
        // The window between verifying and passing custody. Nothing about "custody has already
        // passed" covers it, so this is the state machine's job: RECEIPT_VERIFIED is not
        // reachable from RECEIPT_VERIFIED, and the first receipt is the one that was checked.
        CustodyTransfer transfer = atAipCreated();
        assertTrue(transfer.verifyReceipt(receiptFor(SIP_DIGEST), "t").accepted());

        CustodyTransfer.Moved second = transfer.verifyReceipt(
                new CustodyReceipt("sub-2", "aip-9", "c".repeat(64), SIP_DIGEST, "PASSED",
                        "roda-agent", "t", null, false), "t");

        assertFalse(second.accepted(), "the checked receipt was replaced by a later one");
        assertEquals("aip-1", transfer.receipt().aipId());
    }

    @Test
    @DisplayName("a receipt that names only our package is not enough to verify")
    void aReceiptMustNameWhoIsAnswerable() {
        // It passed before: the constructor requires only the SIP digest, and verifyReceipt
        // checked the digest and the outcome. So a receipt saying "OK, digest X" and nothing
        // else reached RECEIPT_VERIFIED, after which an ordinary advance passes custody — to
        // nobody in particular, at no stated time, with no submission or AIP id to ask about
        // it by. The state machine's own claim is that the state you are in IS the diagnosis,
        // and "we checked" over that receipt is a false one.
        CustodyTransfer transfer = atAipCreated();

        CustodyTransfer.Moved moved = transfer.verifyReceipt(
                new CustodyReceipt(null, null, null, SIP_DIGEST, "OK", null, null, null, false),
                "2026-08-26T02:00:00Z");

        assertFalse(moved.accepted(), "an anonymous receipt was accepted as verification");
        assertEquals(CustodyState.AIP_CREATED, transfer.state());
        assertTrue(moved.refusedReason().contains("submissionId"), moved.refusedReason());
    }

    @Test
    @DisplayName("each identifying field is required — not just the first one checked")
    void everyIdentifyingFieldIsRequired() {
        // A guard that only ever fires on one field would let the other four through, and the
        // test above would still pass.
        record Case(String field, CustodyReceipt receipt) {}
        List<Case> cases = List.of(
                new Case("submissionId", new CustodyReceipt(null, "aip-1", "b".repeat(64),
                        SIP_DIGEST, "PASSED", "roda-agent", "t", null, false)),
                new Case("aipId", new CustodyReceipt("sub-1", "  ", "b".repeat(64),
                        SIP_DIGEST, "PASSED", "roda-agent", "t", null, false)),
                // aipChecksum is deliberately NOT here — see
                // aReceiptWithNoAipChecksumIsStillVerified below.
                new Case("receivingAgent", new CustodyReceipt("sub-1", "aip-1", "b".repeat(64),
                        SIP_DIGEST, "PASSED", "", "t", null, false)),
                new Case("receivedAt", new CustodyReceipt("sub-1", "aip-1", "b".repeat(64),
                        SIP_DIGEST, "PASSED", "roda-agent", null, null, false)));

        for (Case each : cases) {
            CustodyTransfer transfer = atAipCreated();
            CustodyTransfer.Moved moved = transfer.verifyReceipt(each.receipt(), "t");
            assertFalse(moved.accepted(),
                    "a receipt with no " + each.field() + " was accepted as verification");
            assertTrue(moved.refusedReason().contains(each.field()),
                    "the refusal blamed the wrong field for a receipt missing "
                            + each.field() + ": " + moved.refusedReason());
        }
    }

    @Test
    @DisplayName("a receipt's limits attribute the report to the RECEIPT, not to the receiver")
    void theLimitsDoNotAttributeAnUnsignedReceiptToTheReceiver() {
        // The opening sentence said "the receiving system reported this outcome" while the same
        // string ended with "anything that could reach this endpoint could have sent it". Three
        // review rounds each caught ONE exit of that claim -- the release note, this opening, the
        // checksum suffix, the state's text, the controller's -- and each fix was locked except
        // this one, so reverting the opening alone stayed green.
        String limits = receiptFor(SIP_DIGEST).limits();

        // "the receiving system reported" is NOT what stood here. Checked against the commit
        // that carried it (c419f2941): the sentence was "the receiving system TOOK IN THIS
        // package". So this assertion could never fire — a lock written against a string
        // nobody had ever typed, which is a lock on nothing. The whole guarantee rested on the
        // assertTrue below.
        //
        // What has to be true is broader than either phrasing: the receiver must not be named
        // as the author of an outcome that arrived over an endpoint.
        assertFalse(limits.contains("the receiving system"),
                "an unsigned receipt's own limits attribute it to the receiving system, in the "
                        + "same sentence that goes on to say anyone could have sent it: " + limits);
        assertTrue(limits.contains("THIS RECEIPT reports"),
                "the limits do not say whose statement this is: " + limits);
    }

    @Test
    @DisplayName("an unrecognised word is not reported to the operator as a rejection")
    void anUnrecognisedWordIsNotCalledARejection() {
        // Archivematica reporting the literal "SUCCESS" — a word it was never measured to use —
        // becomes UNRECOGNISED_BY_CONNECTOR, which cannot pass. That is right. But the refusal
        // used to read "reports 'SUCCESS'. A receipt that says the receiving system did not
        // accept the package is a reason to stop": self-contradictory, and it sends an operator
        // to ask the receiving organisation about a rejection that never happened. The problem
        // is the connector's vocabulary, and the message has to say so.
        CustodyTransfer transfer = atAipCreated();
        CustodyReceipt unrecognised = new CustodyReceipt("sub-1", "aip-1", null, SIP_DIGEST,
                jp.aegif.nemaki.custody.connector.ReceivingSystem.UNRECOGNISED, "SUCCESS",
                "am-agent", "2026-08-27T04:45:00Z", null, false);

        CustodyTransfer.Moved moved = transfer.verifyReceipt(unrecognised, "2026-08-27T04:46:00Z");

        assertFalse(moved.accepted(), "an unreadable outcome reached RECEIPT_VERIFIED");
        assertTrue(moved.refusedReason().contains("SUCCESS"),
                "the receiver's own word is not in the refusal: " + moved.refusedReason());
        assertFalse(moved.refusedReason().contains("did not accept"),
                "an unrecognised word was reported as a rejection by the receiver, which is a "
                        + "different problem in a different organisation: " + moved.refusedReason());
        // Keyed on the two things that have to be true — it is not a rejection, and the word is
        // outside the vocabulary this transfer's receiver was measured on — rather than on the
        // clause "never measured to USE", which was part of "which this RECEIVING SYSTEM was
        // never measured to use": an attribution the same response's limits deny.
        assertTrue(moved.refusedReason().contains("not a rejection"), moved.refusedReason());
        assertTrue(moved.refusedReason().contains("measured to use"), moved.refusedReason());
        // Lowercased and space-separated, so it passes today only because the code spells the
        // field camelCase (`receivingSystem`). Rephrasing for readability -- "the receiving
        // system" in prose -- would fail it, and the reader would then be tempted to delete the
        // assertion rather than the phrase. Strip the spacing so the check is about the CLAIM,
        // not about which casing the sentence happens to use.
        String said = moved.refusedReason().toLowerCase(java.util.Locale.ROOT)
                .replace(" ", "").replace("'", "");
        assertFalse(said.contains("receivingsystem")
                        && !said.contains("notthistransfersreceiver"),
                "the refusal hands the receiver's name to a word that arrived in a request "
                        + "body: " + moved.refusedReason());
    }

    @Test
    @DisplayName("an in-progress word is not reported as a rejection either")
    void anUnfinishedOutcomeIsNotCalledARejection() {
        // The first correction split out UNRECOGNISED_BY_CONNECTOR because that was the case it
        // had an example of. RODA's RUNNING and SKIPPED and Archivematica's PROCESSING are
        // carried through verbatim -- neither this product's word nor a measured refusal -- so
        // they landed in the "did not accept the package" message. The receiver has not turned
        // anything down; it has not finished. (Found by review, not by the first fix.)
        CustodyTransfer transfer = atAipCreated();
        CustodyReceipt running = new CustodyReceipt("sub-1", "aip-1", null, SIP_DIGEST,
                "RUNNING", null, "roda-agent", "2026-08-27T04:45:00Z", null, false);

        CustodyTransfer.Moved moved = transfer.verifyReceipt(running, "2026-08-27T04:46:00Z");

        assertFalse(moved.accepted(), "an unfinished ingest reached RECEIPT_VERIFIED");
        assertTrue(moved.refusedReason().contains("RUNNING"), moved.refusedReason());
        assertFalse(moved.refusedReason().contains("did not accept"),
                "an unfinished ingest was reported as a rejection by the receiver: "
                        + moved.refusedReason());
    }

    @Test
    @DisplayName("a receipt carrying no outcome at all is not reported as a rejection")
    void anAbsentOutcomeIsNotCalledARejection() {
        // Reachable from the REST endpoint, which does not require verificationOutcome. The
        // message used to quote 'null' and then explain that the receiver had refused.
        CustodyTransfer transfer = atAipCreated();
        CustodyReceipt silent = new CustodyReceipt("sub-1", "aip-1", null, SIP_DIGEST,
                null, null, "roda-agent", "2026-08-27T04:45:00Z", null, false);

        CustodyTransfer.Moved moved = transfer.verifyReceipt(silent, "2026-08-27T04:46:00Z");

        assertFalse(moved.accepted());
        assertFalse(moved.refusedReason().contains("did not accept"), moved.refusedReason());
        assertFalse(moved.refusedReason().contains("null"),
                "the literal string 'null' was quoted back to an operator as the receiver's "
                        + "word: " + moved.refusedReason());
    }

    @Test
    @DisplayName("every word the two receivers are recorded to use lands in the right message")
    void theWholeRecordedVocabularyIsClassified() {
        // Two words were exercised (FAILED, RUNNING) and the other five were not, so adding a
        // word to the refusal set -- or dropping one -- stayed green. The set is what decides
        // whether an operator is told "the receiving system did not accept the package", which
        // sends them to a different organisation, so every word this product has written down
        // for either receiver is pinned here.
        //
        // REFUSAL means: the operator is told the receiver turned it down.
        // `expect` is a phrase the message MUST contain, so the three non-refusal messages
        // cannot be swapped for each other. Checking only for the absence of "did not accept"
        // left them interchangeable -- a partial ingest could be described as unfinished, which
        // sends an operator to wait for something that already happened.
        record Case(String word, boolean refusal, String expect, String why) {}
        List<Case> cases = List.of(
                new Case("FAILURE", true, "a reason to stop", "RODA pluginState, seen live (§10 追試 1)"),
                new Case("FAILED", true, "a reason to stop", "Archivematica status, seen live (§12)"),
                new Case("REJECTED", true, "a reason to stop", "AM status, read from source (§12)"),
                // NOT a refusal, and NOT unfinished. Whether a partial ingest counts as
                // acceptance is left to the submission agreement (§1.4) -- answering it here
                // would be the product deciding for the parties.
                new Case("PARTIAL_SUCCESS", false, "submission-agreement question",
                        "RODA pluginState — partial: neither refused nor unfinished"),
                new Case("RUNNING", false, "not something this product knows", "RODA pluginState — not finished"),
                new Case("SKIPPED", false, "not something this product knows", "RODA — the plugin did not run"),
                new Case("PROCESSING", false, "not something this product knows", "AM status — not finished"),
                new Case("USER_INPUT", false, "not something this product knows", "AM — waiting on a person"));

        for (Case each : cases) {
            CustodyTransfer transfer = atAipCreated();
            CustodyReceipt receipt = new CustodyReceipt("sub-1", "aip-1", null, SIP_DIGEST,
                    each.word(), null, "agent", "2026-08-27T04:45:00Z", null, false);

            CustodyTransfer.Moved moved = transfer.verifyReceipt(receipt, "2026-08-27T04:46:00Z");

            assertFalse(moved.accepted(), each.word() + " reached RECEIPT_VERIFIED");
            assertTrue(moved.refusedReason().contains(each.word()),
                    "the receiver's own word is missing from the refusal: "
                            + moved.refusedReason());
            // Keyed on "turn a package down", not on the sentence. The predicate used to be
            // contains("did not accept") — from a text that ALSO read "a receipt that says the
            // receiving system did not accept", the attribution CUSTODY_LIMITS denies on the
            // same response. Pinning the phrase pinned the attribution with it, so correcting
            // the claim broke a test whose subject was the CLASSIFICATION, not the wording.
            // "is a reason to stop", not "turn a package down": the LEFTOVER text says "no
            // receiver ... uses this word to turn a package down", so a predicate keyed on that
            // phrase matched the branch that denies it. A substring that appears in a sentence
            // and in its negation classifies nothing.
            assertEquals(each.refusal(), moved.refusedReason().contains("is a reason to stop"),
                    each.word() + " (" + each.why() + ") is classified wrongly: "
                            + moved.refusedReason());
            assertFalse(moved.refusedReason().toLowerCase(java.util.Locale.ROOT)
                            .contains("receiving system"),
                    each.word() + ": the refusal hands the receiver's name to a word that "
                            + "arrived in a request body: " + moved.refusedReason());
            assertTrue(moved.refusedReason().contains(each.expect()),
                    each.word() + " (" + each.why() + ") got the wrong message of the right "
                            + "class: " + moved.refusedReason());
        }
    }

    @Test
    @DisplayName("a genuine rejection IS reported as one — the control")
    void aGenuineRejectionStillReadsAsARejection() {
        // Without this, saying "not a rejection" for everything would satisfy the test above and
        // hide the case where the far end really did refuse the package.
        CustodyTransfer transfer = atAipCreated();
        CustodyReceipt rejected = new CustodyReceipt("sub-1", "aip-1", null, SIP_DIGEST,
                "FAILED", null, "roda-agent", "2026-08-27T04:45:00Z", null, false);

        CustodyTransfer.Moved moved = transfer.verifyReceipt(rejected, "2026-08-27T04:46:00Z");

        assertFalse(moved.accepted());
        assertTrue(moved.refusedReason().contains("turn a package down"),
                "a word a measured receiver really uses for a refusal did not read as one: "
                        + moved.refusedReason());
        assertTrue(moved.refusedReason().contains("a reason to stop"), moved.refusedReason());
    }

    @Test
    @DisplayName("a receipt with no AIP checksum is still verified — the measured RODA run returned none")
    void aReceiptWithNoAipChecksumIsStillVerified() {
        // Measured against a live RODA 6.3.0 (design §16): a genuine, successful ingest produces
        // no checksum of RODA's own AIP in anything that run returned, so requiring one refused every real
        // RODA receipt. The field is never checked here — verification is against sipDigest, the
        // far end's copy of OUR package — so requiring it protected nothing and blocked a
        // receiver this product documents.
        CustodyTransfer transfer = atAipCreated();
        CustodyReceipt noChecksum = new CustodyReceipt("sub-1", "aip-1", null,
                SIP_DIGEST, "SUCCESS", "roda-agent", "2026-08-27T09:26:00Z", null, false);

        CustodyTransfer.Moved moved = transfer.verifyReceipt(noChecksum, "2026-08-27T09:26:30Z");

        assertTrue(moved.accepted(), "a receipt carrying no AIP checksum was "
                + "refused, so no RODA ingest can ever reach RECEIPT_VERIFIED: "
                + moved.refusedReason());
        assertEquals(CustodyState.RECEIPT_VERIFIED, moved.state());
        String limits = transfer.receipt().limits();
        assertTrue(limits.contains("NO checksum of the receiver's own copy"),
                "the receipt passed without saying what it therefore does not establish: "
                        + limits);
        // The disclosure must be about THIS RECEIPT, not a statement about what the receiver
        // did. The field is blank when the receiver gave none OR when a caller left it out --
        // and Archivematica does publish an AIP checksum -- so "the receiving system reported
        // no checksum" is a claim this object is not in a position to make.
        assertFalse(limits.contains("receiving system reported NO"),
                "an empty field was reported as a statement by the receiving system: " + limits);
        // The disclosure used to END with the RODA-strength claim -- "the package they took in
        // is the one this repository sent" -- which the Archivematica route does not support
        // (the recovered value is a line from a manifest THIS product wrote and the receiver
        // merely stored). Nor is this a corner case: the assembler passes aipChecksum straight
        // through from its caller, and the REST endpoint stopped requiring it, so an empty field
        // is ordinary. What it is NOT is an observation about the receiver -- Archivematica does
        // publish an AIP checksum, in the pointer file's PREMIS; the reason this product does not
        // use it is that it digests THEIR artefact, not that they withhold it. Asserting only
        // that the disclosure EXISTS could not tell the two wordings apart.
        assertFalse(limits.contains("package they took in"),
                "the disclosure re-asserts a claim only the RODA route supports, on the branch "
                        + "that every receipt from either measured receiver takes: " + limits);
    }

    @Test
    @DisplayName("verifying too early says which state we are in, not what the receiver did")
    void verifyingBeforeAipCreatedDoesNotAttributeAnythingToTheReceiver() {
        // No test ever called verifyReceipt from an early state, so this refusal's wording was
        // unmeasured -- it said "once the receiving system has reported an AIP" while the guard
        // is only "is this transfer at AIP_CREATED", a state an operator advances into. Same
        // claim as limits(), same class, a different method.
        CustodyTransfer transfer = transfer();

        CustodyTransfer.Moved moved = transfer.verifyReceipt(receiptFor(SIP_DIGEST),
                "2026-08-27T00:00:00Z");

        assertFalse(moved.accepted());
        assertFalse(moved.refusedReason().contains("the receiving system has reported"),
                "the refusal attributes an AIP report to the receiving system, when what is "
                        + "missing is a state this repository records: " + moved.refusedReason());
        assertTrue(moved.refusedReason().contains("recorded as reaching AIP_CREATED"),
                moved.refusedReason());
    }

    @Test
    @DisplayName("a receipt checked at no stated time is refused")
    void anUntimedVerificationIsRefused() {
        // Every other move requires a time and restore() refuses a step without one. Without
        // the same rule here, a public call could produce an accepted RECEIPT_VERIFIED transfer
        // that cannot be read back — a state the product can create and cannot reload.
        CustodyTransfer transfer = atAipCreated();

        CustodyTransfer.Moved moved = transfer.verifyReceipt(receiptFor(SIP_DIGEST), "  ");

        assertFalse(moved.accepted(), "a receipt was verified at no stated time");
        assertEquals(CustodyState.AIP_CREATED, transfer.state());
        assertTrue(moved.refusedReason().contains("stated time"), moved.refusedReason());
    }

    @Test
    @DisplayName("custody does NOT pass on the far end's word that an AIP exists")
    void aipCreatedDoesNotTransferCustody() {
        // AIP_CREATED is their claim; RECEIPT_VERIFIED is our finding. Collapsing the two makes
        // this repository's record depend on an unverified assertion by the party taking over.
        CustodyTransfer transfer = atAipCreated();

        assertFalse(transfer.state().custodyHasPassed());
        CustodyTransfer.Moved moved = transfer.advance(CustodyState.CUSTODY_TRANSFERRED,
                "2026-08-26T02:00:00Z", "they said it is fine");

        assertFalse(moved.accepted(),
                "custody passed without any receipt being checked");
        assertEquals(CustodyState.AIP_CREATED, transfer.state());
    }

    // ---- the machine ----

    @Test
    @DisplayName("a skipped step is refused, not quietly allowed")
    void aSkippedStepIsRefused() {
        // Jumping to AIP_CREATED because the first reply mentioned an AIP erases the fact that
        // we never heard it was received or validated — which is what somebody asking what
        // went wrong looks at.
        CustodyTransfer transfer = transfer();
        CustodyTransfer.Moved sent = transfer.advance(CustodyState.SENT, "t", "sent");
        assertTrue(sent.accepted());

        CustodyTransfer.Moved skipped = transfer.advance(CustodyState.AIP_CREATED, "t", "skip");

        assertFalse(skipped.accepted(), "a two-step jump was allowed");
        assertEquals(CustodyState.SENT, transfer.state());
        assertTrue(skipped.refusedReason().contains("RECEIVED"),
                "the refusal does not say what WAS available: " + skipped.refusedReason());
    }

    @Test
    @DisplayName("a reversal is refused")
    void aReversalIsRefused() {
        CustodyTransfer transfer = transfer();
        transfer.advance(CustodyState.SENT, "t", "sent");
        transfer.advance(CustodyState.RECEIVED, "t", "received");

        assertFalse(transfer.advance(CustodyState.SENT, "t", "resend").accepted(),
                "a transfer went backwards, so the history no longer says what happened");
    }

    @Test
    @DisplayName("a failed transfer is terminal — a retry does not overwrite what went wrong")
    void aFailedTransferIsTerminal() {
        CustodyTransfer transfer = transfer();
        transfer.advance(CustodyState.SENT, "t", "sent");
        assertTrue(transfer.advance(CustodyState.FAILED, "t", "no response in 7 days")
                .accepted());

        assertTrue(transfer.state().isTerminal());
        assertFalse(transfer.advance(CustodyState.SENT, "t", "trying again").accepted(),
                "a failed transfer was re-driven in place, so the record of what went wrong is "
                        + "overwritten by the retry");
    }

    @Test
    @DisplayName("FAILED is reachable from every step before custody passes")
    void failureIsReachableEverywhere() {
        // A machine with no failure state forces every real failure to be recorded as "still at
        // the previous step", which is how a stalled transfer becomes invisible.
        for (CustodyState state : CustodyState.values()) {
            if (state.custodyHasPassed() || state == CustodyState.FAILED) {
                continue;
            }
            assertTrue(state.allowedNext().contains(CustodyState.FAILED),
                    state + " cannot fail, so a transfer stuck there is indistinguishable from "
                            + "one still in progress");
        }
    }

    @Test
    @DisplayName("custody passing does not dispose of the local copy by itself")
    void custodyPassingIsNotDisposition() {
        // Deleting the local copy is an irreversible act P3-3 governs. It happens because
        // somebody decided to, not because a transfer completed.
        assertEquals(java.util.Set.of(CustodyState.LOCAL_DISPOSITION),
                CustodyState.CUSTODY_TRANSFERRED.allowedNext(),
                "custody transfer leads somewhere other than a deliberate disposition step");
        assertFalse(CustodyState.CUSTODY_TRANSFERRED.isTerminal());
    }

    @Test
    @DisplayName("a transfer that does not know what it sent cannot be created")
    void aTransferMustKnowItsPackage() {
        // Otherwise the moment that matters — checking a receipt — is the moment it is found out.
        assertThrows(IllegalArgumentException.class,
                () -> new CustodyTransfer("t-1", "bedroom", "doc-1", "  ", "RODA", "t"));
    }

    // ---- what a reader is told ----

    @Test
    @DisplayName("every state says what it does NOT establish")
    void everyStateCarriesItsLimits() {
        for (CustodyState state : CustodyState.values()) {
            assertNotNull(state.limits(), state.name());
            assertFalse(state.limits().isBlank(), state.name());
        }
        assertTrue(CustodyState.AIP_CREATED.limits().contains("has not checked"),
                "AIP_CREATED does not say the claim is unverified: "
                        + CustodyState.AIP_CREATED.limits());
        assertTrue(CustodyState.SENT.limits().contains("NOT a statement that it arrived"),
                CustodyState.SENT.limits());
        // The state's text and the receipt's text go into ONE response body (stateLimits and
        // receipt.limits, plus stateMeans on the describe endpoint). When CustodyReceipt.limits()
        // was weakened to stop claiming the far end took in our package, THIS text still said
        // "establishes that the far end received and processed OUR package" -- so a reader got
        // the retracted claim and its retraction together. It survived a grep for the receipt's
        // wording because it is a different sentence, split across a concatenation, in a file
        // the change never opened.
        // EVERY state, not just the one that was corrected. Round 3 fixed RECEIPT_VERIFIED and
        // locked it; round 4 found the same attribution three case arms away in the same switch,
        // and the lock could not see it. These states are reached by an operator calling
        // POST /advance -- this release has no sending path at all -- so nothing here has ever
        // heard from a receiver, and a text saying "the receiving system says/accepted/REPORTS"
        // hands an operator's own entry back to them as the far end's word.
        // A POSITIVE requirement, not a list of banned phrasings. The first version banned the
        // three exact phrases the old texts used, so INGEST_ACCEPTED (whose old text contained
        // none of them) and any reworded attribution -- "The receiving system has the package"
        // -- walked straight through. Naming what the text must SAY cannot be sidestepped by
        // rephrasing.
        // COMPUTED, not listed. The listed version named the four arms round 4 had corrected,
        // and PACKAGE_CREATED and SENT -- the two arms BEFORE them in the same switch -- kept
        // the attribution for two more rounds: "a package exists here", "the package was handed
        // over". Nothing builds a package (the digest comes from the caller) and nothing sends
        // one, so they were the same defect, one case arm earlier. Taking every state before
        // RECEIPT_VERIFIED from sequence() means a state added later is covered on the day it
        // is added rather than on the day somebody remembers this list.
        List<CustodyState> beforeAnythingIsChecked = CustodyState.sequence().stream()
                .takeWhile(state -> state != CustodyState.RECEIPT_VERIFIED)
                .toList();
        // A LOWER bound, not an exact count. The comment two lines up promises that a state
        // added later is covered on the day it is added — and an exact 6 would have FAILED on
        // that day, which teaches the next person to bump the number rather than to look. The
        // fixture check it exists for is "the takeWhile found something", and that is what a
        // bound says.
        assertTrue(beforeAnythingIsChecked.size() >= 6,
                "fixture check: only " + beforeAnythingIsChecked.size() + " state(s) precede "
                        + "RECEIPT_VERIFIED, so the sequence is not the one this assertion "
                        + "covers: " + beforeAnythingIsChecked);
        for (CustodyState state : beforeAnythingIsChecked) {
            assertTrue(state.limits().startsWith("SOMEBODY RECORDED"),
                    state.name() + " is reached only by an operator calling advance -- this "
                            + "release has no sending path -- so its text has to say who "
                            + "recorded it, not attribute it to the receiving system: "
                            + state.limits());
        }

        String verified = CustodyState.RECEIPT_VERIFIED.limits();
        assertFalse(verified.contains("received and processed"),
                "the RECEIPT_VERIFIED state asserts the far end took in our package — the claim "
                        + "the receipt's own limits were rewritten to stop making, and the two "
                        + "are rendered side by side: " + verified);
        assertTrue(verified.contains("unauthenticated") || verified.contains("NOT a finding"),
                "the state says what it establishes without saying what it does not: " + verified);
    }

    @Test
    @DisplayName("an unsigned receipt says it is unauthenticated")
    void anUnsignedReceiptSaysSo() {
        // "The far end confirmed it" is what a receipt is read as. Without a signature, anything
        // that could reach the endpoint could have sent it, and that has to be on the record.
        String limits = receiptFor(SIP_DIGEST).limits();

        assertTrue(limits.contains("NO signature"), limits);
        assertTrue(limits.contains("anything that could reach this endpoint"), limits);
    }

    @Test
    @DisplayName("a carried but unverified signature is not reported as verified")
    void anUnverifiedSignatureIsNotVerified() {
        CustodyReceipt receipt = new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), SIP_DIGEST,
                "PASSED", "roda-agent", "2026-08-26T01:00:00Z", "MEUCIQ...", false);

        // The MEANING, and the cause it must not name. This asserted
        // contains("has NOT been verified") -- true of the old wording, which went on to give
        // "this product holds no key material" as the reason. That is ONE of the three ways the
        // flag stays false, and naming it made a check that RAN and FAILED read as an
        // unconfigured deployment.
        assertTrue(receipt.limits().contains("NOT marked as verified"), receipt.limits());
        assertFalse(receipt.limits().contains("holds no key material"),
                "the receipt names a cause it cannot know: " + receipt.limits());
        assertEquals(Boolean.FALSE, receipt.asMap().get("signatureVerified"));
        assertEquals(Boolean.TRUE, receipt.asMap().get("hasSignature"),
                "the signature was dropped, so it cannot be checked later either");
    }

    @Test
    @DisplayName("claiming a verified signature without one is refused")
    void aVerifiedFlagWithoutASignatureIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), SIP_DIGEST, "PASSED",
                        "roda-agent", "2026-08-26T01:00:00Z", null, true));
    }

    @Test
    @DisplayName("the history records every move, with its reason")
    void theHistoryIsTheAnswer() {
        // "It is at INGEST_ACCEPTED" answers less than "it reached INGEST_ACCEPTED at 09:14 and
        // has not moved since", and only the second tells an operator whether to chase somebody.
        CustodyTransfer transfer = transfer();
        transfer.advance(CustodyState.SENT, "2026-08-26T09:00:00Z", "handed to RODA");
        transfer.advance(CustodyState.FAILED, "2026-08-26T09:14:00Z", "no response in 7 days");

        List<CustodyTransfer.Step> history = transfer.history();
        assertEquals(3, history.size(), history.toString());
        assertEquals("no response in 7 days", history.get(2).reason());
        assertEquals("2026-08-26T09:14:00Z", history.get(2).at());

        // A refused move leaves NO trace in the history: it did not happen.
        transfer.advance(CustodyState.SENT, "t", "retry");
        assertEquals(3, transfer.history().size(),
                "a refused move was written into the history, so the record shows a step that "
                        + "never took place");
    }

    @Test
    @DisplayName("the rendered transfer puts the caveat beside the state")
    void theRenderedStateCarriesItsCaveat() {
        Map<String, Object> body = atAipCreated().asMap();

        assertEquals("AIP_CREATED", body.get("state"));
        assertNotNull(body.get("stateLimits"),
                "the state name is shown with nothing saying what it does not establish: "
                        + body);
        // ORDER, not just presence. "Straight after the state" is what the javadoc claims, and
        // a caveat further down the map is one a reader skimming for the verdict never meets.
        // Moving the put to the end of asMap left the presence assertion green.
        List<String> keys = new java.util.ArrayList<>(body.keySet());
        assertEquals(keys.indexOf("state") + 1, keys.indexOf("stateLimits"),
                "the caveat is not immediately after the state: " + keys);
        assertEquals(Boolean.FALSE, body.get("custodyHasPassed"));
        assertNull(body.get("receipt"));
    }

    @Test
    @DisplayName("the opening step does not say a package was built")
    void theOpeningStepDoesNotClaimAPackageWasBuilt() {
        // The persisted history outlives every response, so this is the one place the wording
        // cannot be corrected afterwards. The constructor takes a digest from its caller and
        // never reads a package; the endpoint that reaches it takes that digest from a request
        // body. "a package was built for this record" is the same attribution CustodyState's
        // limits() lost, in a different file, which is why grepping the switch never found it.
        CustodyTransfer transfer = new CustodyTransfer("t-1", "bedroom", "obj-1", SIP_DIGEST,
                "RODA", "2026-08-28T00:00:00Z");

        String opening = transfer.history().get(0).reason();

        assertFalse(opening.contains("was built"),
                "the persisted history says this product built a package it never saw: "
                        + opening);
        assertTrue(opening.contains("opened") && opening.contains("digest"),
                "the opening step does not say what actually happened — a transfer was opened "
                        + "naming a digest: " + opening);
    }

    @Test
    @DisplayName("no refusal from verifyReceipt attributes the receipt's word to the receiver")
    void noRefusalAttributesTheWordToTheReceiver() {
        // CUSTODY_LIMITS is locked against this and travels on the SAME response as these
        // texts, which said "nothing here knows what THE RECEIVING SYSTEM found" and "a receipt
        // that says THE RECEIVING SYSTEM did not accept the package". The outcome reaches this
        // class from a REST request body; without a verified signature nothing establishes who
        // wrote it, so the response carried the retracted claim and its retraction together.
        //
        // Every branch is driven, not the two that were wrong. The five arms share one method
        // and one caller: reverting either corrected line goes red on the arm a narrower test
        // named, and the other three would be free to acquire the attribution later.
        // ONE WORD PER BRANCH, and the branch each one reaches is asserted. The first version
        // of this test listed five words that hit only four arms: "SOMETHING_NOBODY_USES" and
        // "RUNNING" are BOTH leftover (neither is in this product's success vocabulary), and
        // UNRECOGNISED — which needs a word this product accepts but the receiver was never
        // measured to use, e.g. RODA and "PASSED" — was never driven at all, under a comment
        // saying "Every branch is driven".
        record Arm(String word, String reaches) { }
        List<Arm> arms = List.of(
                new Arm("", "carries no verification outcome"),
                new Arm("PASSED", "not a word ANY connector"),
                new Arm("FAILURE", "is a reason to stop"),
                new Arm("PARTIAL_SUCCESS", "part of it succeeded"),
                new Arm("RUNNING", "not something this product"));
        List<String> attributing = new java.util.ArrayList<>();
        for (Arm arm : arms) {
            CustodyTransfer transfer = atAipCreated();
            jp.aegif.nemaki.custody.connector.ReceivingSystem.Outcome outcome =
                    jp.aegif.nemaki.custody.connector.ReceivingSystem.RODA.read(arm.word());
            CustodyTransfer.Moved moved = transfer.verifyReceipt(
                    new CustodyReceipt("sub-1", "aip-1", "b".repeat(64), SIP_DIGEST,
                            outcome.readable() ? outcome.verificationOutcome() : arm.word(),
                            outcome.readable() ? outcome.reportedOutcome() : null,
                            "roda-agent", "2026-08-26T01:00:00Z", null, false),
                    "2026-08-28T00:00:00Z");

            org.junit.jupiter.api.Assertions.assertFalse(moved.accepted(),
                    "fixture check: '" + arm.word() + "' was expected to refuse");
            org.junit.jupiter.api.Assertions.assertTrue(
                    moved.refusedReason().contains(arm.reaches()),
                    "fixture check: '" + arm.word() + "' did not reach the branch this test "
                            + "drives it for; two words on one arm leaves another untested: "
                            + moved.refusedReason());
            // "receiving system", not "the receiving system " — no article, no trailing space.
            // The earlier ban had both, and so matched neither "the receiving system's own
            // documentation" nor "this receiving system was never measured".
            if (String.valueOf(moved.refusedReason()).toLowerCase(java.util.Locale.ROOT)
                    .contains("receiving system")) {
                attributing.add("'" + arm.word() + "' -> " + moved.refusedReason());
            }
        }
        if (!attributing.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(
                    "a refusal hands the receiver's name to a word a REST caller posted, on the "
                            + "same response whose limits say who wrote a receipt is unknown "
                            + "without a verified signature:\n" + String.join("\n", attributing));
        }
    }
}
