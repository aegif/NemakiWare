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
package jp.aegif.nemaki.rest.controller;

import jakarta.servlet.http.HttpServletRequest;

import jp.aegif.nemaki.custody.CustodyReceipt;
import jp.aegif.nemaki.custody.CustodyState;
import jp.aegif.nemaki.custody.CustodyTransferService;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the HTTP layer must not let a caller assert (P3-4).
 */
class CustodyTransferControllerTest {

    private static final String REPO = "bedroom";
    private static final String DIGEST = "a".repeat(64);

    private CustodyTransferController controller;
    private CustodyTransferService service;

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        controller = new CustodyTransferController();
        service = mock(CustodyTransferService.class);
        setField(controller, "service", service);
        CallContext context = mock(CallContext.class);
        when(context.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(context);
        controller.setHttpRequest(request);
    }

    @Test
    @DisplayName("the receiver's own word survives the endpoint")
    void theReportedWordIsNotDropped() {
        // This endpoint built a 9-argument receipt and dropped reportedOutcome on the floor.
        // A connector that maps Archivematica's COMPLETE to SUCCESS (design §13.1) and posts
        // both would have had the raw word silently discarded -- and the raw word is the one
        // the far end signs, and the one a later dispute quotes.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("submissionId", "sub-1");
        body.put("aipId", "aip-1");
        body.put("aipChecksum", "c".repeat(64));
        body.put("sipDigest", DIGEST);
        body.put("verificationOutcome", "SUCCESS");
        body.put("reportedOutcome", "COMPLETE");
        body.put("receivingAgent", "am-agent");
        body.put("receivedAt", "2026-08-27T00:00:00Z");
        when(service.verifyReceipt(anyString(), anyString(), any())).thenReturn(
                new CustodyTransferService.Outcome(false, null, "refused for this test", null));

        controller.receipt(REPO, "t-1", body);

        ArgumentCaptor<CustodyReceipt> received = ArgumentCaptor.forClass(CustodyReceipt.class);
        verify(service).verifyReceipt(anyString(), anyString(), received.capture());
        assertEquals("SUCCESS", received.getValue().verificationOutcome());
        assertEquals("COMPLETE", received.getValue().reportedOutcome(),
                "the receiver's own word was dropped by the endpoint");
        assertEquals("COMPLETE", received.getValue().asMap().get("reportedOutcome"),
                "the receiver's own word is not in the receipt's own map either");
    }

    @Test
    @DisplayName("no reportedOutcome means nothing was mapped, not an empty word")
    void anAbsentReportedWordIsNull() {
        // A receiver whose vocabulary already matches -- RODA saying SUCCESS -- maps nothing.
        // Echoing the same word into both slots would make a translation look like it happened.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("submissionId", "sub-1");
        body.put("aipId", "aip-1");
        body.put("aipChecksum", "c".repeat(64));
        body.put("sipDigest", DIGEST);
        body.put("verificationOutcome", "SUCCESS");
        body.put("receivingAgent", "roda-agent");
        body.put("receivedAt", "2026-08-27T00:00:00Z");
        when(service.verifyReceipt(anyString(), anyString(), any())).thenReturn(
                new CustodyTransferService.Outcome(false, null, "refused for this test", null));

        controller.receipt(REPO, "t-1", body);

        ArgumentCaptor<CustodyReceipt> received = ArgumentCaptor.forClass(CustodyReceipt.class);
        verify(service).verifyReceipt(anyString(), anyString(), received.capture());
        assertNull(received.getValue().reportedOutcome(),
                "an absent reportedOutcome became something other than null");
        assertEquals("SUCCESS", received.getValue().asReported(),
                "with nothing mapped, the receiver's word IS verificationOutcome");
    }

