package jp.aegif.nemaki.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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
 * captured a Response (e.g. via XSS, an open-relay redirect, or a TLS
 * compromise) could replay it within the validity window
 * (commonly 5–15 minutes) from any IP to mint a session token for the
 * victim. This cache closes that window by remembering the IDs that
 * have already been accepted.
 *
 * <p>The cache is in-memory and per-JVM. For multi-replica deployments
 * with a shared SP entity, an attacker who beats the original Response
 * to a *different* replica could still replay; the LeaderElection /
 * sticky-session story for SAML is a follow-up.
 *
 * <p>Entries auto-expire after {@link #DEFAULT_TTL_SECONDS} (15 minutes —
 * longer than the typical SAML validity window so that a Response cached
 * here remains blocked even if its NotOnOrAfter has passed). A daemon
 * thread sweeps expired entries every minute.
 */
public final class SamlReplayCache {

    private static final Logger logger = LoggerFactory.getLogger(SamlReplayCache.class);

    /** Default TTL — must exceed the IdP's NotOnOrAfter window. */
    static final long DEFAULT_TTL_SECONDS = 15 * 60L; // 15 min

    /** Maximum entries — bounds memory if an IdP misbehaves. */
    static final int MAX_ENTRIES = 100_000;

    private static final SamlReplayCache INSTANCE = new SamlReplayCache(DEFAULT_TTL_SECONDS);

    public static SamlReplayCache getInstance() {
        return INSTANCE;
    }

    private final long ttlSeconds;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    SamlReplayCache(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SamlReplayCache-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Atomically check whether the SAML element ID has been seen. If not,
     * record it and return false (caller should accept the assertion).
     * If already present and not yet expired, return true (caller must
     * reject as a replay).
     *
     * @param id  SAML Response ID, Assertion ID, or any other unique
     *            identifier the caller wants tracked. Must be non-blank.
     * @return true if this ID was already consumed (REPLAY); false on first use
     */
    public boolean isReplayAndRecord(String id) {
        if (id == null || id.isBlank()) {
            // Defensive: an unidentified Response/Assertion is suspicious;
            // refuse to accept rather than allowing an un-trackable token.
            logger.warn("SAML replay check called with blank id — treating as replay (deny)");
            return true;
        }
        long now = Instant.now().getEpochSecond();
        long expiry = now + ttlSeconds;

        // Bound memory under abuse: if the cache grew past MAX_ENTRIES,
        // sweep before inserting to give expired entries a chance to drain.
        if (seen.size() > MAX_ENTRIES) {
            sweep();
        }

        Long previous = seen.putIfAbsent(id, expiry);
        if (previous == null) {
            return false; // first use
        }
        if (previous < now) {
            // Expired entry was lingering — overwrite and accept.
            seen.put(id, expiry);
            return false;
        }
        return true; // active duplicate — replay
    }

    /** Remove expired entries. Called periodically and whenever the cache is large. */
    void sweep() {
        long now = Instant.now().getEpochSecond();
        seen.entrySet().removeIf(e -> e.getValue() < now);
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
