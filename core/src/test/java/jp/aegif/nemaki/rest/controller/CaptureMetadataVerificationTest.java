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
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.rest.ingest.EvidenceMetadataHash;
import jp.aegif.nemaki.rest.ingest.capture.CaptureMaintenanceStore;
import jp.aegif.nemaki.util.constant.CallContextKey;

import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The metadata-hash verifier's three-valued verdicts (p1-1d-metadata-hash.md §4).
 *
 * <p>The rules under test, each with the failure it prevents:
 *
 * <ul>
 *   <li>MATCH / MISMATCH compare against the latest HASH-BEARING completed row — not simply the
 *       latest row, because wrappers that do not hash yet and replays through the plain path
 *       complete rows without hashes.</li>
 *   <li>A would-be MISMATCH downgrades to UNVERIFIABLE when later activity exists for the same
 *       source item — claiming "an unrecorded change" while a record sits in the store would be
 *       the exact overstatement this whole work item exists to stop.</li>
 *   <li>No hash anywhere is UNVERIFIABLE, never MATCH — absence of evidence is not evidence of
 *       integrity.</li>
 * </ul>
 */
class CaptureMetadataVerificationTest {

    private static final String REPO = "bedroom";
    private static final String OBJECT = "chat-obj";
    private static final String CHAT = "nemaki:chatContextMetadata";

    private CaptureIntentController controller;
    private CaptureMaintenanceStore store;
    private ContentService contentService;
    private Document stored;
    private String storedChatHash;

    @BeforeEach
    void setUp() {
        controller = new CaptureIntentController();
        store = mock(CaptureMaintenanceStore.class);
        contentService = mock(ContentService.class);
        controller.setMaintenanceStore(store);
        controller.setContentService(contentService);

        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext callContext = mock(CallContext.class);
        when(callContext.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(request.getAttribute("CallContext")).thenReturn(callContext);
        controller.setHttpRequest(request);

        Aspect chat = new Aspect();
        chat.setName(CHAT);
        chat.setProperties(new ArrayList<>(List.of(
                new Property("nemaki:chatChannelId", "C-CAPTURED"),
                new Property("nemaki:chatParticipants", "otsuka,ishii"))));
        stored = new Document();
        stored.setId(OBJECT);
        stored.setAspects(new ArrayList<>(List.of(chat)));
        when(contentService.getContent(REPO, OBJECT)).thenReturn(stored);

        storedChatHash = EvidenceMetadataHash.compute(stored.getAspects()).chatEvidenceHash();
        when(store.listRowsForSourceSince(anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(List.of());
    }

    private Map<String, Object> completedRow(String chatHash, long capturedAtMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("intentId", "lineage_capture:x");
        row.put("sourceObjectId", "1720000000.000200");
        row.put("capturedAtMs", capturedAtMs);
        if (chatHash != null) {
            row.put("appliedChatEvidenceHash", chatHash);
            row.put("metadataHashFormula", "mh1");
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verify() {
        ResponseEntity<Map<String, Object>> response =
                controller.verifyMetadata(REPO, OBJECT);
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }

    @Test
    @DisplayName("untouched evidence verifies as MATCH")
    void matchWhenUntouched() {
        when(store.listCapturedForObject(REPO, OBJECT, 20))
                .thenReturn(List.of(completedRow(storedChatHash, 1000L)));

        assertEquals("MATCH", verify().get("chatEvidence"));
    }

    @Test
    @DisplayName("a changed value with no later record is MISMATCH — the detection this exists for")
    void mismatchWhenChangedWithNoRecord() {
        when(store.listCapturedForObject(REPO, OBJECT, 20))
                .thenReturn(List.of(completedRow(storedChatHash, 1000L)));
        // Someone edited the store directly after the capture.
        stored.getAspects().get(0).getProperties().get(0).setValue("C-REWRITTEN");

        assertEquals("MISMATCH", verify().get("chatEvidence"),
                "a direct-DB edit of a captured value went undetected");
    }

    @Test
    @DisplayName("a would-be MISMATCH with later activity downgrades to UNVERIFIABLE")
    void mismatchDowngradesWhenLaterActivityExists() {
        when(store.listCapturedForObject(REPO, OBJECT, 20))
                .thenReturn(List.of(completedRow(storedChatHash, 1000L)));
        stored.getAspects().get(0).getProperties().get(0).setValue("C-REWRITTEN");
        // A later pass for the same source item left a record — perhaps unresolved, perhaps a
        // wrapper that does not hash. Either way "unrecorded change" would be false.
        when(store.listRowsForSourceSince(eq(REPO), eq("1720000000.000200"), eq(1000L), anyInt()))
                .thenReturn(List.of(Map.of("captureState", "UNRESOLVED")));

        Map<String, Object> body = verify();
        assertEquals("UNVERIFIABLE", body.get("chatEvidence"),
                "the verifier claimed an unrecorded change while a record of a later pass exists");
    }

    @Test
    @DisplayName("hash-less completed rows are skipped back over, not compared")
    void hashLessRowsAreSkippedToTheLatestHashBearingRow() {
        // Newest row has no hash (a replay through the plain path); the older one has the hash
        // of the CURRENT state. Comparing against the newest would be UNVERIFIABLE at best;
        // comparing against the older hash-bearing row gives the honest answer — but because a
        // newer hash-less row exists, a mismatch would have been downgraded. Here they match.
        when(store.listCapturedForObject(REPO, OBJECT, 20)).thenReturn(List.of(
                completedRow(null, 2000L),
                completedRow(storedChatHash, 1000L)));

        assertEquals("MATCH", verify().get("chatEvidence"));
    }

    @Test
    @DisplayName("no hash anywhere is UNVERIFIABLE — never MATCH")
    void noHashAnywhereIsUnverifiable() {
        when(store.listCapturedForObject(REPO, OBJECT, 20))
                .thenReturn(List.of(completedRow(null, 1000L)));

        Map<String, Object> body = verify();
        assertEquals("UNVERIFIABLE", body.get("chatEvidence"),
                "absence of a recorded hash was reported as integrity");
        assertEquals("UNVERIFIABLE", body.get("sourceIdentity"));
    }

    @Test
    @DisplayName("the two hashes are judged independently")
    void theTwoHashesAreIndependent() {
        Map<String, Object> row = completedRow(storedChatHash, 1000L);
        // No sourceIdentity hash recorded — that half is unverifiable while chat matches.
        when(store.listCapturedForObject(REPO, OBJECT, 20)).thenReturn(List.of(row));

        Map<String, Object> body = verify();
        assertEquals("MATCH", body.get("chatEvidence"));
        assertEquals("UNVERIFIABLE", body.get("sourceIdentity"),
                "a hash that was never recorded must not borrow the other hash's verdict");
    }
}
