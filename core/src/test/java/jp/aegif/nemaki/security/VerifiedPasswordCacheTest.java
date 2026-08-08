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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cache may only ever answer "you already checked this one".
 *
 * <h2>What is actually at risk</h2>
 *
 * <p>This cache sits in front of the password check, so a bug here is an authentication bug, not
 * a performance bug. Two things must hold no matter what: a credential that was never verified
 * must not be reported as verified, and an entry must stop matching the moment the account's
 * stored hash changes — that is the only invalidation mechanism there is, so if the key were
 * built without the hash a changed password would keep working until the TTL ran out.
 *
 * <p>The tests therefore probe the near misses (same user different password, same password
 * different user, same everything but a rotated hash) rather than just the happy path, because
 * the happy path passes under any key derivation, including a broken one.
 */
class VerifiedPasswordCacheTest {

    private static final String REPO = "bedroom";
    private static final String USER = "alice";
    private static final String HASH = "$2a$12$abcdefghijklmnopqrstuv";
    private static final String PASSWORD = "correct horse battery staple";

    private static VerifiedPasswordCache cache() {
        return new VerifiedPasswordCache(60_000L, 1000);
    }

    @Test
    @DisplayName("検証済みの組み合わせだけが hit する")
    void onlyTheRememberedCombinationHits() {
        VerifiedPasswordCache c = cache();
        assertFalse(c.isVerified(REPO, USER, HASH, PASSWORD), "nothing is verified before it is");

        c.remember(REPO, USER, HASH, PASSWORD);
        assertTrue(c.isVerified(REPO, USER, HASH, PASSWORD));

        assertFalse(c.isVerified(REPO, USER, HASH, "wrong"), "a different password must miss");
        assertFalse(c.isVerified(REPO, "bob", HASH, PASSWORD), "a different user must miss");
        assertFalse(c.isVerified("canopy", USER, HASH, PASSWORD), "a different repository must miss");
    }

    @Test
    @DisplayName("保存ハッシュが変われば hit しない (パスワード変更の失効はこれだけが担保する)")
    void rotatingTheStoredHashInvalidates() {
        VerifiedPasswordCache c = cache();
        c.remember(REPO, USER, HASH, PASSWORD);
        assertTrue(c.isVerified(REPO, USER, HASH, PASSWORD));

        String rotated = "$2a$12$ZZZZZZZZZZZZZZZZZZZZZZ";
        assertFalse(c.isVerified(REPO, USER, rotated, PASSWORD),
                "a changed password means a changed stored hash, and the entry must stop matching");
    }

    @Test
    @DisplayName("TTL を過ぎれば hit しない")
    void entriesExpire() throws InterruptedException {
        VerifiedPasswordCache c = new VerifiedPasswordCache(30L, 1000);
        c.remember(REPO, USER, HASH, PASSWORD);
        assertTrue(c.isVerified(REPO, USER, HASH, PASSWORD));

        Thread.sleep(60L);
        assertFalse(c.isVerified(REPO, USER, HASH, PASSWORD), "the TTL is the revocation window");
    }

    @Test
    @DisplayName("TTL 0 で完全に無効 (覚えないし答えない)")
    void zeroTtlDisablesEntirely() {
        VerifiedPasswordCache c = new VerifiedPasswordCache(0L, 1000);
        assertFalse(c.isEnabled());
        c.remember(REPO, USER, HASH, PASSWORD);
        assertEquals(0, c.size(), "a disabled cache must not accumulate anything");
        assertFalse(c.isVerified(REPO, USER, HASH, PASSWORD));
    }

    @Test
    @DisplayName("空のハッシュや空のパスワードは覚えない")
    void blankInputsAreNeverRemembered() {
        VerifiedPasswordCache c = cache();
        c.remember(REPO, USER, "", PASSWORD);
        c.remember(REPO, USER, HASH, "");
        c.remember(REPO, USER, null, PASSWORD);
        assertEquals(0, c.size());
        assertFalse(c.isVerified(REPO, USER, "", PASSWORD));
        assertFalse(c.isVerified(REPO, USER, HASH, ""));
    }

    @Test
    @DisplayName("上限を超えても際限なく育たない (失うのは BCrypt 1 回分だけ)")
    void staysBounded() {
        VerifiedPasswordCache c = new VerifiedPasswordCache(60_000L, 50);
        for (int i = 0; i < 500; i++) {
            c.remember(REPO, "user-" + i, HASH, PASSWORD);
        }
        assertTrue(c.size() <= 50,
                "an unbounded cache is a memory-exhaustion vector for anyone who can attempt logins,"
                        + " saw " + c.size());
    }

    /**
     * Not a security property, but the one that pays for the class: repeated lookups must be
     * cheap. A BCrypt at cost 12 is ~225 ms; 100k lookups here should not approach that.
     */
    @Test
    @DisplayName("lookup は BCrypt に比べて桁違いに安い")
    void lookupIsCheap() {
        VerifiedPasswordCache c = cache();
        c.remember(REPO, USER, HASH, PASSWORD);
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            c.isVerified(REPO, USER, HASH, PASSWORD);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 2000L,
                "100k lookups took " + elapsedMs + "ms — that is not a cache");
    }
}
