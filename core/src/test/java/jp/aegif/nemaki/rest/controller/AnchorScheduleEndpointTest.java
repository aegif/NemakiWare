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
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.evidence.RecordContentStateRecorder;
import jp.aegif.nemaki.evidence.anchor.AnchorReceiptStore;
import jp.aegif.nemaki.evidence.anchor.AnchorScheduleSettings;
import jp.aegif.nemaki.evidence.anchor.AnchorScheduler;
import jp.aegif.nemaki.evidence.anchor.AnchorService;
import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorTarget;
import jp.aegif.nemaki.rest.purview.anchor.Rfc3161AnchorTarget;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The settings endpoint of the scheduler (design anchor-scheduler.md §5 / §9), measured through
 * the controller's own GET and PUT with a real {@link AnchorScheduler} behind them.
 */
class AnchorScheduleEndpointTest {

    private static final String REPO = "bedroom";

    private IntegrationSettingsService settingsService;
    private AnchorReceiptStore receipts;
    private EvidenceLedgerStore store;
    private AnchorService anchors;
    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void wire() {
        settingsService = mock(IntegrationSettingsService.class);
        receipts = mock(AnchorReceiptStore.class);
        store = mock(EvidenceLedgerStore.class);
        anchors = mock(AnchorService.class);
        when(settingsService.readRepositorySettings(anyString(), any())).thenAnswer(inv -> new HashMap<>(saved));
        when(store.isActive()).thenReturn(true);
        when(receipts.isActive()).thenReturn(true);
        when(anchors.targets()).thenReturn(List.of());
    }

    private AnchorController controller() throws Exception {
        AnchorScheduler scheduler = new AnchorScheduler();
        PropertyManager props = mock(PropertyManager.class);
        when(props.getConfiguration(SystemConst.NEMAKI_CONF_DB)).thenReturn(new Configuration());
        RepositoryInfoMap repos = mock(RepositoryInfoMap.class);
        when(repos.keys()).thenReturn(new LinkedHashSet<>(List.of(REPO)));
        set(scheduler, "propertyManager", props);
        set(scheduler, "settingsService", settingsService);
        set(scheduler, "repositoryInfoMap", repos);
        set(scheduler, "ledgerStore", store);
        set(scheduler, "receiptStore", receipts);
        set(scheduler, "anchorService", anchors);
        set(scheduler, "ledgerService", mock(EvidenceLedgerService.class));

        AnchorController controller = new AnchorController();
        set(controller, "anchorScheduler", scheduler);
        set(controller, "integrationSettingsService", settingsService);
        set(controller, "anchorService", anchors);
        HttpServletRequest request = mock(HttpServletRequest.class);
        CallContext ctx = mock(CallContext.class);
        when(ctx.get(CallContextKey.IS_ADMIN)).thenReturn(true);
        when(request.getAttribute("CallContext")).thenReturn(ctx);
        controller.setHttpRequest(request);
        return controller;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Map<String, Object> put(String... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put(pairs[i], pairs[i + 1]);
        }
        return body;
    }

    @Test
    @DisplayName("enabled with an empty interval cannot be saved (400), and nothing is written")
    void anEnabledScheduleWithoutAnIntervalCannotBeSaved() throws Exception {
        ResponseEntity<Map<String, Object>> response = controller().updateSchedule(REPO,
                put(AnchorScheduleSettings.ENABLED, "true", AnchorScheduleSettings.INTERVAL_MINUTES, ""));

        assertEquals(400, response.getStatusCode().value(), String.valueOf(response.getBody()));
        Map<?, ?> errors = (Map<?, ?>) response.getBody().get("errors");
        assertTrue(errors.containsKey(AnchorScheduleSettings.INTERVAL_MINUTES), String.valueOf(errors));
        verify(settingsService, never()).writeRepositorySettings(anyString(), anyMap());
    }

