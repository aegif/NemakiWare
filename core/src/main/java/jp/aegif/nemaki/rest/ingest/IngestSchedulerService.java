package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.rest.ingest.mail.ImapConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.mail.ImapConnectorAdapter.MessageSummary;
import jp.aegif.nemaki.util.PropertyManager;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private CanonicalImportService canonicalImportService;
    private PropertyManager propertyManager;
    private RepositoryInfoMap repositoryInfoMap;

    public void setProfileService(ImportProfileDefinitionService profileService) {
        this.profileService = profileService;
    }

    public void setConnectorService(ConnectorDefinitionService connectorService) {
        this.connectorService = connectorService;
    }

    public void setCanonicalImportService(CanonicalImportService canonicalImportService) {
        this.canonicalImportService = canonicalImportService;
    }

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
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

    /**
     * Execute IMAP fetch for a specific profile + connector.
     * Connects to the IMAP server, lists new messages, and imports each via
     * the canonical mail import pipeline.
     *
     * @param callContext CMIS context for document creation
     * @param profile     the import profile
     * @param connector   the IMAP connector
     * @param mailboxFolder IMAP folder name (e.g. "INBOX")
     * @param limit       max messages to fetch per run
     * @return summary of results
     */
    public ImapFetchResult executeImapFetch(CallContext callContext, ImportProfileDefinition profile,
                                            ConnectorDefinition connector, String mailboxFolder, int limit) {
        if (canonicalImportService == null) {
            return new ImapFetchResult(0, 0, List.of("CanonicalImportService not available"));
        }

        String password = resolvePassword(connector);
        if (password == null) {
            return new ImapFetchResult(0, 0, List.of("Could not resolve IMAP password for connector: " + connector.getConnectorId()));
        }

        ImapConnectorAdapter imap = new ImapConnectorAdapter(connector, password);
        List<String> errors = new ArrayList<>();
        int fetched = 0;
        int imported = 0;

        try {
            imap.connect();
            List<MessageSummary> messages = imap.listMessages(mailboxFolder, null, limit);
            fetched = messages.size();
            logger.info("IMAP fetch: {} messages from {}:{}", fetched, connector.getEndpoint(), mailboxFolder);

            for (MessageSummary msg : messages) {
                try {
                    InputStream eml = imap.fetchMessage(mailboxFolder, msg.uid());

                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(String.valueOf(msg.uid()));
                    req.setSourceObjectType("message");
                    req.setFileName(sanitizeSubject(msg.subject()) + ".eml");
                    req.setMimeType("message/rfc822");
                    req.setContentStream(eml);
                    req.setExecutionMode("scheduled");

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("mailboxId", mailboxFolder);
                    metadata.put("messageStableId", String.valueOf(msg.uid()));
                    if (msg.messageId() != null) metadata.put("internetMessageId", msg.messageId());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    if (result.isSuccess()) {
                        imported++;
                    } else if (result.skipped()) {
                        logger.debug("IMAP message {} skipped: {}", msg.uid(), result.skipReason());
                    } else {
                        errors.add("Message UID " + msg.uid() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    errors.add("Message UID " + msg.uid() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("IMAP connection failed: " + e.getMessage());
            logger.error("IMAP fetch failed for {}: {}", connector.getEndpoint(), e.getMessage());
        } finally {
            imap.disconnect();
        }

        logger.info("IMAP fetch complete: fetched={}, imported={}, errors={}", fetched, imported, errors.size());
        return new ImapFetchResult(fetched, imported, errors);
    }

    private String resolvePassword(ConnectorDefinition connector) {
        String credentialRef = connector.getCredentialRef();
        if (credentialRef == null || credentialRef.isBlank()) return null;
        if (propertyManager != null) {
            String value = propertyManager.readValue(credentialRef);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String sanitizeSubject(String subject) {
        if (subject == null || subject.isBlank()) return "untitled";
        return subject.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
    }

    /**
     * Result of an IMAP fetch run.
     */
    public record ImapFetchResult(int fetched, int imported, List<String> errors) {
        public boolean hasErrors() { return errors != null && !errors.isEmpty(); }
    }
}
