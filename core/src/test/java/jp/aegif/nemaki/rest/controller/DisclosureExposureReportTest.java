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

import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageEventBuilder;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalEntry;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalRow;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.util.constant.CallContextKey;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which stored events carry facts the evidence table now calls INTERNAL_ONLY.
 *
 * <h2>The fail-open this closes</h2>
 *
 * <p>Disclosure §1.1: the product's retention governs the journal, and the catalog has no such
 * rule. Since P1-1(e) a v2 record structurally cannot carry an INTERNAL_ONLY fact to a sink, so
 * nothing new goes out — but v1 events sent their whole snapshot map, and "these values are
 * already in a catalog we do not own" had no way to be answered. The endpoint answers it; the
 * operator acts on their own catalog.
 */
class DisclosureExposureReportTest {

    private LineageJournalStore store;
    private LineageJournalController controller;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(LineageJournalStore.class);
        controller = new LineageJournalController();
        set(controller, "journalStore", store);
        set(controller, "lineageConfig",
                mock(jp.aegif.nemaki.rest.purview.journal.LineageConfig.class));

        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = LineageJournalController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static LineageJournalRow v1RowWith(String... snapshotPairs) {
        LineageEventBuilder builder = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .addOutput("nemaki://bedroom/archives/doc-1")
                .targets(List.of("purview"));
        for (int i = 0; i < snapshotPairs.length; i += 2) {
            builder.snapshotAttribute(snapshotPairs[i], snapshotPairs[i + 1]);
        }
        LineageEvent event = builder.build();
        return new LineageJournalRow.Decoded(LineageJournalEntry.ofV1(event));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> scan() {
        ResponseEntity<Map<String, Object>> response =
                controller.disclosureExposure("bedroom", 500, 0);
        return response.getBody();
    }

    @Test
    @DisplayName("a v1 event carrying participants is reported — with the KEY, never the value")
    void reportsInternalOnlyKeysWithoutValues() {
        when(store.findByRepositoryId(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(v1RowWith(
                        "chat.participants", "otsuka,ishii",
                        "chat.channelId", "C123")));

        Map<String, Object> body = scan();

        assertEquals(1, body.get("exposedEventCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) events.get(0).get("keys");
        assertTrue(keys.contains("chat.participants"),
                "the fact the disclosure table calls INTERNAL_ONLY was not reported");
        assertFalse(keys.contains("chat.channelId"),
                "an EXTERNAL_OK fact was reported as an exposure — the report would drown in "
                        + "ordinary identifiers and be ignored");

        // The report is read and forwarded by operators. Repeating the personal data in it
        // would spread exactly what it exists to help remove.
        assertFalse(body.toString().contains("otsuka"),
                "the report repeated the personal data it is about");
    }

    @Test
    @DisplayName("an event with nothing internal-only is not an exposure — the control")
    void ordinaryEventsAreNotReported() {
        when(store.findByRepositoryId(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(v1RowWith("chat.channelId", "C123",
                        "contentHash", "a".repeat(64))));

        assertEquals(0, scan().get("exposedEventCount"),
                "a report that flags everything tells an operator nothing");
    }

    @Test
    @DisplayName("undecodable rows are COUNTED, not silently skipped")
    void undecodableRowsAreCounted() {
        // A row nobody can decode is a row nobody can clear either. Reporting "0 exposures"
        // over a pile of them is the same fail-open one level down.
        when(store.findByRepositoryId(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(new LineageJournalRow.Undecodable("lineage:x", "lineage_event",
                        1, "codec refused the payload")));

        Map<String, Object> body = scan();

        assertEquals(1, body.get("undecodableRows"));
        assertEquals(0, body.get("scannedEvents"));
    }

    @Test
    @DisplayName("a truncated scan hands back the offset to continue from")
    void truncationOffersAWayToFinish() {
        // The rows this report is about are the OLD v1 events. A fixed offset of 0 over a
        // store that answers newest-first scans exactly the events that CANNOT be exposed and
        // never reaches the ones that can — so "truncated: true" has to come with a way to
        // carry on, or the sweep can only ever describe one page (external review).
        List<LineageJournalRow> page = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            page.add(v1RowWith("chat.channelId", "C" + i));
        }
        when(store.findByRepositoryId(anyString(), anyInt(), anyInt())).thenReturn(page);

        Map<String, Object> body = controller.disclosureExposure("bedroom", 5, 10).getBody();

        assertEquals(Boolean.TRUE, body.get("truncated"));
        assertEquals(10, body.get("offset"), "the requested offset must be reported back");
        assertEquals(15, body.get("nextOffset"),
                "a truncated scan with no way to continue is a dead end");
        assertTrue(String.valueOf(body.get("note")).contains("does not mean the repository is "
                + "clean"), String.valueOf(body.get("note")));
    }

    @Test
    @DisplayName("a full page says so — a truncated scan must not read as a clean bill")
    void truncationIsReported() {
        List<LineageJournalRow> page = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            page.add(v1RowWith("chat.channelId", "C" + i));
        }
        when(store.findByRepositoryId(anyString(), anyInt(), anyInt())).thenReturn(page);

        ResponseEntity<Map<String, Object>> response =
                controller.disclosureExposure("bedroom", 5, 0);

        assertEquals(Boolean.TRUE, response.getBody().get("truncated"),
                "a scan that hit its limit reported as if it had seen everything");
    }
}
