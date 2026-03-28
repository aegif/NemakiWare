package jp.aegif.nemaki.rest.purview.journal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jp.aegif.nemaki.util.PropertyManager;

/**
 * Configuration for the Lineage Journal subsystem.
 *
 * <h2>Resolution Priority (highest wins)</h2>
 *
 * <p>For {@code lineage.mode}, the effective value for a given repository
 * is resolved in strict priority order:
 *
 * <ol>
 *   <li><b>Priority 1 — Repository override</b>:
 *       {@code lineage.mode.override.{repositoryId}} (e.g.
 *       {@code lineage.mode.override.bedroom=journaled}).
 *       If set and non-blank, this wins unconditionally.</li>
 *   <li><b>Priority 2 — Global dynamic</b>:
 *       {@code lineage.mode} resolved from {@link PropertyManager}
 *       (CouchDB nemaki_conf). If the PropertyManager returns a
 *       non-null value, it overrides the startup default.</li>
 *   <li><b>Priority 3 — Startup default</b>:
 *       {@code @Value("${lineage.mode:disabled}")} from property files
 *       or environment variables. Evaluated once at Spring startup.</li>
 * </ol>
 *
 * <p>All other settings ({@code lineage.targets}, {@code lineage.retention.days},
 * {@code lineage.purge.cron}, etc.) follow priorities 2 and 3 only
 * (no per-repository override). They are <b>instance-wide</b> and
 * shared across all repositories.
 *
 * <h2>Dynamic Reflection — No Restart Required</h2>
 *
 * <p>All values are resolved dynamically on every read via
 * {@link PropertyManager} (backed by CouchDB nemaki_conf). Changes
 * written through the Settings UI or REST API take effect on the
 * <b>next getter call</b> without JVM restart.
 *
 * <h2>Cluster Consistency</h2>
 *
 * <p>In a multi-node deployment, nemaki_conf is stored in CouchDB which
 * provides <b>eventual consistency</b> across nodes (typically converges
 * within seconds). During the propagation window, different nodes may
 * briefly see different config values. This is acceptable for most
 * settings but has operational implications for mode changes:
 *
 * <ul>
 *   <li><b>Mode change ({@code disabled → journaled} or vice versa):</b>
 *       nodes will transition at slightly different times. Events emitted
 *       during the window may be handled by the old mode on some nodes.
 *       For clean transitions, operators should change the mode during a
 *       maintenance window or accept a brief overlap period.</li>
 *   <li><b>Non-mode settings (targets, retention, cron):</b> eventual
 *       consistency is harmless — values converge quickly and
 *       inconsistency does not cause data loss or corruption.</li>
 * </ul>
 *
 * <h2>Upgrade Path for Existing Users (specification)</h2>
 *
 * <p>The startup default for {@code lineage.mode} is {@code disabled}.
 * This is a deliberate design choice for zero-cost upgrades:
 *
 * <ul>
 *   <li><b>Existing installations without Purview:</b> upgrade changes
 *       nothing. No events emitted, no journal DB created, zero
 *       additional CPU/IO.</li>
 *   <li><b>Existing installations with Purview sync enabled:</b>
 *       upgrade changes nothing. {@code lineage.mode} remains
 *       {@code disabled}. <b>There is no automatic migration from
 *       "Purview enabled" to {@code lineage.mode=direct}.</b>
 *       The existing Purview incremental-sync and full-sync services
 *       continue to operate normally and independently.</li>
 * </ul>
 *
 * <p>Enabling lineage requires an <b>explicit configuration change</b>
 * by an administrator (set {@code lineage.mode} to {@code direct} or
 * {@code journaled} via the Settings UI or REST API).
 *
 * <p><b>Rationale:</b> automatic mode promotion would surprise
 * operators who have not evaluated the lineage feature and would
 * incur unexpected resource costs (CouchDB storage for journaled,
 * network I/O for direct). Opt-in is safer.
 */
@Component
public class LineageConfig {

    private static final Logger logger = LoggerFactory.getLogger(LineageConfig.class);

    @Autowired(required = false)
    private PropertyManager propertyManager;

    @Value("${lineage.mode:disabled}")
    private String mode;

    @Value("${lineage.targets:}")
    private String targets;

    @Value("${lineage.retention.days:90}")
    private int retentionDays;

    @Value("${lineage.capture.version-events:false}")
    private boolean captureVersionEvents;

