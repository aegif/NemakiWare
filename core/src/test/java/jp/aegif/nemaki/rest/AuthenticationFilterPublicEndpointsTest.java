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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jp.aegif.nemaki.cmis.factory.auth.AuthenticationService;
import jp.aegif.nemaki.cmis.factory.auth.TokenService;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * Which requests the REST filter lets through without credentials (3.4.1, zero-trust P0).
 *
 * <p>The filter used to compare parts of the request URI: any path with {@code /ui/} in it skipped authentication —
 * measured on nb33, {@code /core/rest/repo/ui/user/list} and {@code /core/rest/repo/bedroom/group/show/ui/} reached
 * their resources with no credentials at all — and the other public names counted wherever they stood. The public
 * endpoints are now matched whole, by the path the container routes the request to. Each request here carries no
 * credentials, so a request the filter does not let through is answered 401.
 */
public class AuthenticationFilterPublicEndpointsTest {

    private AuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthenticationFilter();
        filter.setPropertyManager(mock(PropertyManager.class));
        filter.setAuthenticationService(mock(AuthenticationService.class));
        filter.setTokenService(mock(TokenService.class));
        filter.setRepositoryInfoMap(mock(RepositoryInfoMap.class));
    }

    /** What the filter did with a request routed to {@code servletPath} + {@code pathInfo}: passed it on, or its status. */
    private String send(String method, String servletPath, String pathInfo) throws Exception {
        return send(method, "/core" + servletPath + (pathInfo == null ? "" : pathInfo), servletPath, pathInfo);
    }

    private String send(String method, String requestUri, String servletPath, String pathInfo) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getContextPath()).thenReturn("/core");
        when(request.getRequestURI()).thenReturn(requestUri);
        when(request.getServletPath()).thenReturn(servletPath);
        when(request.getPathInfo()).thenReturn(pathInfo);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        if (!mockingDetails(chain).getInvocations().isEmpty()) {
            return "passed";
        }
        for (org.mockito.invocation.Invocation call : mockingDetails(response).getInvocations()) {
            String name = call.getMethod().getName();
            if ((name.equals("sendError") || name.equals("setStatus")) && call.getArguments()[0] instanceof Integer) {
                return String.valueOf(call.getArguments()[0]);
            }
        }
        return "nothing";
    }

    @Test
    @DisplayName("a path with /ui/ in it is authenticated — it reached the resources with no credentials (zero-trust P0)")
    void aPathWithUiInItIsAuthenticated() throws Exception {
        assertEquals("401", send("GET", "/rest", "/repo/ui/user/list"));
        assertEquals("401", send("GET", "/rest", "/repo/bedroom/group/show/ui/"));
        assertEquals("401", send("GET", "/rest", "/repo/bedroom/user/show/ui/"));
        assertEquals("401", send("POST", "/api/v1/cmis", "/repositories/bedroom/ui/x"));
    }

    @Test
    @DisplayName("the endpoints that answer before anyone has logged in are still let through")
    void theLoginEndpointsAreLetThrough() throws Exception {
        assertEquals("passed", send("GET", "/rest", "/all/build-info"));
        assertEquals("passed", send("GET", "/rest", "/all/repositories"));
        assertEquals("passed", send("GET", "/rest", "/auth/config"));
        assertEquals("passed", send("POST", "/rest", "/repo/bedroom/authtoken/admin/login"));
        for (String provider : new String[] {"saml", "oidc", "google", "microsoft"}) {
            assertEquals("passed", send("POST", "/rest", "/repo/bedroom/authtoken/" + provider + "/convert"), provider);
        }
        assertEquals("passed", send("POST", "/rest", "/repo/bedroom/webauthn/authenticate/begin"));
        assertEquals("passed", send("POST", "/rest", "/repo/bedroom/webauthn/authenticate/complete"));
        for (String door : new String[] {"login", "saml", "oidc"}) {
            assertEquals("passed", send("POST", "/api/v1/cmis", "/auth/repositories/bedroom/" + door), door);
        }
        assertEquals("passed", send("GET", "/api/v1/cmis", "/openapi.json"));
        assertEquals("passed", send("GET", "/api/v1/cmis", "/openapi.yaml"));
        assertEquals("passed", send("GET", "/api/v1/setup", "/status"));
        assertEquals("passed", send("POST", "/api", "/v1/ingest-webhook/subscribe-slack"));
        assertEquals("passed", send("GET", "/api", "/v1/ingest-webhook/teams-1"));
    }

    @Test
    @DisplayName("a public endpoint's name elsewhere in the path is authenticated — the path is matched whole")
    void aPublicNameElsewhereInThePathIsAuthenticated() throws Exception {
        assertEquals("401", send("GET", "/rest", "/repo/bedroom/user/show/rest/all/build-info"));
        assertEquals("401", send("GET", "/rest", "/repo/bedroom/group/show/rest/all/repositories"));
        assertEquals("401", send("GET", "/rest", "/repo/bedroom/user/show/rest/auth/config"));
        assertEquals("401", send("POST", "/rest", "/repo/bedroom/group/authtoken/oidc/convert"));
        assertEquals("401", send("POST", "/rest", "/repo/bedroom/authtoken/admin/x/login"));
        assertEquals("401", send("POST", "/rest", "/repo/bedroom/user/list/webauthn/authenticate/begin"));
        assertEquals("401", send("POST", "/api/v1/cmis", "/repositories/bedroom/objects/x/auth/repositories/bedroom/login"));
        assertEquals("401", send("GET", "/api/v1/cmis", "/repositories/bedroom/openapi.json"));
        assertEquals("401", send("GET", "/api/v1/cmis", "/auth/repositories/bedroom/me"));
        assertEquals("401", send("POST", "/api/v1/cmis", "/auth/repositories/bedroom/token/refresh"));
    }

    @Test
    @DisplayName("an ingest webhook's subscription routes are an administrator's — and so is any path below a receiver")
    void anIngestWebhooksSubscriptionIsAuthenticated() throws Exception {
        assertEquals("401", send("POST", "/api", "/v1/ingest-webhook/teams-1/subscribe"));
        assertEquals("401", send("DELETE", "/api", "/v1/ingest-webhook/teams-1/subscribe"));
        assertEquals("401", send("POST", "/api", "/v1/ingest-webhook/teams-1/x"));
    }

    @Test
    @DisplayName("the decision is the routed path's, not the request URI's — a URI naming a public endpoint does not let another one through")
    void theRoutedPathDecidesNotTheRequestUri() throws Exception {
        assertEquals("401", send("GET", "/core/rest/all/build-info", "/rest", "/repo/bedroom/user/list"));
        assertEquals("401", send("GET", "/core/rest/repo/bedroom/ui/", "/rest", "/repo/bedroom/user/list"));
        assertEquals("passed", send("GET", "/core/rest/all/build-info;x=1", "/rest", "/all/build-info"));
    }
}
