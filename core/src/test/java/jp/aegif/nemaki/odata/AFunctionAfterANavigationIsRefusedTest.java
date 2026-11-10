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

import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.junit.jupiter.api.Test;

/**
 * A function after a navigation segment is refused (R263 — 3.4.2). {@code CmisFunctionProcessor} took the entity a function is
 * bound to from the key of the first segment, so {@code Folders('F')/children('D2')/NemakiWare.CMIS.Document/
 * NemakiWare.CMIS.GetAllVersions()} read the versions of F (a 404 on nb33, F being a folder), and {@code Objects('D')/
 * NemakiWare.CMIS.Folder/children('D2')/…} answered those of D (measured on nb33). The navigation is not translated: such a
 * request is refused with a 400 before any CMIS service is called. A function on the entity its entity set and key name — with
 * or without a cast on that entity — is answered as before.
 *
 * <p>The requests go through Olingo's own handler — its parser, its validator and its dispatcher — to the processors, registered
 * as {@code ODataServlet} registers them ({@link ODataWriteRequests}).
 */
class AFunctionAfterANavigationIsRefusedTest {

    private static final String VERSIONS = "/NemakiWare.CMIS.Document/NemakiWare.CMIS.GetAllVersions()";

    @Test
    void aFunctionAfterANavigationIsRefusedBeforeAnythingIsRead() throws Exception {
        for (String path : new String[] { "/Folders('F')/children('D2')" + VERSIONS,
                "/Objects('D')/NemakiWare.CMIS.Folder/children('D2')" + VERSIONS,
                "/Documents('D')/parent/children('D2')" + VERSIONS }) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(requests.send("GET", path, null), CmisFunctionProcessor.FUNCTION_AFTER_A_NAVIGATION_REFUSED);
        }
    }

    @Test
    void aFunctionAfterANavigationNamedByEntityIdIsRefusedToo() throws Exception {
        ODataWriteRequests requests = new ODataWriteRequests();
        requests.assertRefused(requests.send("GET", "/$entity?$id=Folders('F')/children('D2')" + VERSIONS, null),
                CmisFunctionProcessor.FUNCTION_AFTER_A_NAVIGATION_REFUSED);
    }

    @Test
    void aFunctionOnTheEntityItsKeyNamesIsAnsweredAsBefore() throws Exception {
        for (String path : new String[] { "/Documents('D')/NemakiWare.CMIS.GetAllVersions()", "/Objects('D')" + VERSIONS }) {
            ODataWriteRequests requests = new ODataWriteRequests();
            List<ObjectData> versions = List.of(ODataWriteRequests.object("D"), ODataWriteRequests.object("D-1.0"));
            when(requests.versioning.getAllVersions(any(), eq("bedroom"), eq("D"), any(), any(), any(), any())).thenReturn(versions);
            ODataWriteRequests.Answer answer = assertDoesNotThrow(() -> requests.send("GET", path, null));
            assertEquals(200, answer.status(), path + " was answered " + answer.body());
            verify(requests.versioning).getAllVersions(eq(requests.callContext), eq("bedroom"), eq("D"), any(), any(), any(), any());
            assertEquals(2, answer.body().split("\"objectId\":").length - 1, path + " did not answer D's two versions: " + answer.body());
        }
    }
}
