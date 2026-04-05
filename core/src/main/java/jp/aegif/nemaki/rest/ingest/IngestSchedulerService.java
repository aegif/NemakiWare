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
import jp.aegif.nemaki.rest.ingest.chat.TeamsConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.chat.TeamsConnectorAdapter.TeamsMessage;
import jp.aegif.nemaki.rest.ingest.chat.TeamsConnectorAdapter.TeamsFile;
import jp.aegif.nemaki.rest.ingest.chat.MattermostConnectorAdapter;
import jp.aegif.nemaki.rest.ingest.chat.MattermostConnectorAdapter.MattermostPost;
import jp.aegif.nemaki.rest.ingest.chat.MattermostConnectorAdapter.MattermostFile;
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

    // ── IMAP IDLE monitoring ──────────────────────────────────────

    private final Map<String, ImapConnectorAdapter> idleAdapters = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Start IMAP IDLE monitoring for a specific profile.
     * Runs on a virtual thread and processes new messages as they arrive.
     */
    public String startIdle(String profileId) {
        if (idleAdapters.containsKey(profileId)) {
            return "IDLE already running for profile: " + profileId;
        }

        ImportProfileDefinition profile = profileService != null
                ? profileService.get(profileId) : null;
        if (profile == null) return "Profile not found: " + profileId;

        ConnectorDefinition connector = resolveConnectorForProfile(profile);
        if (connector == null) return "No connector for profile: " + profileId;
        if (!"imap".equals(connector.getSourceSystem())) {
            return "IDLE is only supported for IMAP connectors (system=" + connector.getSourceSystem() + ")";
        }

        String password = resolvePassword(connector);
        if (password == null) return "No password for IMAP connector";

        String mailbox = profile.getSchedulerParams() != null
                ? profile.getSchedulerParams().getOrDefault("mailbox", "INBOX") : "INBOX";

        ImapConnectorAdapter imap = new ImapConnectorAdapter(connector, password);
        idleAdapters.put(profileId, imap);

        Thread.ofVirtual().name("imap-idle-" + profileId).start(() -> {
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
                        req.setFileName(sanitizeSubject(msg.subject()) + ".eml");
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

        logger.info("IMAP IDLE monitoring started for profile {}", profileId);
        return null; // success
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

    private void pollScheduledProfiles() {
        try {
            List<ImportProfileDefinition> profiles = getScheduledProfiles();
            if (profiles.isEmpty()) return;
            logger.debug("Polling {} scheduled profiles", profiles.size());

            for (ImportProfileDefinition profile : profiles) {
                ConnectorDefinition connector = resolveConnectorForProfile(profile);
                if (connector == null) continue;

                // Create job record
                IngestJobRecord job = null;
                if (ingestJobService != null) {
                    try {
                        job = ingestJobService.createJob(profile.getProfileId(),
                                connector.getConnectorId(), profile.getRepositoryId());
                    } catch (Exception e) {
                        logger.debug("Failed to create job record: {}", e.getMessage());
                    }
                }

                try {
                    Map<String, String> params = profile.getSchedulerParams() != null
                            ? profile.getSchedulerParams() : Map.of();
                    FetchResult result = executeFetch(null, profile, connector, params);

                    // Complete job record
                    if (job != null && ingestJobService != null) {
                        try {
                            ingestJobService.completeJob(job, result);
                        } catch (Exception e) {
                            logger.debug("Failed to update job record: {}", e.getMessage());
                        }
                    }

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
                    // Record failed job
                    if (job != null && ingestJobService != null) {
                        try {
                            ingestJobService.completeJob(job,
                                    new FetchResult(0, 0, List.of(e.getMessage())));
                        } catch (Exception je) {
                            logger.debug("Failed to record job failure: {}", je.getMessage());
                        }
                    }
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
    private IngestJobService ingestJobService;

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

    public void setIngestJobService(IngestJobService ingestJobService) {
        this.ingestJobService = ingestJobService;
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

        // Prefer defaultConnectorId — but still enforce profile's own restrictions
        String defaultId = profile.getDefaultConnectorId();
        if (defaultId != null && !defaultId.isBlank()) {
            ConnectorDefinition connector = connectorService.get(defaultId);
            if (connector != null && connector.isEnabled()
                    && profile.isConnectorAllowed(connector.getConnectorId())
                    && profile.isArchetypeAllowed(connector.getSourceArchetype())) {
                return connector;
            }
            if (connector != null) {
                logger.warn("Default connector {} for profile {} is not usable " +
                        "(enabled={}, connectorAllowed={}, archetypeAllowed={})",
                        defaultId, profile.getProfileId(), connector.isEnabled(),
                        profile.isConnectorAllowed(connector.getConnectorId()),
                        profile.isArchetypeAllowed(connector.getSourceArchetype()));
            } else {
                logger.warn("Default connector {} for profile {} does not exist", defaultId, profile.getProfileId());
            }
            // Scheduler-enabled profiles must NOT fall back to a different connector —
            // doing so would silently switch the data source being polled
            if (profile.isSchedulerEnabled()) {
                return null;
            }
        }

        // Fallback for non-scheduled profiles only: find first allowed connector by archetype
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
            long throttleMs = calculateThrottleDelayMs(connector);

            for (MessageSummary msg : oldestFirst) {
                throttle(throttleMs);
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
     * Calculate per-request throttle delay in milliseconds based on connector rateLimitRpm.
     * Returns 0 if no rate limit is configured.
     */
    static long calculateThrottleDelayMs(ConnectorDefinition connector) {
        if (connector.getRateLimitRpm() == null || connector.getRateLimitRpm() <= 0) return 0;
        // Convert RPM to milliseconds between requests
        return 60_000L / connector.getRateLimitRpm();
    }

    /** Sleep for the throttle delay, if configured. Swallows InterruptedException. */
    static void throttle(long delayMs) {
        if (delayMs > 0) {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Truncate text to a reasonable size for externalContext storage (max 2000 chars). */
    private static String truncateForContext(String text) {
        if (text == null) return "";
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }

    /** Create a direct relationship, logging errors instead of throwing. */
    private void createRelationshipSafe(CallContext callContext, String repositoryId,
                                        String sourceId, String targetId, List<String> errors) {
        try {
            if (canonicalImportService instanceof CanonicalImportServiceImpl impl) {
                String err = impl.createDirectRelationship(callContext, repositoryId, sourceId, targetId);
                if (err != null) errors.add(err);
            }
        } catch (Exception e) {
            errors.add("Relationship " + sourceId + " → " + targetId + ": " + e.getMessage());
        }
    }

    /** Load a simple string checkpoint for non-IMAP adapters. */
    private String loadSimpleCheckpoint(String profileId, String scope) {
        if (settingsService == null) return null;
        String key = "ingest.checkpoint." + profileId + "." + scope;
        String value = settingsService.readSetting(key);
        return (value != null && !value.isBlank()) ? value : null;
    }

    /** Save a simple string checkpoint for non-IMAP adapters. */
    private void saveSimpleCheckpoint(String profileId, String scope, String value) {
        if (settingsService == null || value == null) return;
        String key = "ingest.checkpoint." + profileId + "." + scope;
        settingsService.writeSetting(key, value);
        logger.debug("Checkpoint saved: {} → {}", key, value);
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
            // Append checkpoint date to query to avoid re-fetching old messages
            // Gmail `after:` uses YYYY/MM/DD format
            String lastDate = loadSimpleCheckpoint(profile.getProfileId(), "gmail");
            String effectiveQuery = query;
            if (lastDate != null) {
                effectiveQuery = query + " after:" + lastDate;
            }
            List<GmailMessageSummary> messages = gmail.listMessages(effectiveQuery, limit);
            fetched = messages.size();
            long throttleMs = calculateThrottleDelayMs(connector);

            for (GmailMessageSummary msg : messages) {
                throttle(throttleMs);
                try {
                    InputStream eml = gmail.fetchRawMessage(msg.id());
                    ExternalIngestRequest req = buildMailRequest(profile, connector, msg.id(), msg.subject(), eml, "inbox");
                    req.getMetadata().put("internetMessageId", msg.id());
                    req.getMetadata().put("gmailThreadId", msg.threadId());

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    if (result.isSuccess()) imported++;
                    else if (!result.skipped()) errors.add("Gmail " + msg.id() + ": " + String.join(", ", result.errors()));
                } catch (Exception e) {
                    errors.add("Gmail " + msg.id() + ": " + e.getMessage());
                }
            }
            // Save current date as checkpoint (Gmail after: uses YYYY/MM/DD)
            if (fetched > 0) {
                saveSimpleCheckpoint(profile.getProfileId(), "gmail",
                        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")));
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
            String mailboxScope = folderId != null ? folderId : "inbox";
            // Use checkpoint to filter by receivedDateTime
            String lastDateTime = loadSimpleCheckpoint(profile.getProfileId(), "m365mail." + mailboxScope);
            String filter = lastDateTime != null ? "receivedDateTime gt " + lastDateTime : null;
            List<M365MessageSummary> messages = m365.listMessages(mailboxScope, limit, filter);
            fetched = messages.size();
            long throttleMs = calculateThrottleDelayMs(connector);

            for (M365MessageSummary msg : messages) {
                throttle(throttleMs);
                try {
                    InputStream eml = m365.fetchMimeMessage(msg.id());
                    String mailbox = folderId != null ? folderId : "inbox";
                    ExternalIngestRequest req = buildMailRequest(profile, connector, msg.id(), msg.subject(), eml, mailbox);
                    if (msg.internetMessageId() != null) req.getMetadata().put("internetMessageId", msg.internetMessageId());

                    ExternalIngestResult result = canonicalImportService.executeMailImport(callContext, req);
                    if (result.isSuccess()) imported++;
                    else if (!result.skipped()) errors.add("M365 " + msg.id() + ": " + String.join(", ", result.errors()));
                } catch (Exception e) {
                    errors.add("M365 " + msg.id() + ": " + e.getMessage());
                }
            }
            // Save current ISO timestamp as checkpoint
            if (fetched > 0) {
                saveSimpleCheckpoint(profile.getProfileId(), "m365mail." + mailboxScope,
                        java.time.Instant.now().toString());
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
            String lastEditedCheckpoint = loadSimpleCheckpoint(profile.getProfileId(), "notion");
            List<NotionPageSummary> pages = notion.searchPages(query, limit);
            fetched = pages.size();
            String highWaterEditedTime = lastEditedCheckpoint;
            long throttleMs = calculateThrottleDelayMs(connector);

            for (NotionPageSummary page : pages) {
                throttle(throttleMs);
                // Skip pages not modified since last checkpoint
                if (lastEditedCheckpoint != null && page.lastEditedTime() != null
                        && page.lastEditedTime().compareTo(lastEditedCheckpoint) <= 0) {
                    continue;
                }
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

                    // Fetch file attachments — separate binary data from persisted metadata
                    // to prevent multi-MB base64 blobs from leaking into externalContext
                    List<NotionFile> files = notion.extractFiles(page.id());
                    List<byte[]> attachmentBinaries = new ArrayList<>();
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
                                // Binary kept in separate list; NOT in the metadata map
                                attachments.add(att);
                                attachmentBinaries.add(baos.toByteArray());
                            } catch (Exception e) {
                                errors.add("Notion file " + f.name() + ": " + e.getMessage());
                            }
                        }
                        metadata.put("attachments", attachments);
                    }
                    req.setMetadata(metadata);

                    // Inject contentBase64 transiently for executeNoteImport to consume,
                    // then strip it so it is never persisted to externalContext
                    if (!attachmentBinaries.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> attList = (List<Map<String, Object>>) metadata.get("attachments");
                        for (int ai = 0; ai < attList.size() && ai < attachmentBinaries.size(); ai++) {
                            attList.get(ai).put("contentBase64",
                                    java.util.Base64.getEncoder().encodeToString(attachmentBinaries.get(ai)));
                        }
                    }

                    ExternalIngestResult result = canonicalImportService.executeNoteImport(callContext, req);

                    // Strip contentBase64 after import (defense in depth — CanonicalImportServiceImpl
                    // also strips via stripBinaryContent, but this ensures the scheduler's reference is clean)
                    if (!attachmentBinaries.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> attList = (List<Map<String, Object>>) metadata.get("attachments");
                        for (Map<String, Object> att : attList) {
                            att.remove("contentBase64");
                        }
                    }

                    if (result.isSuccess() || result.skipped()) {
                        if (page.lastEditedTime() != null
                                && (highWaterEditedTime == null || page.lastEditedTime().compareTo(highWaterEditedTime) > 0)) {
                            highWaterEditedTime = page.lastEditedTime();
                        }
                    }
                    if (result.isSuccess()) imported++;
                    else if (!result.skipped()) errors.add("Notion " + page.id() + ": " + String.join(", ", result.errors()));
                } catch (Exception e) {
                    errors.add("Notion page " + page.id() + ": " + e.getMessage());
                }
            }
            if (highWaterEditedTime != null && !highWaterEditedTime.equals(lastEditedCheckpoint)) {
                saveSimpleCheckpoint(profile.getProfileId(), "notion", highWaterEditedTime);
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
            // Inject checkpoint filter into SOQL — only when query has no WHERE clause
            String lastModified = loadSimpleCheckpoint(profile.getProfileId(), "salesforce");
            String effectiveSoql = soql;
            if (lastModified != null) {
                String lower = soql.toLowerCase();
                if (!lower.contains("where")) {
                    // Find LIMIT/ORDER BY/GROUP BY insertion point (case-insensitive)
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "\\b(limit|order\\s+by|group\\s+by)\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(soql);
                    String whereClause = " WHERE LastModifiedDate > " + lastModified;
                    if (m.find()) {
                        effectiveSoql = soql.substring(0, m.start()) + whereClause + " " + soql.substring(m.start());
                    } else {
                        effectiveSoql = soql + whereClause;
                    }
                }
                // If user already has WHERE, trust their query — dedupe handles the rest
            }
            List<SalesforceConnectorAdapter.SalesforceRecord> records = sf.query(effectiveSoql);
            fetched = records.size();
            long throttleMs = calculateThrottleDelayMs(connector);

            for (var rec : records) {
                throttle(throttleMs);
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
            if (fetched > 0) {
                saveSimpleCheckpoint(profile.getProfileId(), "salesforce",
                        java.time.Instant.now().toString());
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
            String lastTs = loadSimpleCheckpoint(profile.getProfileId(), "slack." + channelId);
            List<SlackMessage> messages = slack.getHistory(channelId, lastTs, limit);
            fetched = messages.size();
            String highWaterTs = lastTs;
            long throttleMs = calculateThrottleDelayMs(connector);

            for (SlackMessage msg : messages) {
                throttle(throttleMs);
                try {
                    // Import message itself as a chat_message document
                    String messageText = msg.text() != null ? msg.text() : "";
                    ExternalIngestRequest msgReq = new ExternalIngestRequest();
                    msgReq.setProfileId(profile.getProfileId());
                    msgReq.setConnectorId(connector.getConnectorId());
                    msgReq.setRepositoryId(profile.getRepositoryId());
                    msgReq.setSourceObjectId(channelId + "/" + msg.ts());
                    msgReq.setSourceObjectType("chat_message");
                    msgReq.setFileName("slack-" + msg.ts().replace(".", "-") + ".txt");
                    msgReq.setMimeType("text/plain");
                    msgReq.setContentStream(new ByteArrayInputStream(messageText.getBytes(StandardCharsets.UTF_8)));
                    msgReq.setExecutionMode("scheduled");
                    Map<String, Object> msgMeta = new LinkedHashMap<>();
                    msgMeta.put("channelId", channelId);
                    msgMeta.put("threadId", msg.threadTs());
                    msgMeta.put("messageId", msg.ts());
                    msgMeta.put("senderId", msg.userId());
                    msgMeta.put("messageText", truncateForContext(messageText));
                    msgMeta.put("workspaceId", connector.getTenantId());
                    msgReq.setMetadata(msgMeta);

                    ExternalIngestResult msgResult = canonicalImportService.executeChatContextImport(callContext, msgReq);
                    String parentObjectId = msgResult.isSuccess() ? msgResult.objectId() : null;
                    if (msgResult.isSuccess() || msgResult.skipped()) {
                        if (highWaterTs == null || msg.ts().compareTo(highWaterTs) > 0) {
                            highWaterTs = msg.ts();
                        }
                    }
                    if (msgResult.isSuccess()) imported++;
                    else if (!msgResult.skipped()) errors.add("Slack msg " + msg.ts() + ": " + String.join(", ", msgResult.errors()));

                    // Import file attachments as children
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
                            Map<String, Object> fileMeta = new LinkedHashMap<>();
                            fileMeta.put("channelId", channelId);
                            fileMeta.put("threadId", msg.threadTs());
                            fileMeta.put("messageId", msg.ts());
                            fileMeta.put("senderId", msg.userId());
                            fileMeta.put("messageText", truncateForContext(messageText));
                            fileMeta.put("workspaceId", connector.getTenantId());
                            req.setMetadata(fileMeta);

                            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                            if (result.isSuccess()) {
                                imported++;
                                // Link file to parent message
                                if (parentObjectId != null) {
                                    createRelationshipSafe(callContext, profile.getRepositoryId(),
                                            parentObjectId, result.objectId(), errors);
                                }
                            } else if (!result.skipped()) {
                                errors.add("Slack file " + file.id() + ": " + String.join(", ", result.errors()));
                            }
                        } catch (Exception e) {
                            errors.add("Slack file " + file.id() + ": " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    errors.add("Slack msg " + msg.ts() + ": " + e.getMessage());
                }
            }
            // Save checkpoint after batch
            if (highWaterTs != null && !highWaterTs.equals(lastTs)) {
                saveSimpleCheckpoint(profile.getProfileId(), "slack." + channelId, highWaterTs);
            }
        } catch (Exception e) {
            errors.add("Slack connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Teams fetch ────────────────────────────────────────────────

    public FetchResult executeTeamsFetch(CallContext callContext, ImportProfileDefinition profile,
                                         ConnectorDefinition connector, String teamId, String channelId, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Teams connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            TeamsConnectorAdapter teams = new TeamsConnectorAdapter(token);
            List<TeamsMessage> messages = teams.getMessages(teamId, channelId, limit);
            fetched = messages.size();
            String lastDateTime = loadSimpleCheckpoint(profile.getProfileId(), "teams." + teamId + "." + channelId);
            String highWaterDateTime = lastDateTime;
            long throttleMs = calculateThrottleDelayMs(connector);

            for (TeamsMessage msg : messages) {
                throttle(throttleMs);
                // Skip messages older than checkpoint
                if (lastDateTime != null && msg.createdDateTime() != null
                        && msg.createdDateTime().compareTo(lastDateTime) <= 0) {
                    continue;
                }
                try {
                    // Import message itself as a chat_message document
                    String messageBody = msg.body() != null ? msg.body() : "";
                    ExternalIngestRequest msgReq = new ExternalIngestRequest();
                    msgReq.setProfileId(profile.getProfileId());
                    msgReq.setConnectorId(connector.getConnectorId());
                    msgReq.setRepositoryId(profile.getRepositoryId());
                    msgReq.setSourceObjectId(channelId + "/" + msg.id());
                    msgReq.setSourceObjectType("chat_message");
                    msgReq.setFileName("teams-" + msg.id() + ".html");
                    msgReq.setMimeType("text/html");
                    msgReq.setContentStream(new ByteArrayInputStream(messageBody.getBytes(StandardCharsets.UTF_8)));
                    msgReq.setExecutionMode("scheduled");
                    Map<String, Object> msgMeta = new LinkedHashMap<>();
                    msgMeta.put("channelId", channelId);
                    msgMeta.put("messageId", msg.id());
                    msgMeta.put("senderId", msg.from());
                    msgMeta.put("messageText", truncateForContext(messageBody));
                    msgMeta.put("workspaceId", teamId);
                    if (msg.replyToId() != null) msgMeta.put("threadId", msg.replyToId());
                    msgReq.setMetadata(msgMeta);

                    ExternalIngestResult msgResult = canonicalImportService.executeChatContextImport(callContext, msgReq);
                    String parentObjectId = msgResult.isSuccess() ? msgResult.objectId() : null;
                    if (msgResult.isSuccess() || msgResult.skipped()) {
                        if (msg.createdDateTime() != null && (highWaterDateTime == null || msg.createdDateTime().compareTo(highWaterDateTime) > 0)) {
                            highWaterDateTime = msg.createdDateTime();
                        }
                    }
                    if (msgResult.isSuccess()) imported++;
                    else if (!msgResult.skipped()) errors.add("Teams msg " + msg.id() + ": " + String.join(", ", msgResult.errors()));

                    // Import file attachments as children
                    for (TeamsFile file : msg.attachments()) {
                        if (file.contentUrl() == null) continue;
                        try {
                            InputStream content = teams.downloadFile(file.contentUrl());
                            ExternalIngestRequest req = new ExternalIngestRequest();
                            req.setProfileId(profile.getProfileId());
                            req.setConnectorId(connector.getConnectorId());
                            req.setRepositoryId(profile.getRepositoryId());
                            req.setSourceObjectId(file.id());
                            req.setSourceObjectType("attachment");
                            req.setFileName(file.name());
                            req.setMimeType(file.contentType());
                            req.setContentStream(content);
                            req.setExecutionMode("scheduled");
                            Map<String, Object> fileMeta = new LinkedHashMap<>();
                            fileMeta.put("channelId", channelId);
                            fileMeta.put("messageId", msg.id());
                            fileMeta.put("senderId", msg.from());
                            fileMeta.put("messageText", truncateForContext(messageBody));
                            fileMeta.put("workspaceId", teamId);
                            if (msg.replyToId() != null) fileMeta.put("threadId", msg.replyToId());
                            req.setMetadata(fileMeta);
                            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                            if (result.isSuccess()) {
                                imported++;
                                if (parentObjectId != null) {
                                    createRelationshipSafe(callContext, profile.getRepositoryId(),
                                            parentObjectId, result.objectId(), errors);
                                }
                            } else if (!result.skipped()) {
                                errors.add("Teams " + file.id() + ": " + String.join(", ", result.errors()));
                            }
                        } catch (Exception e) {
                            errors.add("Teams file " + file.id() + ": " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    errors.add("Teams msg " + msg.id() + ": " + e.getMessage());
                }
            }
            if (highWaterDateTime != null && !highWaterDateTime.equals(lastDateTime)) {
                saveSimpleCheckpoint(profile.getProfileId(), "teams." + teamId + "." + channelId, highWaterDateTime);
            }
        } catch (Exception e) {
            errors.add("Teams connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Mattermost fetch ─────────────────────────────────────────────

    public FetchResult executeMattermostFetch(CallContext callContext, ImportProfileDefinition profile,
                                              ConnectorDefinition connector, String channelId, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Mattermost connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            MattermostConnectorAdapter mm = new MattermostConnectorAdapter(connector.getEndpoint(), token);
            String lastCreateAt = loadSimpleCheckpoint(profile.getProfileId(), "mattermost." + channelId);
            List<MattermostPost> posts = mm.getPosts(channelId, limit);
            fetched = posts.size();
            long highWaterCreateAt = 0;
            try {
                if (lastCreateAt != null) highWaterCreateAt = Long.parseLong(lastCreateAt);
            } catch (NumberFormatException e) {
                logger.warn("Invalid Mattermost checkpoint '{}', resetting", lastCreateAt);
            }
            long throttleMs = calculateThrottleDelayMs(connector);

            for (MattermostPost post : posts) {
                throttle(throttleMs);
                // Skip posts older than checkpoint
                if (post.createAt() > 0 && post.createAt() <= highWaterCreateAt) continue;
                try {
                    // Import message itself as a chat_message document
                    String postText = post.message() != null ? post.message() : "";
                    ExternalIngestRequest msgReq = new ExternalIngestRequest();
                    msgReq.setProfileId(profile.getProfileId());
                    msgReq.setConnectorId(connector.getConnectorId());
                    msgReq.setRepositoryId(profile.getRepositoryId());
                    msgReq.setSourceObjectId(channelId + "/" + post.id());
                    msgReq.setSourceObjectType("chat_message");
                    msgReq.setFileName("mm-" + post.id() + ".txt");
                    msgReq.setMimeType("text/plain");
                    msgReq.setContentStream(new ByteArrayInputStream(postText.getBytes(StandardCharsets.UTF_8)));
                    msgReq.setExecutionMode("scheduled");
                    Map<String, Object> msgMeta = new LinkedHashMap<>();
                    msgMeta.put("channelId", channelId);
                    msgMeta.put("messageId", post.id());
                    msgMeta.put("senderId", post.userId());
                    msgMeta.put("messageText", truncateForContext(postText));
                    msgMeta.put("workspaceId", connector.getTenantId());
                    if (post.rootId() != null && !post.rootId().isEmpty()) msgMeta.put("threadId", post.rootId());
                    msgReq.setMetadata(msgMeta);

                    ExternalIngestResult msgResult = canonicalImportService.executeChatContextImport(callContext, msgReq);
                    String parentObjectId = msgResult.isSuccess() ? msgResult.objectId() : null;
                    if (msgResult.isSuccess() || msgResult.skipped()) {
                        if (post.createAt() > highWaterCreateAt) highWaterCreateAt = post.createAt();
                    }
                    if (msgResult.isSuccess()) imported++;
                    else if (!msgResult.skipped()) errors.add("MM msg " + post.id() + ": " + String.join(", ", msgResult.errors()));

                    // Import file attachments as children
                    for (String fileId : post.fileIds()) {
                        try {
                            MattermostFile fileInfo = mm.getFileInfo(fileId);
                            InputStream content = mm.downloadFile(fileId);
                            ExternalIngestRequest req = new ExternalIngestRequest();
                            req.setProfileId(profile.getProfileId());
                            req.setConnectorId(connector.getConnectorId());
                            req.setRepositoryId(profile.getRepositoryId());
                            req.setSourceObjectId(fileId);
                            req.setSourceObjectType("attachment");
                            req.setFileName(fileInfo.name());
                            req.setMimeType(fileInfo.mimeType());
                            req.setContentStream(content);
                            req.setExecutionMode("scheduled");
                            Map<String, Object> fileMeta = new LinkedHashMap<>();
                            fileMeta.put("channelId", channelId);
                            fileMeta.put("messageId", post.id());
                            fileMeta.put("senderId", post.userId());
                            fileMeta.put("messageText", truncateForContext(postText));
                            fileMeta.put("workspaceId", connector.getTenantId());
                            if (post.rootId() != null && !post.rootId().isEmpty()) fileMeta.put("threadId", post.rootId());
                            req.setMetadata(fileMeta);
                            ExternalIngestResult result = canonicalImportService.executeChatContextImport(callContext, req);
                            if (result.isSuccess()) {
                                imported++;
                                if (parentObjectId != null) {
                                    createRelationshipSafe(callContext, profile.getRepositoryId(),
                                            parentObjectId, result.objectId(), errors);
                                }
                            } else if (!result.skipped()) {
                                errors.add("MM " + fileId + ": " + String.join(", ", result.errors()));
                            }
                        } catch (Exception e) {
                            errors.add("MM file " + fileId + ": " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    errors.add("MM post " + post.id() + ": " + e.getMessage());
                }
            }
            long initialHighWater = 0;
            try {
                if (lastCreateAt != null) initialHighWater = Long.parseLong(lastCreateAt);
            } catch (NumberFormatException ignored) { /* already warned above */ }
            if (highWaterCreateAt > initialHighWater) {
                saveSimpleCheckpoint(profile.getProfileId(), "mattermost." + channelId, String.valueOf(highWaterCreateAt));
            }
        } catch (Exception e) {
            errors.add("Mattermost connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Chatwork fetch ─────────────────────────────────────────────

    public FetchResult executeChatworkFetch(CallContext callContext, ImportProfileDefinition profile,
                                            ConnectorDefinition connector, String roomId, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Chatwork connector"));
        if (roomId == null || roomId.isBlank()) return new FetchResult(0, 0, List.of("roomId is required for Chatwork"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            var chatwork = new jp.aegif.nemaki.rest.ingest.chat.ChatworkConnectorAdapter(token);
            // Always use force=1 (latest 100 messages) to avoid depending on
            // Chatwork's server-side delta cursor which can be advanced by other
            // API consumers or webhook-triggered fetches, causing message loss.
            // Dedupe is handled by the persisted message ID checkpoint below.
            String lastMsgId = loadSimpleCheckpoint(profile.getProfileId(), "chatwork." + roomId);
            var messages = chatwork.getMessages(roomId, true);
            fetched = messages.size();
            long throttleMs = calculateThrottleDelayMs(connector);
            String highWaterMsgId = lastMsgId;

            for (var msg : messages) {
                throttle(throttleMs);
                // Skip messages already processed (by message ID comparison)
                if (lastMsgId != null && msg.messageId().compareTo(lastMsgId) <= 0) continue;

                try {
                    String messageText = msg.body() != null ? msg.body() : "";
                    ExternalIngestRequest msgReq = new ExternalIngestRequest();
                    msgReq.setProfileId(profile.getProfileId());
                    msgReq.setConnectorId(connector.getConnectorId());
                    msgReq.setRepositoryId(profile.getRepositoryId());
                    // Use plain messageId — channelId is already in metadata and canonical URI builder
                    msgReq.setSourceObjectId(msg.messageId());
                    msgReq.setSourceObjectType("chat_message");
                    msgReq.setFileName("chatwork-" + msg.messageId() + ".txt");
                    msgReq.setMimeType("text/plain");
                    msgReq.setContentStream(new ByteArrayInputStream(messageText.getBytes(StandardCharsets.UTF_8)));
                    msgReq.setExecutionMode("scheduled");
                    Map<String, Object> msgMeta = new LinkedHashMap<>();
                    msgMeta.put("channelId", roomId);
                    msgMeta.put("messageId", msg.messageId());
                    msgMeta.put("senderId", msg.accountId());
                    msgMeta.put("senderName", msg.accountName());
                    msgMeta.put("messageText", truncateForContext(messageText));
                    msgMeta.put("workspaceId", connector.getTenantId());
                    msgReq.setMetadata(msgMeta);

                    ExternalIngestResult msgResult = canonicalImportService.executeChatContextImport(callContext, msgReq);
                    if (msgResult.isSuccess() || msgResult.skipped()) {
                        if (highWaterMsgId == null || msg.messageId().compareTo(highWaterMsgId) > 0) {
                            highWaterMsgId = msg.messageId();
                        }
                    }
                    if (msgResult.isSuccess()) imported++;
                    else if (!msgResult.skipped()) errors.add("Chatwork msg " + msg.messageId() + ": " + String.join(", ", msgResult.errors()));
                } catch (Exception e) {
                    errors.add("Chatwork msg " + msg.messageId() + ": " + e.getMessage());
                }
            }

            // Also import files from the room
            try {
                var files = chatwork.listFiles(roomId);
                for (var file : files) {
                    throttle(throttleMs);
                    try {
                        String dlUrl = chatwork.getFileDownloadUrl(roomId, file.fileId());
                        if (dlUrl == null) continue;
                        InputStream content = chatwork.downloadFile(dlUrl);
                        ExternalIngestRequest fileReq = new ExternalIngestRequest();
                        fileReq.setProfileId(profile.getProfileId());
                        fileReq.setConnectorId(connector.getConnectorId());
                        fileReq.setRepositoryId(profile.getRepositoryId());
                        fileReq.setSourceObjectId("file-" + file.fileId());
                        fileReq.setSourceObjectType("attachment");
                        fileReq.setFileName(file.name());
                        fileReq.setContentStream(content);
                        fileReq.setExecutionMode("scheduled");
                        Map<String, Object> fileMeta = new LinkedHashMap<>();
                        fileMeta.put("channelId", roomId);
                        fileMeta.put("workspaceId", connector.getTenantId());
                        fileReq.setMetadata(fileMeta);

                        ExternalIngestResult fileResult = canonicalImportService.executeChatContextImport(callContext, fileReq);
                        if (fileResult.isSuccess()) imported++;
                        else if (!fileResult.skipped()) errors.add("Chatwork file " + file.fileId() + ": " + String.join(", ", fileResult.errors()));
                    } catch (Exception e) {
                        errors.add("Chatwork file " + file.fileId() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                errors.add("Chatwork file list failed: " + e.getMessage());
            }

            if (highWaterMsgId != null && !highWaterMsgId.equals(lastMsgId)) {
                saveSimpleCheckpoint(profile.getProfileId(), "chatwork." + roomId, highWaterMsgId);
            }
        } catch (Exception e) {
            errors.add("Chatwork connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Box fetch ─────────────────────────────────────────────────

    public FetchResult executeBoxFetch(CallContext callContext, ImportProfileDefinition profile,
                                       ConnectorDefinition connector, String folderId, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Box connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            var box = new jp.aegif.nemaki.rest.ingest.fileshare.BoxConnectorAdapter(token);
            String lastModified = loadSimpleCheckpoint(profile.getProfileId(), "box." + folderId);
            var files = box.listFiles(folderId, limit);
            fetched = files.size();
            long throttleMs = calculateThrottleDelayMs(connector);
            String highWaterModified = lastModified;

            for (var file : files) {
                throttle(throttleMs);
                // Skip files not modified since checkpoint
                if (lastModified != null && file.modifiedAt() != null
                        && file.modifiedAt().compareTo(lastModified) <= 0) continue;

                try {
                    InputStream content = box.downloadFile(file.id());
                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(file.id());
                    req.setSourceObjectType("file");
                    req.setFileName(file.name());
                    req.setContentStream(content);
                    req.setExecutionMode("scheduled");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("boxFileId", file.id());
                    metadata.put("boxParentId", file.parentId());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.execute(callContext, req);
                    if (result.isSuccess()) {
                        imported++;
                        if (file.modifiedAt() != null
                                && (highWaterModified == null || file.modifiedAt().compareTo(highWaterModified) > 0)) {
                            highWaterModified = file.modifiedAt();
                        }
                    } else if (!result.skipped()) {
                        errors.add("Box " + file.id() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    errors.add("Box file " + file.id() + ": " + e.getMessage());
                }
            }
            if (highWaterModified != null && !highWaterModified.equals(lastModified)) {
                saveSimpleCheckpoint(profile.getProfileId(), "box." + folderId, highWaterModified);
            }
        } catch (Exception e) {
            errors.add("Box connection failed: " + e.getMessage());
        }
        return new FetchResult(fetched, imported, errors);
    }

    // ── Dropbox fetch ────────────────────────────────────────────────

    public FetchResult executeDropboxFetch(CallContext callContext, ImportProfileDefinition profile,
                                            ConnectorDefinition connector, String folderPath, int limit) {
        String token = resolvePassword(connector);
        if (token == null) return new FetchResult(0, 0, List.of("No token for Dropbox connector"));

        List<String> errors = new ArrayList<>();
        int fetched = 0, imported = 0;
        try {
            var dropbox = new jp.aegif.nemaki.rest.ingest.fileshare.DropboxConnectorAdapter(token);
            String lastModified = loadSimpleCheckpoint(profile.getProfileId(), "dropbox");
            var files = dropbox.listFiles(folderPath, limit);
            fetched = files.size();
            long throttleMs = calculateThrottleDelayMs(connector);
            String highWaterModified = lastModified;

            for (var file : files) {
                throttle(throttleMs);
                if (lastModified != null && file.serverModified() != null
                        && file.serverModified().compareTo(lastModified) <= 0) continue;

                try {
                    InputStream content = dropbox.downloadFile(file.pathDisplay());
                    ExternalIngestRequest req = new ExternalIngestRequest();
                    req.setProfileId(profile.getProfileId());
                    req.setConnectorId(connector.getConnectorId());
                    req.setRepositoryId(profile.getRepositoryId());
                    req.setSourceObjectId(file.id());
                    req.setSourceObjectType("file");
                    req.setFileName(file.name());
                    req.setContentStream(content);
                    req.setExecutionMode("scheduled");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("dropboxPath", file.pathDisplay());
                    metadata.put("dropboxFileId", file.id());
                    req.setMetadata(metadata);

                    ExternalIngestResult result = canonicalImportService.execute(callContext, req);
                    if (result.isSuccess()) {
                        imported++;
                        if (file.serverModified() != null
                                && (highWaterModified == null || file.serverModified().compareTo(highWaterModified) > 0)) {
                            highWaterModified = file.serverModified();
                        }
                    } else if (!result.skipped()) {
                        errors.add("Dropbox " + file.id() + ": " + String.join(", ", result.errors()));
                    }
                } catch (Exception e) {
                    errors.add("Dropbox file " + file.id() + ": " + e.getMessage());
                }
            }
            if (highWaterModified != null && !highWaterModified.equals(lastModified)) {
                saveSimpleCheckpoint(profile.getProfileId(), "dropbox", highWaterModified);
            }
        } catch (Exception e) {
            errors.add("Dropbox connection failed: " + e.getMessage());
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
        int limit = 50;
        try {
            if (params.containsKey("limit")) {
                limit = Math.max(1, Math.min(500, Integer.parseInt(params.get("limit"))));
            }
        } catch (NumberFormatException e) {
            logger.warn("Invalid limit parameter '{}', using default 50", params.get("limit"));
        }
        // Enforce rateLimitRpm: cap limit to avoid exceeding the connector's rate limit
        // in a single fetch cycle. Each fetched item typically requires 1-2 API calls.
        if (connector.getRateLimitRpm() != null && connector.getRateLimitRpm() > 0) {
            int maxPerCycle = connector.getRateLimitRpm() / 2; // conservative: ~2 API calls per item
            if (limit > maxPerCycle) {
                logger.debug("Rate limit: capping fetch limit from {} to {} for connector {}",
                        limit, maxPerCycle, connector.getConnectorId());
                limit = Math.max(1, maxPerCycle);
            }
        }

        return switch (archetype) {
            case MESSAGE_CONTEXT -> switch (system != null ? system : "") {
                case "gmail_mail" -> executeGmailFetch(callContext, profile, connector,
                        params.getOrDefault("query", "in:inbox is:unread"), limit);
                case "m365_mail" -> executeM365MailFetch(callContext, profile, connector,
                        params.getOrDefault("folderId", "inbox"), limit);
                case "imap" -> executeImapFetch(callContext, profile, connector,
                        params.getOrDefault("mailbox", "INBOX"), limit);
                default -> new FetchResult(0, 0, List.of(
                        "Unknown sourceSystem '" + system + "' for MESSAGE_CONTEXT. Supported: imap, gmail_mail, m365_mail"));
            };
            case COMPOUND_NOTE -> switch (system != null ? system : "") {
                case "notion" -> executeNotionFetch(callContext, profile, connector,
                        params.getOrDefault("query", null), limit);
                default -> new FetchResult(0, 0, List.of(
                        "Unknown sourceSystem '" + system + "' for COMPOUND_NOTE. Supported: notion"));
            };
            case BUSINESS_RECORD -> switch (system != null ? system : "") {
                case "salesforce" -> executeSalesforceFetch(callContext, profile, connector,
                        params.getOrDefault("soql", "SELECT Id, Name FROM Account LIMIT " + limit));
                default -> new FetchResult(0, 0, List.of(
                        "Unknown sourceSystem '" + system + "' for BUSINESS_RECORD. Supported: salesforce"));
            };
            case CHAT_CONTEXT -> switch (system != null ? system : "") {
                case "slack" -> executeSlackFetch(callContext, profile, connector,
                        params.getOrDefault("channelId", ""), limit);
                case "teams" -> executeTeamsFetch(callContext, profile, connector,
                        params.getOrDefault("teamId", ""), params.getOrDefault("channelId", ""), limit);
                case "mattermost" -> executeMattermostFetch(callContext, profile, connector,
                        params.getOrDefault("channelId", ""), limit);
                case "chatwork" -> executeChatworkFetch(callContext, profile, connector,
                        params.getOrDefault("roomId", ""), limit);
                default -> new FetchResult(0, 0, List.of(
                        "Unknown sourceSystem '" + system + "' for CHAT_CONTEXT. Supported: slack, teams, mattermost, chatwork"));
            };
            case FILE_SHARE -> switch (system != null ? system : "") {
                case "box" -> executeBoxFetch(callContext, profile, connector,
                        params.getOrDefault("folderId", "0"), limit);
                case "dropbox" -> executeDropboxFetch(callContext, profile, connector,
                        params.getOrDefault("folderPath", ""), limit);
                case "google_drive", "onedrive" -> new FetchResult(0, 0, List.of(
                        system + " uses cloud drive sync (push/pull), not scheduled fetch"));
                default -> new FetchResult(0, 0, List.of(
                        "Unknown sourceSystem '" + system + "' for FILE_SHARE. Supported: box, dropbox, google_drive, onedrive"));
            };
        };
    }

    private ExternalIngestRequest buildMailRequest(ImportProfileDefinition profile, ConnectorDefinition connector,
                                                   String sourceId, String subject, InputStream eml,
                                                   String mailboxId) {
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
        metadata.put("mailboxId", mailboxId != null ? mailboxId : "inbox");
        metadata.put("messageStableId", sourceId);
        req.setMetadata(metadata);
        return req;
    }
}
