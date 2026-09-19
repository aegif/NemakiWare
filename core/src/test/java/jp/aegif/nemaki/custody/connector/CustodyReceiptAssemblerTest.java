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
package jp.aegif.nemaki.custody.connector;

import jp.aegif.nemaki.custody.CustodyReceipt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two receiver-shaped traps, and the refusals that keep them from becoming receipts.
 *
 * <p>Everything here runs without a receiver: {@link SubmittedDigestRecovery} is replaced by a
 * stub, and the vocabulary rules are pure. That is deliberate — the live stacks are stopped by
 * default (both compose files say so), and a lock that only holds when a container is up is not
 * a lock. What a live receiver settles instead is <b>whether the recovered value matches</b>,
 * which is design §13.2's open acceptance condition, not a rule this file can decide.
 */
class CustodyReceiptAssemblerTest {

    private static final byte[] SUBMITTED = "the package this transfer sent".getBytes(StandardCharsets.UTF_8);
    private static final String SUBMITTED_DIGEST = sha256(SUBMITTED);
    private static final String OTHER_DIGEST = sha256("something else entirely".getBytes(StandardCharsets.UTF_8));

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A recovery that answers with whatever the test wants the receiver to be holding — and
     * <b>records which method was called and with what</b>.
     *
     * <p>An earlier version discarded every argument, which meant the assembler could pass
     * {@code submissionId} where {@code aipId} belongs, swap the two receivers' branches, or
     * hand Archivematica the Dashboard's base URL instead of the Storage Service's, and every
     * test here stayed green while a live fetch 404'd.
     */
    private static final class StubRecovery extends SubmittedDigestRecovery {
        private final String digest;
        private final String unavailable;
        String calledMethod;
        List<String> calledWith = List.of();

        StubRecovery(String digest, String unavailable) {
            this.digest = digest;
            this.unavailable = unavailable;
        }

        @Override
        public Recovered fromRodaTransfer(String baseUrl, String uuid, String authorization) {
            calledMethod = "roda";
            calledWith = java.util.Arrays.asList(baseUrl, uuid, authorization);
            return answer();
        }

        @Override
        public Recovered fromArchivematicaManifest(String baseUrl, String aipUuid,
                String relativePath, String payloadName, String authorization) {
            calledMethod = "am";
            calledWith = java.util.Arrays.asList(baseUrl, aipUuid, relativePath, payloadName,
                    authorization);
            return answer();
        }

        private Recovered answer() {
            return unavailable != null
                    ? Recovered.unavailable(unavailable)
                    : Recovered.of(digest, "the stub receiver");
        }
    }

    private static SubmittedDigestRecovery recoveryReturning(String digest, String unavailable) {
        return new StubRecovery(digest, unavailable);
    }

    private static CustodyReceiptAssembler.Inputs inputs(ReceivingSystem receiver, String word) {
        return new CustodyReceiptAssembler.Inputs(receiver, "http://receiver.example:8080",
                "sub-1", "aip-1", "b".repeat(64), word, "far-end-agent",
                "2026-08-27T00:00:00Z", null, SUBMITTED_DIGEST, null,
                "tr-uuid", "data/objects/metadata/transfers/t/manifest-sha256.txt", "sip.zip");
    }

    // ---------------------------------------------------------------- RODA

