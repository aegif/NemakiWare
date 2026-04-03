package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Scheduler service that periodically checks for import profiles with
 * {@code schedulerEnabled=true} and triggers ingest jobs.
 *
 * <p>Phase 1: provides the scheduling infrastructure (scan + dispatch).
 * Actual content fetching requires concrete connector adapters (Phase 3+).
 *
 * <p>This service is NOT a Spring @Scheduled bean — it is invoked by
 * the existing NemakiWare scheduler infrastructure or by an admin API call.
 */
public class IngestSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(IngestSchedulerService.class);

    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private RepositoryInfoMap repositoryInfoMap;

    public void setProfileService(ImportProfileDefinitionService profileService) {
        this.profileService = profileService;
    }

    public void setConnectorService(ConnectorDefinitionService connectorService) {
        this.connectorService = connectorService;
    }

    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }

    /**
     * Scans all repositories for profiles with schedulerEnabled=true and
     * returns the list of eligible profiles. Does NOT execute ingest —
     * that requires a concrete connector adapter.
     *
     * @return list of profiles eligible for scheduled execution
     */
    public List<ImportProfileDefinition> getScheduledProfiles() {
        if (repositoryInfoMap == null || profileService == null) {
            return List.of();
        }
        return repositoryInfoMap.keys().stream()
                .flatMap(repoId -> profileService.listByRepository(repoId).stream())
                .filter(ImportProfileDefinition::isEnabled)
                .filter(ImportProfileDefinition::isSchedulerEnabled)
                .toList();
    }

    /**
     * Resolves the default connector for a scheduled profile.
     *
     * @return the connector to use, or null if none available
     */
    public ConnectorDefinition resolveConnectorForProfile(ImportProfileDefinition profile) {
        if (connectorService == null) return null;

        // Prefer defaultConnectorId
        String defaultId = profile.getDefaultConnectorId();
        if (defaultId != null && !defaultId.isBlank()) {
            ConnectorDefinition connector = connectorService.get(defaultId);
            if (connector != null && connector.isEnabled()) {
                return connector;
            }
        }

        // Fallback: find first allowed connector by archetype
        if (profile.getAllowedArchetypes() != null && !profile.getAllowedArchetypes().isEmpty()) {
            for (SourceArchetype archetype : profile.getAllowedArchetypes()) {
                List<ConnectorDefinition> candidates = connectorService.listByArchetype(archetype);
                for (ConnectorDefinition c : candidates) {
                    if (c.isEnabled() && profile.isConnectorAllowed(c.getConnectorId())) {
                        return c;
                    }
                }
            }
        }

        return null;
    }
}
