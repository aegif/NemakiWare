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

    /**
     * Messages this monitor did not capture AND could not record, per profile.
     *
     * <p>In memory on purpose: the condition that produces one is the configuration database
     * being unreachable, so there is nowhere durable to put it. Counted rather than dropped,
     * and surfaced on the IDLE status endpoint, because the alternative the previous round
     * chose — stopping the session — permanently ended capture for a transient fault, with
     * nothing to re-arm it. Lost on restart; the UID checkpoint has not moved, so a re-fetch
     * of the mailbox is the recovery either way.
     */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> undurableMisses =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** How many messages this profile missed without being able to record the miss. */
    public int undurableMissCount(String profileId) {
        java.util.concurrent.atomic.AtomicInteger n = undurableMisses.get(profileId);
        return n == null ? 0 : n.get();
    }

    /** The same counts for every profile that has one, for the IDLE status endpoint. */
    public Map<String, Integer> undurableMisses() {
        Map<String, Integer> out = new LinkedHashMap<>();
        undurableMisses.forEach((profileId, n) -> {
            if (n.get() > 0) out.put(profileId, n.get());
        });
        return out;
    }

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
     * How the adapter is built. A field so a test can drive the per-message listener without a
     * mail server; until it existed nothing inside that listener had ever been measured.
     */
    java.util.function.BiFunction<ConnectorDefinition, String, ImapConnectorAdapter> adapterFactory =
            ImapConnectorAdapter::new;

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
                // The per-message arm 170 lines below was given this distinction a round ago;
                // this one still answered every reason the same way, and the endpoint maps
                // "Delegated authorization denied" to 403 BEFORE it tests "could not ask" — so
                // a CouchDB blip in the creator lookup told an administrator the creator is not
                // authorised, and they went off revoking and regranting. A review found the
                // startIdle arm ignoring the very reason this branch added to carry it.
                if (denialCouldNotAsk(auth.getDenialReason())) {
                    return "delegated IMAP IDLE could not be authorised for profile "
                            + profileId + ": the authorisation could not be established ("
                            + auth.getDenialReason() + "); retry shortly";
                }
                return "Delegated authorization denied for profile " + profileId
                        + " (" + auth.getDenialReason() + ")";
            }
        }

        // The STARTUP read, not just the per-message one. "No password for IMAP connector" is
        // classified 400 by the endpoint — the request blamed for a configuration database that
        // did not answer — while the documented answer for that state is 503. A review found
        // startup still on the reading that cannot refuse.
        String password;
        try {
            password = fetchSupport.resolvePasswordOrRefuse(connector);
        } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                .SettingUnreadableException couldNotAsk) {
            return couldNotAsk.getMessage();
        }
        if (password == null) return "No password for IMAP connector";

        String mailbox = profile.getSchedulerParams() != null
                ? profile.getSchedulerParams().getOrDefault("mailbox", "INBOX") : "INBOX";

        ImapConnectorAdapter imap = adapterFactory.apply(connector, password);
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
                                // The message is NOT captured — the re-read is an
                                // authorisation check and it did not pass. But it must not be
                                // dropped in silence either: IMAP does not re-deliver the
                                // "added" event, so the round that stopped tearing the session
                                // down traded a permanent stop for a permanently missing mail.
                                // A review caught the trade. Record it where every other lost
                                // source item is recorded, so it can be replayed.
                                // The record is claimed AFTER it is written, not before. The
                                // usual trigger for this branch is nemaki_conf being
                                // unreachable, and that is the database the row goes into — so
                                // the first version announced a dead-letter that the same
                                // outage had just prevented. A review found the claim preceding
                                // the fact.
                                boolean recorded = false;
                                if (fetchSupport != null) {
                                    ExternalIngestRequest missed = new ExternalIngestRequest();
                                    missed.setProfileId(profileId);
                                    missed.setConnectorId(connector.getConnectorId());
                                    missed.setRepositoryId(session.repositoryId());
                                    missed.setSourceObjectId(msg.stableKey());
                                    missed.setSourceObjectType("message");
                                    missed.setExecutionMode("idle");
                                    Map<String, Object> missedMeta = new LinkedHashMap<>();
                                    missedMeta.put("mailboxId", mailbox);
                                    missedMeta.put("messageStableId", msg.stableKey());
                                    missed.setMetadata(missedMeta);
                                    recorded = fetchSupport.saveSourceNeverReadToDlq(missed,
                                            "[transient] the message was not captured because"
                                            + " the authorisation could not be re-checked: "
                                            + now.refusal());
                                }
                                if (recorded) {
                                    logger.warn("IDLE: did not capture a message on profile {}"
                                            + " — the authorisation could not be re-checked."
                                            + " IDLE is left running so the next message"
                                            + " re-asks, and this message is recorded in the"
                                            + " dead-letter queue (it carries no .eml, so it is"
                                            + " a RECORD of the miss, not a replayable item —"
                                            + " recover by re-fetching the mailbox): {}",
                                            profileId, now.refusal());
                                    return;
                                }
                                // Nothing could be recorded. The previous round stopped IDLE
                                // here, on the ground that a stopped session is visible while
                                // a skipped message is not — but nothing re-arms IDLE except
                                // the admin endpoint, so a config blip permanently ended
                                // capture. That is the same over-throw a review raised two
                                // rounds earlier for the opposite behaviour. Removing the
                                // FALSE claim of a durable record does not require disabling
                                // the session.
                                //
                                // So the session stays and the miss is counted where the
                                // status endpoint can show it: undurable, in memory, lost if
                                // this JVM restarts — which is exactly what it is.
                                int missed = undurableMisses
                                        .computeIfAbsent(profileId, k -> new java.util.concurrent.atomic.AtomicInteger())
                                        .incrementAndGet();
                                logger.error("IDLE: profile {} did not capture a message and"
                                        + " could NOT record the miss ({} undurable misses this"
                                        + " session). IDLE stays up; the UID checkpoint has not"
                                        + " moved, so re-fetch the mailbox to recover: {}",
                                        profileId, missed, now.refusal());
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
                                // "Revoked" is a settled answer. CREATOR_LOOKUP_FAILED and
                                // SERVICES_UNAVAILABLE are not — they mean this node could not
                                // ASK — and this arm printed both as a revocation and then
                                // tore the session down for good, with the message dropped and
                                // not even counted. The arm forty lines above already treats
                                // that distinction correctly; a review found this one ignoring
                                // the very DenialReason this branch added to carry it.
                                DenialReason why = auth.getDenialReason();
                                if (denialCouldNotAsk(why)) {
                                    boolean kept = false;
                                    if (fetchSupport != null) {
                                        ExternalIngestRequest missedItem = new ExternalIngestRequest();
                                        missedItem.setProfileId(profileId);
                                        missedItem.setConnectorId(connector.getConnectorId());
                                        missedItem.setRepositoryId(session.repositoryId());
                                        missedItem.setSourceObjectId(msg.stableKey());
                                        missedItem.setSourceObjectType("message");
                                        missedItem.setExecutionMode("idle");
                                        Map<String, Object> meta = new LinkedHashMap<>();
                                        meta.put("mailboxId", mailbox);
                                        meta.put("messageStableId", msg.stableKey());
                                        missedItem.setMetadata(meta);
                                        kept = fetchSupport.saveSourceNeverReadToDlq(missedItem,
                                                "[transient] the message was not captured because"
                                                + " the delegated authorisation could not be"
                                                + " established (" + why + ")");
                                    }
                                    if (!kept) {
                                        undurableMisses.computeIfAbsent(profileId,
                                                k -> new java.util.concurrent.atomic.AtomicInteger())
                                                .incrementAndGet();
                                    }
                                    logger.error("IDLE: profile {} did not capture a message —"
                                            + " the delegated authorisation could not be"
                                            + " ESTABLISHED ({}), which is not a revocation."
                                            + " IDLE stays up; the miss {} recorded",
                                            profileId, why, kept ? "was" : "could NOT be");
                                    return;
                                }
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
                        ExternalIngestResult outcome =
                                canonicalImportService.executeMailImport(idleCtx, req);
                        if (outcome.skipped()) {
                            logger.info("IDLE: message {} from {} was skipped: {}",
                                    msg.stableKey(), mailbox, outcome.skipReason());
                        } else if (outcome.isSuccess()) {
                            logger.info("IDLE: imported message {} from {}", msg.stableKey(), mailbox);
                        } else {
                            recordRefusedIdleImport(profileId, mailbox, msg, req, outcome);
                        }
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
     * The import ANSWERED that it did not import this message.
     *
     * <p>The line this replaces logged "imported" regardless of the result and dropped it, so
     * the refusals that arrive as results rather than exceptions — a target folder that could
     * not be read, a profile the index could not show — reached nothing: not the dead-letter
     * queue, not the miss counter, and IMAP does not re-deliver the event. The two arms above
     * record the same class for a refused authorisation; a review found this one asserting
     * success. An exception inside the import is dead-lettered by the import itself before it
     * becomes a result, so such a row is written twice and its failure count is one high —
     * a metadata-only row is a RECORD of the miss either way, not a replayable item.
     */
    private void recordRefusedIdleImport(String profileId, String mailbox,
            ImapConnectorAdapter.MessageSummary msg, ExternalIngestRequest req,
            ExternalIngestResult outcome) {
        String why = outcome.errors() == null || outcome.errors().isEmpty()
                ? "no reason given" : String.join("; ", outcome.errors());
        boolean missRecorded = fetchSupport != null
                && fetchSupport.saveSourceReadToDlq(req, "IDLE: the message was not imported: " + why);
        if (!missRecorded) {
            undurableMisses.computeIfAbsent(profileId,
                    k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        }
        logger.error("IDLE: message {} from {} was NOT imported ({}); the miss {} recorded."
                + " The UID checkpoint has not moved, so re-fetch the mailbox to recover",
                msg.stableKey(), mailbox, why, missRecorded ? "was" : "could NOT be");
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
    static boolean refusalCouldNotAsk(String refusal) {
        return couldNotAsk(refusal);
    }

    /**
     * True when a delegated denial means "this node could not ASK", not "the answer is no".
     *
     * <p>The per-message arm printed every denial as a revocation and stopped the session for
     * good. {@code CREATOR_LOOKUP_FAILED} exists in this branch precisely to carry the
     * distinction, and that consumer ignored it. Package-visible so the predicate is locked;
     * the arm that uses it needs a live IMAP session and is recorded as unmeasured.
     */
    static boolean denialCouldNotAsk(DenialReason why) {
        return IngestSchedulerService.denialCouldNotAsk(why);
    }

    private static boolean couldNotAsk(String refusal) {
        if (refusal == null) return false;
        // Matched on the common prefix of all three phrasings: "as a profile", "as a
        // connector", and "as THAT connector" — the deterministic-id mismatch, which the first
        // version's two-item list missed. That omission left the session NEVER tearing down:
        // every arriving message took the transient arm, captured nothing, and wrote a DLQ row,
        // for ever, with no signal that the condition was standing. A review found it.
        if (refusal.contains("could not be read as ")) {
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
                    + " exists but could not be read as that connector");
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
            String livePassword;
            try {
                // resolvePassword answers null for "no credential", "no stored value" and
                // "the store did not answer" alike. The third made the comparison below fail
                // and this method report that the CONNECTION CHANGED — a fact about the
                // connector that nothing established — after which the caller tore the
                // session down for good. A review traced it.
                livePassword = fetchSupport.resolvePasswordOrRefuse(conn);
            } catch (jp.aegif.nemaki.rest.controller.IntegrationSettingsService
                    .SettingUnreadableException couldNotAsk) {
                return new LiveLoad(null, null, couldNotAsk.getMessage());
            }
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