    @Test
    @DisplayName("signatureVerified is never taken from the request")
    void aCallerCannotAssertAVerifiedSignature() {
        // Otherwise anything that can POST a receipt can tell this repository that a signature
        // was checked, by saying so. The flag is a finding; findings are not accepted as input.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("submissionId", "sub-1");
        body.put("aipId", "aip-1");
        body.put("aipChecksum", "c".repeat(64));
        body.put("sipDigest", DIGEST);
        body.put("verificationOutcome", "PASSED");
        body.put("receivingAgent", "roda-agent");
        body.put("receivedAt", "2026-08-26T02:00:00Z");
        body.put("signature", "c2ln");
        body.put("signatureVerified", true);
        when(service.verifyReceipt(anyString(), anyString(), any())).thenReturn(
                new CustodyTransferService.Outcome(false, null, "refused for this test", null));

        controller.receipt(REPO, "t-1", body);

        ArgumentCaptor<CustodyReceipt> received = ArgumentCaptor.forClass(CustodyReceipt.class);
        verify(service).verifyReceipt(anyString(), anyString(), received.capture());
        assertFalse(received.getValue().signatureVerified(),
                "a caller asserted that its own signature had been verified, and was believed");
        assertEquals("c2ln", received.getValue().signature(),
                "the signature itself was dropped, so it cannot be checked later either");
    }

    @Test
    @DisplayName("/advance cannot reach CUSTODY_TRANSFERRED through HTTP either")
    void theOrdinaryDoorIsClosedOverHttp() {
        when(service.advance(anyString(), anyString(), any(), anyString())).thenReturn(
                new CustodyTransferService.Outcome(false, null, "use passCustody", null));

        controller.advance(REPO, "t-1", CustodyState.CUSTODY_TRANSFERRED.name(), "done");

        // The service refuses it; what this pins is that the controller forwards it there
        // rather than having its own shortcut.
        verify(service).advance(REPO, "t-1", CustodyState.CUSTODY_TRANSFERRED, "done");
        verify(service, never()).passCustody(anyString(), anyString());
    }

