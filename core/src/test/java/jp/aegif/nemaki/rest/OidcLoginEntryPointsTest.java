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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.Response;
import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.auth.Token;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * The two generic OIDC logins, through their own entry points (3.4.1, zero-trust P0): the REST
 * {@code /repo/{repositoryId}/authtoken/oidc/convert} and the API v1 {@code /auth/repositories/{repositoryId}/oidc}.
 *
 * <p>The provider is a local server: its discovery document, its keys, and a UserInfo endpoint that answers alice for
 * any access token — as a provider answers for a token it issued to any of its applications. Both logins used to send
 * the body's access token there, with OIDC enabled or not, and to log in — and create — whoever the answer named. What
 * logs in now is the ID token the provider issued to this application, verified through the discovery the server reads
 * itself; otherwise nothing logs in, no user is looked up or created, and the UserInfo endpoint is never called.
 */
public class OidcLoginEntryPointsTest {

    private static final String CLIENT = "nemakiware-ui";

    private HttpServer provider;
    private String issuer;
    private final AtomicInteger userInfoCalls = new AtomicInteger();
    private RSAKey key;
    private UserItem alice;
    private PropertyManager properties;
    private ContentService contentService;
    private TokenService tokenService;
    private ApplicationContext previousSpring;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + provider.getAddress().getPort() + "/realms/r";
        serve("/realms/r/.well-known/openid-configuration", "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer
                + "/certs\",\"userinfo_endpoint\":\"" + issuer + "/userinfo\"}");
        serve("/realms/r/certs", new JWKSet(key.toPublicJWK()).toString());
        provider.createContext("/realms/r/userinfo", exchange -> {
            userInfoCalls.incrementAndGet();
            byte[] body = "{\"sub\":\"sub-123\",\"preferred_username\":\"alice\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        provider.start();

        properties = mock(PropertyManager.class);
        when(properties.readValue("bedroom", PropertyKey.SSO_OIDC_ENABLED)).thenReturn("true");
        when(properties.readValue("bedroom", PropertyKey.OIDC_ISSUER)).thenReturn(issuer);
        when(properties.readValue("bedroom", "oidc.clientId")).thenReturn(CLIENT);
        contentService = mock(ContentService.class);
        // alice: an account SSO is allowed for (as SSO creates them), not linked to an OIDC subject yet.
        alice = account("alice", "cloud", null);
        when(contentService.getUserItemById("bedroom", "alice")).thenReturn(alice);
        tokenService = mock(TokenService.class);
        when(tokenService.setToken(anyString(), eq("bedroom"), anyString()))
                .thenAnswer(set -> new Token(set.getArgument(2), "t-" + set.getArgument(2), System.currentTimeMillis() + 60_000));
        // The API v1 resource reads the properties through the application context.
        previousSpring = SpringContext.getApplicationContext();
        ApplicationContext spring = mock(ApplicationContext.class);
        when(spring.getBean("propertyManager", PropertyManager.class)).thenReturn(properties);
        new SpringContext().setApplicationContext(spring);
    }

    @AfterEach
    void tearDown() {
        new SpringContext().setApplicationContext(previousSpring);
        provider.stop(0);
    }

    private void serve(String path, String json) {
        provider.createContext(path, exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    /** The REST conversion's answer to the body, as the client reads it. */
    private JSONObject rest(String body) throws Exception {
        AuthTokenResource resource = new AuthTokenResource();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        ServletContext servletContext = mock(ServletContext.class);
        WebApplicationContext web = mock(WebApplicationContext.class);
        when(request.getServletContext()).thenReturn(servletContext);
        when(servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE)).thenReturn(web);
        when(web.getBean("propertyManager", PropertyManager.class)).thenReturn(properties);
        when(web.getBean("ContentService", ContentService.class)).thenReturn(contentService);
        set(resource, "request", request);
        set(resource, "response", mock(HttpServletResponse.class));
        resource.setTokenService(tokenService);
        return (JSONObject) new JSONParser().parse(resource.convertOIDCToken("bedroom", body));
    }

    /** The API v1 login's answer to the body: the user it logged in, or the refusal's status. */
    private String apiV1(Map<String, Object> body) throws Exception {
        jp.aegif.nemaki.api.v1.resource.AuthResource resource = new jp.aegif.nemaki.api.v1.resource.AuthResource();
        set(resource, "contentService", contentService);
        set(resource, "tokenService", tokenService);
        try {
            Response response = resource.oidcAuth("bedroom", body);
            return "logged in: " + ((jp.aegif.nemaki.api.v1.model.response.AuthResponse) response.getEntity()).getUser().getUserId();
        } catch (ApiException refused) {
            return "refused: " + refused.getStatus();
        }
    }

    /** As {@link #account(String, String, String, String)} with no route mark. */
    private static UserItem account(String userId, String allowedAuthMethods, String linkedTo) {
        return account(userId, allowedAuthMethods, linkedTo, null);
    }

    /**
     * An account as the store holds it: the authentication methods it allows, the OIDC subject it is linked to,
     * and WHICH ROUTE made it (R168 — {@code cloud} is written by the other SSO routes and by the cloud
     * directory sync, so it is not an administrator's decision on its own).
     */
    private static UserItem account(String userId, String allowedAuthMethods, String linkedTo, String route) {
        UserItem user = new UserItem("doc-" + userId, "nemaki:user", userId, userId, "$2a$10$x", false, "users");
        java.util.List<jp.aegif.nemaki.model.Property> properties = new java.util.ArrayList<>();
        if (allowedAuthMethods != null) {
            properties.add(new jp.aegif.nemaki.model.Property("nemaki:allowedAuthMethods", allowedAuthMethods));
        }
        if (linkedTo != null) {
            properties.add(new jp.aegif.nemaki.model.Property("nemaki:oidcSubject", linkedTo));
        }
        if (route != null) {
            properties.add(new jp.aegif.nemaki.model.Property(SsoLogin.ROUTE_KEY, route));
        }
        user.setSubTypeProperties(properties);
        return user;
    }

    private String idTokenFor(String preferredUsername, String subject) throws Exception {
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(issuer).subject(subject)
                .audience(CLIENT).claim("preferred_username", preferredUsername).issueTime(new java.util.Date())
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 300_000)).build();
        com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                com.nimbusds.jose.JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
        return jwt.serialize();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** An ID token the provider's key signs: this issuer, the audience given, alice, valid for five minutes. */
    private String idToken(String audience) throws Exception {
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(issuer).subject("sub-123")
                .audience(audience).claim("preferred_username", "alice").issueTime(new java.util.Date())
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 300_000)).build();
        com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                com.nimbusds.jose.JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
        return jwt.serialize();
    }

    private void nothingLoggedIn() {
        verify(contentService, never()).getUserItemById(any(), any());
        verify(tokenService, never()).setToken(any(), any(), any());
        assertEquals(0, userInfoCalls.get(), "the UserInfo endpoint was called with the body's access token");
    }

    @Test
    @DisplayName("REST: an access token, with the provider's UserInfo endpoint named or not, does not log in — the endpoint is not called")
    void restAnAccessTokenDoesNotLogIn() throws Exception {
        JSONObject named = rest("{\"access_token\":\"another-apps\",\"userinfo_endpoint\":\"" + issuer + "/userinfo\"}");
        JSONObject discovered = rest("{\"access_token\":\"another-apps\",\"oidc_token\":\"another-apps\"}");
        assertEquals("failure", named.get("status"), named.toJSONString());
        assertEquals("failure", discovered.get("status"), discovered.toJSONString());
        nothingLoggedIn();
    }

    @Test
    @DisplayName("REST: nothing logs in where OIDC is not enabled for the repository")
    void restNothingLogsInWhereOidcIsNotEnabled() throws Exception {
        when(properties.readValue("bedroom", PropertyKey.SSO_OIDC_ENABLED)).thenReturn("false");
        JSONObject answer = rest("{\"id_token\":\"" + idToken(CLIENT) + "\",\"access_token\":\"opaque\"}");
        assertEquals("failure", answer.get("status"), answer.toJSONString());
        nothingLoggedIn();
    }

    @Test
    @DisplayName("REST: an ID token the provider issued to another of its applications does not log in")
    void restAnIdTokenForAnotherApplicationDoesNotLogIn() throws Exception {
        JSONObject answer = rest("{\"id_token\":\"" + idToken("nemakiware-oidc-client") + "\",\"access_token\":\"opaque\"}");
        assertEquals("failure", answer.get("status"), answer.toJSONString());
        nothingLoggedIn();
    }

    @Test
    @DisplayName("REST: the ID token issued to this application logs in as the user it names")
    void restTheIdTokenIssuedToThisApplicationLogsIn() throws Exception {
        JSONObject answer = rest("{\"id_token\":\"" + idToken(CLIENT) + "\",\"access_token\":\"opaque\"}");
        assertEquals("success", answer.get("status"), answer.toJSONString());
        assertEquals("alice", ((JSONObject) answer.get("value")).get("userName"));
    }

    @Test
    @DisplayName("API v1: an access token, with the provider's UserInfo endpoint named or not, does not log in — the endpoint is not called")
    void apiV1AnAccessTokenDoesNotLogIn() throws Exception {
        String named = apiV1(Map.of("access_token", "another-apps", "userinfo_endpoint", issuer + "/userinfo"));
        String discovered = apiV1(Map.of("access_token", "another-apps"));
        assertTrue(named.startsWith("refused: "), named);
        assertTrue(discovered.startsWith("refused: "), discovered);
        nothingLoggedIn();
    }

    @Test
    @DisplayName("API v1: nothing logs in where OIDC is not enabled for the repository")
    void apiV1NothingLogsInWhereOidcIsNotEnabled() throws Exception {
        when(properties.readValue("bedroom", PropertyKey.SSO_OIDC_ENABLED)).thenReturn("false");
        String answer = apiV1(Map.of("id_token", idToken(CLIENT), "access_token", "opaque"));
        assertTrue(answer.startsWith("refused: "), answer);
        nothingLoggedIn();
    }

    @Test
    @DisplayName("API v1: an ID token the provider issued to another of its applications does not log in")
    void apiV1AnIdTokenForAnotherApplicationDoesNotLogIn() throws Exception {
        String answer = apiV1(Map.of("id_token", idToken("nemakiware-oidc-client"), "access_token", "opaque"));
        assertTrue(answer.startsWith("refused: "), answer);
        nothingLoggedIn();
    }

    @Test
    @DisplayName("behind split host names, the ID token carrying the issuer the provider's document names logs in — read at the configured address")
    void behindSplitHostNamesTheDocumentsIssuerLogsIn() throws Exception {
        // The server reads the provider at its own address; browsers reach it — and it signs — under a public name.
        provider.removeContext("/realms/r/.well-known/openid-configuration");
        String publicName = "https://idp.example/realms/r";
        serve("/realms/r/.well-known/openid-configuration", "{\"issuer\":\"" + publicName + "\",\"jwks_uri\":\"" + issuer
                + "/certs\"}");
        String configured = issuer;
        issuer = publicName;
        try {
            JSONObject answer = rest("{\"id_token\":\"" + idToken(CLIENT) + "\"}");
            assertEquals("success", answer.get("status"), answer.toJSONString());
        } finally {
            issuer = configured;
        }
    }

    @Test
    @DisplayName("REST and API v1: a token naming the built-in admin does not log in as it — the name is the IdP's to give (batch 16 review round 1: both reviewers)")
    void aTokenNamingTheBuiltInAdminDoesNotLogInAsIt() throws Exception {
        UserItem admin = account("admin", null, null);
        when(contentService.getUserItemById("bedroom", "admin")).thenReturn(admin);
        // A legacy account whose methods read "null" — every method, as the policy reads it — is not SSO's either.
        UserItem legacy = account("legacy", "null", null);
        when(contentService.getUserItemById("bedroom", "legacy")).thenReturn(legacy);
        assertEquals("failure", rest("{\"id_token\":\"" + idTokenFor("legacy", "someone") + "\"}").get("status"));
        JSONObject rest = rest("{\"id_token\":\"" + idTokenFor("admin", "someone") + "\"}");
        String apiV1 = apiV1(Map.of("id_token", idTokenFor("admin", "someone")));
        assertEquals("failure", rest.get("status"), rest.toJSONString());
        assertTrue(apiV1.startsWith("refused: "), apiV1);
        verify(tokenService, never()).setToken(any(), any(), any());
        verify(contentService, never()).applyUserUpdate(any(), any(), any());
    }

    @Test
    @DisplayName("REST and API v1: a disabled account is refused and is NOT bound to the token's subject — \"cloud,disabled\" used to be eligible, and the binding was written before the refusal (batch 18 review round 1: subagent P1)")
    void aDisabledAccountIsNeitherLoggedIntoNorBound() throws Exception {
        UserItem held = account("eve", "cloud,disabled", null);
        when(contentService.getUserItemById("bedroom", "eve")).thenReturn(held);

        JSONObject rest = rest("{\"id_token\":\"" + idTokenFor("eve", "sub-eve") + "\"}");
        String apiV1 = apiV1(Map.of("id_token", idTokenFor("eve", "sub-eve")));

        assertEquals("failure", rest.get("status"), rest.toJSONString());
        assertTrue(apiV1.startsWith("refused: "), apiV1);
        verify(tokenService, never()).setToken(any(), any(), any());
        // The harm was the binding, not the login: it was persisted before the method gate refused, so re-enabling
        // the account later let that subject in. Nothing may be written for a disabled account.
        verify(contentService, never()).applyUserUpdate(any(), any(), any());
        assertTrue(held.getSubTypeProperties().stream().noneMatch(pr -> "nemaki:oidcSubject".equals(pr.getKey())),
                "a disabled account was bound to the token's OIDC subject");
    }

    @Test
    @DisplayName("REST and API v1: an account linked to one subject is not logged into with another's token of the same name (batch 16 review round 1)")
    void anAccountLinkedToAnotherSubjectIsNotLoggedInto() throws Exception {
        UserItem bob = account("bob", "cloud", issuer + " sub-bob");
        when(contentService.getUserItemById("bedroom", "bob")).thenReturn(bob);
        JSONObject rest = rest("{\"id_token\":\"" + idTokenFor("bob", "sub-mallory") + "\"}");
        String apiV1 = apiV1(Map.of("id_token", idTokenFor("bob", "sub-mallory")));
        assertEquals("failure", rest.get("status"), rest.toJSONString());
        assertTrue(apiV1.startsWith("refused: "), apiV1);
        verify(tokenService, never()).setToken(any(), any(), any());
        assertEquals("success", rest("{\"id_token\":\"" + idTokenFor("bob", "sub-bob") + "\"}").get("status"));
    }

    @Test
    @DisplayName("REST and API v1: an account ANOTHER ROUTE made is not adopted, though it lists cloud (R168)")
    void anAccountAnotherRouteMadeIsNotAdopted() throws Exception {
        // cloud is listed — by the cloud directory sync itself, which writes it both on the accounts it
        // creates and onto accounts that already exist. Reading that as 「an administrator allowed SSO」 is
        // what let the first generic-OIDC token naming such an account adopt it.
        UserItem synced = account("dave", "cloud", null, SsoLogin.DIRECTORY_SYNC);
        when(contentService.getUserItemById("bedroom", "dave")).thenReturn(synced);

        JSONObject rest = rest("{\"id_token\":\"" + idTokenFor("dave", "sub-dave") + "\"}");
        String apiV1 = apiV1(Map.of("id_token", idTokenFor("dave", "sub-dave")));

        assertEquals("failure", rest.get("status"), rest.toJSONString());
        assertTrue(apiV1.startsWith("refused: "), apiV1);
        verify(tokenService, never()).setToken(any(), any(), any());
        verify(contentService, never()).applyUserUpdate(any(), any(), any());
        assertTrue(synced.getSubTypeProperties().stream().noneMatch(pr -> "nemaki:oidcSubject".equals(pr.getKey())),
                "an account the directory sync made was bound to the token's subject");
    }

    @Test
    @DisplayName("REST: a binding the store does not keep refuses the login — «we wrote it» is not «it is stored»")
    void aBindingThatIsNotStoredRefusesTheLogin() throws Exception {
        // The save can return without storing (and a refusal that THROWS leaves the cached instance
        // carrying the binding — residual R196). Either way the login must not proceed as an account whose
        // link was never stored.
        UserItem carol = account("carol", "cloud", null);
        UserItem stillUnlinked = account("carol", "cloud", null);
        when(contentService.getUserItemById("bedroom", "carol")).thenReturn(carol, stillUnlinked);

        JSONObject rest = rest("{\"id_token\":\"" + idTokenFor("carol", "sub-carol") + "\"}");

        assertEquals("failure", rest.get("status"), rest.toJSONString());
        verify(tokenService, never()).setToken(any(), any(), any());
    }

    @Test
    @DisplayName("REST: the first login links an account SSO is allowed for to the token's subject, and the link is kept (batch 16 review round 1)")
    void theFirstLoginLinksTheAccount() throws Exception {
        assertEquals("success", rest("{\"id_token\":\"" + idToken(CLIENT) + "\"}").get("status"));
        verify(contentService).applyUserUpdate(eq("bedroom"), eq(alice), eq("alice"));
        assertTrue(alice.getSubTypeProperties().stream().anyMatch(p -> "nemaki:oidcSubject".equals(p.getKey())
                && (issuer + " sub-123").equals(p.getValue())), "alice was not linked to the token's subject");
        assertEquals("failure", rest("{\"id_token\":\"" + idTokenFor("alice", "sub-other") + "\"}").get("status"),
                "another subject logged in as alice after she was linked");
    }

    @Test
    @DisplayName("REST and API v1: an account that allows only passwords, or is disabled, is not logged into — read from the account, not through a service that may be missing (batch 16 review round 1: Codex P1)")
    void anAccountThatDoesNotAllowSsoIsNotLoggedInto() throws Exception {
        UserItem carol = account("carol", "password", issuer + " sub-carol");
        UserItem dave = account("dave", "disabled", issuer + " sub-dave");
        when(contentService.getUserItemById("bedroom", "carol")).thenReturn(carol);
        when(contentService.getUserItemById("bedroom", "dave")).thenReturn(dave);
        assertEquals("failure", rest("{\"id_token\":\"" + idTokenFor("carol", "sub-carol") + "\"}").get("status"));
        assertEquals("failure", rest("{\"id_token\":\"" + idTokenFor("dave", "sub-dave") + "\"}").get("status"));
        assertTrue(apiV1(Map.of("id_token", idTokenFor("carol", "sub-carol"))).startsWith("refused: "));
        assertTrue(apiV1(Map.of("id_token", idTokenFor("dave", "sub-dave"))).startsWith("refused: "));
        verify(tokenService, never()).setToken(any(), any(), any());
    }

    @Test
    @DisplayName("REST and API v1: a second client listed in oidc.clientId logs in — the setting is read as a list (batch 16 review round 1: subagent P2)")
    void aSecondListedClientLogsIn() throws Exception {
        when(properties.readValue("bedroom", "oidc.clientId")).thenReturn("nemakiware-ui, nemakiware-oidc-client");
        assertEquals("success", rest("{\"id_token\":\"" + idToken("nemakiware-oidc-client") + "\"}").get("status"));
        assertEquals("logged in: alice", apiV1(Map.of("id_token", idToken("nemakiware-oidc-client"))));
    }

    @Test
    @DisplayName("API v1: the ID token issued to this application logs in as the user it names")
    void apiV1TheIdTokenIssuedToThisApplicationLogsIn() throws Exception {
        assertEquals("logged in: alice", apiV1(Map.of("id_token", idToken(CLIENT), "access_token", "opaque")));
    }
}
