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
package jp.aegif.nemaki.odata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.aspect.query.CutShortAnswers;
import jp.aegif.nemaki.cmis.aspect.query.CutShortByAclScanLimit;

/**
 * OData's {@code Query(...)} function refuses an answer the ACL scan limit cut short (R344, 3.4.2).
 *
 * <p>The function converted every row of the answer and set its {@code numItems} as the collection's count, so while the
 * ACL propagation had not converged the window's rows read as the query's answer (read from the code; the running product
 * cannot be brought into that state with reads alone — the evidence is this fixture).
 *
 * <p>What is locked, through Olingo's own handler — its parser, its validator and its dispatcher — to the processors as
 * {@code ODataServlet} registers them ({@link ODataWriteRequests}), with the discovery service a stand-in answering the marks
 * of the product's own producer: every marked answer is a 400 whose message is the shared refusal and whose body names no
 * row, id or count; an answer without the mark, or with it {@code false}, is answered with its rows as before.
 */
class AQueryFunctionAnswerCutShortIsRefusedTest {

    private static final String QUERY =
            "/Query(statement='SELECT%20*%20FROM%20cmis:document',searchAllVersions=false,maxItems=10,skipCount=0)";

    private static ODataWriteRequests.Answer ask(ObjectList answer) throws Exception {
        ODataWriteRequests requests = new ODataWriteRequests();
        when(requests.discovery.query(any(), eq("bedroom"), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(answer);
        return requests.send("GET", QUERY, null);
    }

    @Test
    @DisplayName("every answer the ACL scan limit cut short is a 400 naming no row, id or count")
    void everyCutShortAnswerIsRefused() throws Exception {
        for (Map.Entry<String, ? extends ObjectList> c : CutShortAnswers.marked().entrySet()) {
            ODataWriteRequests.Answer refused = ask(c.getValue());
            assertEquals(400, refused.status(), c.getKey() + ": " + refused.body());
            assertEquals(CutShortByAclScanLimit.REFUSAL, refused.message(), c.getKey() + " was refused by another arm: " + refused.body());
            CutShortAnswers.assertNamesNoRowIdOrCount(refused.body(), c.getKey());
        }
    }

    @Test
    @DisplayName("an answer without the mark, or with it false, is answered with its rows as before")
    void anAnswerWithoutTheMarkIsAnsweredAsBefore() throws Exception {
        for (Map.Entry<String, ObjectList> c : Map.<String, ObjectList>of("unmarked", CutShortAnswers.unmarked(),
                "marked false", CutShortAnswers.markedFalse()).entrySet()) {
            ODataWriteRequests.Answer answered = assertDoesNotThrow(() -> ask(c.getValue()), c.getKey());
            assertEquals(200, answered.status(), c.getKey() + ": " + answered.body());
            assertTrue(answered.body().contains(CutShortAnswers.rowId(1)) && answered.body().contains(CutShortAnswers.rowId(2)),
                    c.getKey() + ": the rows " + answered.body());
        }
    }
}
