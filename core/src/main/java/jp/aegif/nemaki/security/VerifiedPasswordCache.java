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
package jp.aegif.nemaki.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Remembers, for a few seconds, that a password was already verified.
 *
 * <h2>Why this exists</h2>
 *
 * <p>CMIS clients send Basic credentials on <em>every</em> request — that is how the protocol
 * works, there is no session. Without this cache the server re-runs BCrypt each time. Measured
 * on the benchmark stack, one verification of a cost-12 hash takes 225 ms, and a profile of a
 * loaded server put <strong>96.8% of all CPU samples inside jbcrypt</strong>: a document
 * repository was spending its machine on re-deriving a key it had already derived a moment
 * earlier. Caching the verdict for 30 seconds raised read throughput from 40 to 104 rps.
 *
 * <p>Lowering the cost factor would also have "fixed" it, and is the wrong fix — the cost factor
 * is what makes a stolen hash expensive to attack. What is wasteful is repeating the work, not
 * the work itself.
 *
 * <h2>What makes it safe</h2>
 *
 * <ul>
 * <li><b>The stored hash is part of the key.</b> Change a password and the stored hash changes,
 *     so every cached entry for that account stops matching — no explicit invalidation to forget
 *     and no window where the old password still works. Disabling an account works the same way
 *     as it does today, because the allowedAuthMethods gate runs before this cache is consulted.
 * <li><b>Only successes are cached.</b> A wrong password always pays for a real BCrypt, so
 *     {@link LoginThrottle} still sees every failure and brute force is not accelerated.
 * <li><b>Keys are HMACs under a per-JVM random secret</b>, so the password is not recoverable
 *     from what is held and a key from one process means nothing in another. Note what this does
 *     NOT claim: the HMAC input includes the plaintext, so an attacker who can both read the heap
 *     and guess candidate passwords can confirm a guess with one HMAC instead of one BCrypt. That
 *     is a real reduction against a heap-reading attacker — but such an attacker already has the
 *     live {@code CallContext} passwords of every in-flight request, so it is not the weak point.
 * <li><b>Bounded.</b> Over capacity the cache drops expired entries and, failing that, empties
 *     itself. Losing an entry costs one BCrypt, so the failure mode is slow, never wrong.
 * </ul>
 *
 * <p>The TTL <em>is</em> the revocation window: for at most that long after a password changes
 * elsewhere, a replica that already verified the old one may still accept it. State is JVM-local,
 * so with N replicas the window is per-replica — see docs/MULTI-REPLICA-DEPLOYMENT.md.
 *
 * <p>Tunable by system property:
 * <ul>
 *   <li>{@code nemakiware.security.passwordCache.seconds} (default 30; {@code 0} disables)</li>
 *   <li>{@code nemakiware.security.passwordCache.maxEntries} (default 10000)</li>
 * </ul>
 */
public final class VerifiedPasswordCache {

    private static final String P = "nemakiware.security.passwordCache.";

    private final long ttlMs;
    private final int maxEntries;
    private final byte[] secret;

    /** Key → expiry (epoch millis). Presence of an unexpired key means "verified". */
    private final Map<String, Long> verified = new ConcurrentHashMap<>();

    /** Production constructor: reads config from system properties. */
    public VerifiedPasswordCache() {
        this(longProp(P + "seconds", 30L) * 1000L, intProp(P + "maxEntries", 10000));
    }

    /** Test/explicit constructor. A {@code ttlMs} of zero or less disables the cache. */
    public VerifiedPasswordCache(long ttlMs, int maxEntries) {
        this.ttlMs = ttlMs;
        this.maxEntries = Math.max(1, maxEntries);
        byte[] s = new byte[32];
        new SecureRandom().nextBytes(s);
        this.secret = s;
    }

    public boolean isEnabled() {
        return ttlMs > 0L;
    }

    /**
     * True if this exact (account, stored hash, password) combination was verified recently.
     * A false answer means "verify it properly" — never "reject".
     */
    public boolean isVerified(String repositoryId, String userId, String storedHash, String password) {
        if (!isEnabled() || isBlank(storedHash) || isBlank(password)) {
            return false;
        }
        Long expiry = verified.get(key(repositoryId, userId, storedHash, password));
        return expiry != null && expiry > System.currentTimeMillis();
    }

    /** Records a verification that actually ran and succeeded. */
    public void remember(String repositoryId, String userId, String storedHash, String password) {
        if (!isEnabled() || isBlank(storedHash) || isBlank(password)) {
            return;
        }
        if (verified.size() >= maxEntries) {
            evict();
        }
        verified.put(key(repositoryId, userId, storedHash, password),
                System.currentTimeMillis() + ttlMs);
    }

    /** Drops everything. Used when the process wants to force re-verification. */
    public void clear() {
        verified.clear();
    }

    /** Entries currently held — for tests and diagnostics; never a secret. */
    public int size() {
        return verified.size();
    }

    /**
     * Expired entries first; if that was not enough, empty the map. A cache that throws away
     * live entries only costs a BCrypt, whereas an unbounded one is a memory-exhaustion vector
     * for anyone able to attempt logins.
     */
    private void evict() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = verified.entrySet().iterator();
        while (it.hasNext()) {
            Long expiry = it.next().getValue();
            if (expiry == null || expiry <= now) {
                it.remove();
            }
        }
        if (verified.size() >= maxEntries) {
            verified.clear();
        }
    }

    /**
     * The stored hash is in the key on purpose: it is what makes a password change invalidate
     * the entry without anyone having to remember to call {@link #clear()}.
     */
    private String key(String repositoryId, String userId, String storedHash, String password) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(nullToEmpty(repositoryId).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(nullToEmpty(userId).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(storedHash.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(mac.doFinal());
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is required of every JRE; if it is missing, decline to cache rather
            // than fall back to a weaker key.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int intProp(String key, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longProp(String key, long fallback) {
        try {
            return Long.parseLong(System.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
