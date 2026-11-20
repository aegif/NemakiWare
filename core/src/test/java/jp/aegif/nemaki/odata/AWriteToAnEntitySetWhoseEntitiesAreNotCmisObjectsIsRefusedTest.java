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

import static jp.aegif.nemaki.odata.ODataWriteRequests.ACL;
import static jp.aegif.nemaki.odata.ODataWriteRequests.MALFORMED;
import static jp.aegif.nemaki.odata.ODataWriteRequests.MOVE;
import static jp.aegif.nemaki.odata.ODataWriteRequests.NAME;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.odata.ODataWriteRequests.Answer;

/**
 * A create, an update or a delete of {@code Types}, {@code Users} or {@code Groups} is refused with a 400 before the body,
 * the entity or the repository is read and before any CMIS service is called (R272 — 3.4.2). Their entities are not CMIS
 * objects and are not translated.
 *
 * <p>Measured before the fix through Olingo's own handler and these processors: {@code DELETE Users('admin')},
 * {@code DELETE Types('cmis:document')} and {@code DELETE Groups('g')} called {@code deleteObject} with the key as an object
 * id, and {@code PATCH Users('admin')}, {@code PATCH Types('cmis:document')} and {@code POST Users} answered a 500. Each public
 * entry point the fix changed is named by a test of its own.
 *
 * <p>Every request goes through Olingo's parser and dispatcher to the processors registered as {@code ODataServlet} registers
 * them ({@link ODataWriteRequests}).
 */
class AWriteToAnEntitySetWhoseEntitiesAreNotCmisObjectsIsRefusedTest {

    private static String refused(String entitySet) {
        return WriteTarget.NOT_TRANSLATED + entitySet;
    }

    @Test
    @DisplayName("deleteEntity: DELETE of Types, Users or Groups — with no cast or the entity set's own type — is refused and nothing is read or deleted")
    void deleteEntityRefusesTypesUsersAndGroups() {
        for (String[] request : new String[][] {{"/Users('admin')", "Users"}, {"/Types('cmis:document')", "Types"},
                {"/Groups('g')", "Groups"}, {"/Users('admin')/NemakiWare.CMIS.User", "Users"},
                {"/Types/NemakiWare.CMIS.Type('cmis:document')", "Types"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("DELETE", request[0], null)), refused(request[1]));
        }
    }

    @Test
    @DisplayName("updateEntity: PATCH and PUT of Types, Users or Groups are refused before the body is read and nothing is read or updated")
    void updateEntityRefusesTypesUsersAndGroups() {
        for (String method : List.of("PATCH", "PUT")) {
            for (String[] request : new String[][] {{"/Users('admin')", "Users", NAME},
                    {"/Types('cmis:document')", "Types", NAME}, {"/Groups('g')", "Groups", NAME},
                    {"/Groups('g')/NemakiWare.CMIS.Group", "Groups", NAME}, {"/Users('admin')", "Users", MALFORMED}}) {
                ODataWriteRequests requests = new ODataWriteRequests();
                requests.assertRefused(assertDoesNotThrow(() -> requests.send(method, request[0], request[2])),
                        refused(request[1]));
            }
        }
    }

    @Test
    @DisplayName("createEntity: POST to Types, Users or Groups is refused before the body or the repository is read and nothing is created")
    void createEntityRefusesTypesUsersAndGroups() {
        for (String[] request : new String[][] {{"/Users", "Users", NAME}, {"/Types", "Types", NAME},
                {"/Groups", "Groups", NAME}, {"/Users/NemakiWare.CMIS.User", "Users", NAME}, {"/Users", "Users", MALFORMED}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("POST", request[0], request[2])),
                    refused(request[1]));
        }
    }

    @Test
    @DisplayName("a navigation after Types, Users or Groups and its key is named before the entity set is")
    void aNavigationIsNamedBeforeTheEntitySet() {
        for (String[] request : new String[][] {{"DELETE", "/Users('admin')/groups('g')", null},
                {"PATCH", "/Groups('g')/users('admin')", NAME}, {"POST", "/Users('admin')/groups", NAME},
                {"DELETE", "/Types('cmis:document')/childTypes('cmis:folder')", null}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send(request[0], request[1], request[2])),
                    WriteTarget.NAVIGATION_REFUSED);
        }
    }

    @Test
    @DisplayName("no action is bound to Types, Users or Groups: Olingo refuses one before any processor (measures Olingo, not the fix)")
    void anActionOnTypesUsersOrGroupsIsRefusedBeforeAnyProcessor() {
        for (String[] request : new String[][] {{"/Users('admin')/NemakiWare.CMIS.ApplyAcl", ACL},
                {"/Types('cmis:document')/NemakiWare.CMIS.Move", MOVE}, {"/Groups('g')/NemakiWare.CMIS.ApplyAcl", ACL},
                {"/Users('admin')/NemakiWare.CMIS.CheckOut", null}, {"/Groups('g')/NemakiWare.CMIS.CancelCheckOut", null}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("POST", request[0], request[1]));
            assertEquals(400, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(List.of(), requests.calls(), answer.request() + " called a CMIS service");
        }
    }
}
