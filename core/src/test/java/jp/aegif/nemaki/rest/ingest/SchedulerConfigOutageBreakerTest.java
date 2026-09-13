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
package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A configuration-store outage is not the CONNECTOR's failure.
 *
 * <p>The credential read refuses from before each orchestrator's own try, so the refusal
 * arrives at the scheduler's per-profile catch — which counted it towards opening the
 * connector's circuit breaker. Five polls of a {@code nemaki_conf} outage then left the
 * connector OPEN, and the source comments in eleven orchestrators claimed the refusal landed
 * in their own outer catch, which it does not. A review measured the placement.
 */
class SchedulerConfigOutageBreakerTest {

    private static final String REPO = "bedroom";

    @SuppressWarnings("unchecked")
    private Map<String, Integer> breakerCounts(IngestSchedulerService scheduler) throws Exception {
        Field f = IngestSchedulerService.class.getDeclaredField("consecutiveFailures");
        f.setAccessible(true);
        return (Map<String, Integer>) f.get(scheduler);
    }

    private IngestSchedulerService schedulerWhoseFetchThrows(RuntimeException thrown) {
        IngestSchedulerService scheduler = new IngestSchedulerService();
        ImportProfileDefinitionService profileService = mock(ImportProfileDefinitionService.class);
        ConnectorDefinitionService connectorService = mock(ConnectorDefinitionService.class);
        RepositoryInfoMap repositoryInfoMap = mock(RepositoryInfoMap.class);
        FetchOrchestratorRegistry registry = mock(FetchOrchestratorRegistry.class);
        FetchOrchestrator orchestrator = mock(FetchOrchestrator.class);

        ImportProfileDefinition p = new ImportProfileDefinition();
        p.setProfileId("p1");
        p.setRepositoryId(REPO);
        p.setEnabled(true);
        p.setSchedulerEnabled(true);
        p.setDelegated(false);
        p.setDefaultConnectorId("c1");
        p.setAllowedConnectorIds(List.of("c1"));
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorId("c1");
        c.setEnabled(true);
        c.setSourceSystem("slack");
        c.setSourceArchetype(SourceArchetype.CHAT_CONTEXT);

        when(profileService.listScheduledIndexFree()).thenReturn(List.of(p));
        when(connectorService.get("c1")).thenReturn(c);
        when(connectorService.countIndexFree("c1")).thenReturn(1);
        when(repositoryInfoMap.keys()).thenReturn(Set.of(REPO));
        when(registry.get(anyString())).thenReturn(orchestrator);
        when(registry.isRegistered(anyString())).thenReturn(true);
        when(orchestrator.execute(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(thrown);

        scheduler.setProfileService(profileService);
        scheduler.setConnectorService(connectorService);
        scheduler.setRepositoryInfoMap(repositoryInfoMap);
        scheduler.setOrchestratorRegistry(registry);
        return scheduler;
    }

    @Test
    @DisplayName("a configuration outage does not open the connector's breaker")
    void aConfigurationOutageDoesNotOpenTheConnectorsBreaker() throws Exception {
        IngestSchedulerService scheduler = schedulerWhoseFetchThrows(
                new IntegrationSettingsService.SettingUnreadableException(
                        "the credential 'secret.slack' of connector c1 could not be read: the"
                                + " configuration database did not answer; retry shortly"));

        scheduler.pollScheduledProfiles();

        assertTrue(breakerCounts(scheduler).isEmpty(),
                "a store that never answered was counted as the connector failing: "
                        + breakerCounts(scheduler));
    }

    @Test
    @DisplayName("an ordinary connector failure still opens it")
    void anOrdinaryFailureStillCountsAgainstTheBreaker() throws Exception {
        // The other direction. Exempting too much would leave a genuinely broken connector
        // polled for ever — over-throwing is a defect here too.
        IngestSchedulerService scheduler = schedulerWhoseFetchThrows(
                new RuntimeException("Slack returned HTTP 500"));

        scheduler.pollScheduledProfiles();

        assertFalse(breakerCounts(scheduler).isEmpty(),
                "a genuine connector failure stopped counting towards the breaker");
    }
}
