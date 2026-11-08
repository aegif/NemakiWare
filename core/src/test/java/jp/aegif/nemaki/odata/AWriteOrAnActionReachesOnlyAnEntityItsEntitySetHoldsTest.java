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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.odata.ODataWriteRequests.Answer;

/**
 * A keyed update, a delete or an action reaches only an entity its entity set holds: the entity is read with the request's
 * call context before the body or the action's parameters, and a base type the entity set does not hold is answered 404 with
 * nothing written (R271 — 3.4.2). Objects holds documents, folders, relationships, policies and items; Documents, Folders,
 * Relationships, Policies and Items each hold their own base type only. A read that fails, or a base type that cannot be
 * read, is not answered as an entity that is not there.
 *
 * <p>Measured before the fix through Olingo's own handler and these processors: {@code DELETE Folders('D')} for a document D
 * called {@code deleteObject(D)}, {@code PATCH Folders('D')} {@code updateProperties(D)},
 * {@code POST Folders('D')/NemakiWare.CMIS.ApplyAcl} {@code applyAcl(D)} and {@code DELETE Documents('F')} for a folder F
 * {@code deleteObject(F)}. Each public entry point the fix changed is named by tests of its own.
 *
 * <p>Every request goes through Olingo's parser and dispatcher to the processors registered as {@code ODataServlet} registers
 * them ({@link ODataWriteRequests}). An id names its base type ({@link ODataWriteRequests#baseTypeOf}).
 */
class AWriteOrAnActionReachesOnlyAnEntityItsEntitySetHoldsTest {

    /** One object of each base type. */
    private static final List<String> ONE_OF_EACH = List.of("D", "F", "REL1", "POL1", "ITEM1");

    /** The entity sets of CMIS objects and the ids of {@link #ONE_OF_EACH} each holds. */
    private static final Map<String, List<String>> HOLDS = Map.of("Objects", ONE_OF_EACH, "Documents", List.of("D"),
            "Folders", List.of("F"), "Relationships", List.of("REL1"), "Policies", List.of("POL1"), "Items", List.of("ITEM1"));

