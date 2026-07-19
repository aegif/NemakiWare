package jp.aegif.nemaki.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    // maxFailures=3, window=1000ms, lockout=5000ms
    private LoginThrottle t() {
        return new LoginThrottle(true, 3, 1000L, 5000L);
    }

    private static final String K = "bedroom:alice:203.0.113.9";

    @Test
    void underThresholdNotBlocked() {
        LoginThrottle t = t();
        t.recordFailure(K, 0);
        t.recordFailure(K, 100);
        assertFalse(t.isBlocked(K, 200), "2 < 3 failures should not lock");
    }

    @Test
    void reachingThresholdLocks() {
        LoginThrottle t = t();
        t.recordFailure(K, 0);
        t.recordFailure(K, 100);
        t.recordFailure(K, 200);
        assertTrue(t.isBlocked(K, 300), "3rd failure within window should lock");
    }

    @Test
    void successClearsCounter() {
        LoginThrottle t = t();
        t.recordFailure(K, 0);
        t.recordFailure(K, 100);
        t.recordSuccess(K);
        t.recordFailure(K, 200);
        assertFalse(t.isBlocked(K, 300), "success reset the counter, so 1 new failure must not lock");
    }

    @Test
    void lockoutExpires() {
        LoginThrottle t = t();
        t.recordFailure(K, 0);
        t.recordFailure(K, 10);
        t.recordFailure(K, 20); // locked at 20, until 5020
        assertTrue(t.isBlocked(K, 5000));
        assertFalse(t.isBlocked(K, 5021), "lockout should expire after lockoutMs");
    }

    @Test
    void windowResetsFailuresWhenNotLocked() {
        LoginThrottle t = t();
        t.recordFailure(K, 0);
        t.recordFailure(K, 100); // 2 failures in window
        // Next failure is beyond the 1000ms window → counter resets to 1
        t.recordFailure(K, 2000);
        assertFalse(t.isBlocked(K, 2100), "failures outside the window should not accumulate to a lock");
    }

    @Test
    void keysAreIndependent() {
        LoginThrottle t = t();
        String other = "bedroom:bob:203.0.113.9";
        t.recordFailure(K, 0);
        t.recordFailure(K, 10);
        t.recordFailure(K, 20); // K locked
        assertTrue(t.isBlocked(K, 30));
        assertFalse(t.isBlocked(other, 30), "a different user/IP key must be unaffected");
    }

    @Test
    void disabledNeverBlocks() {
        LoginThrottle t = new LoginThrottle(false, 1, 1000L, 5000L);
        t.recordFailure(K, 0);
        t.recordFailure(K, 1);
        t.recordFailure(K, 2);
        assertFalse(t.isBlocked(K, 3), "disabled throttle must never block");
    }

    @Test
    void nullKeySafe() {
        LoginThrottle t = t();
        t.recordFailure(null, 0);
        assertFalse(t.isBlocked(null, 1));
    }
}
