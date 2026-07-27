package jp.aegif.nemaki.rest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.epoch.AclEpochFinalizationService;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * Guards on the ACL-epoch scanner admin surface (increment 11).
 *
 * <p>The sweep itself is covered by {@code AclEpochFinalizationServiceIT} against a live CouchDB;
 * what is tested here is everything that decides WHETHER it runs — the places where the previous two
 * increments were caught reporting an absence as a success.
 */
public class AclEpochScanControllerTest {

    private AclEpochScanController controller;
    private AclEpochFinalizationService svc;

    @BeforeEach
    void setUp() {
        svc = mock(AclEpochFinalizationService.class);
        RepositoryInfoMap repos = mock(RepositoryInfoMap.class);
        when(repos.contains("bedroom")).thenReturn(true);
        when(repos.keys()).thenReturn(Set.of("bedroom", "canopy"));

        controller = new AclEpochScanController();
        controller.setFinalizationService(svc);
        controller.setRepositoryInfoMap(repos);
        controller.setHttpRequest(adminRequest(true));
    }

    private static HttpServletRequest adminRequest(boolean admin) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(r.getAttribute("CallContext")).thenReturn(ctx);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> r) {
        return (Map<String, Object>) r.getBody();
    }

    /**
     * An UNKNOWN repository must be a 404 and must NOT touch the scanner.
     *
     * <p>Increment 10a found this exact shape in the migration runner: a typo matched nothing, the
     * operation "succeeded", and an all-zero result was indistinguishable from a clean one. A scan of
     * a nonexistent repository would report a summary of all zeros — a clean sweep, to the eye.
     */
    @Test
    public void anUnknownRepositoryIs404AndNeverReachesTheScanner() {
        ResponseEntity<?> r = controller.scan("bedr00m-typo", 0);
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertTrue(String.valueOf(body(r).get("message")).contains("bedroom"),
                "and it must say what IS configured: " + body(r).get("message"));
        verify(svc, never()).scan(anyString(), anyInt());

        ResponseEntity<?> f = controller.finalizeOne("bedr00m-typo", "d1");
        assertEquals(HttpStatus.NOT_FOUND, f.getStatusCode());
        verify(svc, never()).finalizePending(anyString(), anyString());

        assertEquals(HttpStatus.NOT_FOUND, controller.lastScan("bedr00m-typo").getStatusCode());
    }

    /** Without the map every id would look valid — that must be a 503, not an open door. */
    @Test
    public void aMissingRepositoryInfoMapRefusesRatherThanAcceptingEveryId() {
        controller.setRepositoryInfoMap(null);
        ResponseEntity<?> r = controller.scan("bedroom", 0);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode());
        verify(svc, never()).scan(anyString(), anyInt());
    }

    /** Non-admin gets 403 before anything else is evaluated. */
    @Test
    public void nonAdminIsForbidden() {
        controller.setHttpRequest(adminRequest(false));
        assertEquals(HttpStatus.FORBIDDEN, controller.scan("bedroom", 0).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.finalizeOne("bedroom", "d1").getStatusCode());
        verify(svc, never()).scan(anyString(), anyInt());
    }

    /**
     * A second concurrent sweep of the SAME repository is refused. It would be safe — every write is
     * a CAS — but the two summaries would each report a fraction of the work, and the summary is the
     * only thing the operator has to decide whether to run it again.
     */
    @Test
    public void aSecondConcurrentScanOfTheSameRepositoryIsRefused() throws Exception {
        CountDownLatch inScan = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(svc.scan(anyString(), anyInt())).thenAnswer(inv -> {
            inScan.countDown();
            release.await();
            return new AclEpochFinalizationService.ScanSummary();
        });

        Thread first = new Thread(() -> controller.scan("bedroom", 0));
        first.start();
        assertTrue(inScan.await(5, java.util.concurrent.TimeUnit.SECONDS));

        ResponseEntity<?> second = controller.scan("bedroom", 0);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());

        release.countDown();
        first.join(5000);

        // ...and the guard is RELEASED afterwards, or one scan would disable the endpoint for ever.
        ResponseEntity<?> third = controller.scan("bedroom", 0);
        assertEquals(HttpStatus.OK, third.getStatusCode());
    }

    /** more=true must be surfaced as an instruction, not left for the reader to infer. */
    @Test
    public void moreTrueTellsTheOperatorToRunItAgain() {
        AclEpochFinalizationService.ScanSummary s = new AclEpochFinalizationService.ScanSummary();
        s.scanned = 500;
        s.more = true;
        when(svc.scan(anyString(), anyInt())).thenReturn(s);

        Map<String, Object> b = body(controller.scan("bedroom", 0));
        assertEquals(Boolean.TRUE, b.get("more"));
        assertTrue(String.valueOf(b.get("note")).contains("again"), String.valueOf(b.get("note")));
        assertEquals(500, b.get("scanned"));
    }

    /**
     * A missing Mango index makes the scan REFUSE to run (it will not let CouchDB silently
     * full-scan). That is a deployment fault, so it must not be reported as a server error the
     * operator can do nothing about.
     */
    @Test
    public void aMissingMangoIndexIsAPreconditionFailure_notA500() {
        when(svc.scan(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("ACL epoch scan refused to run: no index named ..."));
        ResponseEntity<?> r = controller.scan("bedroom", 0);
        assertEquals(HttpStatus.PRECONDITION_FAILED, r.getStatusCode());
    }

    /**
     * A corrupt document must NOT be quarantined by this one-shot endpoint: quarantine is the
     * scanner's decision, taken with a re-read and a re-validation, and isolating on a single
     * observation could strand a document a concurrent repair had already fixed.
     */
    @Test
    public void aCorruptDocumentIsReportedNotQuarantinedHere() {
        when(svc.finalizePending(anyString(), anyString()))
                .thenThrow(new jp.aegif.nemaki.epoch.AclEpochAnomalyException("corrupt epoch on d1"));
        ResponseEntity<?> r = controller.finalizeOne("bedroom", "d1");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.getStatusCode());
        assertTrue(String.valueOf(body(r).get("message")).contains("run a scan"),
                String.valueOf(body(r).get("message")));
    }
}