    @Test
    @DisplayName("a state this machine does not have is a 400, not a 500")
    void anUnknownStateIsARequestProblem() {
        var response = controller.advance(REPO, "t-1", "TELEPORTED", "");

        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("not a state"),
                String.valueOf(response.getBody()));
        verify(service, never()).advance(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("a refusal is a 409 with the reason, and carries the limits")
    void aRefusalSaysWhy() {
        when(service.passCustody(anyString(), anyString())).thenReturn(
                new CustodyTransferService.Outcome(false, null,
                        "the ledger is not reachable, so custody has NOT passed", null));

        var response = controller.passCustody(REPO, "t-1");

        assertEquals(409, response.getStatusCode().value());
        assertEquals("refused", response.getBody().get("status"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("NOT passed"),
                String.valueOf(response.getBody()));
        assertTrue(String.valueOf(response.getBody().get("limits")).contains("do NOT send"),
                String.valueOf(response.getBody().get("limits")));
    }

    @Test
    @DisplayName("every response says what these endpoints do not establish")
    void theLimitsTravelWithEveryAnswer() {
        // Including the ones that look like good news. "CUSTODY_TRANSFERRED" reads as "the
        // record is safe somewhere else", and it does not mean that.
        assertTrue(CustodyTransferController.CUSTODY_LIMITS.contains("do NOT send anything"),
                CustodyTransferController.CUSTODY_LIMITS);
        assertTrue(CustodyTransferController.CUSTODY_LIMITS.contains(
                        "not that the record is safe somewhere else"),
                CustodyTransferController.CUSTODY_LIMITS);
    }

    @Test
    @DisplayName("without admin nothing is reachable")
    void notAdminIsForbidden() throws Exception {
        CallContext context = mock(CallContext.class);
        when(context.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.FALSE);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(context);
        controller.setHttpRequest(request);

        assertEquals(403, controller.passCustody(REPO, "t-1").getStatusCode().value());
        verify(service, never()).passCustody(anyString(), anyString());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the endpoint limits do not attribute a receipt to the receiver")
    void theEndpointLimitsSayWhoseStatementAReceiptIs() {
        // Same claim, fifth exit. CUSTODY_LIMITS said the endpoints record "what the receiving
        // system reported"; without a verified signature nothing establishes a receipt came from
        // there. The existing assertions on this constant only checked its closing clauses, so
        // reverting this half stayed green -- which is how it survived rounds 1 and 2.
        String limits = CustodyTransferController.CUSTODY_LIMITS;

        // This assertion used to REQUIRE "a receipt SAYS the receiving system reported" -- the
        // half-fix. A lock written against the wording you just typed pins whatever you typed,
        // including the part still wrong. What has to be true is that the receiver's name is not
        // attached to a value that arrives on this endpoint at all.
        // No trailing space. With one, this matched neither "the receiving system's" nor "this
        // receiving system" — the sibling test in CustodyTransferTest says so in its own
        // comment, and this one was not updated with it. Same claim, two locks, one corrected.
        org.junit.jupiter.api.Assertions.assertFalse(limits.contains("receiving system"),
                "the endpoint limits still hand the receiving system's name to something a REST "
                        + "caller posted: " + limits);
        org.junit.jupiter.api.Assertions.assertTrue(limits.contains("what outcome a receipt "),
                "the limits do not say whose statement a receipt's outcome is: " + limits);
        org.junit.jupiter.api.Assertions.assertTrue(
                limits.contains("who wrote") && limits.contains("signature was verified"),
                "nothing says that who wrote a receipt is an open question without a verified "
                        + "signature: " + limits);
        // The other half of the same sentence, which the ban above cannot see. It read "do NOT
        // send anything, DO NOT CHECK ANY CLAIM IN A RECEIPT, ..." three words after saying the
        // endpoints record "whether that report was about the package we sent" -- and
        // verifyReceipt checks the digest, the required fields and the outcome mapping. A
        // correction that lands too weak is still a wrong statement about the product, and a
        // reader who believes it reads RECEIPT_VERIFIED as meaning nothing at all.
        org.junit.jupiter.api.Assertions.assertFalse(limits.contains("do not check any claim"),
                "the limits deny a check the product performs, in the same sentence that "
                        + "describes it: " + limits);
        org.junit.jupiter.api.Assertions.assertTrue(limits.contains("receipt IS checked"),
                "the limits do not say that the receipt is checked at all: " + limits);
    }

    @Test
    @DisplayName("signatureCheck reaches the RESPONSE, on a refusal as well as a success")
    void theSignatureCheckLeavesTheJvm() {
        // The receipt's own boolean is false for three different reasons, and signatureCheck is
        // what says which. That was pinned in the SERVICE and nowhere else: this response body
        // is the only place the finding actually leaves the JVM, and deleting the six lines
        // that put it here left the whole suite green while CustodyReceipt went on telling
        // operators "see signatureCheck on the response that carried it".
        //
        // Driven on the REFUSED arm deliberately. A check that ran and failed is exactly as
        // reportable on a refusal as on a success, and putting it in one arm is how it came to
        // be missing in the first place.
        Map<String, Object> check = Map.of("verified", false, "reason", "the key did not match");
        // The canonical constructor of a public record is public (JLS 8.10.4) — only the
        // static factories are package-private. An earlier version built this by reflection,
        // believing the constructor was hidden too; a direct call fails at COMPILE time when
        // the shape changes, which is louder and sooner.
        when(service.verifyReceipt(anyString(), anyString(), any()))
                .thenReturn(new CustodyTransferService.Outcome(false, null,
                        "the receipt is about another package", check));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("submissionId", "sub-1");
        body.put("sipDigest", "a".repeat(64));
        body.put("verificationOutcome", "SUCCESS");
        body.put("receivedAt", "2026-08-30T00:00:00Z");
        org.springframework.http.ResponseEntity<Map<String, Object>> response =
                controller.receipt(REPO, "t-1", body);

        assertEquals(check, response.getBody().get("signatureCheck"),
                "the signature finding never left the JVM, so nothing outside it can tell a "
                        + "FAILED check from a missing key: " + response.getBody());
    }
}