    @Test
    @DisplayName("a retry interval under an hour is refused (a failed TSA is not bought from every minute); 60 is accepted")
    void aRetryIntervalUnderAnHourIsRefused() throws Exception {
        AnchorController controller = controller();

        ResponseEntity<Map<String, Object>> under = controller.updateSchedule(REPO,
                put(AnchorScheduleSettings.RETRY_UNSETTLED_INTERVAL_MINUTES, "59"));
        assertEquals(400, under.getStatusCode().value(), String.valueOf(under.getBody()));
        verify(settingsService, never()).writeRepositorySettings(anyString(), anyMap());

        ResponseEntity<Map<String, Object>> hour = controller.updateSchedule(REPO,
                put(AnchorScheduleSettings.RETRY_UNSETTLED_INTERVAL_MINUTES, "60"));
        assertEquals(200, hour.getStatusCode().value(), String.valueOf(hour.getBody()));
    }

    @Test
    @DisplayName("where anchors are SENT cannot be set here, and a misspelt key is refused rather than dropped")
    void whereAnchorsAreSentCannotBeSetHere() throws Exception {
        AnchorController controller = controller();

        ResponseEntity<Map<String, Object>> destination = controller.updateSchedule(REPO,
                put("anchor.rfc3161.tsa.url", "http://tsa.example.invalid/"));
        ResponseEntity<Map<String, Object>> typo = controller.updateSchedule(REPO,
                put("anchor.schedule.intervalMinutes", "60"));

        assertEquals(400, destination.getStatusCode().value(), String.valueOf(destination.getBody()));
        assertTrue(String.valueOf(destination.getBody().get("refusedKeys")).contains("anchor.rfc3161.tsa.url"));
        assertEquals(400, typo.getStatusCode().value(), String.valueOf(typo.getBody()));
        verify(settingsService, never()).writeRepositorySettings(anyString(), anyMap());
        verify(settingsService, never()).writeSettings(anyMap());
    }

    @Test
    @DisplayName("a valid schedule is written for THAT domain — not as a deployment-wide setting")
    void aValidScheduleIsWrittenForThatDomainOnly() throws Exception {
        ResponseEntity<Map<String, Object>> response = controller().updateSchedule(REPO,
                put(AnchorScheduleSettings.ENABLED, "true", AnchorScheduleSettings.INTERVAL_MINUTES, "1440"));

        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        verify(settingsService).writeRepositorySettings(eq(REPO), eq(Map.of(
                AnchorScheduleSettings.ENABLED, "true", AnchorScheduleSettings.INTERVAL_MINUTES, "1440")));
        verify(settingsService, never()).writeSettings(anyMap());
    }

    @Test
    @DisplayName("a save that fails answers 500 with an incident id, not the store's own words")
    void aSaveThatFailsDoesNotCopyTheStoresWords() throws Exception {
        String marker = "https://couchdb.internal:5984/nemaki_conf rev 7-abc SECRET";
        doThrow(new IllegalStateException(marker)).when(settingsService).writeRepositorySettings(anyString(), anyMap());

        ResponseEntity<Map<String, Object>> response = controller().updateSchedule(REPO,
                put(AnchorScheduleSettings.ENABLED, "false"));

        assertEquals(500, response.getStatusCode().value(), String.valueOf(response.getBody()));
        assertNotNull(response.getBody().get("incidentId"));
        assertFalse(String.valueOf(response.getBody()).contains(marker), String.valueOf(response.getBody()));
    }

    @Test
    @DisplayName("GET: an anchored position that cannot be read is UNAVAILABLE with no count — never 0")
    void anUnreadableAnchoredPositionShowsNoCount() throws Exception {
        when(store.latestCheckpoint(REPO)).thenReturn(EvidenceCheckpoint.of(REPO, 0, 4, "ab".repeat(32), null,
                "2026-09-29T09:00:00Z"));
        when(store.highestSequence(REPO)).thenReturn(9L);
        when(receipts.forCheckpoint(eq(REPO), anyLong())).thenThrow(new IllegalStateException("view timed out"));

        ResponseEntity<Map<String, Object>> response = controller().schedule(REPO);

        Map<?, ?> unanchored = (Map<?, ?>) response.getBody().get("unanchored");
        assertEquals("UNAVAILABLE", unanchored.get("status"), String.valueOf(unanchored));
        assertFalse(unanchored.containsKey("count"), "an unreadable position was given a count: " + unanchored);
    }