    @Value("${lineage.capture.generic-relationships:false}")
    private boolean captureGenericRelationships;

    @Value("${lineage.purge.cron:}")
    private String purgeCron;

    @Value("${lineage.snapshot.max-name-length:512}")
    private int snapshotMaxNameLength;

    @Value("${lineage.snapshot.max-path-length:2048}")
    private int snapshotMaxPathLength;

    @Value("${lineage.snapshot.capture-path:true}")
    private boolean snapshotCapturePath;

    // --- Backlog management (journaled mode) ---

    @Value("${lineage.backlog.max-retry-count:5}")
    private int backlogMaxRetryCount;

    @Value("${lineage.backlog.max-retry-age-hours:72}")
    private int backlogMaxRetryAgeHours;

    @Value("${lineage.backlog.max-docs:10000}")
    private int backlogMaxDocs;

    @Value("${lineage.backlog.max-size-mb:100}")
    private int backlogMaxSizeMb;

    // --- Projection loop settings (journaled mode) ---

    @Value("${lineage.projection.poll-interval-seconds:10}")
    private int projectionPollIntervalSeconds;

    @Value("${lineage.projection.batch-size:50}")
    private int projectionBatchSize;

    @Value("${lineage.projection.stale-threshold-minutes:5}")
    private int projectionStaleThresholdMinutes;

    // --- Leader election (multi-node deployments) ---

    @Value("${lineage.leader-election.enabled:false}")
    private boolean leaderElectionEnabled;

    @Value("${lineage.leader-election.heartbeat-seconds:15}")
    private int leaderHeartbeatSeconds;

    @Value("${lineage.leader-election.ttl-seconds:60}")
    private int leaderTtlSeconds;

    /** Returns the instance-wide default lineage mode. */
    public LineageMode getMode() {
        return LineageMode.fromString(readDynamic("lineage.mode", mode));
    }

    /**
     * Returns the effective lineage mode for a specific repository.
     *
     * <p>Resolution (highest priority wins):
     * <ol>
     *   <li><b>Priority 1:</b> {@code lineage.mode.override.{repositoryId}}
     *       from PropertyManager (CouchDB). Wins if non-null and non-blank.</li>
     *   <li><b>Priority 2:</b> {@code lineage.mode} from PropertyManager
     *       (CouchDB). Wins if non-null.</li>
     *   <li><b>Priority 3:</b> {@code @Value} startup default
     *       ({@code disabled}).</li>
     * </ol>
     *
     * <p>All resolution is dynamic — no restart required. See class-level
     * javadoc for cluster consistency implications.
     *
     * @param repositoryId the repository to check (null/empty falls back
     *                     to global default)
     * @return the effective mode (never null; invalid values fall back to
     *         {@link LineageMode#DISABLED})
     */
    public LineageMode getModeForRepository(String repositoryId) {
        if (repositoryId != null && !repositoryId.isEmpty()) {
            String override = readDynamic("lineage.mode.override." + repositoryId, null);
            if (override != null && !override.isBlank()) {
                return LineageMode.fromString(override);
            }
        }
        return getMode();
    }

