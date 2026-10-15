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
package jp.aegif.nemaki.api.v1.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;

/**
 * The API v1 application's own filter lets through without credentials what the REST filter's table names, matched whole
 * (3.4.1, zero-trust P0 — batch 15 review round 1: subagent P3, "a second reader of the same rule"). It found
 * {@code .../openapi.json} and {@code auth/.../oidc} anywhere in the path; the outer filter refuses those first today, but
 * nothing here said so.
 */
class ApiAuthenticationFilterPublicPathsTest {

    /** What the filter did with a request for {@code path} (relative to /api/v1/cmis), carrying no credentials. */
    private static String filter(String path) throws Exception {
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter();
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        Field field = ApiAuthenticationFilter.class.getDeclaredField("httpServletRequest");
        field.setAccessible(true);
        field.set(filter, servletRequest);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        when(request.getUriInfo()).thenReturn(uri);
        when(request.getHeaderString(any())).thenReturn(null);
        filter.filter(request);
        return mockingDetails(request).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("abortWith"))
                ? "refused" : "let through";
    }

    @Test
    @DisplayName("the OpenAPI document and the logins are let through")
    void theOpenApiDocumentAndTheLoginsAreLetThrough() throws Exception {
        assertEquals("let through", filter("openapi.json"));
        assertEquals("let through", filter("openapi.yaml"));
        assertEquals("let through", filter("auth/repositories/bedroom/login"));
        assertEquals("let through", filter("auth/repositories/bedroom/saml"));
        assertEquals("let through", filter("auth/repositories/bedroom/oidc"));
    }

    @Test
    @DisplayName("their names elsewhere in the path are not — the path is matched whole")
    void theirNamesElsewhereInThePathAreNot() throws Exception {
        assertEquals("refused", filter("repositories/bedroom/openapi.json"));
        assertEquals("refused", filter("repositories/bedroom/objects/x/auth/repositories/bedroom/oidc"));
        assertEquals("refused", filter("auth/repositories/bedroom/oidc/x"));
        assertEquals("refused", filter("auth/repositories/bedroom/me"));
    }
}
