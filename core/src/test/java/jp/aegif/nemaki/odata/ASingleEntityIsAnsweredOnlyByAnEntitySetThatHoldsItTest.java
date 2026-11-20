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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.odata.ODataWriteRequests.Answer;

/**
 * A single-entity read answers an entity only from an entity set that holds its base type; for any other it answers 404 and
 * not the entity (R268 — 3.4.2). Objects holds documents, folders, relationships, policies and items; Documents, Folders,
 * Relationships, Policies and Items each hold their own base type only. A base type that cannot be read is not answered as an
 * entity that is absent. A cast is still decided by the URL alone (R254).
 *
 * <p>Measured before the fix on nb33: {@code Folders('<document>')}, {@code Policies('<document>')} and
 * {@code Items('<document>')} answered 200 with the document, and {@code Documents('<folder>')} with the folder.
 *
 * <p>Every request goes through Olingo's parser and dispatcher to the processors registered as {@code ODataServlet} registers
 * them ({@link ODataWriteRequests}). An id names its base type ({@link ODataWriteRequests#baseTypeOf}).
 */
class ASingleEntityIsAnsweredOnlyByAnEntitySetThatHoldsItTest {

    /** One object of each base type. */
    private static final List<String> ONE_OF_EACH = List.of("D", "F", "REL1", "POL1", "ITEM1");

    /** The entity sets of CMIS objects and the ids of {@link #ONE_OF_EACH} each holds. */
    private static final Map<String, List<String>> HOLDS = Map.of("Objects", ONE_OF_EACH, "Documents", List.of("D"),
            "Folders", List.of("F"), "Relationships", List.of("REL1"), "Policies", List.of("POL1"), "Items", List.of("ITEM1"));

    @Test
    @DisplayName("readEntity: an entity its entity set does not hold is answered 404 and not answered — with no cast, the own type or $entity")
    void readEntityAnswers404ForAnEntityItsEntitySetDoesNotHold() {
        for (String[] request : new String[][] {{"/Folders('D')", "Folders", "D"}, {"/Documents('F')", "Documents", "F"},
                {"/Policies('D')", "Policies", "D"}, {"/Items('D')", "Items", "D"}, {"/Relationships('D')", "Relationships", "D"},
                {"/Folders('ITEM1')", "Folders", "ITEM1"}, {"/Documents('REL1')", "Documents", "REL1"},
                {"/Policies('F')", "Policies", "F"}, {"/Folders('D')/NemakiWare.CMIS.Folder", "Folders", "D"},
                {"/Documents/NemakiWare.CMIS.Document('F')", "Documents", "F"}, {"/$entity?$id=Folders('D')", "Folders", "D"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertNotHeldOnRead(assertDoesNotThrow(() -> requests.send("GET", request[0], null)), request[1],
                    request[2]);
        }
    }

    @Test
    @DisplayName("readEntity: each entity set answers the base types it holds — Objects all five — and 404 for the rest")
    void readEntityAnswersWhatItsEntitySetHolds() {
        for (Map.Entry<String, List<String>> set : HOLDS.entrySet()) {
            for (String id : ONE_OF_EACH) {
                ODataWriteRequests requests = new ODataWriteRequests();
                Answer answer = assertDoesNotThrow(() -> requests.send("GET", "/" + set.getKey() + "('" + id + "')", null));
                if (set.getValue().contains(id)) {
                    assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
                    assertEquals(id, answer.objectId(), answer.request());
                } else {
                    requests.assertNotHeldOnRead(answer, set.getKey(), id);
                }
            }
        }
    }

    @Test
    @DisplayName("readEntity: Objects answers a folder and a document as before — with no cast, its own type or $entity")
    void objectsAnswersAFolderAndADocumentAsBefore() {
        for (String[] request : new String[][] {{"/Objects('F')", "F"}, {"/Objects('D')", "D"},
                {"/Objects('F')/NemakiWare.CMIS.Object", "F"}, {"/Objects/NemakiWare.CMIS.Object('D')", "D"},
                {"/$entity?$id=Objects('F')", "F"}, {"/Folders('F')/NemakiWare.CMIS.Folder", "F"},
                {"/Documents('D')/NemakiWare.CMIS.Document", "D"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("GET", request[0], null));
            assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(request[1], answer.objectId(), answer.request());
        }
    }

    @Test
    @DisplayName("a cast to another type is still refused by the URL alone, before the entity is read — even when the entity is of that type")
    void aCastIsStillDecidedByTheUrl() {
        for (String[] request : new String[][] {{"/Objects('F')/NemakiWare.CMIS.Folder", "NemakiWare.CMIS.Folder"},
                {"/Objects/NemakiWare.CMIS.Document('D')", "NemakiWare.CMIS.Document"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("GET", request[0], null)),
                    "a type cast on one entity is answered only for its entity set's own type, not for " + request[1]);
        }
    }

    private static ObjectDataImpl withBaseType(String baseType) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl("cmis:objectId", "X"));
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:document"));
        if (baseType != null) {
            properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", baseType));
        }
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }

    @Test
    @DisplayName("readEntity: an entity whose base type cannot be read is answered neither as itself nor as absent")
    void readEntityDoesNotAnswerAnEntityWhoseBaseTypeCannotBeRead() {
        for (String baseType : new String[] {null, "cmis:secondary", "Folder"}) {
            for (String path : List.of("/Objects('X')", "/Folders('X')", "/Documents('X')")) {
                ODataWriteRequests requests = new ODataWriteRequests();
                requests.onRead.put("X", (Callable<Object>) () -> withBaseType(baseType));
                Answer answer = assertDoesNotThrow(() -> requests.send("GET", path, null));
                assertEquals(500, answer.status(), answer.request() + " (" + baseType + ") was answered " + answer.body());
                assertNotEquals(404, answer.status(), answer.request() + " answered an unreadable base type as absent");
                assertTrue(answer.message() != null && answer.message().startsWith(CmisEntityProcessor.READ_UNREADABLE),
                        answer.request() + " (" + baseType + ") was answered by another arm: " + answer.body());
                assertNull(answer.objectId(), answer.request() + " (" + baseType + ") answered the entity");
            }
        }
    }

    @Test
    @DisplayName("readEntity: an entity that is absent is answered 404 「Entity not found」, as before")
    void anAbsentEntityIsAnswered404AsBefore() {
        for (String path : List.of("/Objects('GONE')", "/Folders('GONE')", "/Documents('GONE')")) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.onRead.put("GONE", () -> {
                throw new CmisObjectNotFoundException("[ObjectId:GONE]The specified object is not found");
            });
            Answer answer = assertDoesNotThrow(() -> requests.send("GET", path, null));
            assertEquals(404, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals("Entity not found: GONE", answer.message(), answer.request() + " was answered by another arm");
        }
    }
}