    @Test
    @DisplayName("GET shows where anchors go without a password in the URL and without the certificate")
    void theDestinationIsShownWithoutItsSecrets() throws Exception {
        Rfc3161AnchorTarget tsa = mock(Rfc3161AnchorTarget.class);
        when(tsa.kind()).thenReturn(AnchorKind.RFC3161_TSA);
        when(tsa.isConfigured()).thenReturn(true);
        when(tsa.tsaUrl()).thenReturn("https://operator:s3cr3t@tsa.example.invalid/ts");
        when(tsa.hasTrustAnchor()).thenReturn(true);
        when(anchors.targets()).thenReturn(List.<AnchorTarget>of(tsa));

        ResponseEntity<Map<String, Object>> response = controller().schedule(REPO);

        Map<?, ?> destinations = (Map<?, ?>) response.getBody().get("destinations");
        // A URL with an @ is not shown at all (R132); the key says so rather than being dropped.
        assertTrue(String.valueOf(destinations.get("tsaUrl")).startsWith("(not shown"), String.valueOf(destinations));
        assertEquals(Boolean.TRUE, destinations.get("trustAnchorConfigured"));
        assertFalse(String.valueOf(response.getBody()).contains("s3cr3t"), String.valueOf(response.getBody()));
        assertFalse(String.valueOf(response.getBody()).contains("BEGIN CERTIFICATE"));
    }

    /**
     * The other destination on the same card: the OpenTimestamps sidecar URL is not shown when it
     * carries an {@code @} either (subagent, c44 — the TSA's arm had a lock and a control, this
     * one had neither since the card was written).
     */
    @Test
    @DisplayName("GET does not show a sidecar URL that carries an @")
    void theSidecarDestinationIsNotShownWithAnAt() throws Exception {
        jp.aegif.nemaki.rest.purview.anchor.OpenTimestampsAnchorTarget ots =
                mock(jp.aegif.nemaki.rest.purview.anchor.OpenTimestampsAnchorTarget.class);
        when(ots.kind()).thenReturn(AnchorKind.OPENTIMESTAMPS);
        when(ots.isConfigured()).thenReturn(true);
        when(ots.sidecarUrl()).thenReturn("http://ops:s3cr3t@ots_sidecar:8082");
        when(anchors.targets()).thenReturn(List.<AnchorTarget>of(ots));

        ResponseEntity<Map<String, Object>> response = controller().schedule(REPO);

        Map<?, ?> destinations = (Map<?, ?>) response.getBody().get("destinations");
        assertTrue(String.valueOf(destinations.get("otsSidecarUrl")).startsWith("(not shown"),
                String.valueOf(destinations));
        assertFalse(String.valueOf(response.getBody()).contains("s3cr3t"), String.valueOf(response.getBody()));
    }

    @Test
    @DisplayName("record-content is a domain the schedule can be set for; an unknown id is 400")
    void recordContentIsADomainTheScheduleCanBeSetFor() throws Exception {
        AnchorController controller = controller();

        ResponseEntity<Map<String, Object>> recordContent = controller.schedule(RecordContentStateRecorder.DOMAIN);
        ResponseEntity<Map<String, Object>> unknown = controller.schedule("no-such-domain");

        assertEquals(200, recordContent.getStatusCode().value(), String.valueOf(recordContent.getBody()));
        assertEquals(400, unknown.getStatusCode().value(), String.valueOf(unknown.getBody()));
    }

    @Test
    @DisplayName("GET: saved settings that cannot be read are said to be unreadable — not shown as defaults")
    void unreadableSettingsAreNotShownAsDefaults() throws Exception {
        when(settingsService.readRepositorySettings(anyString(), any())).thenThrow(new IllegalStateException("down"));

        ResponseEntity<Map<String, Object>> response = controller().schedule(REPO);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(null, response.getBody().get("settings"));
        assertNotNull(response.getBody().get("settingsUnavailable"));
        assertFalse(response.getBody().containsKey("effective"), "defaults were shown for settings nobody read");
    }
}
