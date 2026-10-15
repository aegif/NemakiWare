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
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * The generic OIDC login, verified on the ID token (3.4.1, zero-trust P0): what logs in, and what does not.
 *
 * <p>The provider here is a key made for the test, published through a discovery that the test counts. The issuer the
 * tokens carry is the one the discovery names ({@code https://idp.example/realms/r}); the repository is configured with
 * the address the server reads it at ({@code http://idp:8080/realms/r}) — split host names, as in the Docker set-up.
 */
public class OidcLoginTest {

    static final String CONFIGURED = "http://idp:8080/realms/r";
    static final String ISSUER = "https://idp.example/realms/r";
    static final String CLIENT = "nemakiware-ui";

    private RSAKey key;
    private AtomicInteger discoveries;
    private OidcLogin login;
    private final OidcLogin.Settings enabled = new OidcLogin.Settings(true, CONFIGURED, java.util.Set.of(CLIENT));

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        discoveries = new AtomicInteger();
        login = new OidcLogin(configured -> {
            discoveries.incrementAndGet();
            assertEquals(CONFIGURED, configured);
            return new OidcLogin.Provider(ISSUER, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())));
        });
    }

    /** An ID token signed with {@code signer}'s key: issuer, audience, subject, and the times, then the extra claims. */
    static String token(RSAKey signer, String issuer, Object audience, Object... extra) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).subject("sub-123")
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 300_000));
        if (audience instanceof String a) {
            claims.audience(a);
        } else {
            @SuppressWarnings("unchecked")
            List<String> all = (List<String>) audience;
            claims.audience(all);
        }
        for (int i = 0; i < extra.length; i += 2) {
            claims.claim((String) extra[i], extra[i + 1]);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
    }

    @Test
    @DisplayName("an ID token the configured provider issued to this application logs in as its preferred_username")
    void anIdTokenIssuedToThisApplicationLogsIn() throws Exception {
        OidcLogin.Outcome outcome = login.verify(enabled, token(key, ISSUER, CLIENT, "preferred_username", "alice"));
        assertEquals("alice", outcome.userName(), outcome.refusal());
        // The subject the account is linked to: the issuer the provider signs as, and the token's subject.
        assertEquals(ISSUER + " sub-123", OidcLogin.binding(outcome));
    }

    @Test
    @DisplayName("each client listed in oidc.clientId logs in — the screen's and an API client's — and no other (batch 16 review round 1)")
    void eachListedClientLogsIn() throws Exception {
        OidcLogin.Settings two = new OidcLogin.Settings(true, CONFIGURED, java.util.Set.of(CLIENT, "nemakiware-oidc-client"));
        assertEquals("alice", login.verify(two, token(key, ISSUER, "nemakiware-oidc-client", "preferred_username", "alice")).userName());
        assertEquals("alice", login.verify(two, token(key, ISSUER, CLIENT, "preferred_username", "alice")).userName());
        assertNull(login.verify(two, token(key, ISSUER, "another-app", "preferred_username", "alice")).userName());
        assertEquals("alice", login.verify(two, token(key, ISSUER, List.of(CLIENT, "nemakiware-oidc-client"),
                "preferred_username", "alice", "azp", "nemakiware-oidc-client")).userName());
    }

    @Test
    @DisplayName("an ID token without its subject, its issue time or its expiry does not log in (batch 16 review round 1)")
    void anIdTokenWithoutTheRequiredClaimsDoesNotLogIn() throws Exception {
        for (String missing : new String[] {"sub", "iat", "exp"}) {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(CLIENT).claim("preferred_username", "alice");
            if (!missing.equals("sub")) {
                claims.subject("sub-123");
            }
            if (!missing.equals("iat")) {
                claims.issueTime(new Date());
            }
            if (!missing.equals("exp")) {
                claims.expirationTime(new Date(System.currentTimeMillis() + 300_000));
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            assertNull(login.verify(enabled, jwt.serialize()).userName(), "an ID token without " + missing + " logged in");
        }
    }

    @Test
    @DisplayName("an identity claim in a form this does not read is refused — not taken for absent, which would name the subject (batch 16 review round 1)")
    void aMalformedIdentityClaimIsRefused() throws Exception {
        assertNull(login.verify(enabled, token(key, ISSUER, CLIENT, "preferred_username", 42)).userName());
        assertNull(login.verify(enabled, token(key, ISSUER, CLIENT, "email", "admin@example.com", "email_verified", "true")).userName());
        assertNull(login.verify(enabled, token(key, ISSUER, CLIENT, "email", List.of("admin@example.com"))).userName());
    }

    @Test
    @DisplayName("nothing logs in where OIDC is not enabled — the provider is not even asked (it was: any token logged in)")
    void nothingLogsInWhereOidcIsNotEnabled() throws Exception {
        OidcLogin.Outcome outcome = login.verify(new OidcLogin.Settings(false, CONFIGURED, java.util.Set.of(CLIENT)),
                token(key, ISSUER, CLIENT, "preferred_username", "alice"));
        assertNull(outcome.userName());
        assertEquals(0, discoveries.get(), "the provider was asked though OIDC is not enabled");
    }

    @Test
    @DisplayName("nothing logs in where the issuer or this application's client id is not configured")
    void nothingLogsInWithoutTheIssuerOrTheClientId() throws Exception {
        String alice = token(key, ISSUER, CLIENT, "preferred_username", "alice");
        assertNull(login.verify(new OidcLogin.Settings(true, null, java.util.Set.of(CLIENT)), alice).userName());
        assertNull(login.verify(new OidcLogin.Settings(true, CONFIGURED, java.util.Set.of()), alice).userName());
        assertEquals(0, discoveries.get());
    }

    @Test
    @DisplayName("an access token alone does not log in — the ID token is required")
    void anAccessTokenAloneDoesNotLogIn() {
        assertNull(login.verify(enabled, null).userName());
        assertNull(login.verify(enabled, " ").userName());
    }

    @Test
    @DisplayName("an ID token the same provider issued to another application does not log in (token substitution)")
    void anIdTokenForAnotherApplicationDoesNotLogIn() throws Exception {
        OidcLogin.Outcome outcome = login.verify(enabled, token(key, ISSUER, "another-app", "preferred_username", "alice"));
        assertNull(outcome.userName());
        assertTrue(outcome.refusal() != null && !outcome.refusal().isBlank());
    }

    @Test
    @DisplayName("an ID token naming this application among others logs in only where it names this application as the authorised party")
    void aTokenForSeveralAudiencesNeedsThisApplicationAsTheAuthorisedParty() throws Exception {
        assertNull(login.verify(enabled, token(key, ISSUER, List.of(CLIENT, "another-app"), "preferred_username", "alice",
                "azp", "another-app")).userName());
        assertNull(login.verify(enabled, token(key, ISSUER, List.of(CLIENT, "another-app"), "preferred_username", "alice"))
                .userName());
        assertEquals("alice", login.verify(enabled, token(key, ISSUER, List.of(CLIENT, "another-app"),
                "preferred_username", "alice", "azp", CLIENT)).userName());
    }

    @Test
    @DisplayName("an ID token another issuer issued does not log in — not even the configured address, where the provider signs as another")
    void anIdTokenFromAnotherIssuerDoesNotLogIn() throws Exception {
        assertNull(login.verify(enabled, token(key, "https://evil.example/realms/r", CLIENT, "preferred_username", "alice"))
                .userName());
        assertNull(login.verify(enabled, token(key, CONFIGURED, CLIENT, "preferred_username", "alice")).userName());
    }

    @Test
    @DisplayName("an expired ID token does not log in")
    void anExpiredIdTokenDoesNotLogIn() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("sub-123").audience(CLIENT)
                .claim("preferred_username", "alice").issueTime(new Date(System.currentTimeMillis() - 3_600_000))
                .expirationTime(new Date(System.currentTimeMillis() - 600_000)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(key));
        assertNull(login.verify(enabled, jwt.serialize()).userName());
    }

    @Test
    @DisplayName("an ID token signed with a key the provider does not publish does not log in")
    void anIdTokenSignedWithAnotherKeyDoesNotLogIn() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).keyID("k1").generate();
        assertNull(login.verify(enabled, token(other, ISSUER, CLIENT, "preferred_username", "alice")).userName());
    }

    @Test
    @DisplayName("an unsigned ID token, or one signed with a shared secret — even one the provider publishes — does not log in")
    void anUnsignedOrHmacTokenDoesNotLogIn() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("sub-123").audience(CLIENT)
                .claim("preferred_username", "alice").issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)).build();
        assertNull(login.verify(enabled, new PlainJWT(claims).serialize()).userName());
        // A secret in the key set is anyone's who can read the key set: a token it signs proves nothing.
        com.nimbusds.jose.jwk.OctetSequenceKey secret = new com.nimbusds.jose.jwk.OctetSequenceKey.Builder(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)).keyID("s1").build();
        OidcLogin publishing = new OidcLogin(configured -> new OidcLogin.Provider(ISSUER,
                new ImmutableJWKSet<>(new JWKSet(List.of(key.toPublicJWK(), secret)))));
        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("s1").build(), claims);
        hmac.sign(new MACSigner(secret));
        assertNull(publishing.verify(enabled, hmac.serialize()).userName());
        assertEquals("alice", publishing.verify(enabled, token(key, ISSUER, CLIENT, "preferred_username", "alice")).userName());
    }

    @Test
    @DisplayName("an email the provider has not verified names no one — the subject does")
    void anUnverifiedEmailNamesNoOne() throws Exception {
        assertEquals("sub-123", login.verify(enabled, token(key, ISSUER, CLIENT, "email", "admin@example.com")).userName());
        assertEquals("sub-123", login.verify(enabled, token(key, ISSUER, CLIENT, "email", "admin@example.com",
                "email_verified", false)).userName());
        assertEquals("admin@example.com", login.verify(enabled, token(key, ISSUER, CLIENT, "email", "admin@example.com",
                "email_verified", true)).userName());
    }

    @Test
    @DisplayName("where the provider's discovery cannot be read, nothing logs in")
    void nothingLogsInWhereTheDiscoveryCannotBeRead() throws Exception {
        OidcLogin unreadable = new OidcLogin(configured -> {
            throw new IllegalStateException("HTTP 503");
        });
        assertNull(unreadable.verify(enabled, token(key, ISSUER, CLIENT, "preferred_username", "alice")).userName());
    }
}
