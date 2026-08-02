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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import jp.aegif.nemaki.rest.purview.journal.LineageEndpoint;
import jp.aegif.nemaki.rest.purview.journal.LineageEvent;
import jp.aegif.nemaki.rest.purview.journal.LineageEventBuilder;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalEntry;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalRow;
import jp.aegif.nemaki.rest.purview.journal.LineageJournalStore;
import jp.aegif.nemaki.rest.purview.journal.LineageProcessType;
import jp.aegif.nemaki.rest.purview.journal.LineagePublishStatus;
import jp.aegif.nemaki.util.constant.CallContextKey;

/**
 * Pins the v1 /replay branch's shape (D-rest-3 review round 1, finding 5): the schema
 * dispatch must leave the v1 behavior byte-identical — INCLUDING the HTTP-200 body with
 * {@code status:"error"} when the reset does not persist.
 */
public class LineageJournalControllerReplayShapeTest {

    private LineageJournalController controller;
    private LineageJournalStore journalStore;

    @BeforeEach
    void setUp() throws Exception {
        controller = new LineageJournalController();
        journalStore = mock(LineageJournalStore.class);
        set("journalStore", journalStore);

        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        set("httpRequest", request);
    }

    private void set(String field, Object value) throws Exception {
        Field f = LineageJournalController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(controller, value);
    }

    private static LineageJournalRow v1Row() {
        LineageEvent event = new LineageEventBuilder()
                .repositoryId("bedroom")
                .processType(LineageProcessType.ARCHIVE_LOCAL)
                .addInputObject("bedroom", "doc-1")
                .addOutput("nemaki://bedroom/archives/doc-1")
                .targets(List.of("purview"))
                .build();
        return new LineageJournalRow.Decoded(LineageJournalEntry.ofV1(event));
    }

    @Test
    public void theV1FailureShapeIsHttp200WithAnErrorBody() {
        when(journalStore.findByRecordId(anyString())).thenReturn(v1Row());
        when(journalStore.updatePublishStatus(anyString(), anyString(),
                any(LineagePublishStatus.class))).thenReturn(0); // reset did not persist

        ResponseEntity<Map<String, Object>> response =
                controller.replayEvent("rec-1", "purview");
        assertEquals(200, response.getStatusCode().value(),
                "the v1 branch's failure shape is HTTP 200 + status:error, byte-identical");
        assertEquals("error", response.getBody().get("status"));
        assertEquals("Failed to reset status", response.getBody().get("message"));
    }

    @Test
    public void theV1SuccessShapeIsUnchangedToo() {
        when(journalStore.findByRecordId(anyString())).thenReturn(v1Row());
        when(journalStore.updatePublishStatus(anyString(), anyString(),
                any(LineagePublishStatus.class))).thenReturn(1);

        ResponseEntity<Map<String, Object>> response =
                controller.replayEvent("rec-1", "purview");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("ok", response.getBody().get("status"));
        assertEquals("Event queued for replay", response.getBody().get("message"));
    }
}
