package jp.aegif.nemaki.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-side registry of SAML AuthnRequest IDs that this SP has issued
 * and is awaiting a Response for, bound to an HttpOnly browser cookie.
 *
 * <p><b>Trust model (post-review redesign).</b> An earlier revision
 * exposed a public registration endpoint that accepted any client-supplied
 * ID, which made strict {@code InResponseTo} mode meaningless: an
 * attacker could simply pre-register the ID embedded in a captured SAML
 * Response and then submit it. The current model:
 *
 * <ul>
 *   <li>{@link #issue()} is called only by the server-side
 *       {@code SamlInitiateServlet} on behalf of an authenticated
 *       (well, browser-bound) user. It generates BOTH a random
 *       {@code authnRequestId} and a random opaque {@code bindingToken}
 *       and stores the binding {bindingToken → authnRequestId}.</li>
 *   <li>The {@code bindingToken} is returned to the caller, set as an
 *       HttpOnly + Secure + SameSite=Lax cookie on the browser, and
 *       presented back to the SP automatically when the SAML Response
 *       arrives.</li>
 *   <li>{@link #consume(String, String)} atomically checks that the
 *       cookie's bindingToken maps to exactly the {@code InResponseTo}
 *       value carried by the Response and removes the entry on hit.
 *       An attacker without the cookie cannot satisfy this check.</li>
 * </ul>
 *
 * <p>Single-tenant in-memory store, per-JVM. Multi-replica deployments
 * would need a shared backing store or sticky sessions; this is acceptable
 * for the current single-replica deployment guidance.
 *
 * <p>Time arithmetic is in milliseconds for unambiguous TTL semantics.
 */
public final class SamlAuthnRequestRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SamlAuthnRequestRegistry.class);

    /** Default TTL for an outstanding AuthnRequest. */
    static final long DEFAULT_TTL_SECONDS = 15 * 60L;

    /** Hard cap on outstanding entries. */
    static final int MAX_ENTRIES = 10_000;

    /** Cookie name for the SP-issued binding token. */
    public static final String BINDING_COOKIE_NAME = "NEMAKI_SAML_BIND";

    private static final SamlAuthnRequestRegistry INSTANCE = new SamlAuthnRequestRegistry(DEFAULT_TTL_SECONDS);

    public static SamlAuthnRequestRegistry getInstance() {
        return INSTANCE;
    }

    private final long ttlMillis;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> outstanding = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    SamlAuthnRequestRegistry(long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000L;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SamlAuthnRequestRegistry-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Server-generated AuthnRequest issuance. Returns an {@link Issued}
     * value carrying both the AuthnRequest ID (to embed in the
     * {@code <samlp:AuthnRequest ID="...">} element) and the opaque
     * bindingToken (to set as the HttpOnly cookie that ties the
     * subsequent Response back to this issuance).
     *
     * @return Issued, or null if the registry is at capacity (back-pressure).
     */
    public Issued issue() {
        long now = System.currentTimeMillis();
        if (outstanding.size() >= MAX_ENTRIES) {
            sweep();
            if (outstanding.size() >= MAX_ENTRIES) {
                logger.warn("SAML AuthnRequest registry full ({} entries); refusing new issuance", outstanding.size());
                return null;
            }
        }
        String authnRequestId = "_" + UUID.randomUUID();
        // 32 random bytes → 43 base64url chars. Long enough to make
        // guessing impractical even at thousands of attempts/sec.
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String bindingToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        outstanding.put(bindingToken, new Entry(authnRequestId, now + ttlMillis));
        return new Issued(authnRequestId, bindingToken, ttlMillis);
    }

    /**
     * Atomically validate that the cookie-supplied {@code bindingToken}
     * maps to {@code expectedAuthnRequestId} (the InResponseTo value
     * carried by the SAML Response) and remove the entry on hit.
     * Removal-on-hit prevents a single AuthnRequest from validating
     * two different Responses (defence in depth on top of
     * {@link SamlReplayCache}).
     *
     * @return true on success — the binding existed, matched, and was
     *         not yet expired. false otherwise. The expired/mismatched
     *         entry is also removed in either case to bound memory.
     */
    public boolean consume(String bindingToken, String expectedAuthnRequestId) {
        if (bindingToken == null || bindingToken.isBlank()
                || expectedAuthnRequestId == null || expectedAuthnRequestId.isBlank()) {
            return false;
        }
        Entry entry = outstanding.remove(bindingToken);
        if (entry == null) {
            return false;
        }
        if (entry.expiryMillis <= System.currentTimeMillis()) {
            return false;
        }
        return entry.authnRequestId.equals(expectedAuthnRequestId);
    }

    /**
     * Read-only counterpart to {@link #consume(String, String)}: returns
     * whether the binding currently maps to the expected AuthnRequest ID,
     * without removing the entry. Used during the verifier's pre-flight
     * phase so a downstream failure (user lookup, token issuance) can
     * abort without burning the binding for the legitimate user.
     *
     * <p>The actual atomic check-and-remove must still happen via
     * {@link #consume(String, String)} after the entire flow has succeeded.
     */
    public boolean peekMatches(String bindingToken, String expectedAuthnRequestId) {
        if (bindingToken == null || bindingToken.isBlank()
                || expectedAuthnRequestId == null || expectedAuthnRequestId.isBlank()) {
            return false;
        }
        Entry entry = outstanding.get(bindingToken);
        if (entry == null) {
            return false;
        }
        if (entry.expiryMillis <= System.currentTimeMillis()) {
            return false;
        }
        return entry.authnRequestId.equals(expectedAuthnRequestId);
    }

    void sweep() {
        long now = System.currentTimeMillis();
        outstanding.entrySet().removeIf(e -> e.getValue().expiryMillis <= now);
    }

    /** Visible for tests. */
    int size() {
        return outstanding.size();
    }

    /** Visible for tests — drop everything. */
    void clear() {
        outstanding.clear();
    }

    /** Issuance result returned from {@link #issue()}. */
    public static final class Issued {
        private final String authnRequestId;
        private final String bindingToken;
        private final long ttlMillis;

        Issued(String authnRequestId, String bindingToken, long ttlMillis) {
            this.authnRequestId = authnRequestId;
            this.bindingToken = bindingToken;
            this.ttlMillis = ttlMillis;
        }

        public String getAuthnRequestId() { return authnRequestId; }
        public String getBindingToken()   { return bindingToken; }
        public long   getTtlMillis()      { return ttlMillis; }
    }

    private static final class Entry {
        final String authnRequestId;
        final long   expiryMillis;
        Entry(String authnRequestId, long expiryMillis) {
            this.authnRequestId = authnRequestId;
            this.expiryMillis   = expiryMillis;
        }
    }
}
