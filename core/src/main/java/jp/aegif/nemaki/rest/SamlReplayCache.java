package jp.aegif.nemaki.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Anti-replay cache for SAML Response and Assertion IDs.
 *
 * <p>SAML Responses and Assertions are signed and time-bound, but until
 * RC13 there was no server-side cache of consumed IDs. An attacker who
 * captured a Response could replay it within the validity window (5–15
 * min) from any IP to mint a session token for the victim. This cache
 * closes that window by remembering the IDs that have already been
 * accepted.
 *
 * <p><b>Two-phase commit semantics.</b> Earlier revisions exposed a
 * single {@code isReplayAndRecord(id)} that committed the ID at lookup
 * time. That made the verifier vulnerable to a different attack: an
 * attacker submits a captured Response that fails a *later* check
 * (e.g. wrong InResponseTo), and the legitimate user's subsequent
 * Response — carrying the same Response/Assertion IDs — is then
 * permanently rejected as a replay. The current API splits the
 * operation:
 * <ul>
 *   <li>{@link #isAlreadySeen(String)} — read-only check, used early
 *       to fail-fast on obvious duplicates;</li>
 *   <li>{@link #recordIfNew(String)} — atomic commit, called only
 *       after every other validation has succeeded, returns false if
 *       a concurrent commit beat us (still treated as replay).</li>
 * </ul>
 *
 * <p>Time arithmetic uses {@link System#currentTimeMillis()} for
 * millisecond precision so the deny boundary is unambiguous in tests
 * and so a low TTL does not collide with seconds-resolution rounding.
 *
 * <p>Entries auto-expire after {@link #DEFAULT_TTL_SECONDS} (15 min,
 * longer than typical SAML validity). A daemon thread sweeps every
 * minute. {@link #MAX_ENTRIES} caps memory under abuse.
 */
public final class SamlReplayCache {

    private static final Logger logger = LoggerFactory.getLogger(SamlReplayCache.class);

    /** Default TTL — must exceed the IdP's NotOnOrAfter window. */
    static final long DEFAULT_TTL_SECONDS = 15 * 60L;

    /** Maximum entries — bounds memory if an IdP misbehaves. */
    static final int MAX_ENTRIES = 100_000;

    private static final SamlReplayCache INSTANCE = new SamlReplayCache(DEFAULT_TTL_SECONDS);

    public static SamlReplayCache getInstance() {
        return INSTANCE;
    }

    private final long ttlMillis;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    SamlReplayCache(long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000L;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SamlReplayCache-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Read-only test: is this ID currently recorded as already-consumed?
     * Use this early in verification to short-circuit obvious replays
     * BEFORE any other validator may consume side-effectful state.
     *
     * @return true if the ID was already consumed AND has not yet expired.
     */
    public boolean isAlreadySeen(String id) {
        if (id == null || id.isBlank()) {
            // Defensive: an unidentified Response/Assertion is suspicious.
            return true;
        }
        Long expiry = seen.get(id);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    /**
     * Atomically record this ID as consumed. Call ONLY after every
     * other validation has succeeded (signature, conditions, audience,
     * InResponseTo, ...) so a failure in any later step does not lock
     * out a legitimate retry.
     *
     * @return true if the ID was fresh (commit succeeded — caller may
     *         accept). false if a concurrent commit beat us (caller
     *         must treat as a replay).
     */
    public boolean recordIfNew(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expiry = now + ttlMillis;

        // Bound memory under abuse: sweep before inserting if we are over cap.
        if (seen.size() > MAX_ENTRIES) {
            sweep();
        }

        Long previous = seen.putIfAbsent(id, expiry);
        if (previous == null) {
            return true; // first use — committed
        }
        if (previous <= now) {
            // Expired entry was lingering — try to overwrite atomically.
            // If a concurrent caller already overwrote, treat as replay.
            return seen.replace(id, previous, expiry);
        }
        return false; // active duplicate — caller must reject as replay
    }

    /** Remove expired entries. Called periodically and whenever the cache is large. */
    void sweep() {
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(e -> e.getValue() <= now);
    }

    /** Visible for tests. */
    int size() {
        return seen.size();
    }

    /** Visible for tests — drop everything. */
    void clear() {
        seen.clear();
    }
}
