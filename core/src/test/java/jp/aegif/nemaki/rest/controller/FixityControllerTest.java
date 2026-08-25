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

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.evidence.FixityLedgerRecorder;
import jp.aegif.nemaki.fixity.FixityScanReport;
import jp.aegif.nemaki.fixity.FixityScanService;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The fixity API is admin-only, and a pass it ran reaches the evidence chain.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>It did not. {@code FixityController} shipped with two endpoints, two {@code requireAdmin()}
 * calls and zero tests — the same shape a review found in {@code AnchorController}, where
 * deleting three lines would have opened the door with nothing noticing. This API reports which
 * of an organisation's documents no longer hash to what was recorded, which is a list worth
 * having if you are the one who changed them.
 */
class FixityControllerTest {

    private static final String REPO = "bedroom";

    private static FixityController controllerFor(boolean admin) {
        FixityController controller = new FixityController();
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);
        return controller;
    }

    private static List<Method> mappedEndpoints() {
        List<Method> endpoints = new ArrayList<>();
        for (Method method : FixityController.class.getDeclaredMethods()) {
            for (Class<? extends java.lang.annotation.Annotation> mapping : List.of(
                    GetMapping.class, PostMapping.class,
                    org.springframework.web.bind.annotation.PutMapping.class,
                    org.springframework.web.bind.annotation.DeleteMapping.class,
                    org.springframework.web.bind.annotation.PatchMapping.class,
                    org.springframework.web.bind.annotation.RequestMapping.class)) {
                if (method.getAnnotation(mapping) != null) {
                    endpoints.add(method);
                    break;
                }
            }
        }
        return endpoints;
    }

    private static Object[] argumentsFor(Method method) {
        Object[] args = new Object[method.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = method.getParameterTypes()[i];
            if (type == String.class) {
                args[i] = REPO;
            } else if (type == int.class) {
                args[i] = 10;
            } else if (type == boolean.class) {
                args[i] = false;
            }
        }
        return args;
    }

    @Test
    @DisplayName("EVERY mapped endpoint refuses a non-admin")
    void everyEndpointIsAdminOnly() throws Exception {
        FixityController controller = controllerFor(false);
        List<Method> endpoints = mappedEndpoints();

        assertEquals(2, endpoints.size(),
                "the fixity API has " + endpoints.size() + " mapped endpoints, not 2; if one "
                        + "was added, confirm it is gated and update this number");

        for (Method endpoint : endpoints) {
            Object response = endpoint.invoke(controller, argumentsFor(endpoint));
            HttpStatus status = (HttpStatus) response.getClass()
                    .getMethod("getStatusCode").invoke(response);
            assertEquals(HttpStatus.FORBIDDEN, status,
                    endpoint.getName() + " served a non-admin. This API lists which documents "
                            + "no longer hash to what was recorded — a useful list to whoever "
                            + "changed them.");
        }
    }

    @Test
    @DisplayName("a folder scan reaches the evidence chain, and says it did")
    void aScanIsChained() throws Exception {
        FixityController controller = controllerFor(true);
        FixityScanService scanService = mock(FixityScanService.class);
        FixityScanReport report = new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO,
                3, 3, 0, 0, 0, List.of(), null);
        when(scanService.scan(anyString(), any(), anyInt())).thenReturn(report);
        setField(controller, "fixityScanService", scanService);
        ContentService contentService = mock(ContentService.class);
        when(contentService.getChildren(REPO, "f-1")).thenReturn(List.of());
        setField(controller, "contentService", contentService);
        FixityLedgerRecorder recorder = mock(FixityLedgerRecorder.class);
        when(recorder.recordPass(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FixityLedgerRecorder.Recorded(true, null));
        controller.setFixityLedgerRecorder(recorder);

        Object response = FixityController.class.getDeclaredMethod("scanFolder",
                        String.class, String.class, int.class)
                .invoke(controller, REPO, "f-1", 200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getClass()
                .getMethod("getBody").invoke(response);

        // The SCOPE, not just "something was recorded": a repository has many passes and an
        // entry that does not say which one is not findable.
        org.mockito.Mockito.verify(recorder).recordPass(org.mockito.ArgumentMatchers.eq(REPO),
                org.mockito.ArgumentMatchers.eq("folder:f-1"),
                org.mockito.ArgumentMatchers.same(report), anyString());
        assertEquals(Boolean.TRUE, body.get("chained"),
                "the response does not say the pass reached the chain: " + body);
        assertNull(body.get("chainWarning"));
    }

    @Test
    @DisplayName("a chain gap reaches the caller and does not fail the scan")
    void aChainGapIsReportedNotHidden() throws Exception {
        // Fail-open here, unlike disposition: the pass has already run and its results are in
        // this very response. Losing them to protect a record of them would be backwards.
        FixityController controller = controllerFor(true);
        FixityScanService scanService = mock(FixityScanService.class);
        when(scanService.scan(anyString(), any(), anyInt())).thenReturn(
                new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO, 3, 3, 0, 0, 0,
                        List.of(), null));
        setField(controller, "fixityScanService", scanService);
        ContentService contentService = mock(ContentService.class);
        when(contentService.getChildren(REPO, "f-1")).thenReturn(List.of());
        setField(controller, "contentService", contentService);
        FixityLedgerRecorder recorder = mock(FixityLedgerRecorder.class);
        when(recorder.recordPass(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FixityLedgerRecorder.Recorded(false,
                        "the chain is missing this entry and will not be back-filled"));
        controller.setFixityLedgerRecorder(recorder);

        Object response = FixityController.class.getDeclaredMethod("scanFolder",
                        String.class, String.class, int.class)
                .invoke(controller, REPO, "f-1", 200);
        HttpStatus status = (HttpStatus) response.getClass()
                .getMethod("getStatusCode").invoke(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getClass()
                .getMethod("getBody").invoke(response);

        assertEquals(HttpStatus.OK, status, "a chain gap failed a scan that ran fine");
        assertEquals("success", body.get("status"));
        assertEquals(Boolean.FALSE, body.get("chained"));
        assertTrue(String.valueOf(body.get("chainWarning")).contains("back-filled"),
                "the caller was not told the chain is missing this pass: " + body);
    }

    @Test
    @DisplayName("no recorder wired leaves 'chained' ABSENT, not false")
    void anUnwiredRecorderIsNotAFailedOne() {
        // "We did not try" and "we tried and failed" must not read alike. A false here would
        // tell an operator the chain rejected a pass nobody ever offered it.
        FixityController controller = controllerFor(true);
        FixityScanService scanService = mock(FixityScanService.class);
        when(scanService.scan(anyString(), any(), anyInt())).thenReturn(
                new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO, 3, 3, 0, 0, 0,
                        List.of(), null));
        ContentService contentService = mock(ContentService.class);
        when(contentService.getChildren(REPO, "f-1")).thenReturn(List.of());
        try {
            setField(controller, "fixityScanService", scanService);
            setField(controller, "contentService", contentService);
            Object response = FixityController.class.getDeclaredMethod("scanFolder",
                            String.class, String.class, int.class)
                    .invoke(controller, REPO, "f-1", 200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getClass()
                    .getMethod("getBody").invoke(response);
            assertTrue(!body.containsKey("chained"),
                    "a deployment with no evidence ledger reported chained=false, which reads "
                            + "as a chain that refused the pass: " + body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
