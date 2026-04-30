package jp.aegif.nemaki.rest.ingest.mail;

import jp.aegif.nemaki.rest.ingest.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages IMAP IDLE monitoring sessions.
 *
 * <p>Each session runs on a virtual thread and processes new messages
 * as they arrive via the IMAP IDLE command.
 */
public class ImapIdleMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ImapIdleMonitor.class);

    private final Map<String, ImapConnectorAdapter> idleAdapters = new java.util.concurrent.ConcurrentHashMap<>();

    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private FetchSupport fetchSupport;
    private CanonicalImportService canonicalImportService;

    public void setProfileService(ImportProfileDefinitionService profileService) { this.profileService = profileService; }
    public void setConnectorService(ConnectorDefinitionService connectorService) { this.connectorService = connectorService; }
    public void setFetchSupport(FetchSupport fetchSupport) { this.fetchSupport = fetchSupport; }
    public void setCanonicalImportService(CanonicalImportService canonicalImportService) { this.canonicalImportService = canonicalImportService; }

    /**
     * Start IMAP IDLE monitoring for a specific profile.
     * @return error message, or null on success
     */
    public String startIdle(String profileId) {
        if (idleAdapters.containsKey(profileId)) {
            return "IDLE already running for profile: " + profileId;
        }

        ImportProfileDefinition profile = profileService != null ? profileService.get(profileId) : null;
        if (profile == null) return "Profile not found: " + profileId;

        ConnectorDefinition connector = resolveConnector(profile);
        if (connector == null) return "No connector for profile: " + profileId;
        if (!"imap".equals(connector.getSourceSystem())) {
            return "IDLE is only supported for IMAP connectors (system=" + connector.getSourceSystem() + ")";
        }

        String password = fetchSupport.resolvePassword(connector);
        if (password == null) return "No password for IMAP connector";

        String mailbox = profile.getSchedulerParams() != null
                ? profile.getSchedulerParams().getOrDefault("mailbox", "INBOX") : "INBOX";

        ImapConnectorAdapter imap = new ImapConnectorAdapter(connector, password);
        idleAdapters.put(profileId, imap);

        Thread idle = Thread.ofVirtual().name("imap-idle-" + profileId).start(() -> {
            try {
                imap.connect();
                imap.startIdle(mailbox, msg -> {
                    try {
                        java.io.InputStream eml = imap.fetchMessage(mailbox, msg.uid());
                        ExternalIngestRequest req = new ExternalIngestRequest();
                        req.setProfileId(profileId);
                        req.setConnectorId(connector.getConnectorId());
                        req.setRepositoryId(profile.getRepositoryId());
                        req.setSourceObjectId(msg.stableKey());
                        req.setSourceObjectType("message");
                        req.setFileName(FetchSupport.sanitizeSubject(msg.subject()) + ".eml");
                        req.setMimeType("message/rfc822");
                        req.setContentStream(eml);
                        req.setExecutionMode("idle");
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("mailboxId", mailbox);
                        metadata.put("messageStableId", msg.stableKey());
                        if (msg.messageId() != null) metadata.put("internetMessageId", msg.messageId());
                        req.setMetadata(metadata);
                        canonicalImportService.executeMailImport(null, req);
                        logger.info("IDLE: imported message {} from {}", msg.stableKey(), mailbox);
                    } catch (Exception e) {
                        logger.error("IDLE: failed to import message {}: {}", msg.uid(), e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.error("IDLE monitoring failed for {}: {}", profileId, e.getMessage());
            } finally {
                imap.disconnect();
                idleAdapters.remove(profileId);
            }
        });

        imap.setIdleThread(idle);
        logger.info("IMAP IDLE monitoring started for profile {}", profileId);
        return null;
    }

    /** Stop IDLE monitoring for a specific profile. */
    public String stopIdle(String profileId) {
        ImapConnectorAdapter imap = idleAdapters.remove(profileId);
        if (imap == null) return "No IDLE session running for profile: " + profileId;
        imap.stopIdle();
        imap.disconnect();
        logger.info("IMAP IDLE monitoring stopped for profile {}", profileId);
        return null;
    }

    /** Get the list of profiles currently running IMAP IDLE. */
    public List<String> getIdleProfiles() {
        return List.copyOf(idleAdapters.keySet());
    }

    /** Resolve the connector for a profile. */
    private ConnectorDefinition resolveConnector(ImportProfileDefinition profile) {
        if (connectorService == null) return null;
        String connId = profile.getDefaultConnectorId();
        if (connId != null) return connectorService.get(connId);
        return null;
    }
}