    @Test
    @DisplayName("RODA: pluginState=SUCCESS with matching bytes assembles, and maps nothing")
    void rodaSuccessAssembles() {
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.RODA, "SUCCESS"));

        assertTrue(result.assembled(), result.refusedReason());
        CustodyReceipt receipt = result.receipt();
        assertEquals("SUCCESS", receipt.verificationOutcome());
        // No mapping happened, so nothing is kept separately -- null is the honest value, and a
        // connector that echoed "SUCCESS" into both would make a translation look like it took
        // place.
        assertNull(receipt.reportedOutcome(),
                "RODA's own word is already this product's word, so nothing was mapped");
        assertTrue(receipt.reportsSuccess());
        assertEquals(SUBMITTED_DIGEST, receipt.sipDigest());
        // Every remaining field, because the assembler wires ten and only four were checked.
        // Swapping submissionId with aipId, or dropping the signature, compiled and stayed
        // green -- and a dropped signature makes every signed receipt permanently unverifiable.
        assertEquals("sub-1", receipt.submissionId());
        assertEquals("aip-1", receipt.aipId());
        assertEquals("b".repeat(64), receipt.aipChecksum());
        assertEquals("far-end-agent", receipt.receivingAgent());
        assertEquals("2026-08-27T00:00:00Z", receipt.receivedAt());
        assertFalse(receipt.signatureVerified(),
                "the assembler set signatureVerified. Neither receiver signs anything, and that "
                        + "finding belongs to the service, from key material an agreement supplies");
    }

    @Test
    @DisplayName("RODA: outcomeObjectState=ACTIVE does not become a success")
    void rodaActiveIsNotSuccess() {
        // ACTIVE arrives in the SAME response body as pluginState and is exactly what an
        // accepted AIP looks like, which is what makes taking it tempting. It is not in this
        // product's vocabulary, and it must not be mapped into it: a connector that "fixed" the
        // refusal by translating ACTIVE would be asserting acceptance from a field that means
        // "where the AIP now is".
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.RODA, "ACTIVE"));

        assertTrue(result.assembled(), "a receipt reporting ACTIVE is still a receipt: something "
                + "arrived, and discarding it loses that fact");
        assertEquals("ACTIVE", result.receipt().verificationOutcome());
        assertFalse(result.receipt().reportsSuccess(),
                "ACTIVE passed reportsSuccess(). Either the vocabulary was widened or the "
                        + "mapping translated it -- both were decided against (design §13.1)");
    }

    @Test
    @DisplayName("RODA: PARTIAL_SUCCESS is not nudged into SUCCESS")
    void rodaPartialIsNotSuccess() {
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.RODA, "PARTIAL_SUCCESS"));

        assertEquals("PARTIAL_SUCCESS", result.receipt().verificationOutcome());
        assertFalse(result.receipt().reportsSuccess(),
                "a partial acceptance became an acceptance. The submission agreement §1.4 "
                        + "decided that independently of any receiver");
    }

    @Test
    @DisplayName("RODA: bytes the receiver holds are not ours — no receipt at all")
    void rodaMismatchRefuses() {
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(OTHER_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.RODA, "SUCCESS"));

        assertFalse(result.assembled(),
                "a receipt was built for a package the receiver does not hold. The tempting fix "
                        + "-- fill sipDigest from our own record -- makes the check unfailable");
        assertTrue(result.refusedReason().contains(OTHER_DIGEST)
                        && result.refusedReason().contains(SUBMITTED_DIGEST),
                "the refusal does not say which two values differ: " + result.refusedReason());
    }

    // ------------------------------------------------------- Archivematica

    @Test
    @DisplayName("AM: COMPLETE is mapped to SUCCESS and the raw word is kept")
    void archivematicaCompleteIsMapped() {
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.ARCHIVEMATICA, "COMPLETE"));

        assertTrue(result.assembled(), result.refusedReason());
        CustodyReceipt receipt = result.receipt();
        assertEquals("SUCCESS", receipt.verificationOutcome(),
                "the mapped word is not where the state machine reads it, so a genuine "
                        + "Archivematica acceptance would stop the handover");
        assertEquals("COMPLETE", receipt.reportedOutcome(),
                "the receiver's own word was dropped. It is what the far end would sign, and "
                        + "what a later dispute quotes");
        assertTrue(receipt.reportsSuccess());
        assertEquals("COMPLETE", receipt.asReported());
    }

    @Test
    @DisplayName("AM: FAILED stays FAILED")
    void archivematicaFailedStaysFailed() {
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.ARCHIVEMATICA, "FAILED"));

        assertEquals("FAILED", result.receipt().verificationOutcome());
        assertNull(result.receipt().reportedOutcome(), "nothing was mapped, so nothing is kept");
        assertFalse(result.receipt().reportsSuccess());
    }

    @Test
    @DisplayName("AM: no manifest to recover — no receipt (this is the zipfile route)")
    void archivematicaWithoutManifestRefuses() {
        // An E-ARK SIP sent as zipfile ingests perfectly well and leaves no manifest behind.
        // "It became an AIP" is not "a receipt can be assembled", and reading it as such is how
        // the bag route's one real advantage gets argued away.
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(null,
                        "the manifest could not be read back from AIP aip-1"))
                        .assemble(inputs(ReceivingSystem.ARCHIVEMATICA, "COMPLETE"));

        assertFalse(result.assembled(),
                "a receipt was assembled with nothing recovered from the receiver");
        assertTrue(result.refusedReason().contains("sipDigest"),
                "the refusal does not explain what would have gone wrong: "
                        + result.refusedReason());
    }

    @Test
    @DisplayName("AM: a manifest line for a different package refuses")
    void archivematicaWrongManifestLineRefuses() {
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(OTHER_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.ARCHIVEMATICA, "COMPLETE"));

        assertFalse(result.assembled(),
                "the recovered manifest describes a different package and a receipt was still "
                        + "built");
    }

    // ------------------------------------------------------------- general

    @Test
    @DisplayName("with nothing to compare against, no receipt is built")
    void noExpectedDigestRefuses() {
        CustodyReceiptAssembler.Inputs blind = new CustodyReceiptAssembler.Inputs(
                ReceivingSystem.RODA, "http://receiver.example:8080", "sub-1", "aip-1",
                "b".repeat(64), "SUCCESS", "far-end-agent", "2026-08-27T00:00:00Z", null,
                null, null, "tr-uuid", null, null);

        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(blind);

        assertFalse(result.assembled(),
                "a receipt was built with no expected digest, so its sipDigest is whatever the "
                        + "receiver said and nothing checks it");
    }

    @Test
    @DisplayName("the receiver said nothing usable — no receipt")
    void blankOutcomeRefuses() {
        assertFalse(new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.ARCHIVEMATICA, "  ")).assembled(),
                "a receipt with no verification result at all was assembled");
    }

    @Test
    @DisplayName("each receiver is asked the right question, with the right arguments")
    void theRecoveryIsWiredPerReceiver() {
        StubRecovery roda = new StubRecovery(SUBMITTED_DIGEST, null);
        new CustodyReceiptAssembler(roda).assemble(inputs(ReceivingSystem.RODA, "SUCCESS"));

        assertEquals("roda", roda.calledMethod,
                "RODA was not asked for its transferred resource. Swapping the two branches "
                        + "leaves every other assertion in this file green");
        assertEquals(java.util.Arrays.asList("http://receiver.example:8080", "tr-uuid", null),
                roda.calledWith,
                "RODA was asked with the wrong arguments -- the transferred-resource uuid is "
                        + "what identifies the bytes it still holds");

        StubRecovery am = new StubRecovery(SUBMITTED_DIGEST, null);
        new CustodyReceiptAssembler(am).assemble(inputs(ReceivingSystem.ARCHIVEMATICA, "COMPLETE"));

        assertEquals("am", am.calledMethod);
        assertEquals(java.util.Arrays.asList("http://receiver.example:8080", "aip-1",
                        "data/objects/metadata/transfers/t/manifest-sha256.txt", "sip.zip", null),
                am.calledWith,
                "Archivematica was asked with the wrong arguments. The AIP uuid (not the "
                        + "submission id) identifies the package, and the base URL has to be the "
                        + "STORAGE SERVICE's -- the Dashboard's would 404");
    }

    @Test
    @DisplayName("a success word this receiver never uses cannot ride in on a coincidence")
    void aWordThisReceiverNeverUsesIsNotSuccess() {
        // Archivematica's transfer status is never "SUCCESS" -- that is RODA's plugin state.
        // But "SUCCESS" IS in this product's vocabulary, so carrying it through unchanged would
        // let it pass reportsSuccess() on the strength of a coincidence. That is exactly the
        // "a word means one thing here and another there" risk design §13.1 gave for not
        // widening the list, and it must not sneak in through the mapping instead.
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.ARCHIVEMATICA, "SUCCESS"));

        assertTrue(result.assembled(), "something arrived; the receipt should still record it");
        assertFalse(result.receipt().reportsSuccess(),
                "Archivematica reporting the literal word SUCCESS as a transfer status was "
                        + "accepted. It does not use that word for that field");
        assertEquals("SUCCESS", result.receipt().reportedOutcome(),
                "the word the receiver actually sent was lost");

        // The same for RODA and a word only Archivematica-ish systems would use.
        assertFalse(new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.RODA, "OK")).receipt().reportsSuccess(),
                "RODA reporting OK as a pluginState was accepted; it does not use that word");
    }

    @Test
    @DisplayName("the receiver's word is kept verbatim, not upper-cased")
    void theReceiversWordIsNotNormalised() {
        // The far end signs what it wrote. Upper-casing it here would mean a receiver emitting
        // "Complete" had its signature checked against "COMPLETE" -- every mapped receipt
        // failing verification, which is the failure the two slots exist to avoid.
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.ARCHIVEMATICA, "Complete"));

        assertEquals("SUCCESS", result.receipt().verificationOutcome());
        assertEquals("Complete", result.receipt().reportedOutcome(),
                "the receiver's word was normalised. ReceiptSignatureVerifier signs this field");
    }

    @Test
    @DisplayName("a blank reported word is one representation of nothing, not a second one")
    void aBlankReportedWordIsNull() {
        // SUCCESS with reportedOutcome="  " is the forged pair one notch weaker: it reads as
        // "the receiver said something" while carrying nothing a signature or a re-derivation
        // could work on, and the derivability check would wave it through as an absent mapping.
        CustodyReceipt blank = new CustodyReceipt("sub-1", "aip-1", "b".repeat(64),
                "a".repeat(64), "SUCCESS", "   ", "agent", "2026-08-27T00:00:00Z", null, false);

        assertNull(blank.reportedOutcome(),
                "a whitespace-only reported word survived as a second way of saying 'nothing'");
        assertEquals("SUCCESS", blank.asReported(),
                "with nothing mapped, the receiver's word IS verificationOutcome");
        assertNull(blank.mappingRefusalReason(),
                "an absent mapping was treated as a forged one");
    }

    @Test
    @DisplayName("RODA's own word survives its case too, not just Archivematica's")
    void rodasWordIsAlsoKeptVerbatim() {
        // The "nothing was mapped" test used to look at the NORMALISED word, so a RODA response
        // saying "Success" was stored -- and signed against -- as our "SUCCESS". The mixed-case
        // test only covered Archivematica, so the branch that actually discards a word was the
        // unmeasured one.
        CustodyReceiptAssembler.Assembled result =
                new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                        .assemble(inputs(ReceivingSystem.RODA, "Success"));

        assertTrue(result.receipt().reportsSuccess());
        assertEquals("Success", result.receipt().asReported(),
                "RODA's own spelling was replaced by ours. ReceiptSignatureVerifier signs "
                        + "asReported(), so the far end's signature would be checked against a "
                        + "word it never wrote");
    }

    @Test
    @DisplayName("the derivability rule, every shape a forger would try")
    void theDerivabilityRuleHoldsShapeByShape() {
        // One example is not a rule. These are the shapes that came up while thinking about how
        // to get judged on a word the far end never said -- kept as a table so the NEXT person
        // to touch the mapping sees which of them are supposed to be refusals.
        assertTrue(ReceivingSystem.isDerivableMapping("SUCCESS", null),
                "no mapping claimed at all must always be allowed -- the raw word IS the judged "
                        + "word and the signature covers it");
        assertTrue(ReceivingSystem.isDerivableMapping("SUCCESS", "COMPLETE"),
                "the one mapping this product performs was refused");
        assertTrue(ReceivingSystem.isDerivableMapping("SUCCESS", "complete"),
                "a receiver writing its own word in a different case was refused. It signs what "
                        + "it wrote, so the stored value has to be able to differ in case");
        assertTrue(ReceivingSystem.isDerivableMapping("UNRECOGNISED_BY_CONNECTOR", "SUCCESS"),
                "a word the receiver never uses must still be recordable -- as unrecognised");

        assertFalse(ReceivingSystem.isDerivableMapping("SUCCESS", "FAILED"),
                "the forgery: signed over FAILED, judged on SUCCESS");
        assertFalse(ReceivingSystem.isDerivableMapping("SUCCESS", "failed"),
                "the same forgery in lower case");
        assertFalse(ReceivingSystem.isDerivableMapping("SUCCESS", "SUCCESS"),
                "a word that needs no translation must be recorded with reportedOutcome=null. "
                        + "Allowing it in both slots gives a forger a second shape to hide in");
        assertFalse(ReceivingSystem.isDerivableMapping("SUCCESS", "UNRECOGNISED_BY_CONNECTOR"),
                "the connector's own sentinel was accepted as a receiver's word");
        assertFalse(ReceivingSystem.isDerivableMapping("PASSED", "COMPLETE"),
                "COMPLETE was allowed to map to a DIFFERENT accepted word. The mapping this "
                        + "product performs produces SUCCESS; anything else is not re-derived, "
                        + "it is chosen");
        assertFalse(ReceivingSystem.isDerivableMapping("FAILED", "FAILED"),
                "a negative outcome duplicated into both slots was accepted");
    }

    @Test
    @DisplayName("the signature is carried through, not dropped")
    void theSignatureSurvivesAssembly() {
        // Neither measured receiver signs, so nothing here exercises it end to end -- which is
        // exactly why it can be dropped without anyone noticing. A receipt that arrives signed
        // and loses the signature in assembly is permanently unverifiable afterwards.
        CustodyReceiptAssembler.Inputs signed = new CustodyReceiptAssembler.Inputs(
                ReceivingSystem.RODA, "http://receiver.example:8080", "sub-1", "aip-1",
                "b".repeat(64), "SUCCESS", "far-end-agent", "2026-08-27T00:00:00Z",
                "MEUCIQ...", SUBMITTED_DIGEST, null, "tr-uuid", null, null);

        CustodyReceipt receipt = new CustodyReceiptAssembler(
                recoveryReturning(SUBMITTED_DIGEST, null)).assemble(signed).receipt();

        assertEquals("MEUCIQ...", receipt.signature(),
                "the signature was dropped during assembly, so it can never be checked");
        assertFalse(receipt.signatureVerified(),
                "carrying a signature was taken for having verified one");
    }

    @Test
    @DisplayName("the field each receiver must be read from is named, not guessed")
    void theFieldIsNamed() {
        // A connector author reading only reportsSuccess()'s vocabulary would go looking for a
        // word and pick whichever field carries one. Both receivers have two.
        assertEquals("pluginState", ReceivingSystem.RODA.outcomeFieldName());
        assertEquals("status", ReceivingSystem.ARCHIVEMATICA.outcomeFieldName());
        assertNotNull(ReceivingSystem.ARCHIVEMATICA.read("COMPLETE").reportedOutcome());
    }

    @Test
    @DisplayName("a blank outcome is reported as an absent ARGUMENT, not as receiver silence")
    void aBlankOutcomeIsNotCalledReceiverSilence() {
        // "RODA reported no pluginState" used to come back here. The word arrives as an
        // argument; blank means the caller passed nothing. Round 3 corrected exactly this
        // reasoning for aipChecksum and round 4 found it again on this field -- and the existing
        // blank-outcome test only asserted assembled()==false, so the wording was free to say
        // whatever it liked. Both receivers, because the two enum constants carry the sentence
        // separately and only one of them had ever been exercised.
        for (ReceivingSystem receiver : ReceivingSystem.values()) {
            CustodyReceiptAssembler.Assembled assembled =
                    new CustodyReceiptAssembler(recoveryReturning(SUBMITTED_DIGEST, null))
                            .assemble(inputs(receiver, "  "));

            assertFalse(assembled.assembled(), receiver + " assembled from a blank outcome");
            assertFalse(assembled.refusedReason().contains("reported no"),
                    receiver + " reports an argument the caller omitted as something the "
                            + "receiving system did not say: " + assembled.refusedReason());
            assertTrue(assembled.refusedReason().contains("was given"),
                    receiver + " does not say the outcome was never supplied: "
                            + assembled.refusedReason());
        }
    }
}