    @Test
    @DisplayName("deleteEntity: DELETE of an entity its entity set does not hold is answered 404 and nothing is deleted")
    void deleteEntityAnswers404ForAnEntityItsEntitySetDoesNotHold() {
        for (String[] request : new String[][] {{"/Folders('D')", "Folders", "D"}, {"/Documents('F')", "Documents", "F"},
                {"/Relationships('D')", "Relationships", "D"}, {"/Policies('F')", "Policies", "F"},
                {"/Items('D')", "Items", "D"}, {"/Folders('ITEM1')", "Folders", "ITEM1"},
                {"/Documents('REL1')", "Documents", "REL1"}, {"/Folders('D')/NemakiWare.CMIS.Folder", "Folders", "D"},
                {"/Documents/NemakiWare.CMIS.Document('F')", "Documents", "F"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertNotHeld(assertDoesNotThrow(() -> requests.send("DELETE", request[0], null)), request[1], request[2]);
        }
    }

    @Test
    @DisplayName("updateEntity: PATCH and PUT of an entity its entity set does not hold are answered 404 before the body is read and nothing is updated")
    void updateEntityAnswers404ForAnEntityItsEntitySetDoesNotHold() {
        for (String method : List.of("PATCH", "PUT")) {
            for (String[] request : new String[][] {{"/Folders('D')", "Folders", "D", NAME},
                    {"/Documents('F')", "Documents", "F", NAME}, {"/Items('POL1')", "Items", "POL1", NAME},
                    {"/Folders('D')/NemakiWare.CMIS.Folder", "Folders", "D", NAME},
                    {"/Folders('D')", "Folders", "D", MALFORMED}}) {
                ODataWriteRequests requests = new ODataWriteRequests();
                requests.assertNotHeld(assertDoesNotThrow(() -> requests.send(method, request[0], request[3])), request[1],
                        request[2]);
            }
        }
    }

    @Test
    @DisplayName("processActionEntity: an action on an entity its entity set does not hold is answered 404 before its parameters are read and is not applied")
    void processActionEntityAnswers404ForAnEntityItsEntitySetDoesNotHold() {
        for (String[] request : new String[][] {{"/Folders('D')/NemakiWare.CMIS.ApplyAcl", "Folders", "D", ACL},
                {"/Folders('D')/NemakiWare.CMIS.Move", "Folders", "D", MOVE},
                {"/Documents('F')/NemakiWare.CMIS.CheckOut", "Documents", "F", null},
                {"/Items('D')/NemakiWare.CMIS.ApplyAcl", "Items", "D", ACL},
                {"/Documents('REL1')/NemakiWare.CMIS.CheckOut", "Documents", "REL1", null},
                {"/Folders('D')/NemakiWare.CMIS.Folder/NemakiWare.CMIS.ApplyAcl", "Folders", "D", ACL},
                {"/Folders('D')/NemakiWare.CMIS.ApplyAcl", "Folders", "D", MALFORMED_PARAMETERS}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertNotHeld(assertDoesNotThrow(() -> requests.send("POST", request[0], request[3])), request[1],
                    request[2]);
        }
    }

    @Test
    @DisplayName("processActionVoid: CancelCheckOut on an entity its entity set does not hold is answered 404 and is not applied")
    void processActionVoidAnswers404ForAnEntityItsEntitySetDoesNotHold() {
        for (String[] request : new String[][] {{"/Documents('F')/NemakiWare.CMIS.CancelCheckOut", "F", null},
                {"/Documents('F')/NemakiWare.CMIS.CancelCheckOut", "F", "{}"},
                {"/Documents('POL1')/NemakiWare.CMIS.CancelCheckOut", "POL1", null},
                {"/Documents/NemakiWare.CMIS.Document('ITEM1')/NemakiWare.CMIS.CancelCheckOut", "ITEM1", null}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertNotHeld(assertDoesNotThrow(() -> requests.send("POST", request[0], request[2])), "Documents",
                    request[1]);
        }
    }

    @Test
    @DisplayName("deleteEntity: each entity set deletes the base types it holds — Objects all five — and answers 404 for the rest")
    void deleteEntityDeletesWhatItsEntitySetHolds() {
        for (Map.Entry<String, List<String>> set : HOLDS.entrySet()) {
            for (String id : ONE_OF_EACH) {
                ODataWriteRequests requests = new ODataWriteRequests();
                Answer answer = assertDoesNotThrow(() -> requests.send("DELETE", "/" + set.getKey() + "('" + id + "')", null));
                if (set.getValue().contains(id)) {
                    assertEquals(204, answer.status(), answer.request() + " was answered " + answer.body());
                    assertEquals(List.of("deleteObject(bedroom, " + id + ")"), requests.writes(), answer.request());
                } else {
                    requests.assertNotHeld(answer, set.getKey(), id);
                }
            }
        }
    }

    @Test
    @DisplayName("updateEntity: each entity set updates the base types it holds — Objects all five — and answers 404 for the rest")
    void updateEntityUpdatesWhatItsEntitySetHolds() {
        for (Map.Entry<String, List<String>> set : HOLDS.entrySet()) {
            for (String id : ONE_OF_EACH) {
                ODataWriteRequests requests = new ODataWriteRequests();
                Answer answer = assertDoesNotThrow(() -> requests.send("PATCH", "/" + set.getKey() + "('" + id + "')", NAME));
                if (set.getValue().contains(id)) {
                    assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
                    assertEquals(id, answer.objectId(), answer.request());
                    assertEquals(List.of("updateProperties(bedroom, " + id + ")"), requests.writes(), answer.request());
                } else {
                    requests.assertNotHeld(answer, set.getKey(), id);
                }
            }
        }
    }

    @Test
    @DisplayName("processActionEntity: ApplyAcl is applied to the base types its entity set holds — Objects all five — and answers 404 for the rest")
    void processActionEntityActsOnWhatItsEntitySetHolds() {
        for (Map.Entry<String, List<String>> set : HOLDS.entrySet()) {
            for (String id : ONE_OF_EACH) {
                ODataWriteRequests requests = new ODataWriteRequests();
                Answer answer = assertDoesNotThrow(() -> requests.send("POST",
                        "/" + set.getKey() + "('" + id + "')/NemakiWare.CMIS.ApplyAcl", ACL));
                if (set.getValue().contains(id)) {
                    assertEquals(List.of("applyAcl(bedroom, " + id + ")"), requests.writes(), answer.request());
                    if (set.getKey().equals("Relationships")) {
                        // The answer after the write is R273's (the Relationship type's sourceId is not written into it);
                        // what is measured here is that the relationship was held and its ACL applied.
                        assertNotEquals(404, answer.status(), answer.request() + " was answered " + answer.body());
                        continue;
                    }
                    assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
                    assertEquals(id, answer.objectId(), answer.request());
                } else {
                    requests.assertNotHeld(answer, set.getKey(), id);
                }
            }
        }
        ODataWriteRequests requests = new ODataWriteRequests();
        Answer move = assertDoesNotThrow(() -> requests.send("POST", "/Objects('F')/NemakiWare.CMIS.Move", MOVE));
        assertEquals(200, move.status(), move.request() + " was answered " + move.body());
        assertEquals(List.of("moveObject(bedroom, F)"), requests.writes(), move.request());
    }

    @Test
    @DisplayName("processActionVoid: CancelCheckOut is applied to a document in Documents and answers 404 for the other base types")
    void processActionVoidActsOnWhatItsEntitySetHolds() {
        for (String id : ONE_OF_EACH) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("POST",
                    "/Documents('" + id + "')/NemakiWare.CMIS.CancelCheckOut", null));
            if (id.equals("D")) {
                assertEquals(204, answer.status(), answer.request() + " was answered " + answer.body());
                assertEquals(List.of("cancelCheckOut(bedroom, D)"), requests.writes(), answer.request());
            } else {
                requests.assertNotHeld(answer, "Documents", id);
            }
        }
    }

    /** The reads that fail, or answer an entity whose base type cannot be read, and the status each is answered. */
    private static final Object[][] UNREADABLE = {
            {(Callable<Object>) () -> {
                throw new CmisRuntimeException("the store did not answer");
            }, 500},
            {(Callable<Object>) () -> {
                throw new CmisPermissionDeniedException("not readable by this user");
            }, 403},
            {(Callable<Object>) () -> null, 500},
            {(Callable<Object>) () -> withBaseType(null), 500},
            {(Callable<Object>) () -> withBaseType("cmis:secondary"), 500},
            {(Callable<Object>) () -> withBaseType("Folder"), 500}};

    private static ObjectDataImpl withBaseType(String baseType) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl("cmis:objectId", "X"));
        if (baseType != null) {
            properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", baseType));
        }
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    /** {@code method path} with {@code body} for an entity X that cannot be read in each way, answered as it should be. */
    @SuppressWarnings("unchecked")
    private static void assertEachUnreadableReadWritesNothing(String method, String path, String body) {
        for (Object[] read : UNREADABLE) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.onRead.put("X", (Callable<Object>) read[0]);
            requests.assertNotWritten(assertDoesNotThrow(() -> requests.send(method, path, body)), (Integer) read[1]);
        }
    }