    public List<String> getTargets() {
        String raw = trimToEmpty(readDynamic("lineage.targets", targets));
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public int getRetentionDays() {
        return readDynamicInt("lineage.retention.days", retentionDays);
    }

    public boolean isCaptureVersionEvents() {
        return readDynamicBoolean("lineage.capture.version-events", captureVersionEvents);
    }

    public boolean isCaptureGenericRelationships() {
        return readDynamicBoolean("lineage.capture.generic-relationships", captureGenericRelationships);
    }

    public String getPurgeCron() {
        return trimToEmpty(readDynamic("lineage.purge.cron", purgeCron));
    }

    /** Maximum character length for the {@code name} snapshot attribute. Default: 512. */
    public int getSnapshotMaxNameLength() {
        return readDynamicInt("lineage.snapshot.max-name-length", snapshotMaxNameLength);
    }

    /** Maximum character length for the {@code folderPath} snapshot attribute. Default: 2048. */
    public int getSnapshotMaxPathLength() {
        return readDynamicInt("lineage.snapshot.max-path-length", snapshotMaxPathLength);
    }

    /**
     * Whether to capture folder path in snapshot attributes.
     * When {@code false}, the {@code folderPath} attribute is omitted entirely.
     */
    public boolean isSnapshotCapturePath() {
        return readDynamicBoolean("lineage.snapshot.capture-path", snapshotCapturePath);
    }

    // --- Backlog management getters (journaled mode only) ---

    /**
     * Maximum number of publish retries per event-target pair before
     * auto-discard ({@link LineagePublishStatus#DISCARDED}).
     *
     * <p>When a projector transitions an event to FAILED and the
     * cumulative failure count for that (eventId, target) exceeds this
     * threshold, the projector must transition the status to DISCARDED
     * instead of leaving it in FAILED. This prevents infinite retry
     * loops for permanently unreachable or misconfigured targets.
     *
     * <p>Default: 5. Set to 0 to disable retry-count-based discard
     * (age-based discard still applies).
     */
    public int getBacklogMaxRetryCount() {
        return readDynamicInt("lineage.backlog.max-retry-count", backlogMaxRetryCount);
    }

    /**
     * Maximum age (in hours) of a non-terminal event before auto-discard.
     *
     * <p>If an event's {@code occurredAt} is older than this threshold
     * and any target is still in PENDING or FAILED, the projector must
     * transition those targets to DISCARDED. This ensures that even
     * events that have not exhausted their retry count are eventually
     * cleaned up if the target outage outlasts the age window.
     *
     * <p>Default: 72 (3 days). Set to 0 to disable age-based discard
     * (retry-count-based discard still applies).
     */
    public int getBacklogMaxRetryAgeHours() {
        return readDynamicInt("lineage.backlog.max-retry-age-hours", backlogMaxRetryAgeHours);
    }

    /**
     * Maximum number of non-terminal event documents in the journal.
     *
     * <p>When the count of events with at least one non-terminal target
     * exceeds this limit, the projector must auto-discard the oldest
     * non-terminal events (by {@code occurredAt}) until the count drops
     * below the threshold. This is a safety valve against unbounded
     * storage growth during prolonged target outages.
     *
     * <p>Default: 10000. Set to 0 to disable document-count-based discard.
     */
    public int getBacklogMaxDocs() {
        return readDynamicInt("lineage.backlog.max-docs", backlogMaxDocs);
    }

    /**
     * Maximum estimated backlog size in megabytes.
     *
     * <p>Rough estimate based on document count × average event size.
     * When exceeded, the projector applies the same oldest-first discard
     * as {@link #getBacklogMaxDocs()}.
     *
     * <p>Default: 100 (MB). Set to 0 to disable size-based discard.
     */
    public int getBacklogMaxSizeMb() {
        return readDynamicInt("lineage.backlog.max-size-mb", backlogMaxSizeMb);
    }


    // --- Projection loop getters (journaled mode only) ---

    /** Poll interval in seconds for the projection loop. Default: 10. */
    public int getProjectionPollIntervalSeconds() {
        return readDynamicInt("lineage.projection.poll-interval-seconds", projectionPollIntervalSeconds);
    }

    /** Maximum events per projection batch. Default: 50. */
    public int getProjectionBatchSize() {
        return readDynamicInt("lineage.projection.batch-size", projectionBatchSize);
    }

    /** Minutes before a PROJECTING event is considered stale. Default: 5. */
    public int getProjectionStaleThresholdMinutes() {
        return readDynamicInt("lineage.projection.stale-threshold-minutes", projectionStaleThresholdMinutes);
    }

    // --- Leader election getters (multi-node deployments) ---

    /** Whether CouchDB CAS-based leader election is enabled. Default: false (single-node). */
    public boolean isLeaderElectionEnabled() {
        return readDynamicBoolean("lineage.leader-election.enabled", leaderElectionEnabled);
    }

    /** Heartbeat interval in seconds for the leader election. Default: 15. */
    public int getLeaderHeartbeatSeconds() {
        return readDynamicInt("lineage.leader-election.heartbeat-seconds", leaderHeartbeatSeconds);
    }

    /** TTL in seconds before a leader's heartbeat is considered expired. Default: 60. */
    public int getLeaderTtlSeconds() {
        return readDynamicInt("lineage.leader-election.ttl-seconds", leaderTtlSeconds);
    }

    // --- Cached emitter (lazy-init, mode-change tracking) ---
    private volatile LineageEmitter cachedEmitter;
    private volatile LineageMode cachedMode;

    /**
     * Creates the appropriate {@link LineageEmitter} for the current instance-wide mode.
     *
     * <p>This is the single factory for the emission pipeline:
     * <ul>
     *   <li>{@code DISABLED}  → {@link NoopLineageEmitter} (zero I/O, no DB created)</li>
     *   <li>{@code DIRECT}    → {@link DirectLineageEmitter} (publish to sinks, no local storage, no DB created)</li>
     *   <li>{@code JOURNALED} → {@link JournaledLineageEmitter} (store in CouchDB + project;
     *       journal DB is provisioned only when this mode is active — see
     *       {@link LineageJournalStore} "Database Provisioning Guard")</li>
     * </ul>
     *
     * <p>For per-repository mode override, callers should check
     * {@link #getModeForRepository(String)} before emitting.
     *
     * @param store the journal store (used only in JOURNALED mode)
     * @return the emitter for the current lineage mode
     */
    public LineageEmitter createEmitter(LineageJournalStore store) {
        return createEmitter(store, List.of());
    }

    /**
     * Creates the appropriate {@link LineageEmitter} with target sinks.
     *
     * @param store the journal store (used only in JOURNALED mode)
     * @param sinks target sinks for direct mode async dispatch
     * @return the emitter for the current lineage mode
     */
    public LineageEmitter createEmitter(LineageJournalStore store, List<LineageTargetSink> sinks) {
        return switch (getMode()) {
            case DISABLED  -> new NoopLineageEmitter();
            case DIRECT    -> new DirectLineageEmitter(this, sinks);
            case JOURNALED -> new JournaledLineageEmitter(store, this);
        };
    }

    /**
     * Creates an emitter for an explicitly specified mode.
     *
     * <p>Use this when the caller has already resolved the effective mode
     * via {@link #getModeForRepository(String)} and needs an emitter that
     * matches that specific mode (which may differ from the global default).
     *
     * @param mode  the resolved lineage mode
     * @param store the journal store (used only in JOURNALED mode)
     * @return the emitter for the given mode
     */
    public LineageEmitter createEmitterForMode(LineageMode mode, LineageJournalStore store) {
        return createEmitterForMode(mode, store, List.of());
    }

    /**
     * Creates an emitter for an explicitly specified mode with target sinks.
     *
     * @param mode  the resolved lineage mode
     * @param store the journal store (used only in JOURNALED mode)
     * @param sinks target sinks for direct mode async dispatch
     * @return the emitter for the given mode
     */
    public LineageEmitter createEmitterForMode(LineageMode mode, LineageJournalStore store,
                                                List<LineageTargetSink> sinks) {
        return switch (mode) {
            case DISABLED  -> new NoopLineageEmitter();
            case DIRECT    -> new DirectLineageEmitter(this, sinks);
            case JOURNALED -> new JournaledLineageEmitter(store, this);
        };
    }

    /**
     * Returns a cached {@link LineageEmitter}, creating or replacing it
     * when the effective mode changes.
     *
     * <p>This method is the preferred way to obtain an emitter at emit
     * points (REST resources, schedulers). It avoids creating a new
     * instance on every call while still tracking dynamic mode changes
     * made through {@link PropertyManager}.
     *
     * @param store the journal store (passed through to {@link #createEmitter})
     * @return the cached emitter for the current mode (never null)
     */
    public LineageEmitter getEmitter(LineageJournalStore store) {
        return getEmitter(store, List.of());
    }

    /**
     * Returns a cached {@link LineageEmitter} with target sinks.
     *
     * @param store the journal store
     * @param sinks target sinks for direct mode async dispatch
     * @return the cached emitter for the current mode (never null)
     */
    public LineageEmitter getEmitter(LineageJournalStore store, List<LineageTargetSink> sinks) {
        LineageMode current = getMode();
        if (cachedEmitter == null || current != cachedMode) {
            cachedEmitter = createEmitter(store, sinks);
            cachedMode = current;
        }
        return cachedEmitter;
    }

    private String readDynamic(String key, String startupDefault) {
        if (propertyManager != null) {
            String value = propertyManager.readValue(key);
            if (value != null) {
                return value;
            }
        }
        return startupDefault;
    }

    private boolean readDynamicBoolean(String key, boolean startupDefault) {
        String value = readDynamic(key, null);
        if (value == null) {
            return startupDefault;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private int readDynamicInt(String key, int startupDefault) {
        String value = readDynamic(key, null);
        if (value == null || value.trim().isEmpty()) {
            return startupDefault;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer for key '{}': '{}', using default {}", key, value, startupDefault);
            return startupDefault;
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
