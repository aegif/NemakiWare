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
import static jp.aegif.nemaki.odata.ODataWriteRequests.MALFORMED_PARAMETERS;
import static jp.aegif.nemaki.odata.ODataWriteRequests.MOVE;
import static jp.aegif.nemaki.odata.ODataWriteRequests.NAME;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.odata.ODataWriteRequests.Answer;

/**
 * A write or an action is aimed only at the entity set the URL starts with and its key; a navigation after them is refused
 * with a 400 before the body is read or a CMIS service is called (R261, R262 — 3.4.2).
 *
 * <p>Measured before the fix through Olingo's own handler and these processors: {@code DELETE Folders('F')/children('D')}
 * deleted F, {@code PATCH Folders('F')/children('D')} updated F, {@code POST Folders('F')/children} created a folder under the
 * root, {@code POST Folders('F')/children('D')/NemakiWare.CMIS.ApplyAcl} replaced F's ACL and
 * {@code POST Folders('F')/children('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CancelCheckOut} cancelled F's check-out.
 * Each public entry point the fix changed is named by a test of its own, refused and answered.
 *
 * <p>Every request goes through Olingo's parser and dispatcher to the processors registered as {@code ODataServlet} registers
 * them ({@link ODataWriteRequests}).
 */
class AWriteOrAnActionIsAimedOnlyAtTheEntitySetAndKeyItNamesTest {

    private static final String REFUSED = WriteTarget.NAVIGATION_REFUSED;