    @Test
    @DisplayName("deleteEntity: an entity that cannot be read, or whose base type cannot, is not deleted and not answered 404")
    void deleteEntityDoesNotDeleteAnEntityItCannotRead() {
        for (String path : List.of("/Documents('X')", "/Objects('X')", "/Folders('X')")) {
            assertEachUnreadableReadWritesNothing("DELETE", path, null);
        }
    }

    @Test
    @DisplayName("updateEntity: an entity that cannot be read, or whose base type cannot, is not updated, its body is not read and it is not answered 404")
    void updateEntityDoesNotUpdateAnEntityItCannotRead() {
        for (String method : List.of("PATCH", "PUT")) {
            assertEachUnreadableReadWritesNothing(method, "/Documents('X')", NAME);
            assertEachUnreadableReadWritesNothing(method, "/Objects('X')", NAME);
        }
    }

    @Test
    @DisplayName("processActionEntity: an action on an entity that cannot be read, or whose base type cannot, is not applied and not answered 404")
    void processActionEntityDoesNotActOnAnEntityItCannotRead() {
        assertEachUnreadableReadWritesNothing("POST", "/Objects('X')/NemakiWare.CMIS.ApplyAcl", ACL);
        assertEachUnreadableReadWritesNothing("POST", "/Documents('X')/NemakiWare.CMIS.CheckOut", null);
        assertEachUnreadableReadWritesNothing("POST", "/Folders('X')/NemakiWare.CMIS.Move", MOVE);
    }

    @Test
    @DisplayName("processActionVoid: CancelCheckOut on an entity that cannot be read, or whose base type cannot, is not applied and not answered 404")
    void processActionVoidDoesNotActOnAnEntityItCannotRead() {
        assertEachUnreadableReadWritesNothing("POST", "/Documents('X')/NemakiWare.CMIS.CancelCheckOut", null);
        assertEachUnreadableReadWritesNothing("POST", "/Documents('X')/NemakiWare.CMIS.CancelCheckOut", "{}");
    }

    @Test
    @DisplayName("each entry point answers an entity that is not there 404 with its own not-found message, without reading the body or writing (a DELETE of one answered 204 before: deleteObject skips a missing object)")
    void anEntityThatIsNotThereIsAnswered404() {
        for (String[] request : new String[][] {{"DELETE", "/Documents('GONE')", null, "Entity not found: GONE"},
                {"PATCH", "/Folders('GONE')", NAME, "Entity not found: GONE"},
                {"PUT", "/Objects('GONE')", NAME, "Entity not found: GONE"},
                {"POST", "/Objects('GONE')/NemakiWare.CMIS.ApplyAcl", ACL, "Object not found: GONE"},
                {"POST", "/Documents('GONE')/NemakiWare.CMIS.CheckOut", null, "Object not found: GONE"},
                {"POST", "/Documents('GONE')/NemakiWare.CMIS.CancelCheckOut", null, "Object not found: GONE"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.onRead.put("GONE", () -> {
                throw new CmisObjectNotFoundException("[ObjectId:GONE]The specified object is not found");
            });
            Answer answer = assertDoesNotThrow(() -> requests.send(request[0], request[1], request[2]));
            assertEquals(404, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(request[3], answer.message(), answer.request() + " was answered by another arm");
            assertFalse(requests.bodyWasRead(), answer.request() + " read its body although its entity is absent");
            assertEquals(List.of(), requests.writes(), answer.request());
            assertEquals(List.of("GONE"), requests.reads(), answer.request());
        }
    }
}
