package jp.aegif.nemaki.rest.ingest.mail;

import jp.aegif.nemaki.rest.ingest.*;
import org.apache.chemistry.opencmis.commons.server.CallContext;
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

    /**
     * One live IDLE session: the adapter and the repository it imports INTO. A session
     * captures its profile once, at {@link #startIdle}, and every message it later imports
     * carries THAT row's repositoryId — while the map is keyed by profileId alone. With the
     * same profileId in two repositories a caller could delete their own row and keep
     * receiving mail into it, because the deleting side could only see "some repository still
     * has this id".
     *
     * <p>Adapter and repository are ONE value on purpose. They were two maps, and a review
     * showed the pair coming apart: a terminating thread removed both entries by key, so a
     * session started after a slow stop had its registration erased by the old thread's
     * cleanup — after which {@code stopIdle} answered "no session running" for ever and the
     * live session was invisible to {@link #getIdleProfiles()} and unreachable through
     * {@link #stopIdle}. Identity, not key, decides removal.
     *
     * <p>What that costs is a connection nobody can close, not a stream of imports into a
     * removed row: the message loop reloads the profile on every message and refuses when it
     * is gone. An earlier version of this note said the session kept importing; a review
     * checked the loop and it does not.
     */
    record IdleSession(ImapConnectorAdapter adapter, String repositoryId,
            boolean startedDelegated, ConnectionIdentity connectionIdentity) {
        IdleSession(ImapConnectorAdapter adapter, String repositoryId) {
            this(adapter, repositoryId, false, null);
        }
        IdleSession(ImapConnectorAdapter adapter, String repositoryId,
                boolean startedDelegated) {
            this(adapter, repositoryId, startedDelegated, null);
        }
    }

    private final Map<String, IdleSession> idleSessions = new java.util.concurrent.ConcurrentHashMap<>();

    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private FetchSupport fetchSupport;
    private CanonicalImportService canonicalImportService;
    private IngestSchedulerService schedulerService;

    public void setProfileService(ImportProfileDefinitionService profileService) { this.profileService = profileService; }
    public void setConnectorService(ConnectorDefinitionService connectorService) { this.connectorService = connectorService; }
    public void setFetchSupport(FetchSupport fetchSupport) { this.fetchSupport = fetchSupport; }
    public void setCanonicalImportService(CanonicalImportService canonicalImportService) { this.canonicalImportService = canonicalImportService; }
    public void setSchedulerService(IngestSchedulerService schedulerService) { this.schedulerService = schedulerService; }

    /**
     * Start IMAP IDLE monitoring for a specific profile.
     * @return error message, or null on success
     */
    public String startIdle(String profileId) {
        // The real admission test is the putIfAbsent below. This is only a cheap early exit:
        // containsKey-then-put let two callers both pass and the second overwrite the first,
        // leaving a live adapter nobody could reach.
        if (idleSessions.containsKey(profileId)) {
            return "IDLE already running for profile: " + profileId;
        }

        LiveLoad load = loadLiveConfig(profileId, null, null, null);
        if (!load.ok()) {
            return load.refusal();
        }
        final ImportProfileDefinition profile = load.profile();
        ConnectorDefinition connector = load.connector();

        // SECURITY: a delegated profile must pass the same delegation re-authorization
        // the scheduler/webhook paths apply (creator active + cmis:all on target folder
        // + connector still delegated). Without this, IDLE would keep ingesting under an
        // unscoped admin context even after the delegation was revoked. Fail closed when
        // the authorization wiring is missing.
        if (profile.isDelegated()) {
            if (schedulerService == null) {
                // "not wired on this node", not a bad request — the same shape the
                // service's own null monitor and this class's null profile service
                // were corrected for. The endpoint classifies by this text.
                return "delegated IMAP IDLE could not be authorised: the scheduler is"
                        + " not wired on this node; retry shortly against a node that"
                        + " runs it";
            }
            IngestSchedulerService.DelegatedAuthorization auth =
                    schedulerService.authorizeDelegatedFetch(profile, connector);
            if (!auth.isAllowed()) {
                return "Delegated authorization denied for profile " + profileId
                        + " (" + auth.getDenialReason() + ")";
            }
        }

        String password = fetchSupport.resolvePassword(connector);
        if (password == null) return "No password for IMAP connector";

        String mailbox = profile.getSchedulerParams() != null
                ? profile.getSchedulerParams().getOrDefault("mailbox", "INBOX") : "INBOX";

        ImapConnectorAdapter imap = new ImapConnectorAdapter(connector, password);
        IdleSession session = new IdleSession(imap, profile.getRepositoryId(),
                profile.isDelegated(), connectionIdentity(connector, password));
        if (!registerSession(profileId, session)) {
            return "IDLE already running for profile: " + profileId;
        }

        // REGISTERED FIRST, then asked whether the profile is still there. Everything above
        // reads a profile that a concurrent DELETE can remove while this method prepares the
        // connection: that delete looked for a running session, found none, and returned —
        // and this session then held a connection nobody could close. Registering first
        // closes THAT window. It does not close the next one: the delete can still arrive
        // after this read returns true and before the thread is published, take the session
        // out of the map, and leave startIdle() to re-arm the adapter. The thread is created
        // unstarted so stopIdle can join it; the start is refused if we are no longer the
        // registrant; the adapter refuses to arm if stopIdle already ran.
        String home = profile.getRepositoryId();
        boolean stillThere;
        try {
            stillThere = home != null && profileService != null
                    && profileService.getForRepository(profileId, home) != null;
        } catch (RuntimeException couldNotAsk) {
            retireSession(profileId, session);
            return "whether import profile " + profileId + " still exists could not be"
                    + " established; IDLE not started: " + couldNotAsk.getMessage();
        }
        if (!stillThere) {
            retireSession(profileId, session);
            return "import profile " + profileId + " no longer has a row in repository "
                    + home + "; IDLE not started";
        }

        Thread idle = Thread.ofVirtual().name("imap-idle-" + profileId).unstarted(() -> {
            try {
                if (idleSessions.get(profileId) != session) {
                    return;
                }
                imap.connect();
                if (idleSessions.get(profileId) != session) {
                    return;
                }
                imap.startIdle(mailbox, msg -> {
                    try {
                        // Re-read AND re-authorize on every message. The start-time
                        // snapshot used to be reused: a later folder change or
                        // delegation revoke still passed, while executeMailImport
                        // resolved the live row. A review named the split.
                        LiveLoad now = loadLiveConfig(profileId,
                                session.repositoryId(), connector.getConnectorId(), mailbox,
                                session.startedDelegated(), session.connectionIdentity());
                        if (!now.ok()) {
                            // A refusal that could not ASK does not tear the session down. The
                            // re-read is an authorisation check, so this message is NOT
                            // captured either way — but stopping IDLE is permanent here:
                            // startIdle has exactly one caller, the admin endpoint, and
                            // nothing re-arms it. One CouchDB blip while a mail arrived
                            // silently ended capture for that mailbox until a human noticed,
                            // and each of these refusals is a full nemaki_conf walk run per
                            // message. A review traced it. A SETTLED refusal ("no longer
                            // delegated", "not found") still stops: that answer will not
                            // change on the next message.
                            if (couldNotAsk(now.refusal())) {
                                logger.warn("IDLE: skipping a message on profile {} — the"
                                        + " authorisation could not be re-checked, and IDLE is"
                                        + " left running so the next message re-asks: {}",
                                        profileId, now.refusal());
                                return;
                            }
                            logger.warn("IDLE: stopping profile {}: {}", profileId, now.refusal());
                            imap.stopIdle();
                            return;
                        }
                        CallContext idleCtx = null;
                        if (now.profile().isDelegated()) {
                            if (schedulerService == null) {
                                logger.error("IDLE: delegated profile {} but scheduler not wired; stopping", profileId);
                                imap.stopIdle();
                                return;
                            }
                            IngestSchedulerService.DelegatedAuthorization auth =
                                    schedulerService.authorizeDelegatedFetch(
                                            now.profile(), now.connector());
                            if (!auth.isAllowed()) {
                                logger.warn("IDLE: delegated authorization revoked for profile {} ({}); stopping session",
                                        profileId, auth.getDenialReason());
                                imap.stopIdle();
                                return;
                            }
                            idleCtx = auth.getCallContext();
                        }
                        java.io.InputStream eml = imap.fetchMessage(mailbox, msg.uid());
                        ExternalIngestRequest req = new ExternalIngestRequest();
                        req.setProfileId(profileId);
                        req.setConnectorId(now.connector().getConnectorId());
                        req.setRepositoryId(now.profile().getRepositoryId());
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
                        canonicalImportService.executeMailImport(idleCtx, req);
                        logger.info("IDLE: imported message {} from {}", msg.stableKey(), mailbox);
                    } catch (Exception e) {
                        logger.error("IDLE: failed to import message {}: {}", msg.uid(), e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.error("IDLE monitoring failed for {}: {}", profileId, e.getMessage());
            } finally {
                imap.disconnect();
                // remove(key, value), not remove(key): a stop can time out waiting for this
                // thread (the adapter gives up after 10s while an IDLE loop may still be in a
                // backoff sleep) and a replacement session can already be registered. Removing
                // by key alone erased that replacement — it stayed live, invisible to
                // getIdleProfiles(), and unstoppable through stopIdle().
                retireSession(profileId, session);
            }
        });

        imap.setIdleThread(idle);
        if (idleSessions.get(profileId) != session) {
            // DELETE already removed us. Starting the thread would reconnect an adapter
            // that stopIdle has already disarmed, and the session would be absent from
            // getIdleProfiles() — the unstoppable capture a review traced.
            return "import profile " + profileId + " was stopped before IDLE started";
        }
        idle.start();
        logger.info("IMAP IDLE monitoring started for profile {}", profileId);
        return null;
    }

    /**
     * Claim {@code profileId} for {@code session}. False when another session already holds
     * it — the ONLY admission test, because a containsKey-then-put pair lets two callers both
     * pass and the second overwrite a live adapter nobody can then reach.
     */
    boolean registerSession(String profileId, IdleSession session) {
        return idleSessions.putIfAbsent(profileId, session) == null;
    }

    /**
     * Give up {@code profileId}, but only if {@code session} is still the one registered.
     * A stop can time out waiting for a thread that is mid-backoff and a replacement session
     * can already hold the key; removing by key alone erased that replacement, leaving it
     * live, absent from {@link #getIdleProfiles()} and unreachable by {@link #stopIdle}.
     */
    void retireSession(String profileId, IdleSession session) {
        idleSessions.remove(profileId, session);
    }

    /** Stop IDLE monitoring for a specific profile. */
    public String stopIdle(String profileId) {
        IdleSession session = idleSessions.remove(profileId);
        if (session == null) return "No IDLE session running for profile: " + profileId;
        ImapConnectorAdapter imap = session.adapter();
        imap.stopIdle();
        imap.disconnect();
        logger.info("IMAP IDLE monitoring stopped for profile {}", profileId);
        return null;
    }

    /**
     * The repository a live IDLE session imports into, or {@code null} when this profileId
     * has no session OR the session's captured row named no repository. The two are not the
     * same thing, and this method cannot tell them apart — pair it with
     * {@link #getIdleProfiles()}, which answers the first question on its own.
     */
    public String getIdleRepository(String profileId) {
        IdleSession session = idleSessions.get(profileId);
        return session == null ? null : session.repositoryId();
    }

    /** Get the list of profiles currently running IMAP IDLE. */
    public List<String> getIdleProfiles() {
        return List.copyOf(idleSessions.keySet());
    }

    /**
     * The current unique owned profile and its IMAP connector. A refusal means
     * IDLE must not start — or, once running, must stop. The start-time snapshot
     * is not an authorisation to keep ingesting after a later edit.
     */
    record LiveLoad(ImportProfileDefinition profile, ConnectorDefinition connector, String refusal) {
        boolean ok() {
            return refusal == null && profile != null && connector != null;
        }
    }

    /**
     * True when a refusal means "this node could not ask", as opposed to a settled answer.
     *
     * <p>Only the first kind may leave IDLE running: the next message re-asks and the session
     * survives a blip. A settled answer — the profile is gone, the delegation was revoked, the
     * connector is not IMAP — will say the same thing on every message, so it tears down.
     *
     * <p>"could not be read as a profile/connector" is deliberately EXCLUDED: that is a
     * corrupt stored row, which is standing, not transient. A review found the same phrase
     * being read as retryable elsewhere.
     */
    private static boolean couldNotAsk(String refusal) {
        if (refusal == null) return false;
        if (refusal.contains("could not be read as a profile")
                || refusal.contains("could not be read as a connector")) {
            return false;
        }
        return refusal.contains("retry shortly")
                || refusal.contains("could not be established")
                || refusal.contains("could not be read")
                || refusal.contains("could not be looked up");
    }

    /**
     * Package-visible so the registry tests can lock the per-message re-read
     * without a real IMAP session.
     */
    LiveLoad loadLiveConfig(String profileId, String startedRepositoryId,
            String startedConnectorId, String startedMailbox) {
        return loadLiveConfig(profileId, startedRepositoryId, startedConnectorId,
                startedMailbox, null);
    }

    LiveLoad loadLiveConfig(String profileId, String startedRepositoryId,
            String startedConnectorId, String startedMailbox, Boolean startedDelegated) {
        return loadLiveConfig(profileId, startedRepositoryId, startedConnectorId,
                startedMailbox, startedDelegated, null);
    }

    LiveLoad loadLiveConfig(String profileId, String startedRepositoryId,
            String startedConnectorId, String startedMailbox, Boolean startedDelegated,
            ConnectionIdentity startedConnectionIdentity) {
        if (profileService == null) {
            // "Not wired", not "not found". An absent collaborator is a question this node
            // could not ASK; answering it as the store's "no such profile" is the same
            // substitution this batch closed at the webhook receiver's profile lookup and at
            // the re-import relationship lookup. A review found the third one here.
            return new LiveLoad(null, null, "import profile " + profileId + " could not be"
                    + " looked up: no profile service is wired on this node; IDLE not started");
        }
        ImportProfileDefinition current;
        try {
            current = profileService.getOwnedRowIndexFree(profileId);
        } catch (ImportProfileDefinitionServiceImpl.ProfileHasTwinRowsException pair) {
            return new LiveLoad(null, null, pair.getMessage());
        } catch (RuntimeException couldNotAsk) {
            return new LiveLoad(null, null, "whether import profile " + profileId
                    + " exists could not be established; IDLE not started: "
                    + couldNotAsk.getMessage());
        }
        if (current == null) {
            return new LiveLoad(null, null, "Profile not found: " + profileId);
        }
        if (!current.isEnabled()) {
            return new LiveLoad(null, null, "Import profile is disabled: " + profileId);
        }
        if (Boolean.TRUE.equals(startedDelegated) && !current.isDelegated()) {
            return new LiveLoad(null, null, "import profile " + profileId
                    + " is no longer delegated; IDLE stopping");
        }
        if (startedRepositoryId != null
                && !startedRepositoryId.equals(current.getRepositoryId())) {
            return new LiveLoad(null, null, "import profile " + profileId
                    + " now belongs to a different repository; IDLE stopping");
        }
        String mailbox = current.getSchedulerParams() != null
                ? current.getSchedulerParams().getOrDefault("mailbox", "INBOX") : "INBOX";
        if (startedMailbox != null && !startedMailbox.equals(mailbox)) {
            return new LiveLoad(null, null, "import profile " + profileId
                    + " mailbox changed; IDLE stopping");
        }
        ConnectorDefinition conn;
        try {
            conn = resolveConnector(current);
        } catch (ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException e) {
            // The refusal's own words, not a fixed sentence: this arm now also carries "the
            // connector service is not wired on this node", for which "exists but could not
            // be read" would assert an existence nothing established.
            return new LiveLoad(null, null, "connector " + current.getDefaultConnectorId()
                    + " could not be read; retry shortly: " + e.getMessage());
        }
        String askedConnectorId = current.getDefaultConnectorId();
        if (conn != null && askedConnectorId != null
                && !askedConnectorId.equals(conn.getConnectorId())) {
            return new LiveLoad(null, null, "connector " + askedConnectorId
                    + " exists but could not be read as that connector; retry shortly");
        }
        if (conn == null) {
            String connId = current.getDefaultConnectorId();
            if (connId != null && connectorService != null) {
                try {
                    if (connectorService.existsIndexFree(connId)) {
                        return new LiveLoad(null, null, "connector " + connId
                                + " exists but could not be read; retry shortly");
                    }
                } catch (RuntimeException couldNotAsk) {
                    return new LiveLoad(null, null, "whether connector " + connId
                            + " exists could not be established; IDLE not started: "
                            + couldNotAsk.getMessage());
                }
            }
            return new LiveLoad(null, null, "No connector for profile: " + profileId);
        }
        if (startedConnectorId != null && !startedConnectorId.equals(conn.getConnectorId())) {
            return new LiveLoad(null, null, "import profile " + profileId
                    + " now uses a different connector; IDLE stopping");
        }
        if (startedConnectionIdentity != null) {
            // Same connectorId can still point at a different host, tenant, or secret.
            // Auth then used the live row while fetchMessage kept the start-time socket.
            if (fetchSupport == null) {
                return new LiveLoad(null, null, "import profile " + profileId
                        + " connector connection could not be re-checked; IDLE stopping");
            }
            String livePassword = fetchSupport.resolvePassword(conn);
            if (!startedConnectionIdentity.equals(connectionIdentity(conn, livePassword))) {
                return new LiveLoad(null, null, "import profile " + profileId
                        + " connector connection changed; IDLE stopping");
            }
        }
        if (connectorService != null) {
            try {
                String countedId = askedConnectorId != null ? askedConnectorId : conn.getConnectorId();
                int seen = connectorService.countIndexFree(countedId);
                if (seen > 1) {
                    return new LiveLoad(null, null, "connector " + countedId
                            + " has more than one definition row");
                }
                if (seen < 1) {
                    return new LiveLoad(null, null, "connector " + countedId
                            + " exists but could not be read; retry shortly");
                }
            } catch (RuntimeException couldNotAsk) {
                return new LiveLoad(null, null, "whether connector "
                        + (askedConnectorId != null ? askedConnectorId : conn.getConnectorId())
                        + " is unique could not be established; IDLE not started: "
                        + couldNotAsk.getMessage());
            }
        }
        if (!conn.isEnabled()) {
            return new LiveLoad(null, null, "Connector is disabled: " + conn.getConnectorId());
        }
        if (!current.isConnectorAllowed(conn.getConnectorId())) {
            return new LiveLoad(null, null, "Connector '" + conn.getConnectorId()
                    + "' is not allowed by profile '" + profileId + "'");
        }
        if (!current.isArchetypeAllowed(conn.getSourceArchetype())) {
            return new LiveLoad(null, null, "Archetype " + conn.getSourceArchetype()
                    + " is not allowed by profile '" + profileId + "'");
        }
        if (!"imap".equals(conn.getSourceSystem())) {
            return new LiveLoad(null, null,
                    "IDLE is only supported for IMAP connectors (system="
                            + conn.getSourceSystem() + ")");
        }
        return new LiveLoad(current, conn, null);
    }

    /**
     * The IMAP socket's inputs, field by field. A newline join of the five
     * values is not an identity: tenantId {@code a} plus authType {@code b\\nc}
     * and tenantId {@code a\\nb} plus authType {@code c} produce the same
     * string, so a later edit would keep the start-time socket.
     */
    record ConnectionIdentity(String endpoint, String tenantId, String authType,
            String credentialRef, String password) {
    }

    static ConnectionIdentity connectionIdentity(ConnectorDefinition connector, String password) {
        return new ConnectionIdentity(
                connector == null ? null : connector.getEndpoint(),
                connector == null ? null : connector.getTenantId(),
                connector == null ? null : connector.getAuthType(),
                connector == null ? null : connector.getCredentialRef(),
                password);
    }

    /** Resolve the connector for a profile. */
    private ConnectorDefinition resolveConnector(ImportProfileDefinition profile) {
        if (connectorService == null) {
            // Answering null here reaches "No connector for profile", which the endpoint
            // classifies 400 — the request blamed for the deployment. The profile-service
            // arm of this same class was corrected for that shape two rounds ago; this one
            // was left behind. A review found it.
            throw new ConnectorDefinitionServiceImpl.ConnectorIndexNotReadyException(
                    "the connector service is not wired on this node, so the profile's"
                            + " connector could not be read; retry shortly against a node"
                            + " that runs it");
        }
        String connId = profile.getDefaultConnectorId();
        if (connId != null) return connectorService.get(connId);
        return null;
    }
}
