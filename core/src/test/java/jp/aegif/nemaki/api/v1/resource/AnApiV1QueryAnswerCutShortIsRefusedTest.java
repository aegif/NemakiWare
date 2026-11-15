/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package jp.aegif.nemaki.api.v1.resource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.api.v1.exception.ApiException;
import jp.aegif.nemaki.api.v1.model.response.ObjectListResponse;
import jp.aegif.nemaki.api.v1.model.response.ObjectResponse;
import jp.aegif.nemaki.cmis.aspect.query.CutShortAnswers;
import jp.aegif.nemaki.cmis.aspect.query.CutShortByAclScanLimit;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.RepositoryService;

/**
 * API v1's query refuses an answer the ACL scan limit cut short, on GET and on POST (R344, 3.4.2).
 *
 * <p>The endpoint wrote the answer's {@code numItems} and {@code hasMoreItems} as they came, so while the ACL propagation had
 * not converged a lower bound read as the total and the window's end as the last page (read from the code; the running
 * product cannot be brought into that state with reads alone — the evidence is this fixture).
 *
 * <p>What is locked, on the product's {@code QueryResource.query} and {@code queryGet} with the discovery service a stand-in
 * answering the marks of the product's own producer: every marked answer — rows with more inside the window, the end of the
 * window, no row, a count equal to its rows — is a 400 whose words are the shared refusal and name no row, id or count; an
 * answer without the mark, or with it {@code false}, is answered as before.
 */
class AnApiV1QueryAnswerCutShortIsRefusedTest {

    private static final String STATEMENT = "SELECT * FROM cmis:document";

    private static QueryResource resourceAnswering(ObjectList answer) throws Exception {
        DiscoveryService discovery = mock(DiscoveryService.class);
        when(discovery.query(any(), eq("bedroom"), anyString(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(answer);
        RepositoryService repositories = mock(RepositoryService.class);
        when(repositories.hasThisRepositoryId("bedroom")).thenReturn(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(mock(CallContext.class));
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost/core/api/v1/cmis/"));
        QueryResource resource = new QueryResource();
        set(resource, "discoveryService", discovery);
        set(resource, "repositoryService", repositories);
        set(resource, "httpRequest", request);
        set(resource, "uriInfo", uriInfo);
        return resource;
    }

    private static void set(QueryResource resource, String name, Object value) throws Exception {
        Field field = QueryResource.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(resource, value);
    }

    private static void assertRefused(ApiException refused, String what) {
        assertEquals(400, refused.getStatus(), what + ": " + refused.getMessage());
        assertEquals(CutShortByAclScanLimit.REFUSAL, refused.getMessage(), what + " was refused by another arm");
        CutShortAnswers.assertNamesNoRowIdOrCount(refused.getMessage(), what);
    }

    @Test
    @DisplayName("POST: every answer the ACL scan limit cut short is a 400 naming no row, id or count")
    void thePostEndpointRefusesEveryCutShortAnswer() throws Exception {
        for (Map.Entry<String, ? extends ObjectList> c : CutShortAnswers.marked().entrySet()) {
            QueryResource resource = resourceAnswering(c.getValue());
            ApiException refused = assertThrows(ApiException.class,
                    () -> resource.query("bedroom", false, false, 10, 0, Map.of("statement", STATEMENT)), "POST " + c.getKey());
            assertRefused(refused, "POST " + c.getKey());
        }
    }

    @Test
    @DisplayName("GET: every answer the ACL scan limit cut short is a 400 naming no row, id or count")
    void theGetEndpointRefusesEveryCutShortAnswer() throws Exception {
        for (Map.Entry<String, ? extends ObjectList> c : CutShortAnswers.marked().entrySet()) {
            QueryResource resource = resourceAnswering(c.getValue());
            ApiException refused = assertThrows(ApiException.class,
                    () -> resource.queryGet("bedroom", STATEMENT, false, false, 10, 0), "GET " + c.getKey());
            assertRefused(refused, "GET " + c.getKey());
        }
    }

    @Test
    @DisplayName("an answer without the mark, or with it false, is answered as before on both")
    void anAnswerWithoutTheMarkIsAnsweredAsBefore() throws Exception {
        Response post = assertDoesNotThrow(() -> resourceAnswering(CutShortAnswers.unmarked())
                .query("bedroom", false, false, 10, 0, Map.of("statement", STATEMENT)), "POST unmarked");
        assertAnswered(post, 2, CutShortAnswers.LOWER_BOUND, true, "POST unmarked");
        Response get = assertDoesNotThrow(() -> resourceAnswering(CutShortAnswers.unmarked())
                .queryGet("bedroom", STATEMENT, false, false, 10, 0), "GET unmarked");
        ObjectListResponse getBody = assertAnswered(get, 2, CutShortAnswers.LOWER_BOUND, true, "GET unmarked");
        assertTrue(getBody.getLinks().containsKey("next"), "GET unmarked: the next link of an answer with more");
        Response postFalse = assertDoesNotThrow(() -> resourceAnswering(CutShortAnswers.markedFalse())
                .query("bedroom", false, false, 10, 0, Map.of("statement", STATEMENT)), "POST marked false");
        assertAnswered(postFalse, 2, 2, false, "POST marked false");
        Response getFalse = assertDoesNotThrow(() -> resourceAnswering(CutShortAnswers.markedFalse())
                .queryGet("bedroom", STATEMENT, false, false, 10, 0), "GET marked false");
        assertAnswered(getFalse, 2, 2, false, "GET marked false");
    }

    private static ObjectListResponse assertAnswered(Response response, int rows, long numItems, boolean more, String what) {
        assertEquals(200, response.getStatus(), what);
        ObjectListResponse body = (ObjectListResponse) response.getEntity();
        List<ObjectResponse> objects = body.getObjects();
        assertEquals(rows, objects.size(), what + ": the rows");
        assertEquals(CutShortAnswers.rowName(1), objects.get(0).getName(), what + ": the first row");
        assertEquals(numItems, body.getNumItems(), what + ": numItems");
        assertEquals(more, body.getHasMoreItems(), what + ": hasMoreItems");
        return body;
    }
}
