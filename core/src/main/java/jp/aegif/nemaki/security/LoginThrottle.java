package jp.aegif.nemaki.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force throttle for password logins.
 *
 * <p>Keyed per (repository, user, client-IP) so that an attacker hammering one
 * account from one IP is locked out without letting them lock out a legitimate
 * user globally (the IP is part of the key). Only <em>failures</em> are counted;
 * a successful login clears the counter, so correct credentials are never
 * throttled. During a lockout the login is denied before the password is even
 * checked (fail-closed).
 *
 * <p>State is JVM-local (like the SAML replay cache and other single-replica
 * subsystems — see docs/MULTI-REPLICA-DEPLOYMENT.md). Tunable via system
 * properties; enabled by default with generous limits so normal use and the
 * test suites (which authenticate with correct credentials) are unaffected:
 * <ul>
 *   <li>{@code nemakiware.security.loginThrottle.enabled} (default true)</li>
 *   <li>{@code nemakiware.security.loginThrottle.maxFailures} (default 15)</li>
 *   <li>{@code nemakiware.security.loginThrottle.windowSeconds} (default 300)</li>
 *   <li>{@code nemakiware.security.loginThrottle.lockoutSeconds} (default 900)</li>
 * </ul>
 */
public final class LoginThrottle {

    private static final String P = "nemakiware.security.loginThrottle.";

    private final boolean enabled;
    private final int maxFailures;
    private final long windowMs;
    private final long lockoutMs;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private static final class Attempt {
        int failures;
        long windowStart;
        long lockedUntil;
    }

    /** Production constructor: reads config from system properties. */
    public LoginThrottle() {
        this(
            !"false".equalsIgnoreCase(System.getProperty(P + "enabled", "true")),
            intProp(P + "maxFailures", 15),
            longProp(P + "windowSeconds", 300L) * 1000L,
            longProp(P + "lockoutSeconds", 900L) * 1000L);
    }

    /** Test/explicit constructor. */
    public LoginThrottle(boolean enabled, int maxFailures, long windowMs, long lockoutMs) {
        this.enabled = enabled;
        this.maxFailures = Math.max(1, maxFailures);
        this.windowMs = Math.max(1L, windowMs);
        this.lockoutMs = Math.max(1L, lockoutMs);
    }

    /** True if this key is currently locked out (deny before checking the password). */
    public boolean isBlocked(String key) {
        return isBlocked(key, System.currentTimeMillis());
    }

    boolean isBlocked(String key, long now) {
        if (!enabled || key == null) {
            return false;
        }
        Attempt a = attempts.get(key);
        return a != null && a.lockedUntil > now;
    }

    /** Record a failed login; locks the key once maxFailures is reached within the window. */
    public void recordFailure(String key) {
        recordFailure(key, System.currentTimeMillis());
    }

    void recordFailure(String key, long now) {
        if (!enabled || key == null) {
            return;
        }
        attempts.compute(key, (k, a) -> {
            if (a == null) {
                a = new Attempt();
                a.windowStart = now;
            }
            // Reset the counting window once it has elapsed (and we're not locked).
            if (a.lockedUntil <= now && now - a.windowStart > windowMs) {
                a.failures = 0;
                a.windowStart = now;
            }
            a.failures++;
            if (a.failures >= maxFailures) {
                a.lockedUntil = now + lockoutMs;
            }
            return a;
        });
    }

    /** Record a successful login; clears any failure state for the key. */
    public void recordSuccess(String key) {
        if (!enabled || key == null) {
            return;
        }
        attempts.remove(key);
    }

    /** Number of tracked keys (for tests / diagnostics). */
    public int trackedKeys() {
        return attempts.size();
    }

    private static int intProp(String name, int def) {
        try {
            String v = System.getProperty(name);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static long longProp(String name, long def) {
        try {
            String v = System.getProperty(name);
            return v == null ? def : Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }
}