    @Test
    @DisplayName("deleteEntity: a navigation after the key — two segments or more, before or after the entity set's own type — is refused and nothing is deleted")
    void deleteEntityRefusesANavigationAfterTheKey() {
        for (String path : List.of("/Folders('F')/children('D')", "/Documents('D')/parent", "/Folders('F')/parent",
                "/Folders('F')/parent/children('D')", "/Documents('D')/parents('F')/children('D2')",
                "/Folders('F')/NemakiWare.CMIS.Folder/children('D')", "/Folders('F')/children('D')/NemakiWare.CMIS.Document")) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("DELETE", path, null)), REFUSED);
        }
    }

    @Test
    @DisplayName("updateEntity: PATCH and PUT with a navigation after the key are refused before the body is read and nothing is updated")
    void updateEntityRefusesANavigationAfterTheKey() {
        for (String method : List.of("PATCH", "PUT")) {
            for (String[] request : new String[][] {{"/Folders('F')/children('D')", NAME}, {"/Documents('D')/parent", NAME},
                    {"/Folders('F')/parent/children('D')", NAME}, {"/Documents('D')/parents('F')/children('D2')", NAME},
                    {"/Folders('F')/NemakiWare.CMIS.Folder/children('D')", NAME},
                    {"/Folders('F')/children('D')", MALFORMED}}) {
                ODataWriteRequests requests = new ODataWriteRequests();
                requests.assertRefused(assertDoesNotThrow(() -> requests.send(method, request[0], request[1])), REFUSED);
            }
        }
    }

    @Test
    @DisplayName("createEntity: a POST to a navigation is refused before the body is read and nothing is created")
    void createEntityRefusesANavigationAfterTheEntitySet() {
        for (String[] request : new String[][] {{"/Folders('F')/children", NAME}, {"/Documents('D')/parents", NAME},
                {"/Folders('F')/parent/children", NAME}, {"/Folders('F')/children/NemakiWare.CMIS.Folder", NAME},
                {"/Folders('F')/NemakiWare.CMIS.Folder/children", NAME}, {"/Folders('F')/children", MALFORMED}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("POST", request[0], request[1])), REFUSED);
        }
    }

    @Test
    @DisplayName("processActionEntity: an action after a navigation is refused before its parameters are read and is not applied")
    void processActionEntityRefusesANavigationBeforeTheAction() {
        for (String[] request : new String[][] {
                {"/Documents('D')/parent/NemakiWare.CMIS.ApplyAcl", ACL},
                {"/Folders('F')/children('D')/NemakiWare.CMIS.ApplyAcl", ACL},
                {"/Documents('D')/parent/NemakiWare.CMIS.Move", MOVE},
                {"/Folders('F')/children('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CheckOut", null},
                {"/Folders('F')/parent/children('D')/NemakiWare.CMIS.ApplyAcl", ACL},
                {"/Folders('F')/NemakiWare.CMIS.Folder/children('D')/NemakiWare.CMIS.ApplyAcl", ACL},
                {"/Folders('F')/children('D')/NemakiWare.CMIS.ApplyAcl", MALFORMED_PARAMETERS}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("POST", request[0], request[1])), REFUSED);
        }
    }

    @Test
    @DisplayName("processActionVoid: an action that answers nothing after a navigation is refused and is not applied")
    void processActionVoidRefusesANavigationBeforeTheAction() {
        for (String[] request : new String[][] {
                {"/Folders('F')/children('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CancelCheckOut", null},
                {"/Documents('D')/parent/children('D2')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CancelCheckOut", null},
                {"/Folders('F')/NemakiWare.CMIS.Folder/children('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CancelCheckOut", null},
                {"/Folders('F')/children('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CancelCheckOut", "{}"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("POST", request[0], request[1])), REFUSED);
        }
    }

    @Test
    @DisplayName("each entry point refuses a write or an action that does not start with an entity set with a 400, not a 500")
    void aWriteOrAnActionThatDoesNotStartWithAnEntitySetIsRefused() {
        // Olingo dispatches these to the processors only when the EDM has a singleton (or a composable function); before
        // the fix updateEntity, createEntity and deleteEntity answered a 500 naming Olingo's classes (ClassCastException).
        for (String[] request : new String[][] {{"PATCH", "/TheDocument", NAME}, {"PUT", "/TheDocument", NAME},
                {"POST", "/TheFolder/children", NAME}, {"DELETE", "/TheFolder/children('D')", null},
                {"POST", "/TheDocument/NemakiWare.CMIS.CheckOut", null},
                {"POST", "/TheFolder/NemakiWare.CMIS.ApplyAcl", ACL},
                {"POST", "/TheDocument/NemakiWare.CMIS.CancelCheckOut", null}}) {
            ODataWriteRequests requests = new ODataWriteRequests(true);
            requests.assertRefused(assertDoesNotThrow(() -> requests.send(request[0], request[1], request[2])),
                    WriteTarget.NOT_AN_ENTITY_SET);
        }
    }

    @Test
    @DisplayName("with the servlet's own EDM Olingo refuses every write or action that does not start with an entity set before any processor (measures Olingo, not the fix)")
    void theServletsEdmHasNoWriteThatDoesNotStartWithAnEntitySet() {
        for (String[] request : new String[][] {{"DELETE", "/GetObjectByPath(path='%252F')", "405"},
                {"PATCH", "/GetObjectByPath(path='%252F')", "405"},
                {"DELETE", "/GetObjectByPath(path='%252F')/children('D')", "400"},
                {"PATCH", "/GetObjectByPath(path='%252F')/children('D')", "400"},
                {"POST", "/GetObjectByPath(path='%252F')/children", "400"},
                {"POST", "/GetObjectByPath(path='%252F')/NemakiWare.CMIS.ApplyAcl", "400"},
                {"DELETE", "/$entity?$id=Documents('D')", "400"}, {"PATCH", "/$entity?$id=Documents('D')", "400"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send(request[0], request[1],
                    request[0].equals("DELETE") ? null : request[1].endsWith("ApplyAcl") ? ACL : NAME));
            assertEquals(Integer.parseInt(request[2]), answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(List.of(), requests.calls(), answer.request() + " called a CMIS service");
        }
    }

    @Test
    @DisplayName("deleteEntity: the entity set and its key alone delete that entity, as before")
    void deleteEntityDeletesTheEntityItsKeyNames() {
        for (String[] request : new String[][] {{"/Documents('D')", "D"}, {"/Folders('F')", "F"}, {"/Objects('D')", "D"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("DELETE", request[0], null));
            assertEquals(204, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(List.of("deleteObject(bedroom, " + request[1] + ")"), requests.writes(), answer.request());
        }
    }

    @Test
    @DisplayName("updateEntity: PATCH and PUT on the entity set and its key update that entity and answer it, as before")
    void updateEntityUpdatesTheEntityItsKeyNames() {
        for (String method : List.of("PATCH", "PUT")) {
            for (String[] request : new String[][] {{"/Documents('D')", "D"}, {"/Folders('F')", "F"}, {"/Objects('D')", "D"}}) {
                ODataWriteRequests requests = new ODataWriteRequests();
                Answer answer = assertDoesNotThrow(() -> requests.send(method, request[0], NAME));
                assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
                assertEquals(request[1], answer.objectId(), answer.request());
                assertTrue(requests.bodyWasRead(), answer.request() + " did not read its body");
                assertEquals(List.of("updateProperties(bedroom, " + request[1] + ")"), requests.writes(), answer.request());
            }
        }
    }

    @Test
    @DisplayName("createEntity: a POST to Folders or Documents creates under the root and answers the new entity, as before")
    void createEntityCreatesInTheEntitySetItNames() {
        for (String[] request : new String[][] {{"/Folders", "createFolder", "NEWFOLDER"},
                {"/Documents", "createDocument", "NEWDOC"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("POST", request[0], NAME));
            assertEquals(201, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(request[2], answer.objectId(), answer.request());
            assertEquals(List.of(request[1] + "(bedroom, ROOT)"), requests.writes(), answer.request());
        }
    }

    @Test
    @DisplayName("processActionEntity: an action on the entity set and its key is applied to that entity, as before")
    void processActionEntityActsOnTheEntityItsKeyNames() {
        for (String[] request : new String[][] {
                {"/Documents('D')/NemakiWare.CMIS.CheckOut", null, "checkOut(bedroom, D)"},
                {"/Objects('D')/NemakiWare.CMIS.ApplyAcl", ACL, "applyAcl(bedroom, D)"},
                {"/Folders('F')/NemakiWare.CMIS.ApplyAcl", ACL, "applyAcl(bedroom, F)"},
                {"/Documents('D')/NemakiWare.CMIS.Move", MOVE, "moveObject(bedroom, D)"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("POST", request[0], request[1]));
            assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(request[2].substring(request[2].indexOf(", ") + 2, request[2].length() - 1), answer.objectId(),
                    answer.request());
            assertEquals(List.of(request[2]), requests.writes(), answer.request());
        }
    }

    @Test
    @DisplayName("processActionVoid: CancelCheckOut on the entity set and its key cancels that check-out, as before")
    void processActionVoidActsOnTheEntityItsKeyNames() {
        for (String body : new String[] {null, "{}"}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("POST", "/Documents('D')/NemakiWare.CMIS.CancelCheckOut", body));
            assertEquals(204, answer.status(), answer.request() + " " + body + " was answered " + answer.body());
            assertEquals(List.of("cancelCheckOut(bedroom, D)"), requests.writes(), answer.request() + " " + body);
            if (body != null) {
                assertTrue(requests.bodyWasRead(), answer.request() + " did not read its body");
            }
        }
    }
}
