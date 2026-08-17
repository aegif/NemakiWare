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

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jp.aegif.nemaki.epoch.AclEpochMigrationService;
import jp.aegif.nemaki.util.constant.CallContextKey;

/** Increment 13: the orphan-delete confirm gate — a paste-able URL must not destroy index docs. */
public class AclEpochMigrationControllerOrphanTest {

    private AclEpochMigrationController controller;
    private AclEpochMigrationService svc;

    @BeforeEach
    void setUp() {
        svc = mock(AclEpochMigrationService.class);
        controller = new AclEpochMigrationController();
        controller.setMigrationService(svc);
        HttpServletRequest r = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(r.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(r);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> r) {
        return (Map<String, Object>) r.getBody();
    }

    /** Without ?confirm=true NOTHING is touched — and the response says exactly what to run. */
    @Test
    public void withoutConfirmNothingIsDeleted() {
        ResponseEntity<?> r = controller.deleteOrphans("bedroom", false, 1000);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertTrue(String.valueOf(body(r).get("message")).contains("confirm=true"));
        verify(svc, never()).deleteOrphans(anyString(), anyInt());
    }

    @Test
    public void withConfirmTheDeleteRuns() {
        when(svc.deleteOrphans("bedroom", 1000)).thenReturn(
                newResult(35, 0, 0, 0));
        ResponseEntity<?> r = controller.deleteOrphans("bedroom", true, 1000);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(35, body(r).get("deleted"));
    }

    @Test
    public void unknownRepositoryIs404OnBothRoutes() {
        when(svc.scanOrphans(anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("unknown repository 'x'"));
        when(svc.deleteOrphans(anyString(), anyInt()))
                .thenThrow(new IllegalArgumentException("unknown repository 'x'"));
        assertEquals(HttpStatus.NOT_FOUND, controller.orphans("x", 10).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.deleteOrphans("x", true, 10).getStatusCode());
    }

    private static AclEpochMigrationService.OrphanDeleteResult newResult(int d, int s, int u, int l) {
        try {
            var c = AclEpochMigrationService.OrphanDeleteResult.class
                    .getDeclaredConstructor(int.class, int.class, int.class, int.class);
            c.setAccessible(true);
            return c.newInstance(d, s, u, l);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
