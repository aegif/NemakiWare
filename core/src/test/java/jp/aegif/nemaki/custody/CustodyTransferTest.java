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
        assertTrue(transfer.advance(CustodyState.CUSTODY_TRANSFERRED, "t", "receipt verified")
                .accepted());

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

        assertTrue(receipt.limits().contains("has NOT been verified"), receipt.limits());
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
}
