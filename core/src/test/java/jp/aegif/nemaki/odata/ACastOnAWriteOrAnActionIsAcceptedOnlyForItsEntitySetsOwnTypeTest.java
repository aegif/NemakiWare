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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.odata.ODataWriteRequests.Answer;

/**
 * A type cast on a write or an action is accepted only when it is the entity set's own type; any other is refused with a 400
 * before the body is read or a CMIS service is called (R270 — 3.4.2).
 *
 * <p>Measured before the fix through Olingo's own handler and these processors: {@code DELETE Objects('D')/NemakiWare.CMIS.Folder}
 * and {@code DELETE Objects/NemakiWare.CMIS.Policy('D')} deleted the document D, {@code PATCH Objects('D')/NemakiWare.CMIS.Policy}
 * updated it, {@code POST Objects('D')/NemakiWare.CMIS.Policy/NemakiWare.CMIS.ApplyAcl} replaced its ACL. The type of the
 * entity the key names is not read: {@code Objects('D')/NemakiWare.CMIS.Document} is refused for the document D too, since
 * the cast is decided by the URL alone (the owner's rule, as for R254). The cast before the key and the one after it are each
 * read, and a cast to another type is named before a navigation that follows it.
 *
 * <p>Every request goes through Olingo's parser and dispatcher to the processors registered as {@code ODataServlet} registers
 * them ({@link ODataWriteRequests}).
 */
class ACastOnAWriteOrAnActionIsAcceptedOnlyForItsEntitySetsOwnTypeTest {

    private static String refused(String type) {
        return WriteTarget.CAST_REFUSED + "NemakiWare.CMIS." + type;
    }

    @Test
    @DisplayName("deleteEntity: a cast to another type after the key or before it is refused and nothing is deleted")
    void deleteEntityRefusesACastToAnotherType() {
        for (String[] request : new String[][] {{"/Objects('D')/NemakiWare.CMIS.Folder", "Folder"},
                {"/Objects/NemakiWare.CMIS.Policy('D')", "Policy"}, {"/Objects('D')/NemakiWare.CMIS.Document", "Document"},
                {"/Objects/NemakiWare.CMIS.Document('D')", "Document"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("DELETE", request[0], null)), refused(request[1]));
        }
    }

    @Test
    @DisplayName("updateEntity: PATCH and PUT with a cast to another type are refused before the body is read and nothing is updated")
    void updateEntityRefusesACastToAnotherType() {
        for (String method : List.of("PATCH", "PUT")) {
            for (String[] request : new String[][] {{"/Objects('D')/NemakiWare.CMIS.Policy", "Policy", NAME},
                    {"/Objects/NemakiWare.CMIS.Folder('D')", "Folder", NAME},
                    {"/Objects('D')/NemakiWare.CMIS.Document", "Document", NAME},
                    {"/Objects('D')/NemakiWare.CMIS.Policy", "Policy", MALFORMED}}) {
                ODataWriteRequests requests = new ODataWriteRequests();
                requests.assertRefused(assertDoesNotThrow(() -> requests.send(method, request[0], request[2])),
                        refused(request[1]));
            }
        }
    }

