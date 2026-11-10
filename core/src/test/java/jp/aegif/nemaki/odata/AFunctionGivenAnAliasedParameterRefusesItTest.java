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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.junit.jupiter.api.Test;

/**
 * A function given a parameter as an alias ({@code @name}) refuses the request (R305 — 3.4.2). Olingo gives an aliased parameter
 * no text, and the function read only the text: {@code Query(..., maxItems=@m, ...)?@m=1} answered 195 folders rather than 1, and
 * {@code skipCount=@k?@k=1} skipped none (measured on nb33). The alias is not resolved to its value: the request is refused with
 * a 400 naming the parameter before any CMIS service is called — the statement and the path too, which were refused before as
 * missing. A parameter written as its value is answered as before.
 *
 * <p>The requests go through Olingo's own handler — its parser, its validator and its dispatcher — to the processors, registered
 * as {@code ODataServlet} registers them ({@link ODataWriteRequests}).
 */
class AFunctionGivenAnAliasedParameterRefusesItTest {

    private static final String STATEMENT = "statement='SELECT%20*%20FROM%20cmis:folder'";

    private static ObjectListImpl answered() {
        ObjectListImpl one = new ObjectListImpl();
        one.setObjects(List.<ObjectData>of(ODataWriteRequests.object("F1")));
        one.setNumItems(BigInteger.ONE);
        one.setHasMoreItems(Boolean.TRUE);
        return one;
    }

    @Test
    void aParameterGivenAsAnAliasIsRefusedBeforeAnythingIsRead() throws Exception {
        String[][] aliased = {
                { "maxItems", "/Query(" + STATEMENT + ",searchAllVersions=false,maxItems=@m,skipCount=0)?@m=1" },
                { "skipCount", "/Query(" + STATEMENT + ",searchAllVersions=false,maxItems=2,skipCount=@k)?@k=1" },
                { "searchAllVersions", "/Query(" + STATEMENT + ",searchAllVersions=@v,maxItems=2,skipCount=0)?@v=false" },
                { "statement", "/Query(statement=@s,searchAllVersions=false,maxItems=2,skipCount=0)?@s='SELECT%20*%20FROM%20cmis:folder'" },
                { "path", "/GetObjectByPath(path=@p)?@p='%252F'" },
                { "changeLogToken", "/GetContentChanges(changeLogToken=@t,includeProperties=false,filter='*',includePolicyIds=false,"
                        + "includeAcl=false,maxItems=3)?@t='0'" } };
        for (String[] request : aliased) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(requests.send("GET", request[1], null),
                    request[0] + CmisFunctionProcessor.ALIASED_PARAMETER_REFUSED);
        }
    }

    @Test
    void aParameterWrittenAsItsValueIsAnsweredAsBefore() throws Exception {
        for (String[] request : new String[][] { { "1", "0" }, { "2", "1" } }) {
            ODataWriteRequests requests = new ODataWriteRequests();
            when(requests.discovery.query(any(), eq("bedroom"), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(answered());
            String path = "/Query(" + STATEMENT + ",searchAllVersions=false,maxItems=" + request[0] + ",skipCount=" + request[1] + ")";
            ODataWriteRequests.Answer answer = assertDoesNotThrow(() -> requests.send("GET", path, null));
            assertEquals(200, answer.status(), path + " was answered " + answer.body());
            verify(requests.discovery).query(eq(requests.callContext), eq("bedroom"), eq("SELECT * FROM cmis:folder"), eq(Boolean.FALSE),
                    any(), any(), any(), eq(new BigInteger(request[0])), eq(new BigInteger(request[1])), any());
        }
        ODataWriteRequests byPath = new ODataWriteRequests();
        when(byPath.objects.getObjectByPath(any(), eq("bedroom"), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ODataWriteRequests.object("ROOT"));
        ODataWriteRequests.Answer answer = assertDoesNotThrow(() -> byPath.send("GET", "/GetObjectByPath(path='%252F')", null));
        assertEquals(200, answer.status(), answer.body());
        assertEquals("ROOT", answer.objectId(), answer.body());
    }
}
