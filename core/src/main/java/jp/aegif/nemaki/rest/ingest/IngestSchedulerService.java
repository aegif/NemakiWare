package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import jp.aegif.nemaki.rest.ingest.mail.GmailConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.mail.GmailConnectorAdapter.GmailMessageSummary;
import jp.aegif.nemaki.rest.ingest.mail.ImapConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.mail.ImapConnectorAdapter.MessageSummary;
import jp.aegif.nemaki.rest.ingest.mail.M365MailConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.mail.M365MailConnectorAdapter.M365MessageSummary;
import jp.aegif.nemaki.rest.ingest.note.NotionConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.note.NotionConnectorAdapter.NotionPageSummary;
import jp.aegif.nemaki.rest.ingest.note.NotionConnectorAdapter.NotionFile;
import jp.aegif.nemaki.rest.ingest.record.SalesforceConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter.SlackMessage;
import jp.aegif.nemaki.rest.ingest.chat.SlackConnectorAdapter.SlackFile;
import jp.aegif.nemaki.util.PropertyManager;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private static final long POLL_INTERVAL_SECONDS = 300; // 5 minutes

    private java.util.concurrent.ScheduledExecutorService scheduler;

    /**
     * Start the periodic polling scheduler. Called after all beans are initialized.
     */
    public void startPolling() {
        if (scheduler != null) return;
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IngestScheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollScheduledProfiles,
                POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        logger.info("Ingest scheduler started (interval={}s)", POLL_INTERVAL_SECONDS);
    }

    public void stopPolling() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            logger.info("Ingest scheduler stopped");
        }
    }

    private void pollScheduledProfiles() {
        try {
            List<ImportProfileDefinition> profiles = getScheduledProfiles();
            if (profiles.isEmpty()) return;
            logger.debug("Polling {} scheduled profiles", profiles.size());

            for (ImportProfileDefinition profile : profiles) {
                ConnectorDefinition connector = resolveConnectorForProfile(profile);
                if (connector == null) continue;
                try {
                    // Use a system-level CallContext for scheduled operations
                    // In production, this would use a service account context
                    FetchResult result = executeFetch(null, profile, connector, Map.of());
                    if (result.imported() > 0) {
                        logger.info("Scheduled fetch for {}: imported {} of {} fetched",
                                profile.getProfileId(), result.imported(), result.fetched());
                    }
                    if (result.hasErrors()) {
                        logger.warn("Scheduled fetch for {} had {} errors",
                                profile.getProfileId(), result.errors().size());
                    }
                } catch (Exception e) {
                    logger.error("Scheduled fetch failed for {}: {}", profile.getProfileId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Ingest scheduler poll failed: {}", e.getMessage());
        }
    }

    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private CanonicalImportService canonicalImportService;
    private IntegrationSettingsService settingsService;
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

    public void setSettingsService(IntegrationSettingsService settingsService) {
        this.settingsService = settingsService;
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
    public FetchResult executeImapFetch(CallContext callContext, ImportProfileDefinition profile,
                                            ConnectorDefinition connector, String mailboxFolder, int limit) {
        if (canonicalImportService == null) {
            return new FetchResult(0, 0, List.of("CanonicalImportService not available"));
        }

        String password = resolvePassword(connector);
        if (password == null) {
            return new FetchResult(0, 0, List.of("Could not resolve IMAP password for connector: " + connector.getConnectorId()));
        }

        ImapConnectorAdapter imap = new ImapConnectorAdapter(connector, password);
        List<String> errors = new ArrayList<>();
        int fetched = 0;
        int imported = 0;

        try {
            imap.connect();
            List<MessageSummary> messages = imap.listMessages(mailboxFolder, null, limit);

            // Load checkpoint including uidValidity
            long[] checkpoint = loadCheckpointWithValidity(profile.getProfileId(), mailboxFolder);
            long lastUidValidity = checkpoint[0];
            long lastImportedUid = checkpoint[1];

            // If uidValidity changed, reset checkpoint (mailbox was recreated/compacted)
            long currentUidValidity = messages.isEmpty() ? 0 : messages.get(0).uidValidity();
            if (lastUidValidity > 0 && currentUidValidity > 0 && lastUidValidity != currentUidValidity) {
                logger.warn("UIDVALIDITY changed ({} → {}), resetting checkpoint for {}/{}",
                        lastUidValidity, currentUidValidity, profile.getProfileId(), mailboxFolder);
                lastImportedUid = 0;
            }

            // Filter out already-imported messages
            final long filterUid = lastImportedUid;
            if (filterUid > 0) {
                messages = messages.stream().filter(m -> m.uid() > filterUid).toList();
            }
            fetched = messages.size();
            logger.info("IMAP fetch: {} new messages from {}:{} (checkpoint UID {}, validity {})",
                    fetched, connector.getEndpoint(), mailboxFolder, lastImportedUid, currentUidValidity);

            // Process oldest-first to ensure checkpoint advances monotonically
            List<MessageSummary> oldestFirst = new ArrayList<>(messages);
            oldestFirst.sort((a, b) -> Long.compare(a.uid(), b.uid()));

            long highWaterMark = lastImportedUid;

            for (MessageSummary msg : oldestFirst) {
                try {
                    InputStream eml = imap.fetchMessage(mailboxFolder, msg.uid());

                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(msg.stableKey());
                    req.setSourceObjectType("message");
                    req.setFileName(sanitizeSubject(msg.subject()) + ".eml");
                    req.setMimeType("message/rfc822");
                    req.setContentStream(eml);
                    req.setExecutionMode("scheduled");

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("mailboxId", mailboxFolder);
                    metadata.put("messageStableId", msg.stableKey());
                    metadata.put("uidValidity", String.valueOf(msg.uidValidity()));
                    if (msg.messageId() != null) metadata.put("internetMessageId", msg.messageId());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    if (result.isSuccess()) {
                        // Only advance checkpoint if no attachment failures in warnings
                        boolean hasAttachmentFailure = result.warnings().stream()
                                .anyMatch(w -> w.contains("Attachment") && w.contains("failed"));
                        if (hasAttachmentFailure) {
                            logger.warn("Message UID {} imported with attachment failures, not checkpointing", msg.uid());
                            errors.add("Message UID " + msg.uid() + " partial: " + String.join(", ", result.warnings()));
                        } else {
                            imported++;
                            highWaterMark = Math.max(highWaterMark, msg.uid());
                        }
                    } else if (result.skipped()) {
                        // Advance past skipped messages to avoid re-fetching
                        highWaterMark = Math.max(highWaterMark, msg.uid());
                        logger.debug("IMAP message {} skipped: {}", msg.uid(), result.skipReason());
                    } else {
                        errors.add("Message UID " + msg.uid() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    errors.add("Message UID " + msg.uid() + ": " + e.getMessage());
                }
            }

            // Save checkpoint once after batch completes
            if (highWaterMark > lastImportedUid) {
                saveCheckpointWithValidity(profile.getProfileId(), mailboxFolder, currentUidValidity, highWaterMark);
            }
        } catch (Exception e) {
            errors.add("IMAP connection failed: " + e.getMessage());
            logger.error("IMAP fetch failed for {}: {}", connector.getEndpoint(), e.getMessage());
        } finally {
            imap.disconnect();
        }

        logger.info("IMAP fetch complete: fetched={}, imported={}, errors={}", fetched, imported, errors.size());
        return new FetchResult(fetched, imported, errors);
    }

    /**
     * Load checkpoint as [uidValidity, lastUid].
     */
    private long[] loadCheckpointWithValidity(String profileId, String mailboxFolder) {
        if (settingsService == null) return new long[]{0, 0};
        String key = "ingest.checkpoint." + profileId + "." + mailboxFolder;
        String value = settingsService.readSetting(key);
        if (value == null || value.isBlank()) return new long[]{0, 0};
        try {
            String[] parts = value.split(":");
            if (parts.length == 2) {
                return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
            }
            // Legacy format: uid only
            return new long[]{0, Long.parseLong(value)};
        } catch (NumberFormatException e) {
            return new long[]{0, 0};
        }
    }

    private void saveCheckpointWithValidity(String profileId, String mailboxFolder,
                                            long uidValidity, long uid) {
        if (settingsService == null) return;
        String key = "ingest.checkpoint." + profileId + "." + mailboxFolder;
        settingsService.writeSetting(key, uidValidity + ":" + uid);
        logger.debug("Checkpoint saved: {}/{} → {}:{}", profileId, mailboxFolder, uidValidity, uid);
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
     * Result of a fetch run (any adapter).
     */
    public record FetchResult(int fetched, int imported, List<String> errors) {
        public boolean hasErrors() { return errors != null && !errors.isEmpty(); }
    }

    // ── Gmail fetch ─────────────────────────────────────────────────

    public FetchResult executeGmailFetch(CallContext callContext, ImportProfileDefinition profile,
                                         ConnectorDefinition connector, String query, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No access token for Gmail connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            GmailConnectorAdapter gmail = new GmailConnectorAdapter(token);
            List<GmailMessageSummary> messages = gmail.listMessages(query, limit);
            fetched = messages.size();

            for (GmailMessageSummary msg : messages) {
                try {
                    InputStream eml = gmail.fetchRawMessage(msg.id());
                    ExternalIngestRequest req = buildMailRequest(profile, connector, msg.id(), msg.subject(), eml);
                    req.getMetadata().put("internetMessageId", msg.id());
                    req.getMetadata().put("gmailThreadId", msg.threadId());

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    if (result.isSuccess()) imported++;
                    else if (!result.skipped()) errors.add("Gmail " + msg.id() + ": " + String.join(", ", result.errors()));
                } catch (Exception e) {
                    errors.add("Gmail " + msg.id() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("Gmail connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── M365 Mail fetch ─────────────────────────────────────────────

    public FetchResult executeM365MailFetch(CallContext callContext, ImportProfileDefinition profile,
                                            ConnectorDefinition connector, String folderId, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No access token for M365 Mail connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            M365MailConnectorAdapter m365 = new M365MailConnectorAdapter(token);
            List<M365MessageSummary> messages = m365.listMessages(
                    folderId != null ? folderId : "inbox", limit, null);
            fetched = messages.size();

            for (M365MessageSummary msg : messages) {
                try {
                    InputStream eml = m365.fetchMimeMessage(msg.id());
                    ExternalIngestRequest req = buildMailRequest(profile, connector, msg.id(), msg.subject(), eml);
                    if (msg.internetMessageId() != null) req.getMetadata().put("internetMessageId", msg.internetMessageId());

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    if (result.isSuccess()) imported++;
                    else if (!result.skipped()) errors.add("M365 " + msg.id() + ": " + String.join(", ", result.errors()));
                } catch (Exception e) {
                    errors.add("M365 " + msg.id() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("M365 Mail connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Notion fetch ─────────────────────────────────────────────────

    public FetchResult executeNotionFetch(CallContext callContext, ImportProfileDefinition profile,
                                          ConnectorDefinition connector, String query, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Notion connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            NotionConnectorAdapter notion = new NotionConnectorAdapter(token);
            List<NotionPageSummary> pages = notion.searchPages(query, limit);
            fetched = pages.size();

            for (NotionPageSummary page : pages) {
                try {
                    String html = notion.fetchPageAsHtml(page.id());

                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(page.id());
                    req.setSourceObjectType("page");
                    req.setFileName(sanitizeSubject(page.title()) + ".html");
                    req.setMimeType("text/html");
                    req.setContentStream(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                    req.setExecutionMode("scheduled");

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("pageId", page.id());
                    metadata.put("pageUrl", page.url());
                    metadata.put("parentPageId", page.parentId());
                    metadata.put("workspaceId", connector.getTenantId());

                    // Fetch file attachments
                    List<NotionFile> files = notion.extractFiles(page.id());
                    if (!files.isEmpty()) {
                        List<Map<String, Object>> attachments = new ArrayList<>();
                        for (NotionFile f : files) {
                            try {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                notion.downloadFile(f.url()).transferTo(baos);
                                Map<String, Object> att = new LinkedHashMap<>();
                                att.put("attachmentId", f.blockId());
                                att.put("filename", f.name());
                                att.put("mimeType", f.type().equals("image") ? "image/png" : "application/octet-stream");
                                att.put("contentBase64", java.util.Base64.getEncoder().encodeToString(baos.toByteArray()));
                                attachments.add(att);
                            } catch (Exception e) {
                                errors.add("Notion file " + f.name() + ": " + e.getMessage());
                            }
                        }
                        metadata.put("attachments", attachments);
                    }
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.executeNoteImport(callContext, req);
                    if (result.isSuccess()) imported++;
                    else if (!result.skipped()) errors.add("Notion " + page.id() + ": " + String.join(", ", result.errors()));
                } catch (Exception e) {
                    errors.add("Notion page " + page.id() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("Notion connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Salesforce fetch ─────────────────────────────────────────────

    public FetchResult executeSalesforceFetch(CallContext callContext, ImportProfileDefinition profile,
                                              ConnectorDefinition connector, String soql) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Salesforce connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            SalesforceConnectorAdapter sf = new SalesforceConnectorAdapter(connector.getEndpoint(), token);
            List<SalesforceConnectorAdapter.SalesforceRecord> records = sf.query(soql);
            fetched = records.size();

            for (var rec : records) {
                try {
                    String json = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writerWithDefaultPrettyPrinter().writeValueAsString(rec.fields());

                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(rec.id());
                    req.setSourceObjectType("record");
                    req.setFileName(sanitizeSubject(rec.name()) + ".json");
                    req.setMimeType("application/json");
                    req.setContentStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                    req.setExecutionMode("scheduled");

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("recordType", rec.type());
                    metadata.put("recordId", rec.id());
                    metadata.put("recordUrl", connector.getEndpoint() + "/" + rec.id());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.executeBusinessRecordImport(callContext, req);
                    if (result.isSuccess()) imported++;
                    else if (!result.skipped()) errors.add("SF " + rec.id() + ": " + String.join(", ", result.errors()));
                } catch (Exception e) {
                    errors.add("SF record " + rec.id() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            errors.add("Salesforce connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Slack fetch ───────────────────────────────────────────────────

    public FetchResult executeSlackFetch(CallContext callContext, ImportProfileDefinition profile,
                                         ConnectorDefinition connector, String channelId, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Slack connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            SlackConnectorAdapter slack = new SlackConnectorAdapter(token);
            List<SlackMessage> messages = slack.getHistory(channelId, null, limit);
            fetched = messages.size();

            for (SlackMessage msg : messages) {
                // Import each file attachment from the message
                for (SlackFile file : msg.files()) {
                    if (file.urlPrivateDownload() == null) continue;
                    try {
                        InputStream content = slack.downloadFile(file.urlPrivateDownload());

                        ExternalIngestRequest req = new ExternalIngestRequest();
                        req.setProfileId(profile.getProfileId());
                        req.setConnectorId(connector.getConnectorId());
                        req.setRepositoryId(profile.getRepositoryId());
                        req.setSourceObjectId(file.id());
                        req.setSourceObjectType("attachment");
                        req.setFileName(file.name());
                        req.setMimeType(file.mimeType());
                        req.setContentStream(content);
                        req.setExecutionMode("scheduled");

                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("channelId", channelId);
                        metadata.put("threadId", msg.threadTs());
                        metadata.put("messageId", msg.ts());
                        metadata.put("workspaceId", connector.getTenantId());
                        req.setMetadata(metadata);

                        ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                        if (result.isSuccess()) imported++;
                        else if (!result.skipped()) errors.add("Slack file " + file.id() + ": " + String.join(", ", result.errors()));
                    } catch (Exception e) {
                        errors.add("Slack file " + file.id() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            errors.add("Slack connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Unified dispatch ────────────────────────────────────────────

    /**
     * Dispatch fetch to the appropriate adapter based on connector archetype/sourceSystem.
     */
    public FetchResult executeFetch(CallContext callContext, ImportProfileDefinition profile,
                                    ConnectorDefinition connector, Map<String, String> params) {
        SourceArchetype archetype = connector.getSourceArchetype();
        String system = connector.getSourceSystem();
        int limit = params.containsKey("limit") ? Integer.parseInt(params.get("limit")) : 50;

        return switch (archetype) {
            case MESSAGE_CONTEXT -> switch (system != null ? system : "") {
                case "gmail_mail" -> executeGmailFetch(callContext, profile, connector,
                        params.getOrDefault("query", "in:inbox is:unread"), limit);
                case "m365_mail" -> executeM365MailFetch(callContext, profile, connector,
                        params.getOrDefault("folderId", "inbox"), limit);
                default -> executeImapFetch(callContext, profile, connector,
                        params.getOrDefault("mailbox", "INBOX"), limit);
            };
            case COMPOUND_NOTE -> executeNotionFetch(callContext, profile, connector,
                    params.getOrDefault("query", null), limit);
            case BUSINESS_RECORD -> executeSalesforceFetch(callContext, profile, connector,
                    params.getOrDefault("soql", "SELECT Id, Name FROM Account LIMIT " + limit));
            case CHAT_CONTEXT -> executeSlackFetch(callContext, profile, connector,
                    params.getOrDefault("channelId", ""), limit);
            case FILE_SHARE -> new FetchResult(0, 0, List.of(
                    "FILE_SHARE uses cloud drive sync (push/pull), not scheduled fetch"));
        };
    }

    private ExternalIngestRequest buildMailRequest(ImportProfileDefinition profile, ConnectorDefinition connector,
                                                   String sourceId, String subject, InputStream eml) {
        ExternalIngestRequest req = new ExternalIngestRequest();
        req.setProfileId(profile.getProfileId());
        req.setConnectorId(connector.getConnectorId());
        req.setRepositoryId(profile.getRepositoryId());
        req.setSourceObjectId(sourceId);
        req.setSourceObjectType("message");
        req.setFileName(sanitizeSubject(subject) + ".eml");
        req.setMimeType("message/rfc822");
        req.setContentStream(eml);
        req.setExecutionMode("scheduled");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mailboxId", "inbox");
        metadata.put("messageStableId", sourceId);
        req.setMetadata(metadata);
        return req;
    }
}
