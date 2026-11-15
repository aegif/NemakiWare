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
package jp.aegif.nemaki.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TextExtractionService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.query.CutShortAnswers;
import jp.aegif.nemaki.cmis.aspect.query.CutShortByAclScanLimit;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.config.ObjectMapperFactory;
import jp.aegif.nemaki.rag.search.VectorSearchService;
import tools.jackson.databind.ObjectMapper;

/**
 * The MCP CMIS search answers an answer the ACL scan limit cut short as a tool error (R344, 3.4.2).
 *
 * <p>The tool formatted every row of the answer with its {@code numItems} as the total ("N件以上"), and an empty window as
 * "no document matched", so while the ACL propagation had not converged the window read as the search's answer (read from
 * the code; the running product cannot be brought into that state with reads alone — the evidence is this fixture).
 *
 * <p>What is locked, on the product's {@code McpToolsProvider.executeSearchTool} with the discovery service a stand-in
 * answering the marks of the product's own producer: every marked answer — the empty one too — is an error result, not a
 * success, whose text is the shared refusal and names no row, id or count; an answer without the mark, or with it
 * {@code false}, is the formatted success as before.
 */
class AnMcpSearchAnswerCutShortIsAToolErrorTest {

    private static McpToolResult search(ObjectList answer) {
        DiscoveryService discovery = mock(DiscoveryService.class);
        when(discovery.query(any(), eq("bedroom"), anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(answer);
        ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();
        McpToolsProvider tools = new McpToolsProvider(mock(McpAuthenticationHandler.class), mock(VectorSearchService.class),
                mock(ContentService.class), mock(TextExtractionService.class), mock(PermissionService.class), mock(TypeManager.class),
                discovery, new McpToolResultFactory(mapper), mapper, "http://localhost/core", "bedroom");
        return tools.executeSearchTool(Map.of("query", "SELECT * FROM cmis:document", "maxItems", 20), "bedroom", "admin");
    }

    @Test
    @DisplayName("every answer the ACL scan limit cut short is a tool error naming no row, id or count — the empty one too")
    void everyCutShortAnswerIsAToolError() {
        for (Map.Entry<String, ? extends ObjectList> c : CutShortAnswers.marked().entrySet()) {
            McpToolResult result = search(c.getValue());
            assertTrue(result.isError(), c.getKey() + " was answered as a success: " + result.getContent());
            assertFalse(result.isSuccess(), c.getKey() + " was answered as a success: " + result.getContent());
            assertEquals("{\"error\":\"" + CutShortByAclScanLimit.REFUSAL + "\"}", result.getContent(),
                    c.getKey() + " was refused by another arm");
            CutShortAnswers.assertNamesNoRowIdOrCount(result.getContent(), c.getKey());
        }
    }

    @Test
    @DisplayName("an answer without the mark, or with it false, is the formatted success as before")
    void anAnswerWithoutTheMarkIsAnsweredAsBefore() {
        McpToolResult unmarked = search(CutShortAnswers.unmarked());
        assertTrue(unmarked.isSuccess() && !unmarked.isError(), "unmarked: " + unmarked.getContent());
        // The formatter escapes the names for markdown; the ids are in the links as they are.
        assertTrue(unmarked.getContent().contains(CutShortAnswers.rowId(1)), "unmarked: the rows " + unmarked.getContent());
        assertTrue(unmarked.getContent().contains(String.valueOf(CutShortAnswers.LOWER_BOUND)), "unmarked: its total " + unmarked.getContent());
        McpToolResult markedFalse = search(CutShortAnswers.markedFalse());
        assertTrue(markedFalse.isSuccess() && !markedFalse.isError(), "marked false: " + markedFalse.getContent());
        assertTrue(markedFalse.getContent().contains(CutShortAnswers.rowId(2)), "marked false: the rows " + markedFalse.getContent());
    }
}
