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
package jp.aegif.nemaki.evidence.anchor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.evidence.EvidenceCheckpoint;
import jp.aegif.nemaki.evidence.EvidenceLedgerEntry;
import jp.aegif.nemaki.evidence.EvidenceLedgerService;
import jp.aegif.nemaki.evidence.EvidenceLedgerStore;
import jp.aegif.nemaki.evidence.RecordContentStateRecorder;
import jp.aegif.nemaki.model.Configuration;
import jp.aegif.nemaki.rest.purview.anchor.AnchorKind;
import jp.aegif.nemaki.rest.purview.anchor.AnchorReceipt;
import jp.aegif.nemaki.rest.purview.anchor.AnchorStatus;
import jp.aegif.nemaki.rest.purview.anchor.AnchorTarget;
import jp.aegif.nemaki.rest.controller.IntegrationSettingsService;
import jp.aegif.nemaki.rest.purview.journal.LeaderElection;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.SystemConst;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Seals and anchors the evidence ledger on its own, per ledger domain, when an operator has turned
 * it on (design {@code docs/design/anchor-scheduler.md} §3).
 *
 * <p>The domains are the repositories (the domain their fixity, capture, custody, disposition and
 * rendition entries are chained in) AND {@code record-content} — the one domain E1's record-content
 * statements are chained in for every repository, and the one an exported package's evidence
 * section reads its checkpoint and anchor from. A scheduler that visited only repositories would
 * leave the chain the verifier checks sealed by hand.
 *
 * <p>Every 60 seconds, for each domain: read {@code anchor.schedule.*} afresh; do nothing
 * unless it is enabled and valid, a rung is configured, this node is the leader for
 * {@code "anchor"}, and the ledger and the receipt store both answer; then seal and send when the
 * unanchored entries are old enough (interval) or numerous enough (max), and no sooner than the
 * minimum interval after the last seal. OpenTimestamps upgrades and the retry of failed rungs run
 * on their own timers. Everything it does goes through {@link AnchorRunService} — the code the
 * admin endpoints run.
 *
 * <h2>What this does not claim</h2>
 *
 * <p>The interval is the condition under which a seal is TRIED, not a bound on how long an entry
 * stays unanchored: a refused anchor, a store that does not answer, a node that is not the leader
 * all leave the window open, and the status says which. With leader election switched off, every
 * replica believes it is the leader (design §3.3) — the operator's rule, not something this class
 * can detect.
 *
 * <h2>"Could not read" is not "nothing to do"</h2>
 *
 * <p>Each of the reads it depends on — the settings, the ledger head, the receipts that say how
 * far an anchor reaches — can fail, and each failure makes the tick idle with a reason rather than
 * letting a default stand in. A saved "disabled" that cannot be read does not fall back to a
 * {@code -D} "enabled"; an anchored position that cannot be read is not 0.
 */
