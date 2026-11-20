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
package jp.aegif.nemaki.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.aspect.query.CutShortAnswers;
import jp.aegif.nemaki.cmis.aspect.query.CutShortByAclScanLimit;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.model.User;
import jp.aegif.nemaki.util.constant.CallContextKey;
import jp.aegif.nemaki.util.spring.SpringContext;

/**
 * The REST {@code search-engine/cmis-query} refuses an answer the ACL scan limit cut short with a 400 (R344, 3.4.2).
 *
 * <p>The endpoint wrote the answer's {@code numItems}, {@code hasMoreItems} and every row into a success, so while the ACL
 * propagation had not converged the window read as the query's answer (read from the code; the running product cannot be
 * brought into that state with reads alone — the evidence is this fixture).
 *
 * <p>What is locked, on the product's {@code SolrResource.executeCmisQuery} with its services found where the product finds
 * them (the Spring context) and the discovery service a stand-in answering the marks of the product's own producer: every
 * marked answer is an HTTP 400 — not a failure wrapped in a 200 — whose body is a failure carrying the shared refusal and no
 * row, id or count; an answer without the mark, or with it {@code false}, is the success as before.
 */
class ACmisQueryAnswerCutShortIsRefusedWith400Test {

    @AfterEach
    void unhookSpringContext() {
        new SpringContext().setApplicationContext(null);
    }

    private static HttpServletRequest anAdministratorsRequest() {
        CallContext context = mock(CallContext.class);
        when(context.get(CallContextKey.IS_ADMIN)).thenReturn(Boolean.TRUE);
        when(context.getRepositoryId()).thenReturn("bedroom");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("CallContext")).thenReturn(context);
        when(request.getHeader("X-Requested-With")).thenReturn("XMLHttpRequest");
        when(request.getMethod()).thenReturn("POST");
        return request;
    }

    private static String ask(ObjectList answer) {
        DiscoveryService discovery = mock(DiscoveryService.class);
        when(discovery.query(any(), eq("bedroom"), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(answer);
        PrincipalService principals = mock(PrincipalService.class);
        when(principals.getUserById("bedroom", "admin")).thenReturn(mock(User.class));
        ApplicationContext spring = mock(ApplicationContext.class);
        when(spring.getBean("DiscoveryService", DiscoveryService.class)).thenReturn(discovery);
        when(spring.getBean("PrincipalService", PrincipalService.class)).thenReturn(principals);
        new SpringContext().setApplicationContext(spring);
        return new SolrResource().executeCmisQuery("bedroom", "SELECT * FROM cmis:document", null, 10, 0, anAdministratorsRequest());
    }

    @Test
    @DisplayName("every answer the ACL scan limit cut short is an HTTP 400 failure naming no row, id or count")
    void everyCutShortAnswerIsA400() throws Exception {
        for (Map.Entry<String, ? extends ObjectList> c : CutShortAnswers.marked().entrySet()) {
            WebApplicationException refused = assertThrows(WebApplicationException.class, () -> ask(c.getValue()),
                    c.getKey() + " was answered with a 200");
            assertEquals(400, refused.getResponse().getStatus(), c.getKey());
            String body = String.valueOf(refused.getResponse().getEntity());
            JSONObject parsed = (JSONObject) new JSONParser().parse(body);
            assertEquals("failure", parsed.get("status"), c.getKey() + ": " + body);
            assertEquals(List.of(CutShortByAclScanLimit.REFUSAL), (JSONArray) parsed.get("error"), c.getKey() + " was refused by another arm: " + body);
            CutShortAnswers.assertNamesNoRowIdOrCount(body, c.getKey());
        }
    }

    @Test
    @DisplayName("an answer without the mark, or with it false, is the success as before")
    void anAnswerWithoutTheMarkIsAnsweredAsBefore() throws Exception {
        JSONObject unmarked = (JSONObject) new JSONParser().parse(assertDoesNotThrow(() -> ask(CutShortAnswers.unmarked())));
        assertEquals(Long.valueOf(CutShortAnswers.LOWER_BOUND), unmarked.get("numItems"), "unmarked: " + unmarked);
        assertEquals(Boolean.TRUE, unmarked.get("hasMoreItems"), "unmarked: " + unmarked);
        assertEquals(2, ((JSONArray) unmarked.get("objects")).size(), "unmarked: " + unmarked);
        assertTrue(unmarked.toJSONString().contains(CutShortAnswers.rowName(1)), "unmarked: the rows " + unmarked);
        JSONObject markedFalse = (JSONObject) new JSONParser().parse(assertDoesNotThrow(() -> ask(CutShortAnswers.markedFalse())));
        assertEquals(Long.valueOf(2), markedFalse.get("numItems"), "marked false: " + markedFalse);
        assertEquals(2, ((JSONArray) markedFalse.get("objects")).size(), "marked false: " + markedFalse);
    }
}
