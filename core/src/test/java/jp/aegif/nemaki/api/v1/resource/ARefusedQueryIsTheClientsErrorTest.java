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
package jp.aegif.nemaki.api.v1.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

/**
 * A statement the repository REFUSES is the client's error — 400 — whatever words its message uses (closing batch 2
 * review, Codex P2).
 *
 * <p>The endpoint used to read "parse" or "syntax" out of the exception's message to choose between 400 and 500, so the
 * refusal of an untranslatable WHERE clause (R188), whose message says neither, came back as a server error here and
 * as a 400 through the CMIS bindings.
 */
class ARefusedQueryIsTheClientsErrorTest {

    private static final String REFUSAL = "The WHERE clause of this statement cannot be translated, so the statement is "
            + "refused rather than answered without its conditions (the WHERE clause has a form this server does not "
            + "translate)";

    private QueryResource resource;

    @BeforeEach
    void setUp() throws Exception {
        DiscoveryService discovery = mock(DiscoveryService.class);
        when(discovery.query(any(), eq("bedroom"), anyString(), anyBoolean(), anyBoolean(), any(), any(), any(), any(),
                any())).thenThrow(new CmisInvalidArgumentException(REFUSAL));
        RepositoryService repositories = mock(RepositoryService.class);
        when(repositories.hasThisRepositoryId("bedroom")).thenReturn(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(mock(CallContext.class));

        resource = new QueryResource();
        set("discoveryService", discovery);
        set("repositoryService", repositories);
        set("httpRequest", request);
    }

    private void set(String name, Object value) throws Exception {
        Field field = QueryResource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(resource, value);
    }

    @Test
    @DisplayName("POST: a refused statement is a 400, not a 500")
    void thePostEndpointAnswersARefusalWith400() {
        ApiException refused = assertThrows(ApiException.class, () -> resource.query("bedroom", false, false, 10, 0,
                Map.of("statement", "SELECT * FROM cmis:document WHERE 'a' = ANY nemaki:labels")));
        assertEquals(400, refused.getStatus(), "the repository refused the statement and the client was told the "
                + "server failed: " + refused.getMessage());
    }

    @Test
    @DisplayName("GET: a refused statement is a 400, not a 500")
    void theGetEndpointAnswersARefusalWith400() {
        ApiException refused = assertThrows(ApiException.class, () -> resource.queryGet("bedroom",
                "SELECT * FROM cmis:document WHERE 'a' = ANY nemaki:labels", false, false, 10, 0));
        assertEquals(400, refused.getStatus(), "the repository refused the statement and the client was told the "
                + "server failed: " + refused.getMessage());
    }
}
