package jp.aegif.nemaki.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.UserItem;

/**
 * Tests for {@link AuthenticationUtil} password matching, focused on the
 * blank-credential fail-closed behaviour (security audit follow-up).
 *
 * <p>Previously {@code passwordMatches("", "")} returned {@code true},
 * so an account whose stored hash was empty (legacy / sync / corrupted
 * record) could be logged into with an empty password. Both
 * {@code passwordMatches} and {@code passwordMatchesWithUpgrade} must
 * now reject any blank candidate or blank stored hash.
 */
class AuthenticationUtilTest {

    private static String bcrypt(String pw) {
        return BCrypt.hashpw(pw, BCrypt.gensalt(4)); // low cost = fast test
    }

    // ── fail-closed on blank ──

    @Test
    void blankCandidateAndBlankHash_doesNotMatch() {
        assertFalse(AuthenticationUtil.passwordMatches("", ""));
        assertFalse(AuthenticationUtil.passwordMatches(null, null));
    }

    @Test
    void blankHash_doesNotMatch() {
        assertFalse(AuthenticationUtil.passwordMatches("anything", ""));
        assertFalse(AuthenticationUtil.passwordMatches("anything", null));
    }

    @Test
    void blankCandidate_doesNotMatchRealHash() {
        String hash = bcrypt("realpw");
        assertFalse(AuthenticationUtil.passwordMatches("", hash));
        assertFalse(AuthenticationUtil.passwordMatches(null, hash));
    }

    @Test
    void blankCandidateAndBlankHash_withUpgrade_doesNotMatch() {
        assertFalse(AuthenticationUtil.passwordMatchesWithUpgrade("", "").matches());
        assertFalse(AuthenticationUtil.passwordMatchesWithUpgrade(null, null).matches());
        assertFalse(AuthenticationUtil.passwordMatchesWithUpgrade("anything", "").matches());
    }

    // ── legitimate matches still work (no over-block) ──

    @Test
    void correctBcryptPassword_matches() {
        String hash = bcrypt("correct horse");
        assertTrue(AuthenticationUtil.passwordMatches("correct horse", hash));
        assertFalse(AuthenticationUtil.passwordMatches("wrong", hash));
    }

    @Test
    void correctBcryptPassword_withUpgrade_matchesWithoutUpgrade() {
        String hash = bcrypt("s3cret");
        var r = AuthenticationUtil.passwordMatchesWithUpgrade("s3cret", hash);
        assertTrue(r.matches());
        assertFalse(r.requiresUpgrade(), "BCrypt hash needs no upgrade");
    }

    @Test
    void legacyMd5Password_matchesAndRequestsUpgrade() {
        // MD5("hello") = 5d41402abc4b2a76b9719d911017c592
        String md5 = "5d41402abc4b2a76b9719d911017c592";
        assertTrue(AuthenticationUtil.passwordMatches("hello", md5));
        assertFalse(AuthenticationUtil.passwordMatches("world", md5));
        assertTrue(AuthenticationUtil.passwordMatchesWithUpgrade("hello", md5).matches());
    }

    // ── allowedAuthMethods policy gate (security audit follow-up, 3.2.1) ──
    //
    // Single source of truth consulted by the primary CMIS path, api/v1 login,
    // MCP and the legacy admin re-auth. A disabled / cloud-only account must not
    // be allowed to authenticate via password on any path.

    private static UserItem userWithPolicy(String allowedAuthMethods) {
        UserItem u = new UserItem();
        u.setUserId("bob");
        if (allowedAuthMethods != null) {
            u.setSubTypeProperties(List.of(new Property("nemaki:allowedAuthMethods", allowedAuthMethods)));
        }
        return u;
    }

    @Test
    void nullUserOrMethod_notAllowed_failClosed() {
        assertFalse(AuthenticationUtil.isAuthMethodAllowed(null, "password"));
        assertFalse(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("password"), null));
    }

    @Test
    void unsetPolicy_allowsAllMethods_backwardCompatible() {
        // Fresh account with no nemaki:allowedAuthMethods property.
        assertTrue(AuthenticationUtil.isAuthMethodAllowed(new UserItem(), "password"));
        assertTrue(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy(""), "password"));
        // A literal "null" string value (String.valueOf(null)) is treated as unset.
        assertTrue(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("null"), "password"));
    }

    @Test
    void disabledPolicy_deniesEveryMethod() {
        assertFalse(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("disabled"), "password"));
        assertFalse(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("disabled"), "cloud"));
        // trimming + case-insensitive
        assertFalse(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy(" Disabled "), "password"));
    }

    @Test
    void cloudOnlyPolicy_deniesPassword() {
        assertFalse(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("cloud"), "password"));
        assertTrue(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("cloud"), "cloud"));
    }

    @Test
    void passwordPolicy_allowsPasswordDeniesCloud() {
        assertTrue(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("password"), "password"));
        assertFalse(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("password"), "cloud"));
        // case-insensitive allow-list entry
        assertTrue(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("PASSWORD"), "password"));
    }

    @Test
    void combinedPolicy_allowsBoth() {
        assertTrue(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("password,cloud"), "password"));
        assertTrue(AuthenticationUtil.isAuthMethodAllowed(userWithPolicy("password, cloud"), "cloud"));
    }
}
