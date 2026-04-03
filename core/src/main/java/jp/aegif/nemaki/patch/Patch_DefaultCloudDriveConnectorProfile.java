package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.rest.ingest.ConnectorDefinition;
import jp.aegif.nemaki.rest.ingest.ConnectorDefinitionService;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinition;
import jp.aegif.nemaki.rest.ingest.ImportProfileDefinitionService;
import jp.aegif.nemaki.rest.ingest.SourceArchetype;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.List;

/**
 * Creates default connector and import profile definitions for Google Drive
 * and OneDrive if they don't already exist. This enables the canonical import
 * pipeline for existing cloud drive users without manual configuration.
 *
 * <p>Idempotent — skips creation if definitions already exist.
 */
public class Patch_DefaultCloudDriveConnectorProfile extends AbstractNemakiPatch {

    private static final Log log = LogFactory.getLog(Patch_DefaultCloudDriveConnectorProfile.class);

    @Override
    public String getName() {
        return "default-cloud-drive-connector-profile-20260403";
    }

    @Override
    protected void applySystemPatch() {
        try {
            var ctx = SpringContext.getApplicationContext();
            if (ctx == null) {
                log.warn("ApplicationContext not available, skipping default connector/profile creation");
                return;
            }

            ConnectorDefinitionService connectorService;
            try {
                connectorService = ctx.getBean(ConnectorDefinitionService.class);
            } catch (Exception e) {
                log.info("ConnectorDefinitionService not available yet, skipping");
                return;
            }

            // Google Drive connector
            if (!connectorService.exists("google-drive-default")) {
                ConnectorDefinition google = new ConnectorDefinition();
                google.setConnectorId("google-drive-default");
                google.setDisplayName("Google Drive");
                google.setSourceArchetype(SourceArchetype.FILE_SHARE);
                google.setSourceSystem("google");
                google.setAuthType("oauth2");
                google.setEnabled(true);
                connectorService.create(google);
                log.info("Created default Google Drive connector: google-drive-default");
            }

            // OneDrive connector
            if (!connectorService.exists("onedrive-default")) {
                ConnectorDefinition microsoft = new ConnectorDefinition();
                microsoft.setConnectorId("onedrive-default");
                microsoft.setDisplayName("OneDrive");
                microsoft.setSourceArchetype(SourceArchetype.FILE_SHARE);
                microsoft.setSourceSystem("microsoft");
                microsoft.setAuthType("oauth2");
                microsoft.setEnabled(true);
                connectorService.create(microsoft);
                log.info("Created default OneDrive connector: onedrive-default");
            }
        } catch (Exception e) {
            log.warn("Failed to create default connectors: " + e.getMessage());
        }
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        try {
            var ctx = SpringContext.getApplicationContext();
            if (ctx == null) return;

            ImportProfileDefinitionService profileService;
            try {
                profileService = ctx.getBean(ImportProfileDefinitionService.class);
            } catch (Exception e) {
                log.info("ImportProfileDefinitionService not available yet, skipping");
                return;
            }

            String profileId = "cloud-import-" + repositoryId;
            if (!profileService.exists(profileId)) {
                // Get root folder ID for the repository
                String rootFolderId = null;
                try {
                    var repoInfoMap = ctx.getBean(jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
                    var repoInfo = repoInfoMap.get(repositoryId);
                    if (repoInfo != null) {
                        rootFolderId = repoInfo.getRootFolderId();
                    }
                } catch (Exception e) {
                    log.debug("Could not resolve root folder ID for " + repositoryId);
                }

                ImportProfileDefinition profile = new ImportProfileDefinition();
                profile.setProfileId(profileId);
                profile.setDisplayName("Cloud Import (" + repositoryId + ")");
                profile.setRepositoryId(repositoryId);
                profile.setTargetFolderId(rootFolderId);
                profile.setDefaultObjectTypeId("cmis:document");
                profile.setAllowedArchetypes(List.of(SourceArchetype.FILE_SHARE));
                profile.setDedupePolicy("create_new_version");
                profile.setVersioningPolicy("major");
                profile.setEnabled(true);
                profileService.create(profile);
                log.info("Created default cloud import profile: " + profileId + " for repository: " + repositoryId);
            }
        } catch (Exception e) {
            log.warn("Failed to create default import profile for " + repositoryId + ": " + e.getMessage());
        }
    }
}