@Component
public class AnchorScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AnchorScheduler.class);

    static final int TICK_SECONDS = 60;
    static final String LEADER_ROLE = "anchor";
    /** How many checkpoints the anchored position is looked for through (design §3.1). */
    static final int ANCHORED_WALK_LIMIT = 20;
    static final int UPGRADE_BATCH = 100;

    @Autowired(required = false)
    private AnchorService anchorService;

    @Autowired(required = false)
    private EvidenceLedgerService ledgerService;

    @Autowired(required = false)
    private EvidenceLedgerStore ledgerStore;

    @Autowired(required = false)
    private AnchorReceiptStore receiptStore;

    @Autowired(required = false)
    private LeaderElection leaderElection;

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Autowired(required = false)
    private IntegrationSettingsService settingsService;

    @Autowired(required = false)
    private RepositoryInfoMap repositoryInfoMap;

    private final Map<String, RepoState> states = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    @PostConstruct
    public void start() {
        // A platform daemon thread: the ScheduledExecutorService family is not a virtual-thread
        // client (CLAUDE.md), and one thread is enough for a 60-second tick.
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AnchorScheduler");
            t.setDaemon(true);
            return t;
        });
        // The first tick waits one period: at start-up the configuration store may still be
        // coming up, and a tick then would only record "could not read".
        executor.scheduleWithFixedDelay(this::safeTick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        logger.info("Anchor scheduler started (every {}s; each repository stays idle until "
                + "{} is true)", TICK_SECONDS, AnchorScheduleSettings.ENABLED);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void safeTick() {
        try {
            tick(Instant.now());
        } catch (RuntimeException e) {
            // A scheduled task that throws is never run again. Nothing above this line may end
            // the schedule.
            logger.warn("Anchor scheduler tick failed; the next tick runs as usual: {}", e.toString());
        }
    }

    /** One pass over every repository. The production entry; the tests drive it with a clock. */
    void tick(Instant now) {
        for (String repositoryId : domains()) {
            RepoState state = stateOf(repositoryId);
            try {
                tickRepository(repositoryId, state, now);
            } catch (RuntimeException e) {
                logger.warn("Anchor scheduler tick for {} failed: {}", repositoryId, e.toString(), e);
                state.idle(now, "FAILED", "the tick failed unexpectedly; see the server log", true, logger);
            }
        }
    }

    /** The ledger domains this node visits: every configured repository, then record-content. */
    public Set<String> domains() {
        Set<String> domains = new LinkedHashSet<>();
        if (repositoryInfoMap != null) {
            domains.addAll(repositoryInfoMap.keys());
        }
        domains.add(RecordContentStateRecorder.DOMAIN);
        return domains;
    }

    private void tickRepository(String repositoryId, RepoState state, Instant now) {
        state.lastTickAt = now;

        // 1. The settings, read afresh — from nemaki_conf itself, not this node's configuration
        //    cache (a value saved on another replica must reach the leader on its next tick).
        SettingsRead read = readSettings(repositoryId);
        if (read.unreadable() != null) {
            state.idle(now, "UNAVAILABLE", read.unreadable(), true, logger);
            return;
        }
        AnchorScheduleSettings.Parsed parsed = read.parsed();
        AnchorScheduleSettings.Effective settings = parsed.effective();
        if (!settings.enabled() && !parsed.errors().containsKey(AnchorScheduleSettings.ENABLED)) {
            state.idle(now, "IDLE", "disabled (" + AnchorScheduleSettings.ENABLED + " is not true)", false, logger);
            return;
        }
        if (!parsed.valid()) {
            // Not a default, and not a guess: a value the endpoint would have refused (a -D, an
            // environment variable) keeps the scheduler still until someone corrects it.
            state.idle(now, "IDLE", "the settings are not valid, so nothing is sent: " + parsed.errors(), true, logger);
            return;
        }

        // 2. A rung to send to. Enabled with no rung is a configuration that cannot do anything.
        List<AnchorTarget> configured = configuredRungs();
        if (anchorService == null) {
            state.idle(now, "IDLE", "the anchor service is not wired on this node", true, logger);
            return;
        }
        if (configured.isEmpty()) {
            state.idle(now, "IDLE", "no rung is configured on this node (no TSA URL and no "
                    + "OpenTimestamps sidecar URL), so there is nowhere to send", true, logger);
            return;
        }

        // 3. The leader. With election off every node answers true — design §3.3.
        boolean leader = leaderElection == null || leaderElection.isLeader(LEADER_ROLE);
        state.lastLeader = leader;
        if (!leader) {
            state.idle(now, "IDLE", "not the leader for '" + LEADER_ROLE + "'", false, logger);
            return;
        }

        // 4. The ledger, and what it holds now.
        if (ledgerService == null || ledgerStore == null || !ledgerStore.isActive()) {
            state.idle(now, "IDLE", "the evidence ledger is not active on this node", true, logger);
            return;
        }
        Observation observation = observe(repositoryId, now);
        state.lastObservation = observation;
        if (observation.unavailable() != null) {
            // The plan's gate: an anchored position that cannot be read seals nothing.
            state.idle(now, "UNAVAILABLE", observation.unavailable(), true, logger);
            return;
        }

        // 5. Seal and send, when due.
        decideSeal(repositoryId, state, settings, observation, now);

        // 6. The two timers, independent of the seal (design §3.2).
        maybeUpgrade(repositoryId, state, settings, configured, now);
        maybeRetry(repositoryId, state, settings, observation, now);
    }

    private void decideSeal(String repositoryId, RepoState state, AnchorScheduleSettings.Effective settings,
            Observation obs, Instant now) {
        boolean byCount = settings.maxUnanchoredEntries() != null
                && obs.unanchoredCount() >= settings.maxUnanchoredEntries();
        // Null-safe on purpose: the settings check is the one guard for "enabled without an
        // interval", and a NullPointerException here would be a second, accidental one that a
        // lock could not tell from the first.
        boolean byTime = settings.intervalMinutes() != null && obs.unanchoredCount() >= 1
                && obs.oldestUnanchoredAt() != null
                && !Duration.between(obs.oldestUnanchoredAt(), now)
                        .minus(Duration.ofMinutes(settings.intervalMinutes())).isNegative();
        if (!byCount && !byTime) {
            String why;
            if (obs.unanchoredCount() == 0) {
                why = "nothing is unanchored";
            } else if (obs.oldestUnanchoredAt() == null) {
                why = obs.unanchoredCount() + " unanchored entr" + (obs.unanchoredCount() == 1 ? "y" : "ies")
                        + ", and the time of the oldest could not be read, so the interval cannot be "
                        + "judged" + (settings.maxUnanchoredEntries() == null ? "" : " (the count has not "
                        + "reached " + settings.maxUnanchoredEntries() + ")");
            } else {
                why = "not due: " + obs.unanchoredCount() + (obs.countIsLowerBound() ? " or more" : "")
                        + " unanchored, the oldest since " + obs.oldestUnanchoredAt() + "; interval "
                        + settings.intervalMinutes() + " min"
                        + (settings.maxUnanchoredEntries() == null ? "" : ", count limit " + settings.maxUnanchoredEntries());
            }
            state.idle(now, "IDLE", why, false, logger);
            return;
        }
        if (obs.unsealedCount() == 0) {
            // Due, and everything unanchored is already inside a sealed checkpoint. Sealing again
            // would seal nothing (closeCheckpoint answers noop). The latest checkpoint's anchor is
            // what failed or was refused, and the way back for it is retry-unsettled — its own
            // timer below when configured, or an operator.
            state.idle(now, "IDLE", "due, but every unanchored entry is already sealed into checkpoint "
                    + obs.sealedThrough() + "; its anchor is retried by retry-unsettled"
                    + (settings.retryUnsettledIntervalMinutes() == null ? " (not scheduled here — "
                    + "anchor.schedule.retry-unsettled-interval-minutes is not set; an operator calls it)" : ""),
                    false, logger);
            return;
        }
        if (state.nextEligibleAt != null && now.isBefore(state.nextEligibleAt)) {
            state.idle(now, "IDLE", "due, and holding off after the last error until " + state.nextEligibleAt,
                    false, logger);
            return;
        }
        if (obs.lastSealAt() != null && Duration.between(obs.lastSealAt(), now)
                .compareTo(Duration.ofMinutes(settings.minIntervalMinutes())) < 0) {
            state.idle(now, "IDLE", "due, and the last seal (" + obs.lastSealAt() + ") is within the minimum "
                    + "interval of " + settings.minIntervalMinutes() + " min", false, logger);
            return;
        }

        state.lastSealAttemptAt = now;
        AnchorRunService.Run run = runService().checkpointAndAnchor(repositoryId, now);
        switch (run.kind()) {
            // A rung that refused its configuration was not asked; the record names it rather than
            // reading "sealed and sent" over a rung nothing was sent to (c45, P1).
            case SUCCESS -> state.ran(now, "SUCCESS", "sealed and sent" + notAskedClause(run));
            case NOOP -> {
                // This tick counted unsealed entries and closeCheckpoint found none: another writer
                // (a replica, an operator) sealed them in between, or the two reads disagree.
                logger.warn("Anchor scheduler for {}: {} unsealed entr{} counted, and closeCheckpoint "
                        + "answered noop", repositoryId, obs.unsealedCount(), obs.unsealedCount() == 1 ? "y" : "ies");
                state.ran(now, "NOOP", "nothing was sealed: another seal got there first, or the ledger "
                        + "reads disagree");
            }
            case NOT_SEALED, FAILED, UNAVAILABLE -> {
                // Not retried every minute: the next attempt waits the minimum interval.
                state.nextEligibleAt = now.plus(Duration.ofMinutes(settings.minIntervalMinutes()));
                state.ran(now, "ERROR", String.valueOf(run.body().get("message")));
                logger.warn("Anchor scheduler for {} could not seal and send: {}", repositoryId,
                        run.body().get("message"));
            }
            case REFUSED -> refused(repositoryId, state, run.outcome(), now, notAskedClause(run));
        }
    }

    /** The rungs a seal did not ask, as the run's body names them, or nothing. */
    private static String notAskedClause(AnchorRunService.Run run) {
        Object notAsked = run.body().get("notAsked");
        return notAsked == null ? "" : "; not asked, because they refuse their configuration: " + notAsked;
    }

    /** The refusal arms, each with its own consequence (design §3.2). */
    private void refused(String repositoryId, RepoState state, AnchorService.Outcome outcome, Instant now,
            String notAskedClause) {
        String reason = (outcome == null ? "refused" : outcome.refusedReason()) + notAskedClause;
        if (outcome != null && holdsAConfiguredCommitment(outcome.unstored())) {
            // A commitment exists that this deployment has no record of. Sending again would not
            // recover it; it would make ANOTHER (and buy another token). The retry timer leaves
            // this checkpoint alone until an operator decides.
            state.retryHeldForCheckpoint = outcome.toSequence();
            logger.warn("Anchor scheduler for {}: a commitment for checkpoint {} was made and its "
                    + "receipt was not stored; automatic retry is held for that checkpoint",
                    repositoryId, outcome.toSequence());
        } else {
            logger.warn("Anchor scheduler for {}: the anchor was refused: {}", repositoryId, reason);
        }
        state.ran(now, "REFUSED", reason);
    }

    private static boolean holdsAConfiguredCommitment(List<AnchorReceipt> unstored) {
        for (AnchorReceipt receipt : unstored) {
            if (receipt.status() != AnchorStatus.NOT_CONFIGURED && receipt.status() != AnchorStatus.FAILED) {
                return true;
            }
        }
        return false;
    }

    private void maybeUpgrade(String repositoryId, RepoState state, AnchorScheduleSettings.Effective settings,
            List<AnchorTarget> configured, Instant now) {
        boolean opentimestamps = configured.stream().anyMatch(t -> t.kind() == AnchorKind.OPENTIMESTAMPS);
        if (!opentimestamps || receiptStore == null) {
            return;
        }
        if (state.lastUpgradeAt != null && Duration.between(state.lastUpgradeAt, now)
                .compareTo(Duration.ofMinutes(settings.upgradeIntervalMinutes())) < 0) {
            return;
        }
        state.lastUpgradeAt = now;
        List<AnchorReceiptStore.PendingReceipt> pending;
        try {
            pending = receiptStore.pending(repositoryId, 1);
        } catch (RuntimeException e) {
            state.lastUpgradeOutcome = "UNAVAILABLE: the pending receipts could not be read";
            return;
        }
        if (receiptStore.lastQueryFailed()) {
            state.lastUpgradeOutcome = "UNAVAILABLE: the pending receipts could not be queried";
            return;
        }
        if (pending.isEmpty()) {
            // A row the store could not decode is dropped before this list is built, so an empty
            // list with a drop behind it is "could not read", not "nothing pending" — the rule
            // upgradePending itself follows (subagent, c41).
            int unreadable = receiptStore.unreadableCount();
            state.lastUpgradeOutcome = unreadable > 0
                    ? "UNAVAILABLE: " + unreadable + " pending receipt row(s) could not be read, so "
                            + "whether anything is pending is unknown"
                    : "NOOP: nothing pending";
            return;
        }
        AnchorRunService.Run run = runService().upgradePending(repositoryId, UPGRADE_BATCH);
        state.lastUpgradeOutcome = run.kind() + ": " + (run.kind() == AnchorRunService.Kind.UNAVAILABLE
                || run.kind() == AnchorRunService.Kind.REFUSED
                ? run.body().get("message") : run.body().get("upgradedCount") + " upgraded");
    }

    private void maybeRetry(String repositoryId, RepoState state, AnchorScheduleSettings.Effective settings,
            Observation obs, Instant now) {
        Integer interval = settings.retryUnsettledIntervalMinutes();
        if (interval == null || obs.latest() == null) {
            return;
        }
        if (state.lastRetryAt != null && Duration.between(state.lastRetryAt, now)
                .compareTo(Duration.ofMinutes(interval)) < 0) {
            return;
        }
        if (state.lastSealAttemptAt != null && state.lastSealAttemptAt.equals(now)) {
            // This tick has just contacted every configured rung for a fresh checkpoint; a retry
            // in the same breath would contact a failed one twice.
            state.lastRetryAt = now;
            return;
        }
        Long held = state.retryHeldForCheckpoint;
        if (held != null && held == obs.latest().toSequence()) {
            state.lastRetryOutcome = "HELD: a commitment for checkpoint " + held + " was made and its receipt "
                    + "was not stored; a retry would make another — an operator decides";
            return;
        }
        state.lastRetryAt = now;
        AnchorRunService.Run run = runService().retryUnsettled(repositoryId);
        if (run.outcome() != null && holdsAConfiguredCommitment(run.outcome().unstored())) {
            state.retryHeldForCheckpoint = run.outcome().toSequence();
            logger.warn("Anchor scheduler for {}: a retried commitment for checkpoint {} was made and its "
                    + "receipt was not stored; automatic retry is held for that checkpoint",
                    repositoryId, run.outcome().toSequence());
        }
        state.lastRetryOutcome = run.kind() + ": " + run.body().get("message");
    }

    // ---- reading ----

    private record SettingsRead(AnchorScheduleSettings.Parsed parsed, String unreadable) {
    }

    private SettingsRead readSettings(String repositoryId) {
        if (settingsService == null || propertyManager == null) {
            return new SettingsRead(null, "the configuration is not wired on this node");
        }
        Map<String, String> raw;
        try {
            raw = rawSettings(repositoryId);
        } catch (RuntimeException e) {
            // Not "use the -D value instead": a saved "disabled" that cannot be read must not be
            // replaced by a start-up "enabled".
            logger.warn("Anchor scheduler could not read the settings of {}: {}", repositoryId, e.toString());
            return new SettingsRead(null, "the saved settings could not be read, so whether anchoring "
                    + "is enabled here is unknown; nothing is sent");
        }
        Configuration global = propertyManager.getConfiguration(SystemConst.NEMAKI_CONF_DB);
        if (global == null || global.isLoadFailed()) {
            return new SettingsRead(null, "the global configuration could not be read, so whether "
                    + "anchoring is enabled here is unknown; nothing is sent");
        }
        return new SettingsRead(AnchorScheduleSettings.parse(raw), null);
    }

    /**
     * The raw values for one domain: its own saved value (nemaki_conf, read directly — see
     * {@link IntegrationSettingsService#readRepositorySettings}), and where it has none, the
     * deployment-wide value ({@code PropertyManager}: global nemaki_conf, then {@code -D}, the
     * environment and the properties file). Throws when the domain's own values could not be read.
     */
    Map<String, String> rawSettings(String repositoryId) {
        Map<String, String> stored = settingsService.readRepositorySettings(repositoryId,
                AnchorScheduleSettings.KEYS);
        Map<String, String> raw = new LinkedHashMap<>();
        for (String key : AnchorScheduleSettings.KEYS) {
            String own = stored.get(key);
            raw.put(key, own != null && !own.isBlank() ? own : propertyManager.readValue(key));
        }
        return raw;
    }

    List<AnchorTarget> configuredRungs() {
        List<AnchorTarget> configured = new ArrayList<>();
        if (anchorService != null) {
            for (AnchorTarget target : anchorService.targets()) {
                if (target.isConfigured()) {
                    configured.add(target);
                }
            }
        }
        return configured;
    }

    /**
     * What the ledger and the receipts say now.
     *
     * @param unavailable non-null when something needed could not be read; then no count is given
     * @param anchoredThrough the last sequence a time-proof receipt reaches, -1 when none; an UPPER
     *        bound when {@code countIsLowerBound}
     * @param unanchoredCount entries after {@code anchoredThrough}; a LOWER bound when
     *        {@code countIsLowerBound}
     * @param oldestUnanchoredAt when the oldest unanchored entry happened, or — when the walk ran
     *        out — a time it is known to be no later than; null when it could not be read
     */
    record Observation(String unavailable, EvidenceCheckpoint latest, long highestSequence,
                       long sealedThrough, long unsealedCount, long anchoredThrough, long unanchoredCount,
                       boolean countIsLowerBound, Instant oldestUnanchoredAt, String oldestUnreadable,
                       Instant lastSealAt) {

        static Observation unavailable(String why) {
            return new Observation(why, null, -1, -1, 0, -1, 0, false, null, null, null);
        }

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (unavailable != null) {
                // No count at all: "could not read" must not arrive as 0.
                m.put("status", "UNAVAILABLE");
                m.put("reason", unavailable);
                return m;
            }
            m.put("status", "OK");
            m.put("ledgerHighestSequence", highestSequence);
            m.put("sealedThrough", sealedThrough < 0 ? null : sealedThrough);
            m.put("unsealedCount", unsealedCount);
            m.put("anchoredUpTo", countIsLowerBound || anchoredThrough < 0 ? null : anchoredThrough);
            m.put("count", unanchoredCount);
            m.put("countIsLowerBound", countIsLowerBound);
            m.put("oldestAt", oldestUnanchoredAt == null ? null : oldestUnanchoredAt.toString());
            if (oldestUnreadable != null) {
                m.put("oldestAtUnreadable", oldestUnreadable);
            }
            m.put("lastSealAt", lastSealAt == null ? null : lastSealAt.toString());
            return m;
        }
    }

    /** Reads without writing anything — the tick and the settings endpoint both call it. */
    Observation observe(String repositoryId, Instant now) {
        if (ledgerStore == null || !ledgerStore.isActive()) {
            return Observation.unavailable("the evidence ledger is not active on this node");
        }
        if (receiptStore == null || !receiptStore.isActive()) {
            return Observation.unavailable("the anchor receipt store could not be reached, so how far "
                    + "an anchor reaches is unknown");
        }
        EvidenceCheckpoint latest;
        long highest;
        try {
            latest = ledgerStore.latestCheckpoint(repositoryId);
            highest = ledgerStore.highestSequence(repositoryId);
        } catch (RuntimeException e) {
            logger.warn("Anchor scheduler could not read the ledger of {}: {}", repositoryId, e.toString());
            return Observation.unavailable("the ledger head could not be read");
        }
        Instant lastSealAt = null;
        if (latest != null) {
            try {
                lastSealAt = Instant.parse(latest.createdAt());
            } catch (DateTimeParseException | NullPointerException e) {
                return Observation.unavailable("the latest checkpoint's time could not be read, so the "
                        + "minimum interval cannot be judged");
            }
        }
        long sealedThrough = latest == null ? -1 : latest.toSequence();
        long unsealed = Math.max(0, highest - sealedThrough);

        // How far a time-proof receipt reaches — at most ANCHORED_WALK_LIMIT checkpoints back.
        long anchoredThrough = -1;
        boolean lowerBound = false;
        EvidenceCheckpoint oldestWalked = null;
        try {
            EvidenceCheckpoint cp = latest;
            int walked = 0;
            boolean found = false;
            while (cp != null) {
                if (walked == ANCHORED_WALK_LIMIT) {
                    // Twenty sealed checkpoints and none anchored. The anchored position is at most
                    // this checkpoint's end — a bound, which is enough to judge "due" soundly.
                    anchoredThrough = cp.toSequence();
                    lowerBound = true;
                    break;
                }
                List<AnchorReceipt> receipts = receiptStore.forCheckpoint(repositoryId, cp.toSequence());
                if (receiptStore.lastQueryFailed() || receiptStore.unreadableCount() > 0) {
                    return Observation.unavailable("the anchor receipts for checkpoint " + cp.toSequence()
                            + " could not all be read, so how far an anchor reaches is unknown");
                }
                if (anyTimeProof(receipts)) {
                    anchoredThrough = cp.toSequence();
                    found = true;
                    break;
                }
                oldestWalked = cp;
                walked++;
                cp = ledgerStore.checkpointEndingBefore(repositoryId, cp.fromSequence());
            }
            if (!found && !lowerBound) {
                anchoredThrough = -1;
            }
        } catch (RuntimeException e) {
            logger.warn("Anchor scheduler could not read the anchor receipts of {}: {}", repositoryId, e.toString());
            return Observation.unavailable("the anchor receipts could not be read, so how far an anchor "
                    + "reaches is unknown");
        }
        long unanchored = Math.max(0, highest - anchoredThrough);

        Instant oldest = null;
        String oldestUnreadable = null;
        if (unanchored > 0) {
            if (lowerBound) {
                // The oldest unanchored entry is inside or before the oldest checkpoint walked, and
                // an entry is appended before the checkpoint that seals it — so it has been
                // unanchored at least since that checkpoint was sealed.
                oldest = oldestWalked == null ? null : parseOrNull(oldestWalked.createdAt());
                if (oldest == null) {
                    oldestUnreadable = "the oldest walked checkpoint's time could not be read";
                }
            } else {
                try {
                    List<EvidenceLedgerEntry> first = ledgerStore.range(repositoryId, anchoredThrough + 1,
                            anchoredThrough + 1, 1);
                    if (first == null || first.isEmpty() || ledgerStore.unreadableCount() > 0) {
                        oldestUnreadable = "entry " + (anchoredThrough + 1) + " could not be read";
                    } else {
                        oldest = parseOrNull(first.get(0).occurredAt());
                        if (oldest == null) {
                            oldestUnreadable = "entry " + (anchoredThrough + 1) + " has no readable time";
                        }
                    }
                } catch (RuntimeException e) {
                    oldestUnreadable = "entry " + (anchoredThrough + 1) + " could not be read";
                }
            }
        }
        return new Observation(null, latest, highest, sealedThrough, unsealed, anchoredThrough, unanchored,
                lowerBound, oldest, oldestUnreadable, lastSealAt);
    }

    /** A receipt that says when: a confirmed RFC 3161 token, or an OpenTimestamps commitment. */
    private static boolean anyTimeProof(List<AnchorReceipt> receipts) {
        for (AnchorReceipt receipt : receipts) {
            if (receipt.kind() == AnchorKind.RFC3161_TSA && receipt.status() == AnchorStatus.CONFIRMED) {
                return true;
            }
            if (receipt.kind() == AnchorKind.OPENTIMESTAMPS
                    && (receipt.status() == AnchorStatus.CONFIRMED || receipt.status() == AnchorStatus.PENDING)) {
                return true;
            }
        }
        return false;
    }

    private static Instant parseOrNull(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private AnchorRunService runService() {
        return new AnchorRunService(anchorService, ledgerService, ledgerStore);
    }

    // ---- what the settings endpoint shows ----

    /** This node's record of the last tick for a repository, and whether this node leads. */
    public Map<String, Object> runtime(String repositoryId) {
        Map<String, Object> m = stateOf(repositoryId).asMap();
        m.put("leaderElectionEnabled", leaderElection != null && leaderElection.isEnabled());
        m.put("nodeId", leaderElection == null ? null : leaderElection.getNodeId());
        return m;
    }

    /** The anchored and sealed positions now — read-only. */
    public Map<String, Object> observation(String repositoryId) {
        return observe(repositoryId, Instant.now()).asMap();
    }

    /**
     * The raw {@code anchor.schedule.*} values this domain resolves to, or null when unwired.
     * Throws when the domain's saved values could not be read — the caller says so rather than
     * showing defaults.
     */
    public Map<String, String> settings(String repositoryId) {
        return propertyManager == null || settingsService == null ? null : rawSettings(repositoryId);
    }

    private RepoState stateOf(String repositoryId) {
        return states.computeIfAbsent(repositoryId, id -> new RepoState());
    }

    /** Per-repository, per-node, in memory: what the last tick did and why. */
    static final class RepoState {
        volatile Instant lastTickAt;
        volatile Instant lastSealAttemptAt;
        volatile String lastOutcome;
        volatile String lastReason;
        volatile Instant lastOutcomeAt;
        volatile Instant nextEligibleAt;
        volatile Boolean lastLeader;
        volatile Instant lastUpgradeAt;
        volatile String lastUpgradeOutcome;
        volatile Instant lastRetryAt;
        volatile String lastRetryOutcome;
        volatile Long retryHeldForCheckpoint;
        volatile Observation lastObservation;
        private volatile String warned;

        /** Records an idle tick; a reason worth a WARN is logged once, not every minute. */
        void idle(Instant now, String outcome, String reason, boolean warn, Logger log) {
            this.lastOutcome = outcome;
            this.lastReason = reason;
            this.lastOutcomeAt = now;
            if (warn) {
                if (!reason.equals(warned)) {
                    log.warn("Anchor scheduler is idle: {}", reason);
                    warned = reason;
                }
            } else {
                warned = null;
            }
        }

        void ran(Instant now, String outcome, String reason) {
            this.lastOutcome = outcome;
            this.lastReason = reason;
            this.lastOutcomeAt = now;
            this.warned = null;
            if ("SUCCESS".equals(outcome) || "NOOP".equals(outcome)) {
                this.nextEligibleAt = null;
            }
        }

        /** Test seam: the reason last warned, or null. */
        String warnedReason() {
            return warned;
        }

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lastTickAt", str(lastTickAt));
            m.put("lastOutcome", lastOutcome);
            m.put("lastReason", lastReason);
            m.put("lastOutcomeAt", str(lastOutcomeAt));
            m.put("lastSealAttemptAt", str(lastSealAttemptAt));
            m.put("nextEligibleAt", str(nextEligibleAt));
            m.put("leader", lastLeader);
            m.put("lastUpgradeAt", str(lastUpgradeAt));
            m.put("lastUpgradeOutcome", lastUpgradeOutcome);
            m.put("lastRetryAt", str(lastRetryAt));
            m.put("lastRetryOutcome", lastRetryOutcome);
            m.put("retryHeldForCheckpoint", retryHeldForCheckpoint);
            return m;
        }

        private static String str(Instant i) {
            return i == null ? null : i.toString();
        }
    }

    /** Test seam: the state a repository's ticks left behind. */
    RepoState state(String repositoryId) {
        return stateOf(repositoryId);
    }
}