    @Test
    @DisplayName("createEntity: a POST to an entity set cast to another type is refused before the body is read and nothing is created")
    void createEntityRefusesACastToAnotherType() {
        for (String[] request : new String[][] {{"/Objects/NemakiWare.CMIS.Folder", "Folder", NAME},
                {"/Objects/NemakiWare.CMIS.Document", "Document", NAME}, {"/Objects/NemakiWare.CMIS.Folder", "Folder", MALFORMED}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("POST", request[0], request[2])), refused(request[1]));
        }
    }

    @Test
    @DisplayName("processActionEntity: an action on an entity cast to another type is refused before its parameters are read and is not applied")
    void processActionEntityRefusesACastToAnotherType() {
        for (String[] request : new String[][] {
                {"/Objects('D')/NemakiWare.CMIS.Policy/NemakiWare.CMIS.ApplyAcl", "Policy", ACL},
                {"/Objects('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CheckOut", "Document", null},
                {"/Objects/NemakiWare.CMIS.Folder('D')/NemakiWare.CMIS.Move", "Folder", MOVE},
                {"/Objects/NemakiWare.CMIS.Document('D')/NemakiWare.CMIS.CheckOut", "Document", null},
                {"/Objects('D')/NemakiWare.CMIS.Policy/NemakiWare.CMIS.ApplyAcl", "Policy", MALFORMED_PARAMETERS}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("POST", request[0], request[2])), refused(request[1]));
        }
    }

    @Test
    @DisplayName("processActionVoid: CancelCheckOut on an entity cast to another type is refused and is not applied")
    void processActionVoidRefusesACastToAnotherType() {
        for (String[] request : new String[][] {{"/Objects('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CancelCheckOut", null},
                {"/Objects/NemakiWare.CMIS.Document('D')/NemakiWare.CMIS.CancelCheckOut", null},
                {"/Objects('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CancelCheckOut", "{}"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("POST", request[0], request[1])), refused("Document"));
        }
    }

    @Test
    @DisplayName("a cast to another type is named before a navigation that follows it; the entity set's own type before one is not")
    void aCastToAnotherTypeIsNamedBeforeANavigationAfterIt() {
        for (String[] request : new String[][] {{"DELETE", "/Objects('F')/NemakiWare.CMIS.Folder/children('D')", null},
                {"PATCH", "/Objects('F')/NemakiWare.CMIS.Folder/children('D')", NAME},
                {"POST", "/Objects/NemakiWare.CMIS.Folder('F')/children", NAME},
                {"POST", "/Objects('F')/NemakiWare.CMIS.Folder/children('D')/NemakiWare.CMIS.ApplyAcl", ACL}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send(request[0], request[1], request[2])),
                    refused("Folder"));
        }
        ODataWriteRequests requests = new ODataWriteRequests();
        requests.assertRefused(assertDoesNotThrow(() -> requests.send("DELETE",
                "/Folders('F')/NemakiWare.CMIS.Folder/children('D')", null)), WriteTarget.NAVIGATION_REFUSED);
    }

    @Test
    @DisplayName("deleteEntity: the entity set's own type, after the key or before it, deletes the entity the key names, as before")
    void deleteEntityAcceptsItsEntitySetsOwnType() {
        for (String[] request : new String[][] {{"/Documents('D')/NemakiWare.CMIS.Document", "D"},
                {"/Documents/NemakiWare.CMIS.Document('D')", "D"}, {"/Objects('D')/NemakiWare.CMIS.Object", "D"},
                {"/Objects/NemakiWare.CMIS.Object('D')", "D"}, {"/Folders('F')/NemakiWare.CMIS.Folder", "F"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("DELETE", request[0], null));
            assertEquals(204, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(List.of("deleteObject(bedroom, " + request[1] + ")"), requests.writes(), answer.request());
        }
    }

    @Test
    @DisplayName("updateEntity: the entity set's own type updates the entity the key names and answers it, as before")
    void updateEntityAcceptsItsEntitySetsOwnType() {
        for (String[] request : new String[][] {{"PATCH", "/Documents('D')/NemakiWare.CMIS.Document", "D"},
                {"PUT", "/Objects/NemakiWare.CMIS.Object('D')", "D"}, {"PATCH", "/Objects('D')/NemakiWare.CMIS.Object", "D"},
                {"PUT", "/Folders/NemakiWare.CMIS.Folder('F')", "F"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send(request[0], request[1], NAME));
            assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(request[2], answer.objectId(), answer.request());
            assertEquals(List.of("updateProperties(bedroom, " + request[2] + ")"), requests.writes(), answer.request());
        }
    }

    @Test
    @DisplayName("createEntity: an entity set cast to its own type creates as before; Objects cast to its own type stays not supported")
    void createEntityAcceptsItsEntitySetsOwnType() {
        for (String[] request : new String[][] {{"/Folders/NemakiWare.CMIS.Folder", "createFolder", "NEWFOLDER"},
                {"/Documents/NemakiWare.CMIS.Document", "createDocument", "NEWDOC"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("POST", request[0], NAME));
            assertEquals(201, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(request[2], answer.objectId(), answer.request());
            assertEquals(List.of(request[1] + "(bedroom, ROOT)"), requests.writes(), answer.request());
        }
        ODataWriteRequests requests = new ODataWriteRequests();
        Answer objects = assertDoesNotThrow(() -> requests.send("POST", "/Objects/NemakiWare.CMIS.Object", NAME));
        assertEquals(501, objects.status(), objects.request() + " was answered " + objects.body());
        assertEquals("Create not supported for entity set: Objects", objects.message(), objects.request());
        assertEquals(List.of(), requests.writes(), objects.request());
    }

    @Test
    @DisplayName("processActionEntity: an action on the entity set's own type is applied to the entity the key names, as before")
    void processActionEntityAcceptsItsEntitySetsOwnType() {
        for (String[] request : new String[][] {
                {"/Documents('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CheckOut", null, "checkOut(bedroom, D)"},
                {"/Documents/NemakiWare.CMIS.Document('D')/NemakiWare.CMIS.CheckOut", null, "checkOut(bedroom, D)"},
                {"/Objects('D')/NemakiWare.CMIS.Object/NemakiWare.CMIS.Move", MOVE, "moveObject(bedroom, D)"},
                {"/Objects/NemakiWare.CMIS.Object('D')/NemakiWare.CMIS.ApplyAcl", ACL, "applyAcl(bedroom, D)"},
                {"/Folders('F')/NemakiWare.CMIS.Folder/NemakiWare.CMIS.ApplyAcl", ACL, "applyAcl(bedroom, F)"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("POST", request[0], request[1]));
            assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(request[2].substring(request[2].indexOf(", ") + 2, request[2].length() - 1), answer.objectId(),
                    answer.request());
            assertEquals(List.of(request[2]), requests.writes(), answer.request());
        }
    }

    @Test
    @DisplayName("processActionVoid: CancelCheckOut on the entity set's own type cancels the check-out of the entity the key names, as before")
    void processActionVoidAcceptsItsEntitySetsOwnType() {
        for (String path : List.of("/Documents('D')/NemakiWare.CMIS.Document/NemakiWare.CMIS.CancelCheckOut",
                "/Documents/NemakiWare.CMIS.Document('D')/NemakiWare.CMIS.CancelCheckOut")) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("POST", path, null));
            assertEquals(204, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(List.of("cancelCheckOut(bedroom, D)"), requests.writes(), answer.request());
        }
    }
}
