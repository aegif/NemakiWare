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
                new CustodyTransferService.Outcome(false, null, "refused for this test"));

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
                new CustodyTransferService.Outcome(false, null, "use passCustody"));

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
                        "the ledger is not reachable, so custody has NOT passed"));

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
}
