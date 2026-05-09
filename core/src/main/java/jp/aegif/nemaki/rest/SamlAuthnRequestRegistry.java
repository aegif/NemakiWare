package jp.aegif.nemaki.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * In-memory registry of SAML AuthnRequest IDs that this SP has issued and
 * is awaiting a Response for.
 *
 * <p>The companion to {@link SamlReplayCache}: while the replay cache
 * enforces "do not consume the same Response twice", this registry
 * enforces "do not consume a Response whose InResponseTo is unknown to
 * this SP" — i.e. it ties the SAML Response back to a request the SP
 * actually issued, blocking unsolicited Response injection attacks.
 *
 * <p>Strict enforcement is opt-in via {@code saml.require.inResponseTo=true}
 * because the React-UI-driven SP-initiated flow has historically generated
 * the AuthnRequest entirely client-side. When opt-in is enabled, the UI
 * must register the request ID via {@code POST /rest/all/saml/register-request}
 * before redirecting to the IdP.
 *
 * <p>TTL defaults to 15 minutes — longer than the typical IdP NotOnOrAfter
 * window (5–10 min) so that legitimate slow logins are not invalidated, but
 * short enough to limit memory growth under abuse. A background sweeper
 * removes expired entries every minute. The {@link #MAX_ENTRIES} cap
 * guards against an attacker spamming the register endpoint to exhaust
 * memory.
 */
public final class SamlAuthnRequestRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SamlAuthnRequestRegistry.class);

    /** TTL for an outstanding AuthnRequest ID. */
    static final long DEFAULT_TTL_SECONDS = 15 * 60L; // 15 min

    /** Hard cap on outstanding entries. */
    static final int MAX_ENTRIES = 100_000;

    /**
     * Permitted characters in a SAML ID. The spec (xsd:ID, derived from NCName)
     * forbids anything but letters, digits, '_', '-', '.' and ':'. We intentionally
     * exclude ':' to keep IDs simple — the React UI generates UUID-shaped IDs
     * prefixed with '_'. Length cap defends against memory abuse.
     */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9._\\-]{0,255}$");

    private static final SamlAuthnRequestRegistry INSTANCE = new SamlAuthnRequestRegistry(DEFAULT_TTL_SECONDS);

    public static SamlAuthnRequestRegistry getInstance() {
        return INSTANCE;
    }

    private final long ttlSeconds;
    private final Map<String, Long> outstanding = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    SamlAuthnRequestRegistry(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SamlAuthnRequestRegistry-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Validate the ID shape and record it as outstanding.
     *
     * @return true if the ID was accepted (valid shape + cache had room),
     *         false if the ID is malformed or the cap was reached.
     */
    public boolean register(String requestId) {
        if (requestId == null || !ID_PATTERN.matcher(requestId).matches()) {
            logger.warn("Refusing to register SAML AuthnRequest ID with invalid shape: {}",
                    requestId == null ? "null" : (requestId.length() > 64 ? requestId.substring(0, 64) + "..." : requestId));
            return false;
        }
        if (outstanding.size() > MAX_ENTRIES) {
            sweep();
            if (outstanding.size() > MAX_ENTRIES) {
                logger.warn("SAML AuthnRequest registry full ({} entries); rejecting new registrations to bound memory", outstanding.size());
                return false;
            }
        }
        long expiry = Instant.now().getEpochSecond() + ttlSeconds;
        outstanding.put(requestId, expiry);
        return true;
    }

    /**
     * Atomically check that {@code inResponseTo} matches an outstanding
     * AuthnRequest and consume the entry on success. Consuming on hit
     * prevents the same AuthnRequest from being reused to validate two
     * different Responses (defence-in-depth on top of {@link SamlReplayCache}).
     *
     * @return true if the ID was outstanding and not yet expired.
     */
    public boolean consume(String inResponseTo) {
        if (inResponseTo == null || inResponseTo.isBlank()) {
            return false;
        }
        Long expiry = outstanding.remove(inResponseTo);
        if (expiry == null) {
            return false;
        }
        return expiry >= Instant.now().getEpochSecond();
    }

    void sweep() {
        long now = Instant.now().getEpochSecond();
        outstanding.entrySet().removeIf(e -> e.getValue() < now);
    }

    /** Visible for tests. */
    int size() {
        return outstanding.size();
    }

    /** Visible for tests — drop everything. */
    void clear() {
        outstanding.clear();
    }
}
