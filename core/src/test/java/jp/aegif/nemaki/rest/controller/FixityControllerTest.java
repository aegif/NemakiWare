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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
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
        //
        // This asserted eq("folder:f-1") -- and "folder:" said the pass covered the folder,
        // which it never did: scanFolder enumerates direct children only. So this lock was
        // pinning the overclaim into the append-only chain. A lock written against the string
        // the code happens to pass keeps whatever the code passes, right or wrong.
        org.mockito.Mockito.verify(recorder).recordPass(org.mockito.ArgumentMatchers.eq(REPO),
                org.mockito.ArgumentMatchers.eq("folder-children:f-1"),
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
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
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
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
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

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a folder scan says COMPLETE only beside the scope it actually covered")
    void aFolderScanNamesItsScope() throws Exception {
        // FixityScanService sets COMPLETE when the iterable it was handed runs out -- "the pass
        // reached the end of its scope" -- and scanFolder enumerates IMMEDIATE CHILDREN ONLY.
        // So COMPLETE went out beside a folderId for a scan that never entered a sub-folder:
        // the same COMPLETE trap as an index verdict that means "everything present is
        // stamped", and a folder reindex that skips the folder itself. Nothing asserted on it.
        FixityController controller = controllerFor(true);
        FixityScanService scans = mock(FixityScanService.class);
        FixityScanReport report = new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO,
                3, 3, 0, 0, 0, java.util.List.of(), null);
        when(scans.scan(anyString(), any(), anyInt())).thenReturn(report);
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
        when(contentService.getChildren(REPO, "f-1")).thenReturn(java.util.List.of());
        setField(controller, "fixityScanService", scans);
        setField(controller, "contentService", contentService);

        Object response = FixityController.class.getDeclaredMethod("scanFolder",
                        String.class, String.class, int.class)
                .invoke(controller, REPO, "f-1", 100);
        java.util.Map<String, Object> body = (java.util.Map<String, Object>)
                response.getClass().getMethod("getBody").invoke(response);

        assertEquals("COMPLETE", String.valueOf(body.get("verdict")),
                "fixture check: this test is only meaningful while the verdict is COMPLETE");
        assertEquals("IMMEDIATE_CHILDREN_ONLY", body.get("scope"),
                "a COMPLETE verdict left this endpoint without saying what it covered, so a "
                        + "reader takes it for the whole sub-tree");
        org.junit.jupiter.api.Assertions.assertNotNull(body.get("scopeLimits"));
        assertTrue(String.valueOf(body.get("scopeLimits")).contains("did not descend"),
                String.valueOf(body.get("scopeLimits")));
    }

    @Test
    @DisplayName("the scope recorded in the CHAIN is the one actually covered")
    void theChainedScopeSaysWhatWasActuallyCovered() throws Exception {
        // The response and the chain are two arms of one obligation, and the first version of
        // this fix reached only the response: the recorder was still handed "folder:" + id while
        // the response said IMMEDIATE_CHILDREN_ONLY. FixityLedgerRecorder.passDigest commits the
        // verdict and the scope to ONE digest, and a ledger entry is append-only and never
        // purged -- so that arm is the copy of the overclaim nobody can correct later.
        //
        // The earlier lock wired only fixityScanService and contentService, leaving the recorder
        // null, so the chain arm was never exercised at all.
        FixityController controller = controllerFor(true);
        FixityScanService scans = mock(FixityScanService.class);
        when(scans.scan(anyString(), any(), anyInt())).thenReturn(
                new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO, 3, 3, 0, 0, 0,
                        java.util.List.of(), null));
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
        when(contentService.getChildren(REPO, "f-1")).thenReturn(java.util.List.of());
        FixityLedgerRecorder recorder = mock(FixityLedgerRecorder.class);
        when(recorder.recordPass(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FixityLedgerRecorder.Recorded(true, null));
        setField(controller, "fixityScanService", scans);
        setField(controller, "contentService", contentService);
        setField(controller, "fixityLedgerRecorder", recorder);

        FixityController.class.getDeclaredMethod("scanFolder", String.class, String.class,
                int.class).invoke(controller, REPO, "f-1", 100);

        org.mockito.ArgumentCaptor<String> scope =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(recorder).recordPass(anyString(), scope.capture(), any(),
                anyString());
        org.junit.jupiter.api.Assertions.assertFalse("folder:f-1".equals(scope.getValue()),
                "the chain records the pass as covering the FOLDER, beside a COMPLETE verdict, "
                        + "for a scan that never entered a sub-folder: " + scope.getValue());
        assertEquals("folder-children:f-1", scope.getValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a FAILED pass is not answered 200 success")
    void aFailedPassIsNotSuccess() throws Exception {
        // FixityScanService deliberately does not throw -- "A pass that died half way through
        // has counts that describe nothing in particular. FAILED, not PARTIAL" -- so failure
        // lives in the RETURN VALUE. This method wrote status:"success" before looking at the
        // verdict, while its own error arm already paired FixityScanReport.failed(...) with
        // status:"error" and a 500. The rule was applied to the failure this method produces
        // and not to the failure the service hands back.
        FixityController controller = controllerFor(true);
        FixityScanService scans = mock(FixityScanService.class);
        when(scans.scan(anyString(), any(), anyInt())).thenReturn(
                FixityScanReport.failed(REPO, "the pass died half way through"));
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
        when(contentService.getChildren(REPO, "f-1")).thenReturn(java.util.List.of());
        setField(controller, "fixityScanService", scans);
        setField(controller, "contentService", contentService);

        Object response = FixityController.class.getDeclaredMethod("scanFolder",
                        String.class, String.class, int.class)
                .invoke(controller, REPO, "f-1", 100);
        java.util.Map<String, Object> body = (java.util.Map<String, Object>)
                response.getClass().getMethod("getBody").invoke(response);
        HttpStatus code = (HttpStatus) response.getClass().getMethod("getStatusCode")
                .invoke(response);

        assertEquals("FAILED", String.valueOf(body.get("verdict")),
                "fixture check: this test only means something while the verdict is FAILED");
        org.junit.jupiter.api.Assertions.assertNotEquals("success", body.get("status"),
                "a FAILED pass is announced as a success: " + body);
        assertTrue(code != HttpStatus.OK,
                "a FAILED pass answered " + code + "; tooling reads that without opening the "
                        + "body");
    }

    private static jp.aegif.nemaki.model.Content aFolder() {
        jp.aegif.nemaki.model.Folder folder = new jp.aegif.nemaki.model.Folder();
        folder.setId("f-1");
        folder.setType(org.apache.chemistry.opencmis.commons.enums.BaseTypeId.CMIS_FOLDER.value());
        return folder;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a folder that is not there is not scanned COMPLETE with zero findings")
    void anAbsentFolderIsNotACleanScan() throws Exception {
        // getChildren answers [] for a folder id that does not resolve — a typo, a document id,
        // an object deleted since — exactly as it answers for an empty folder. The report built
        // from [] is verdict=COMPLETE, scanned=0, mismatch=0, and this endpoint writes that
        // verdict into the append-only evidence chain, where it cannot be corrected: a permanent
        // record that a folder nobody looked at came back clean.
        //
        // Five tests in this class scanned "f-1" without ever saying it existed.
        FixityScanService scanService = mock(FixityScanService.class);
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "gone")).thenReturn(null);
        FixityLedgerRecorder recorder = mock(FixityLedgerRecorder.class);
        FixityController controller = controllerFor(true);
        setField(controller, "fixityScanService", scanService);
        setField(controller, "contentService", contentService);
        setField(controller, "fixityLedgerRecorder", recorder);

        org.springframework.http.ResponseEntity<Map<String, Object>> response =
                controller.scanFolder(REPO, "gone", 200);

        assertEquals(404, response.getStatusCode().value(), String.valueOf(response.getBody()));
        assertEquals("error", response.getBody().get("status"));
        assertFalse(String.valueOf(response.getBody()).contains("COMPLETE"),
                "an unscanned folder was given a verdict: " + response.getBody());
        assertTrue(String.valueOf(response.getBody().get("message"))
                        .contains("NOT a finding"),
                "the refusal does not say what it is not: " + response.getBody());
        // And nothing reaches the chain. The response can be corrected; the chain cannot.
        org.mockito.Mockito.verify(recorder, org.mockito.Mockito.never())
                .recordPass(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString());
        // The control: a folder that IS there still scans. Without this, refusing everything
        // would satisfy the assertions above.
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
        when(contentService.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(scanService.scan(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO,
                        0, 0, 0, 0, 0, List.of(), null));

        when(recorder.recordPass(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new FixityLedgerRecorder.Recorded(true, null));

        assertEquals(200, controller.scanFolder(REPO, "f-1", 200).getStatusCode().value());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("children the store could not read are not scanned COMPLETE over the folder")
    void unreadableChildrenAreNotACleanPass() throws Exception {
        // The absent-folder guard covers "the id resolves to nothing". This is the same
        // substitution when the folder IS there and part of it could not be read: a row the
        // view returned and the DAO could not decode is a child that EXISTS, is not in the list
        // handed to the scan, and the scan's COMPLETE then means "the end of a list that was
        // short". That verdict goes into the append-only chain paired with its scope.
        //
        // Both arms are asserted -- the response AND the chain scope -- because correcting the
        // response and leaving the chain is a pairing this very endpoint was already fixed for
        // once, and the chain is the arm that cannot be corrected afterwards.
        FixityScanService scanService = mock(FixityScanService.class);
        when(scanService.scan(anyString(), any(), anyInt())).thenReturn(
                new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO, 2, 2, 0, 0, 0,
                        List.of(), null));
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
        when(contentService.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(3);
        FixityLedgerRecorder recorder = mock(FixityLedgerRecorder.class);
        when(recorder.recordPass(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FixityLedgerRecorder.Recorded(true, null));
        FixityController controller = controllerFor(true);
        setField(controller, "fixityScanService", scanService);
        setField(controller, "contentService", contentService);
        controller.setFixityLedgerRecorder(recorder);

        org.springframework.http.ResponseEntity<Map<String, Object>> response =
                controller.scanFolder(REPO, "f-1", 200);
        Map<String, Object> body = response.getBody();

        assertEquals(3, body.get("unreadableChildren"), String.valueOf(body));
        assertEquals("partial", body.get("status"),
                "a pass that skipped part of the folder was reported as a clean success: " + body);
        assertTrue(String.valueOf(body.get("scopeLimits")).contains("NOT a finding"),
                "the response does not say what the gap is not: " + body);
        // The chain scope. eq(), not contains(): the entry commits verdict and scope together.
        org.mockito.Mockito.verify(recorder).recordPass(org.mockito.ArgumentMatchers.eq(REPO),
                org.mockito.ArgumentMatchers.eq("folder-children-partial:f-1:unread=3"),
                org.mockito.ArgumentMatchers.any(), anyString());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a folder read in full is still an ordinary pass — the control")
    void aFullyReadFolderIsStillAPlainPass() throws Exception {
        // Without this, marking every scan partial would satisfy the test above and no pass
        // could ever be recorded as covering its scope.
        FixityScanService scanService = mock(FixityScanService.class);
        when(scanService.scan(anyString(), any(), anyInt())).thenReturn(
                new FixityScanReport(FixityScanReport.Verdict.COMPLETE, REPO, 2, 2, 0, 0, 0,
                        List.of(), null));
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
        when(contentService.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(0);
        FixityLedgerRecorder recorder = mock(FixityLedgerRecorder.class);
        when(recorder.recordPass(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FixityLedgerRecorder.Recorded(true, null));
        FixityController controller = controllerFor(true);
        setField(controller, "fixityScanService", scanService);
        setField(controller, "contentService", contentService);
        controller.setFixityLedgerRecorder(recorder);

        Map<String, Object> body = controller.scanFolder(REPO, "f-1", 200).getBody();

        assertEquals("success", body.get("status"), String.valueOf(body));
        assertNull(body.get("unreadableChildren"), String.valueOf(body));
        org.mockito.Mockito.verify(recorder).recordPass(org.mockito.ArgumentMatchers.eq(REPO),
                org.mockito.ArgumentMatchers.eq("folder-children:f-1"),
                org.mockito.ArgumentMatchers.any(), anyString());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a FAILED pass that also lost children is an error, not 'partial'")
    void aFailedPassIsNotDowngradedToPartial() throws Exception {
        // The two facts were written as two statements: status from the verdict, then "partial"
        // if children were lost. The second overwrote the first, so a pass that DIED and lost
        // children came back saying "partial" — which reads as "mostly fine" — beside an HTTP
        // 500 computed from the verdict. The body and the code disagreed about the same run.
        //
        // "partial" downgrades a success; it must never upgrade a failure.
        FixityScanService scanService = mock(FixityScanService.class);
        when(scanService.scan(anyString(), any(), anyInt())).thenReturn(
                FixityScanReport.failed(REPO, "the pass died half way through"));
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "f-1")).thenReturn(aFolder());
        when(contentService.getChildren(REPO, "f-1")).thenReturn(List.of());
        when(contentService.lastUnreadableChildCount()).thenReturn(2);
        FixityLedgerRecorder recorder = mock(FixityLedgerRecorder.class);
        when(recorder.recordPass(anyString(), anyString(), any(), anyString()))
                .thenReturn(new FixityLedgerRecorder.Recorded(true, null));
        FixityController controller = controllerFor(true);
        setField(controller, "fixityScanService", scanService);
        setField(controller, "contentService", contentService);
        controller.setFixityLedgerRecorder(recorder);

        org.springframework.http.ResponseEntity<Map<String, Object>> response =
                controller.scanFolder(REPO, "f-1", 200);
        Map<String, Object> body = response.getBody();

        assertEquals("error", body.get("status"),
                "a pass that died was downgraded to 'partial' because children were also lost: "
                        + body);
        assertEquals(500, response.getStatusCode().value(),
                "the body and the HTTP code disagree about the same run: " + body);
        assertEquals(2, body.get("unreadableChildren"),
                "the lost children stopped being reported when the pass failed: " + body);

        // And the arm that cannot be corrected afterwards. A chain reader sees only this
        // string; the verdict lives inside passDigest, a one-way hash. "partial" is reserved
        // for a reduction made ON PURPOSE (FixityScanService says so where it chooses FAILED
        // over it), and "unread=2" claims the shortfall was exactly two when the pass also
        // died at an unknown point.
        org.mockito.ArgumentCaptor<String> scope =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(recorder).recordPass(eq(REPO), scope.capture(), any(), anyString());
        assertEquals("folder-children-incomplete:f-1:unreadAtLeast=2", scope.getValue(),
                "the permanent record of a pass that DIED says it stopped on purpose and "
                        + "accounts for the gap exactly");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("an id that names nothing is a 404, not a verified-but-unverifiable object")
    void anIdThatNamesNothingIsNotASuccessfulVerification() throws Exception {
        // verifyOne answers UNVERIFIABLE for a null content, and the wrapper shipped that as
        // HTTP 200 / status:"success" / outcome:"UNVERIFIABLE" — so a typo came back looking
        // like an object that WAS looked at and whose bytes could not be confirmed. Those are
        // different things to be told: one sends an operator to the storage layer, the other
        // to their own request. The folder endpoint beside it got this a round earlier.
        ContentService contentService = mock(ContentService.class);
        when(contentService.getContent(REPO, "no-such-id")).thenReturn(null);
        FixityScanService scanService = mock(FixityScanService.class);
        FixityController controller = controllerFor(true);
        setField(controller, "contentService", contentService);
        setField(controller, "fixityScanService", scanService);

        org.springframework.http.ResponseEntity<Map<String, Object>> response =
                controller.verifyOne(REPO, "no-such-id");
        Map<String, Object> body = response.getBody();

        assertEquals(404, response.getStatusCode().value(),
                "an id that resolves to nothing was answered 200: " + body);
        assertEquals("error", body.get("status"),
                "a lookup that found nothing was reported as a successful verification: " + body);
        assertTrue(String.valueOf(body.get("message")).contains("NOT a finding"),
                "the refusal does not say it establishes nothing about any bytes: " + body);
        verify(scanService, org.mockito.Mockito.never()).verifyOne(anyString(), any());
    }
}
