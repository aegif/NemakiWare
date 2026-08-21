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

import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore.UnresolvedPage;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The unresolved listing endpoint.
 *
 * <p>Two things must not be confused with each other: "nothing is unresolved" and "we cannot
 * tell". They render identically as an empty list, and only the first is something an operator
 * should act on.
 */
class CaptureIntentControllerTest {

    private static CaptureIntentController controller(CaptureMaintenanceStore store,
            boolean admin) {
        CaptureIntentController c = new CaptureIntentController();
        c.setMaintenanceStore(store);
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(admin);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        c.setHttpRequest(request);
        return c;
    }

    private static UnresolvedPage page(int count, boolean hasMore) {
        return new UnresolvedPage(
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(i -> Map.<String, Object>of("intentId", "i-" + i,
                                "repositoryId", "bedroom", "sourceSystem", "slack"))
                        .toList(),
                50, 0, hasMore);
    }

    @Test
    @DisplayName("an admin sees the rows, whole")
    void adminSeesTheRows() {
        // The row IS the report. An intent id and a timestamp would not tell anyone what was
        // being ingested when it stopped.
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listUnresolved(null, 50, 0)).thenReturn(page(2, false));

        ResponseEntity<Map<String, Object>> response =
                controller(store, true).listUnresolved(null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().get("count"));
        assertEquals(Boolean.FALSE, response.getBody().get("hasMore"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) response.getBody().get("entries");
        assertEquals("slack", entries.get(0).get("sourceSystem"));
    }

    @Test
    @DisplayName("a non-admin is refused")
    void nonAdminIsRefused() {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listUnresolved(null, 50, 0)).thenReturn(page(2, false));

        ResponseEntity<Map<String, Object>> response =
                controller(store, false).listUnresolved(null, 50, 0);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    @DisplayName("no call context is not an admin")
    void missingCallContextIsNotAdmin() {
        CaptureIntentController c = new CaptureIntentController();
        c.setMaintenanceStore(mock(CaptureMaintenanceStore.class));
        c.setHttpRequest(mock(HttpServletRequest.class));   // getAttribute returns null

        assertEquals(HttpStatus.FORBIDDEN, c.listUnresolved(null, 50, 0).getStatusCode());
    }

    @Test
    @DisplayName("an unwired boundary is not reported as an empty listing")
    void unwiredIsNotEmpty() {
        // The distinction that matters. An empty list here would tell an operator that every
        // ingest completed, when in fact nothing was ever recorded.
        ResponseEntity<Map<String, Object>> response =
                controller(null, true).listUnresolved(null, 50, 0);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotEquals("success", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").toString().contains("cannot be answered"));
    }

    @Test
    @DisplayName("a failed read is an error, not an empty listing")
    void failedReadIsNotEmpty() {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listUnresolved(null, 50, 0))
                .thenThrow(new RuntimeException("lineage database unreachable"));

        ResponseEntity<Map<String, Object>> response =
                controller(store, true).listUnresolved(null, 50, 0);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    @DisplayName("an unreadable view surfaces as an error, never as an empty listing")
    void unreadableViewIsNotEmptyListing() {
        // The store now throws rather than answering with an empty list, so this is the path an
        // index rebuild or a database outage actually takes.
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listUnresolved(null, 50, 0)).thenThrow(new RuntimeException(
                "view lineage_capture/unresolved_by_opened_at could not be read"));

        ResponseEntity<Map<String, Object>> response =
                controller(store, true).listUnresolved(null, 50, 0);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotEquals("success", response.getBody().get("status"));
        assertEquals(0, ((Number) response.getBody().getOrDefault("count", 0)).intValue(),
                "and certainly not a count of zero presented as an answer");
    }

    @Test
    @DisplayName("a genuinely empty listing is a success — the control")
    void genuinelyEmptyIsSuccess() {
        // Without this, returning SERVICE_UNAVAILABLE unconditionally would pass the two tests
        // above while making the endpoint useless.
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listUnresolved(null, 50, 0)).thenReturn(page(0, false));

        ResponseEntity<Map<String, Object>> response =
                controller(store, true).listUnresolved(null, 50, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody().get("status"));
        assertEquals(0, response.getBody().get("count"));
    }

    // ── Counts ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("counts are reported per state")
    void countsAreReported() {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.countByState(10000)).thenReturn(
                new CaptureMaintenanceStore.CaptureCounts(3, 7, 120_000, false));

        ResponseEntity<Map<String, Object>> response = controller(store, true).counts(10000);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3L, response.getBody().get("captureIntent"));
        assertEquals(7L, response.getBody().get("unresolved"));
        assertEquals(120_000L, response.getBody().get("captured"));
        assertEquals(Boolean.FALSE, response.getBody().get("truncated"));
    }

    @Test
    @DisplayName("a truncated count is not presented as a total")
    void truncatedCountSaysSo() {
        // A lower bound reported as a total would tell an operator the growth had stopped.
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.countByState(10000)).thenReturn(
                new CaptureMaintenanceStore.CaptureCounts(0, 0, 10_000, true));

        ResponseEntity<Map<String, Object>> response = controller(store, true).counts(10000);

        assertEquals(Boolean.TRUE, response.getBody().get("truncated"));
        assertTrue(response.getBody().get("message").toString().contains("lower bounds"),
                String.valueOf(response.getBody().get("message")));
    }

    @Test
    @DisplayName("counts are admin-only")
    void countsAreAdminOnly() {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);

        assertEquals(HttpStatus.FORBIDDEN,
                controller(store, false).counts(10000).getStatusCode());
    }

    @Test
    @DisplayName("counts that cannot be read are an error, not zeroes")
    void unreadableCountsAreNotZero() {
        // Zeroes would read as "nothing is accumulating", which is the reassuring failure again.
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.countByState(10000)).thenThrow(new RuntimeException("view unreadable"));

        ResponseEntity<Map<String, Object>> response = controller(store, true).counts(10000);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotEquals("success", response.getBody().get("status"));
    }

    @Test
    @DisplayName("the repository filter and paging reach the store")
    void filterAndPagingArePassedThrough() {
        CaptureMaintenanceStore store = mock(CaptureMaintenanceStore.class);
        when(store.listUnresolved("canopy", 10, 20))
                .thenReturn(new UnresolvedPage(List.of(), 10, 20, true));

        ResponseEntity<Map<String, Object>> response =
                controller(store, true).listUnresolved("canopy", 10, 20);

        assertEquals(10, response.getBody().get("limit"));
        assertEquals(20, response.getBody().get("offset"));
        assertEquals(Boolean.TRUE, response.getBody().get("hasMore"));
    }
}
