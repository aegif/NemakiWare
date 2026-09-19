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

import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import jp.aegif.nemaki.rest.ingest.note.NotionFetchOrchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A configuration-store outage must LEAVE the orchestrator, not become "connection failed".
 *
 * <p>The credential read refuses from above the orchestrator's try, so the scheduler could
 * exempt it from the connector's circuit breaker. The CHECKPOINT read refuses from inside that
 * try, and the outer catch turned it into {@code "Notion connection failed: …"} — an error the
 * scheduler counts against the connector, and which the folder and trigger endpoints repeat
 * back as the connector being in trouble. A review found the exemption covering half the class
 * its own headline named.
 *
 * <p>Driven against the real orchestrator: the refusal is raised before the Notion adapter is
 * used, so no endpoint is needed. The scheduler's half is measured separately in
 * {@code SchedulerConfigOutageBreakerTest}.
 */
class NotionOrchestratorRefusalTest {

    @Test
    @DisplayName("a checkpoint outage is not reported as the connector failing")
    void aCheckpointOutageIsNotReportedAsTheConnectorFailing() {
        NotionFetchOrchestrator orchestrator = new NotionFetchOrchestrator();
        FetchSupport fetchSupport = mock(FetchSupport.class);
        CheckpointManager checkpoints = mock(CheckpointManager.class);
        when(fetchSupport.resolvePasswordOrRefuse(any())).thenReturn("tok");
        when(checkpoints.loadSimpleCheckpoint(anyString(), anyString())).thenThrow(
                new IntegrationSettingsService.SettingUnreadableException(
                        "the stored value of 'ingest.checkpoint.p1.notion' could not be read:"
                                + " the configuration database did not answer; retry shortly"));
        orchestrator.setFetchSupport(fetchSupport);
        orchestrator.setCheckpointManager(checkpoints);

        ImportProfileDefinition profile = new ImportProfileDefinition();
        profile.setProfileId("p1");
        profile.setRepositoryId("bedroom");
        ConnectorDefinition connector = new ConnectorDefinition();
        connector.setConnectorId("c1");
        connector.setCredentialRef("secret.notion");

        IntegrationSettingsService.SettingUnreadableException out = assertThrows(
                IntegrationSettingsService.SettingUnreadableException.class,
                () -> orchestrator.execute(null, profile, connector, Map.of(), 10),
                "the configuration refusal was swallowed and reported as the connector failing,"
                        + " which the scheduler counts against its circuit breaker");
        assertTrue(out.getMessage().contains("could not be read"),
                "the refusal lost what happened: " + out.getMessage());
    }
}
