package jp.aegif.nemaki.rest.ingest;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import jp.aegif.nemaki.util.PropertyManager;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 300; // 5 minutes
    private static final int DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final int DEFAULT_FETCH_TIMEOUT_MINUTES = 30;

    /** Resolved at startPolling() from PropertyManager. */
    private long pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS;
    private int circuitBreakerThreshold = DEFAULT_CIRCUIT_BREAKER_THRESHOLD;
    private int fetchTimeoutMinutes = DEFAULT_FETCH_TIMEOUT_MINUTES;
    /** Per-connector consecutive failure counter. Reset on success. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> consecutiveFailures =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Tracks delegated profileIds that we've already emitted a "skipping"
     * WARN for, so subsequent poll cycles don't repeat the same WARN at
     * every poll interval (default 5min). Without this, a single broken
     * record would pollute the log indefinitely. Subsequent skips drop to
     * DEBUG. Cleared if the profile flips back to non-delegated (rare —
     * normally just admin-edit) so a re-set would re-WARN once.
     */
    private final java.util.Set<String> warnedDelegatedSchedulerProfiles =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * RC5 (v2 §12.1): per-profile consecutive CREATOR_USER_INACTIVE
     * counter. When {@code autoDisableInactiveOwners=true} and this
     * exceeds the configured threshold, the profile is auto-disabled
     * (enabled=false) and the counter cleared.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> inactiveCreatorStreak =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * RC5 (v2 §12.1): authorization service for per-tick ACL re-eval
     * on delegated scheduled profiles. Same instance the runtime gate
     * uses, so the security contract is identical between manual and
     * scheduled paths.
     */
    private IngestAuthorizationService ingestAuthorizationService;

    /**
     * RC5 (v2 §12.1): factory for synthesising a CallContext from a
     * profile's {@code createdByUserId}. Null indicates no delegated-
     * scheduler support (legacy wiring, tests).
     */
    private DelegatedCallContextFactory delegatedCallContextFactory;

    public void setIngestAuthorizationService(IngestAuthorizationService svc) {
        this.ingestAuthorizationService = svc;
    }

    public void setDelegatedCallContextFactory(DelegatedCallContextFactory factory) {
        this.delegatedCallContextFactory = factory;
    }

    /**
     * RC5 (v2 §12.1): audit sink for scheduled delegated denials. Same
     * {@code AuditLogger} the manual path uses, so SOC queries that
     * filter on {@code EXTERNAL_INGEST_FAILED} catch both flows.
     */
    private jp.aegif.nemaki.audit.AuditLogger auditLogger;

    public void setAuditLogger(jp.aegif.nemaki.audit.AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    private volatile java.util.concurrent.ScheduledExecutorService scheduler;

    /**
     * Start the periodic polling scheduler. Called after all beans are initialized.
     */
    private volatile java.util.concurrent.ScheduledExecutorService stuckJobDetector;

    public void startPolling() {
        if (scheduler != null) return;
        // Resolve configurable settings from PropertyManager
        if (propertyManager != null) {
            pollIntervalSeconds = readLong("ingest.scheduler.pollIntervalSeconds", DEFAULT_POLL_INTERVAL_SECONDS);
            circuitBreakerThreshold = (int) readLong("ingest.scheduler.circuitBreakerThreshold", DEFAULT_CIRCUIT_BREAKER_THRESHOLD);
            fetchTimeoutMinutes = (int) readLong("ingest.scheduler.fetchTimeoutMinutes", DEFAULT_FETCH_TIMEOUT_MINUTES);
        }
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IngestScheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollScheduledProfiles,
                pollIntervalSeconds, pollIntervalSeconds, java.util.concurrent.TimeUnit.SECONDS);
        logger.info("Ingest scheduler started (interval={}s, leaderElection={})",
                pollIntervalSeconds,
                (leaderElection != null && leaderElection.isEnabled()) ? "enabled" : "disabled");

        // Separate thread for stuck job detection — cannot share the main
        // scheduler thread because if executeFetch() hangs, the scheduler
        // thread is blocked and detectStuckJobs() would never run.
        stuckJobDetector = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IngestStuckDetector");
            t.setDaemon(true);
            return t;
        });
        stuckJobDetector.scheduleWithFixedDelay(() -> {
            if (ingestJobService != null) {
                try { ingestJobService.detectStuckJobs(); }
                catch (Exception e) { logger.debug("Stuck job detection failed: {}", e.getMessage()); }
            }
        }, 5 * 60, 5 * 60, java.util.concurrent.TimeUnit.SECONDS); // every 5 minutes

        // Independent idempotency key purge — runs even when no profiles are scheduled
        stuckJobDetector.scheduleWithFixedDelay(() -> {
            try { purgeExpiredIdempotencyKeysIfDue(); }
            catch (Exception e) { logger.debug("Idempotency purge failed: {}", e.getMessage()); }
        }, 60 * 60, 60 * 60, java.util.concurrent.TimeUnit.SECONDS); // every hour
    }

    public void stopPolling() {
        if (stuckJobDetector != null) {
            stuckJobDetector.shutdown();
            try {
                if (!stuckJobDetector.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    stuckJobDetector.shutdownNow();
                }
            } catch (InterruptedException e) {
                stuckJobDetector.shutdownNow();
                Thread.currentThread().interrupt();
            }
            stuckJobDetector = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.warn("Ingest scheduler did not terminate within 15s, forcing shutdown");
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
            logger.info("Ingest scheduler stopped");
        }
    }

    // ── IMAP IDLE monitoring (delegated to ImapIdleMonitor) ──────

    private jp.aegif.nemaki.rest.ingest.mail.ImapIdleMonitor imapIdleMonitor;

    public void setImapIdleMonitor(jp.aegif.nemaki.rest.ingest.mail.ImapIdleMonitor imapIdleMonitor) {
        this.imapIdleMonitor = imapIdleMonitor;
    }

    public String startIdle(String profileId) {
        return imapIdleMonitor != null ? imapIdleMonitor.startIdle(profileId) : "ImapIdleMonitor not available";
    }

    public String stopIdle(String profileId) {
        return imapIdleMonitor != null ? imapIdleMonitor.stopIdle(profileId) : "ImapIdleMonitor not available";
    }

    public List<String> getIdleProfiles() {
        return imapIdleMonitor != null ? imapIdleMonitor.getIdleProfiles() : List.of();
    }

    // ──────────────────────────────────────────────────────────────────
    // RC5 (v2 §12.1) — Scheduled delegated profile helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Run the per-tick gate for a delegated profile. Returns a
     * synthesised {@link CallContext} for {@code profile.createdByUserId}
     * if all checks pass, or {@code null} if the tick should be skipped.
     * In the null case the appropriate WARN + audit entry has already
     * been emitted before returning.
     *
     * <p>Order of checks (every failure is fail-shut):
     * <ol>
     *   <li>Operator opt-in: {@code nemakiware.ingest.delegated.schedulerEnabled=true}
     *       must be set. Default false → emit
     *       {@code DELEGATED_SCHEDULING_DISABLED}, fall back to the
     *       legacy WARN-once behaviour for visibility.</li>
     *   <li>Required wiring: {@link DelegatedCallContextFactory} and
     *       {@link IngestAuthorizationService} must be present.
     *       Missing → {@code SERVICES_UNAVAILABLE}.</li>
     *   <li>{@code createdByUserId} must be set on the profile (otherwise
     *       there's no identity to evaluate against — log + skip).</li>
     *   <li>UserItem must exist and be active. Missing →
     *       {@code CREATOR_USER_INACTIVE}. Also drives the
     *       auto-disable streak counter.</li>
     *   <li>Creator must still hold {@code cmis:all} on the target
     *       folder. Missing → {@code CREATOR_CMIS_ALL_LOST}.</li>
     * </ol>
     */
    private CallContext prepareDelegatedTick(ImportProfileDefinition profile) {
        String pid = profile.getProfileId();

        // 1. Operator opt-in
        boolean enabled = readBool(
                "nemakiware.ingest.delegated.schedulerEnabled", false);
        if (!enabled) {
            // Maintain the WARN-once-per-JVM behaviour from RC4 so
            // existing operators upgrading from RC4 with property OFF
            // see the same log signature they're used to.
            if (warnedDelegatedSchedulerProfiles.add(pid)) {
                logger.warn("Skipping delegated profile '{}' from scheduler — "
                        + "nemakiware.ingest.delegated.schedulerEnabled=false (default). "
                        + "Set it to true to enable v2 scheduled-delegated behaviour. "
                        + "This WARN fires once per profile per JVM lifetime.", pid);
            } else if (logger.isDebugEnabled()) {
                logger.debug("Scheduler skipping delegated profile '{}' (already warned, property off)", pid);
            }
            auditScheduledDelegatedDenial(profile, null,
                    DenialReason.DELEGATED_SCHEDULING_DISABLED,
                    "Delegated scheduling disabled by property");
            return null;
        }

        // 2. Required wiring
        if (delegatedCallContextFactory == null || ingestAuthorizationService == null) {
            logger.warn("Skipping delegated profile '{}' — DelegatedCallContextFactory "
                    + "or IngestAuthorizationService not wired", pid);
            auditScheduledDelegatedDenial(profile, null,
                    DenialReason.SERVICES_UNAVAILABLE,
                    "DelegatedCallContextFactory or IngestAuthorizationService not wired");
            return null;
        }

        // 3. createdByUserId required
        String creatorUser = profile.getCreatedByUserId();
        if (creatorUser == null || creatorUser.isBlank()) {
            logger.warn("Skipping delegated profile '{}' — createdByUserId is missing", pid);
            auditScheduledDelegatedDenial(profile, null,
                    DenialReason.CREATOR_USER_INACTIVE,
                    "Profile has no createdByUserId (legacy admin-created record?)");
            return null;
        }

        // 4. UserItem must exist (== "active" in NemakiWare's model)
        CallContext ctx = delegatedCallContextFactory.buildOrNull(
                profile.getRepositoryId(), creatorUser);
        if (ctx == null) {
            handleInactiveCreator(profile, creatorUser);
            return null;
        }
        // Reset streak on successful active-user resolution.
        inactiveCreatorStreak.remove(pid);

        // 5. cmis:all re-eval
        String folderId = ingestAuthorizationService.resolveFolderId(
                profile.getRepositoryId(),
                profile.getTargetFolderId(), profile.getTargetFolderPath());
        if (folderId == null) {
            auditScheduledDelegatedDenial(profile, null,
                    DenialReason.TARGET_FOLDER_UNRESOLVABLE,
                    "Profile's target folder no longer resolvable");
            return null;
        }
        if (!ingestAuthorizationService.canManageProfileForFolderAsUser(
                creatorUser, profile.getRepositoryId(), folderId)) {
            auditScheduledDelegatedDenial(profile, null,
                    DenialReason.CREATOR_CMIS_ALL_LOST,
                    "Creator " + creatorUser + " no longer holds cmis:all on target folder");
            return null;
        }
        return ctx;
    }

    /**
     * Increment the inactive-creator streak counter and, if
     * {@code autoDisableInactiveOwners=true} is set AND the streak
     * exceeds the configured threshold, mark the profile
     * {@code enabled=false} so the tick stops repeating. The audit
     * entry includes a {@code creatorActive=false} flag and the
     * current streak count for operator review.
     */
    private void handleInactiveCreator(ImportProfileDefinition profile, String creatorUser) {
        String pid = profile.getProfileId();
        int streak = inactiveCreatorStreak.merge(pid, 1, Integer::sum);
        auditScheduledDelegatedDenial(profile, null,
                DenialReason.CREATOR_USER_INACTIVE,
                "Creator " + creatorUser + " is no longer an active UserItem (streak="
                        + streak + ")");

        boolean autoDisable = readBool(
                "nemakiware.ingest.delegated.autoDisableInactiveOwners", false);
        if (!autoDisable) return;
        int threshold = (int) readLong(
                "nemakiware.ingest.delegated.inactiveOwnerFailureThreshold", 3);
        if (streak < threshold) return;

        // Auto-disable: set enabled=false + record WHY/WHEN so the admin
        // UI can distinguish this from a manual disable. Markers cleared
        // by ImportProfileDefinitionController on the admin re-enable.
        try {
            profile.setEnabled(false);
            profile.setLastAutoDisabledAt(java.time.Instant.now().toString());
            profile.setLastAutoDisabledReason(
                    DenialReason.CREATOR_USER_INACTIVE.name()
                    + ": creator '" + creatorUser + "' inactive for "
                    + streak + " consecutive ticks");
            if (profileService != null) {
                profileService.update(profile);
            }
            inactiveCreatorStreak.remove(pid);
            logger.warn("Auto-disabled delegated profile '{}' after {} consecutive "
                    + "CREATOR_USER_INACTIVE failures (creator: '{}')",
                    pid, streak, creatorUser);
        } catch (Exception e) {
            logger.warn("Auto-disable failed for delegated profile '{}': {}",
                    pid, e.getMessage());
        }
    }

    /**
     * Emit an EXTERNAL_INGEST_FAILED audit entry for a delegated
     * scheduled tick that was refused before {@code executeFetch} ran.
     * Matches the shape used by {@code ExternalIngestController}'s
     * {@code auditDelegatedAttempt} so SOC queries treat the manual
     * and scheduled paths uniformly.
     */
    private void auditScheduledDelegatedDenial(ImportProfileDefinition profile,
                                               ConnectorDefinition connector,
                                               DenialReason reason, String message) {
        String actor = profile.getCreatedByUserId() != null
                ? profile.getCreatedByUserId() : "anonymous";
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("delegated", true);
        details.put("scheduled", true);
        details.put("actorUserId", actor);
        details.put("creatorUserId", actor);
        // creatorActive: true only when this is NOT an inactive-user denial
        details.put("creatorActive", reason != DenialReason.CREATOR_USER_INACTIVE);
        if (profile.getProfileId() != null) details.put("profileId", profile.getProfileId());
        if (connector != null) details.put("connectorId", connector.getConnectorId());
        if (profile.getTargetFolderId() != null) {
            details.put("targetFolderId", profile.getTargetFolderId());
        }
        details.put("denialReason", reason.name());
        // H1 (RC5.5): silent catch replaced by safeEmit (WARN on failure)
        jp.aegif.nemaki.audit.AuditEmitSupport.safeEmit(auditLogger,
                jp.aegif.nemaki.audit.AuditOperation.EXTERNAL_INGEST_FAILED,
                profile.getRepositoryId(), actor,
                profile.getProfileId() != null ? profile.getProfileId() : "",
                false, message, details);
    }

    /** Property reader for booleans. Centralises invalid-value handling. */
    private boolean readBool(String key, boolean defaultValue) {
        if (propertyManager == null) return defaultValue;
        String val = propertyManager.readValue(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }

    /** Visible for tests — driven by the scheduler at fixed delay in production. */
    void pollScheduledProfiles() {
        try {
            // Multi-replica safety: only the leader replica polls. When leader
            // election is disabled (default), isLeader returns true so the
            // existing single-replica behaviour is preserved.
            if (leaderElection != null && !leaderElection.isLeader("ingest-scheduler")) {
                logger.debug("Ingest scheduler skipped: not leader for 'ingest-scheduler'");
                return;
            }

            // Half-open circuit breakers: allow one retry per cycle for each
            // connector that was previously tripped. If the retry succeeds,
            // the counter is cleared; if it fails again, it remains open.
            consecutiveFailures.replaceAll((k, v) -> v >= circuitBreakerThreshold
                    ? circuitBreakerThreshold - 1 : v);
            // Log half-open transitions
            consecutiveFailures.forEach((k, v) -> {
                if (v == circuitBreakerThreshold - 1) {
                    logger.info("Circuit breaker HALF_OPEN for connector '{}', allowing retry", k);
                }
            });

            List<ImportProfileDefinition> profiles = getScheduledProfiles();
            if (profiles.isEmpty()) return;
            logger.debug("Polling {} scheduled profiles", profiles.size());

            for (ImportProfileDefinition profile : profiles) {
                // Re-check: profile may have been disabled since getScheduledProfiles()
                if (!profile.isEnabled() || !profile.isSchedulerEnabled()) continue;
                // RC5 (v2 §12.1): delegated profile handling.
                //
                // Before RC5: delegated profiles were unconditionally
                // skipped from the scheduler regardless of
                // schedulerEnabled, because the scheduler had no
                // CallContext to evaluate ACL against.
                //
                // RC5: if the operator has opted in via
                //   nemakiware.ingest.delegated.schedulerEnabled=true
                // the scheduler synthesises a CallContext from the
                // profile's createdByUserId and re-runs the same
                // gate the runtime ingest path uses. Per-tick — no
                // long-lived cache, so mid-day ACL / group changes
                // are picked up on the next poll.
                //
                // Default remains opt-out (property=false), so existing
                // deployments behave exactly like RC3/RC4.
                CallContext delegatedCtx = null;     // null = admin path / not applicable
                if (profile.isDelegated()) {
                    delegatedCtx = prepareDelegatedTick(profile);
                    if (delegatedCtx == null) {
                        // prepareDelegatedTick already emitted the
                        // appropriate WARN/audit + DenialReason. Skip.
                        continue;
                    }
                }
                // If a previously-delegated profile flipped back, drop our
                // WARN-once memo so a re-flip can re-WARN cleanly.
                if (!profile.isDelegated() && !warnedDelegatedSchedulerProfiles.isEmpty()) {
                    warnedDelegatedSchedulerProfiles.remove(profile.getProfileId());
                }
                ConnectorDefinition connector = resolveConnectorForProfile(profile);
                if (connector == null) continue;

                // RC5: for delegated profiles, also re-check connector
                // delegation against the creator. Catches admin-revoked
                // connector scope between ticks.
                if (delegatedCtx != null) {
                    // RC5.6 (R5): resolve target folder first so we can
                    // attribute a null result to TARGET_FOLDER_UNRESOLVABLE
                    // (folder deleted / ACL change / transient lookup
                    // failure) rather than misreporting it as
                    // CONNECTOR_NOT_DELEGATED. The connector delegation
                    // check returns false on null folderId, so the prior
                    // shape masked folder-resolution failures as connector
                    // denials in the audit trail.
                    String delegatedFolderId = ingestAuthorizationService.resolveFolderId(
                            profile.getRepositoryId(),
                            profile.getTargetFolderId(),
                            profile.getTargetFolderPath());
                    if (delegatedFolderId == null) {
                        auditScheduledDelegatedDenial(profile, connector,
                                DenialReason.TARGET_FOLDER_UNRESOLVABLE,
                                "Profile's target folder no longer resolvable");
                        continue;
                    }
                    if (!ingestAuthorizationService.canUseConnectorForDelegatedProfileAsUser(
                            delegatedCtx.getUsername(), profile.getRepositoryId(),
                            connector, delegatedFolderId)) {
                        auditScheduledDelegatedDenial(profile, connector,
                                DenialReason.CONNECTOR_NOT_DELEGATED,
                                "Connector no longer delegated to "
                                        + delegatedCtx.getUsername() + " for this folder");
                        continue;
                    }
                }

                // Circuit breaker: skip connector if it has failed too many times consecutively
                String connectorKey = connector.getConnectorId();
                int failures = consecutiveFailures.getOrDefault(connectorKey, 0);
                if (failures >= circuitBreakerThreshold) {
                    logger.warn("Circuit breaker OPEN for connector '{}' ({} consecutive failures), skipping",
                            connectorKey, failures);
                    continue;
                }

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

                    // Run fetch on a virtual thread with progress-based
                    // heartbeat via ThreadLocal.  The throttle() method
                    // (called per-item in every adapter loop) sends a
                    // heartbeat at most every 5 minutes, proving the
                    // fetch is making real progress.  If the connector
                    // wedges (no items processed → no throttle calls),
                    // lastHeartbeatAt stops updating and detectStuckJobs()
                    // marks the job STUCK after STUCK_TIMEOUT_MS (30min).
                    final IngestJobRecord heartbeatJob = job;
                    // Track the fetch thread for timeout interruption.
                    // On timeout, Thread.interrupt() triggers RuntimeException in throttle(),
                    // stopping the adapter loop at the next per-item checkpoint.
                    final java.util.concurrent.atomic.AtomicReference<Thread> fetchThread = new java.util.concurrent.atomic.AtomicReference<>();
                    // RC5 (v2 §12.1): effectively-final capture for the
                    // supplyAsync lambda. `delegatedCtx` is built outside
                    // this try block and assigned conditionally, so the
                    // compiler refuses to capture it directly.
                    final CallContext fetchCtx = delegatedCtx;

                    var future = java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                fetchThread.set(Thread.currentThread());
                                FetchSupport.currentJobHolder().set(heartbeatJob);
                                FetchSupport.lastProgressHeartbeat().get()[0] = System.currentTimeMillis();
                                logger.info("Fetch starting: profile={}, connector={}, system={}, archetype={}",
                                        profile.getProfileId(), connector.getConnectorId(),
                                        connector.getSourceSystem(), connector.getSourceArchetype());
                                try {
                                    // RC5 (v2 §12.1): pass the synthesised
                                    // CallContext when this is a delegated
                                    // tick; null for admin profiles keeps
                                    // legacy behaviour.
                                    return executeFetch(fetchCtx, profile, connector, params);
                                } finally {
                                    FetchSupport.currentJobHolder().remove();
                                    FetchSupport.lastProgressHeartbeat().remove();
                                }
                            },
                            r -> Thread.ofVirtual().name("fetch-" + profile.getProfileId()).start(r));

                    // Timeout fetch to prevent a hung adapter from blocking the entire poll cycle.
                    FetchResult result;
                    boolean timedOut = false;
                    try {
                        result = future.get(fetchTimeoutMinutes, java.util.concurrent.TimeUnit.MINUTES);
                    } catch (java.util.concurrent.TimeoutException te) {
                        Thread ft = fetchThread.get();
                        if (ft != null) ft.interrupt();
                        timedOut = true;
                        logger.error("Fetch timed out after {}m for profile {}, cancelling", fetchTimeoutMinutes, profile.getProfileId());
                        result = new FetchResult(0, 0, List.of("Fetch timed out after " + fetchTimeoutMinutes + " minutes"));
                    }

                    // Complete job record. A timeout is recorded as STUCK (not a
                    // FAILED with imported=0), because the interrupted fetch may
                    // have imported items whose counts are lost with the abandoned
                    // future — see IngestJobService.markTimedOut.
                    if (job != null && ingestJobService != null) {
                        try {
                            if (timedOut) {
                                ingestJobService.markTimedOut(job, fetchTimeoutMinutes);
                            } else {
                                ingestJobService.completeJob(job, result);
                            }
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

                    // Circuit breaker: reset on actual success, increment on total failure.
                    // An empty fetch (fetched=0, no errors) is neutral — don't reset, don't increment.
                    if (result.imported() > 0) {
                        if (consecutiveFailures.containsKey(connectorKey)) {
                            logger.info("Circuit breaker reset for connector '{}' after successful import", connectorKey);
                        }
                        consecutiveFailures.remove(connectorKey);
                    } else if (result.fetched() > 0 && !result.hasErrors()) {
                        if (consecutiveFailures.containsKey(connectorKey)) {
                            logger.info("Circuit breaker reset for connector '{}' (all items skipped)", connectorKey);
                        }
                        consecutiveFailures.remove(connectorKey);
                    } else if (result.hasErrors()) {
                        int newCount = consecutiveFailures.merge(connectorKey, 1, Integer::sum);
                        logger.warn("Connector '{}' failure #{}/{}", connectorKey, newCount, circuitBreakerThreshold);
                    }
                    // else: fetched=0, imported=0, no errors → empty fetch, neutral (no change)
                } catch (Exception e) {
                    int newCount = consecutiveFailures.merge(connectorKey, 1, Integer::sum);
                    logger.warn("Connector '{}' exception failure #{}/{}", connectorKey, newCount, circuitBreakerThreshold);

                    Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
                    logger.error("Scheduled fetch failed for {}: {}", profile.getProfileId(),
                            cause != null ? cause.getMessage() : e.getMessage());
                    if (job != null && ingestJobService != null) {
                        try {
                            ingestJobService.completeJob(job,
                                    new FetchResult(0, 0, List.of(
                                            cause != null ? cause.getMessage() : e.getMessage())));
                        } catch (Exception je) {
                            logger.debug("Failed to record job failure: {}", je.getMessage());
                        }
                    }
                }
            }
            // Periodic purge of expired idempotency keys (at most once per hour)
            purgeExpiredIdempotencyKeysIfDue();
            // Note: detectStuckJobs() runs on a separate scheduled thread
            // (stuckJobDetector) so that it can detect hangs even when
            // this scheduler thread is blocked inside executeFetch().
        } catch (Exception e) {
            logger.error("Ingest scheduler poll failed: {}", e.getMessage());
        }
    }

    /**
     * Purge idempotency keys older than 7 days from nemaki_conf.
     * Runs at most once per hour to avoid hammering CouchDB.
     *
     * <p>Uses bookmark-based pagination so that collections with more
     * than 200 entries are fully scanned across multiple pages.</p>
     */
    private void purgeExpiredIdempotencyKeysIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastIdempotencyPurge < IDEMPOTENCY_PURGE_INTERVAL_MS) return;
        lastIdempotencyPurge = now;

        if (settingsService == null || cloudantClientPool == null) return;
        try {
            var confClient = cloudantClientPool
                    .getClient(jp.aegif.nemaki.util.constant.SystemConst.NEMAKI_CONF_DB);
            if (confClient == null) return;
            String dbName = confClient.getDatabaseName();
            var cloudant = confClient.getClient();

            var selector = new java.util.HashMap<String, Object>();
            selector.put("type", "configuration");
            var keySelector = new java.util.HashMap<String, Object>();
            keySelector.put("$regex", "^ingest\\.idempotency\\.");
            selector.put("key", keySelector);

            long ttlMs = 7L * 24 * 60 * 60 * 1000;
            int totalPurged = 0;
            String bookmark = null;
            int PAGE_SIZE = 200;

            for (int page = 0; page < 100; page++) { // safety cap: 100 pages × 200 = 20 000 max
                var builder = new com.ibm.cloud.cloudant.v1.model.PostFindOptions.Builder()
                        .db(dbName).selector(selector).limit(PAGE_SIZE);
                if (bookmark != null) builder.bookmark(bookmark);
                var result = cloudant.postFind(builder.build()).execute().getResult();
                var docs = result.getDocs();
                if (docs == null || docs.isEmpty()) break;

                java.util.Set<String> keysToDelete = new java.util.HashSet<>();
                for (var doc : docs) {
                    Object valObj = doc.get("value");
                    if (valObj instanceof String val) {
                        int sep = val.indexOf('|');
                        if (sep > 0) {
                            try {
                                long savedAt = Long.parseLong(val.substring(sep + 1));
                                if (now - savedAt > ttlMs) {
                                    Object keyObj = doc.get("key");
                                    if (keyObj instanceof String key) keysToDelete.add(key);
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                if (!keysToDelete.isEmpty()) {
                    settingsService.deleteSettings(keysToDelete);
                    totalPurged += keysToDelete.size();
                }

                // Advance bookmark; stop if this was a partial page (last page)
                bookmark = result.getBookmark();
                if (docs.size() < PAGE_SIZE || bookmark == null || bookmark.isBlank()) break;
            }

            if (totalPurged > 0) {
                logger.info("Purged {} expired idempotency keys", totalPurged);
            }
        } catch (Exception e) {
            logger.debug("Idempotency key purge failed: {}", e.getMessage());
        }
    }

    /** Last time the idempotency key purge ran (epoch ms). */
    private volatile long lastIdempotencyPurge = 0;
    private static final long IDEMPOTENCY_PURGE_INTERVAL_MS = 60 * 60 * 1000L; // 1 hour

    private ImportProfileDefinitionService profileService;
    private ConnectorDefinitionService connectorService;
    private CanonicalImportService canonicalImportService;
    private IntegrationSettingsService settingsService;
    private PropertyManager propertyManager;
    private RepositoryInfoMap repositoryInfoMap;
    private IngestJobService ingestJobService;
    private jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool cloudantClientPool;
    private CheckpointManager checkpointManager;
    private FetchSupport fetchSupport;
    private FetchOrchestratorRegistry orchestratorRegistry;
    /**
     * Optional. When wired (and lineage.leader-election.enabled=true), the
     * scheduler executes its polling cycle only on the leader replica. If
     * unwired or disabled, isLeader() returns true and behaviour falls back
     * to single-replica semantics — preserving the existing single-node
     * deployment while preventing the multi-replica stampede where every
     * core container would independently poll the same external systems.
     */
    private jp.aegif.nemaki.rest.purview.journal.LeaderElection leaderElection;

    public void setLeaderElection(jp.aegif.nemaki.rest.purview.journal.LeaderElection leaderElection) {
        this.leaderElection = leaderElection;
    }

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

    private long readLong(String key, long defaultValue) {
        if (propertyManager == null) return defaultValue;
        String val = propertyManager.readValue(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Long.parseLong(val.trim()); }
        catch (NumberFormatException e) {
            logger.warn("Invalid numeric config '{}': '{}', using default {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }

    public void setIngestJobService(IngestJobService ingestJobService) {
        this.ingestJobService = ingestJobService;
    }

    public void setCloudantClientPool(jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool cloudantClientPool) {
        this.cloudantClientPool = cloudantClientPool;
    }

    public void setCheckpointManager(CheckpointManager checkpointManager) {
        this.checkpointManager = checkpointManager;
    }

    public void setFetchSupport(FetchSupport fetchSupport) {
        this.fetchSupport = fetchSupport;
    }

    public void setOrchestratorRegistry(FetchOrchestratorRegistry orchestratorRegistry) {
        this.orchestratorRegistry = orchestratorRegistry;
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

    // ── Checkpoint admin API (delegated to CheckpointManager) ─────

    public Map<String, Object> getCheckpoints(String profileId) {
        return checkpointManager != null ? checkpointManager.getCheckpoints(profileId) : new LinkedHashMap<>();
    }

    public void resetCheckpoint(String profileId, String scope) {
        if (checkpointManager != null) checkpointManager.resetCheckpoint(profileId, scope);
    }

    // ── Delegated authorization (shared by scheduler + webhook) ─────

    /**
     * Outcome of {@link #authorizeDelegatedFetch}. When {@link #allowed}
     * is {@code true} and {@link #callContext} is non-null, the caller
     * must pass that synthesised context to {@link #executeFetch} so the
     * ingest runs under the delegating user's authority. When
     * {@code allowed} is {@code false}, the fetch must be skipped; the
     * denial has already been audited.
     */
    public static final class DelegatedAuthorization {
        private final boolean allowed;
        private final CallContext callContext;
        private final DenialReason denialReason;

        private DelegatedAuthorization(boolean allowed, CallContext callContext, DenialReason denialReason) {
            this.allowed = allowed;
            this.callContext = callContext;
            this.denialReason = denialReason;
        }
        public boolean isAllowed() { return allowed; }
        public CallContext getCallContext() { return callContext; }
        public DenialReason getDenialReason() { return denialReason; }
    }

    /**
     * Re-evaluate, at fetch time, whether a delegated profile may still
     * run against the given connector. This is the SAME two-stage guard
     * the scheduler applies per tick:
     * <ol>
     *   <li>{@link #prepareDelegatedTick} — operator opt-in, required
     *       wiring, creator still active, creator still holds
     *       {@code cmis:all} on the target folder; and</li>
     *   <li>connector delegation re-check — the connector is still
     *       delegated to the creator for that folder.</li>
     * </ol>
     *
     * <p>It exists so non-scheduler entry points (notably the
     * push/webhook path in {@code IngestWebhookController}) authorise
     * delegated fetches the same way the scheduler does, instead of
     * running them as an unscoped admin context. Without this, a
     * delegated profile would keep ingesting on every signed webhook
     * event even after the creator lost folder access or the admin
     * revoked the connector delegation — an authorization bypass that
     * the scheduled path already prevented.
     *
     * <p>Non-delegated (admin) profiles are returned as allowed with a
     * null context, preserving the legacy admin fetch behaviour.
     * Denials are audited inside the helpers (same audit shape /
     * {@link DenialReason} as the scheduler).
     */
    public DelegatedAuthorization authorizeDelegatedFetch(ImportProfileDefinition profile,
                                                          ConnectorDefinition connector) {
        if (profile == null || !profile.isDelegated()) {
            // Admin profile: legacy behaviour (null context = admin path).
            return new DelegatedAuthorization(true, null, null);
        }

        // Stage 1: creator active + cmis:all on target folder (+ opt-in).
        CallContext delegatedCtx = prepareDelegatedTick(profile);
        if (delegatedCtx == null) {
            // prepareDelegatedTick already emitted WARN/audit + DenialReason.
            return new DelegatedAuthorization(false, null, DenialReason.CREATOR_CMIS_ALL_LOST);
        }

        // Stage 2: connector still delegated to the creator for this folder.
        String delegatedFolderId = ingestAuthorizationService.resolveFolderId(
                profile.getRepositoryId(),
                profile.getTargetFolderId(),
                profile.getTargetFolderPath());
        if (delegatedFolderId == null) {
            auditScheduledDelegatedDenial(profile, connector,
                    DenialReason.TARGET_FOLDER_UNRESOLVABLE,
                    "Profile's target folder no longer resolvable");
            return new DelegatedAuthorization(false, null, DenialReason.TARGET_FOLDER_UNRESOLVABLE);
        }
        if (!ingestAuthorizationService.canUseConnectorForDelegatedProfileAsUser(
                delegatedCtx.getUsername(), profile.getRepositoryId(),
                connector, delegatedFolderId)) {
            auditScheduledDelegatedDenial(profile, connector,
                    DenialReason.CONNECTOR_NOT_DELEGATED,
                    "Connector no longer delegated to "
                            + delegatedCtx.getUsername() + " for this folder");
            return new DelegatedAuthorization(false, null, DenialReason.CONNECTOR_NOT_DELEGATED);
        }

        return new DelegatedAuthorization(true, delegatedCtx, null);
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
        // Enforce rateLimitRpm: cap limit to avoid exceeding the connector's
        // rate limit in a single fetch cycle.  API calls per item vary by
        // archetype: Notion pages need 3-5 (blocks + file downloads),
        // chat adapters need 2-3 (messages + file downloads), file-share
        // adapters need 1-2 (list + download).  Use a per-archetype
        // divisor instead of the old blanket "/2".
        if (connector.getRateLimitRpm() != null && connector.getRateLimitRpm() > 0) {
            // Use adapter descriptor for per-adapter API call estimates
            AdapterDescriptor desc = AdapterRegistry.get(connector.getSourceSystem());
            int callsPerItem = (desc != null) ? desc.apiCallsPerItem() : 3; // safe default
            int maxPerCycle = connector.getRateLimitRpm() / callsPerItem;
            if (limit > maxPerCycle) {
                logger.debug("Rate limit: capping fetch limit from {} to {} for connector {} ({}×/item)",
                        limit, maxPerCycle, connector.getConnectorId(), callsPerItem);
                limit = Math.max(1, maxPerCycle);
            }
        }

        // Push/pull-only adapters (no scheduled fetch)
        if ("google_drive".equals(system) || "onedrive".equals(system)) {
            return new FetchResult(0, 0, List.of(
                    system + " uses cloud drive sync (push/pull), not scheduled fetch"));
        }

        // Registry-based dispatch
        FetchOrchestrator orchestrator = (orchestratorRegistry != null) ? orchestratorRegistry.get(system) : null;
        if (orchestrator == null) {
            return new FetchResult(0, 0, List.of(
                    "Unknown sourceSystem '" + system + "' for " + archetype + ". "
                    + "Registered adapters: " + String.join(", ", AdapterRegistry.allSourceSystems())));
        }
        return orchestrator.execute(callContext, profile, connector, params, limit);
    }
}
