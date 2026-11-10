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
package jp.aegif.nemaki.odata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.junit.jupiter.api.Test;

/**
 * A function given {@code $top}, {@code $skip}, {@code $skiptoken}, {@code $count}, {@code $orderby}, {@code $select} or
 * {@code $expand} refuses the request (R259 — 3.4.2). The function processor read none of them: {@code Query(...)} with
 * {@code $top=1}, {@code $skip=2}, {@code $skiptoken}, {@code $top=0} or {@code $count=true} answered the same 3 with no count
 * (measured on nb33). None is translated: the request is refused with a 400, naming the option, before any CMIS service is called.
 * A function that answers one entity is given only {@code $select} and {@code $expand} — Olingo refuses the others first — and
 * refuses those. {@code $filter} is refused on its own, as before (R252). A function without them is answered as before.
 *
 * <p>The requests go through Olingo's own handler — its parser, its validator and its dispatcher — to the processors, registered
 * as {@code ODataServlet} registers them ({@link ODataWriteRequests}).
 */
class AFunctionGivenPagingOrShapingRefusesItTest {

    private static final String QUERY =
            "/Query(statement='SELECT%20*%20FROM%20cmis:document',searchAllVersions=false,maxItems=3,skipCount=0)";
    private static final String VERSIONS = "/Documents('D')/NemakiWare.CMIS.GetAllVersions()";
    private static final String CHANGES =
            "/GetContentChanges(changeLogToken='0',includeProperties=false,filter='*',includePolicyIds=false,includeAcl=false,maxItems=3)";
    private static final String BY_PATH = "/GetObjectByPath(path='%252F')";

    private static final String[][] OPTIONS = { { "$top", "$top=1" }, { "$top", "$top=0" }, { "$skip", "$skip=2" },
            { "$skiptoken", "$skiptoken=opaque" }, { "$count", "$count=true" }, { "$count", "$count=false" },
            { "$orderby", "$orderby=name%20desc" }, { "$select", "$select=name" }, { "$expand", "$expand=*" } };

    @Test
    void aFunctionThatAnswersACollectionGivenAnyOfThemIsRefused() throws Exception {
        for (String function : new String[] { QUERY, VERSIONS, CHANGES }) {
            for (String[] option : OPTIONS) {
                ODataWriteRequests requests = new ODataWriteRequests();
                requests.assertRefused(requests.send("GET", function + "?" + option[1], null),
                        option[0] + CmisFunctionProcessor.OPTION_NOT_TRANSLATED);
            }
        }
        ODataWriteRequests requests = new ODataWriteRequests();
        requests.assertRefused(requests.send("GET", VERSIONS + "?$expand=parent", null), "$expand" + CmisFunctionProcessor.OPTION_NOT_TRANSLATED);
    }

    @Test
    void aFunctionThatAnswersOneEntityGivenSelectOrExpandIsRefused() throws Exception {
        for (String path : new String[] { BY_PATH + "?$select=name", "/$entity?$id=GetObjectByPath(path='%252F')&$select=name" }) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(requests.send("GET", path, null), "$select" + CmisFunctionProcessor.OPTION_NOT_TRANSLATED);
        }
        ODataWriteRequests requests = new ODataWriteRequests();
        requests.assertRefused(requests.send("GET", BY_PATH + "?$expand=*", null), "$expand" + CmisFunctionProcessor.OPTION_NOT_TRANSLATED);
    }

    @Test
    void aFilterIsRefusedAsBeforeAndAFunctionWithoutThemIsAnswered() throws Exception {
        ODataWriteRequests filtered = new ODataWriteRequests();
        filtered.assertRefused(filtered.send("GET", QUERY + "?$filter=name%20eq%20'zz-none'", null),
                "$filter is not translated on a function — put the condition in the statement");

        ODataWriteRequests requests = new ODataWriteRequests();
        ObjectListImpl three = new ObjectListImpl();
        three.setObjects(List.<ObjectData>of(ODataWriteRequests.object("D1"), ODataWriteRequests.object("D2"),
                ODataWriteRequests.object("D3")));
        three.setNumItems(BigInteger.valueOf(3));
        three.setHasMoreItems(Boolean.FALSE);
        when(requests.discovery.query(any(), eq("bedroom"), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(three);
        ODataWriteRequests.Answer answer = assertDoesNotThrow(() -> requests.send("GET", QUERY, null));
        assertEquals(200, answer.status(), answer.body());
        assertEquals(3, answer.body().split("\"objectId\":").length - 1, answer.body());

        ODataWriteRequests byPath = new ODataWriteRequests();
        when(byPath.objects.getObjectByPath(any(), eq("bedroom"), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ODataWriteRequests.object("ROOT"));
        answer = assertDoesNotThrow(() -> byPath.send("GET", BY_PATH, null));
        assertEquals(200, answer.status(), answer.body());
        assertEquals("ROOT", answer.objectId(), answer.body());
    }
}
