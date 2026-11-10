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

import org.junit.jupiter.api.Test;

/**
 * A cast on {@code $entity} of a function that answers one entity is answered only when it is the type the function returns
 * (R269 — 3.4.2). The function processor did not read the cast: {@code $entity/NemakiWare.CMIS.Policy?$id=GetObjectByPath(path='/')}
 * answered the root folder (measured on nb33). Olingo gives the type the function returns when no cast is written; any other
 * cast — the type of the entity read among them, and a narrower one — is refused with a 400 before any CMIS service is called.
 *
 * <p>The requests go through Olingo's own handler — its parser, its validator and its dispatcher — to the processors, registered
 * as {@code ODataServlet} registers them ({@link ODataWriteRequests}).
 */
class ACastOnEntityOfAOneEntityFunctionIsAnsweredOnlyForItsReturnTypeTest {

    private static final String BY_PATH = "$id=GetObjectByPath(path='%252F')";

    private static ODataWriteRequests requests() {
        ODataWriteRequests requests = new ODataWriteRequests();
        when(requests.objects.getObjectByPath(any(), eq("bedroom"), anyString(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ODataWriteRequests.object("ROOT"));
        return requests;
    }

    @Test
    void aCastToAnotherTypeThanTheFunctionReturnsIsRefusedBeforeAnythingIsRead() throws Exception {
        // The root is a folder: a cast to the type of the entity read is refused too — only the return type is answered.
        for (String cast : new String[] { "NemakiWare.CMIS.Policy", "NemakiWare.CMIS.Folder", "NemakiWare.CMIS.Document",
                "NemakiWare.CMIS.Item" }) {
            ODataWriteRequests requests = requests();
            requests.assertRefused(requests.send("GET", "/$entity/" + cast + "?" + BY_PATH, null),
                    CmisFunctionProcessor.CAST_ON_A_FUNCTION_REFUSED + cast);
        }
    }

    @Test
    void noCastOrTheReturnTypeIsAnsweredAsTheFunctionIs() throws Exception {
        for (String path : new String[] { "/$entity?" + BY_PATH, "/$entity/NemakiWare.CMIS.Object?" + BY_PATH,
                "/GetObjectByPath(path='%252F')" }) {
            ODataWriteRequests requests = requests();
            ODataWriteRequests.Answer answer = assertDoesNotThrow(() -> requests.send("GET", path, null));
            assertEquals(200, answer.status(), path + " was answered " + answer.body());
            assertEquals("ROOT", answer.objectId(), path + " did not answer the object at the path");
            verify(requests.objects).getObjectByPath(eq(requests.callContext), eq("bedroom"), anyString(), any(), any(), any(), any(),
                    any(), any(), any());
        }
    }
}
