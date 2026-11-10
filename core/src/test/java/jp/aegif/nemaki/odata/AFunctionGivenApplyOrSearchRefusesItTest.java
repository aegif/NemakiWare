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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.junit.jupiter.api.Test;

/**
 * A function given {@code $apply} or {@code $search} refuses the request (R266 — 3.4.2). The $filter of a function is refused
 * (R252), but the same condition written as {@code $apply=filter(...)} or {@code $search} was dropped: {@code Query(...)?$apply=
 * filter(name eq 'zz-none')} and {@code ?$search=zznone} answered the same 3 as without it, and so did {@code GetAllVersions()} and
 * {@code GetContentChanges(...)} (measured on nb33). Neither is translated: the request is refused with a 400 before any CMIS service
 * is called. A function without them is answered as before.
 *
 * <p>The requests go through Olingo's own handler — its parser, its validator and its dispatcher — to the processors, registered
 * as {@code ODataServlet} registers them ({@link ODataWriteRequests}).
 */
class AFunctionGivenApplyOrSearchRefusesItTest {

    private static final String QUERY =
            "/Query(statement='SELECT%20*%20FROM%20cmis:document',searchAllVersions=false,maxItems=3,skipCount=0)";
    private static final String VERSIONS = "/Documents('D')/NemakiWare.CMIS.GetAllVersions()";
    private static final String CHANGES =
            "/GetContentChanges(changeLogToken='0',includeProperties=false,filter='*',includePolicyIds=false,includeAcl=false,maxItems=3)";

    @Test
    void aFunctionGivenApplyOrSearchIsRefusedBeforeAnythingIsRead() throws Exception {
        for (String function : new String[] { QUERY, VERSIONS, CHANGES }) {
            for (String option : new String[] { "$apply=filter(name%20eq%20'zz-none')", "$apply=aggregate($count%20as%20n)",
                    "$search=zznone" }) {
                ODataWriteRequests requests = new ODataWriteRequests();
                requests.assertRefused(requests.send("GET", function + "?" + option, null),
                        CmisFunctionProcessor.APPLY_OR_SEARCH_NOT_TRANSLATED);
            }
        }
    }

    @Test
    void aFunctionWithoutThemIsAnsweredAsBefore() throws Exception {
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
        verify(requests.discovery).query(eq(requests.callContext), eq("bedroom"), eq("SELECT * FROM cmis:document"), any(), any(), any(),
                any(), any(), any(), any());

        ODataWriteRequests versions = new ODataWriteRequests();
        when(versions.versioning.getAllVersions(any(), eq("bedroom"), eq("D"), any(), any(), any(), any()))
                .thenReturn(List.of(ODataWriteRequests.object("D"), ODataWriteRequests.object("D-1.0")));
        answer = assertDoesNotThrow(() -> versions.send("GET", VERSIONS, null));
        assertEquals(200, answer.status(), answer.body());
        assertEquals(2, answer.body().split("\"objectId\":").length - 1, answer.body());
    }
}
