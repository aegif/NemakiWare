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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.odata.ODataWriteRequests.Answer;

/**
 * A single-entity read of {@code Types}, {@code Users} or {@code Groups} is refused with a 400 before any CMIS service is
 * called (R264 — 3.4.2). Their entities are not CMIS objects and are not translated; a key that is the id of a CMIS object
 * does not answer that object.
 *
 * <p>Measured before the fix on nb33: {@code Types('cmis:document')} and {@code Users('admin')} answered 404 「Entity not
 * found」 though the type and the user exist — {@code readEntity} passed the key to {@code getObject} whatever the entity set.
 *
 * <p>Every request goes through Olingo's parser and dispatcher to the processors registered as {@code ODataServlet} registers
 * them ({@link ODataWriteRequests}).
 */
class ASingleEntityOfAnEntitySetWhoseEntitiesAreNotCmisObjectsIsRefusedTest {

    private static String refused(String entitySet) {
        return CmisEntityProcessor.READ_NOT_TRANSLATED + entitySet;
    }

    @Test
    @DisplayName("readEntity: one entity of Types, Users or Groups — with no cast, the entity set's own type or $entity — is refused and nothing is read")
    void readEntityRefusesTypesUsersAndGroups() {
        for (String[] request : new String[][] {{"/Types('cmis:document')", "Types"}, {"/Users('admin')", "Users"},
                {"/Groups('g')", "Groups"}, {"/Users('admin')/NemakiWare.CMIS.User", "Users"},
                {"/Types/NemakiWare.CMIS.Type('cmis:document')", "Types"}, {"/$entity?$id=Users('admin')", "Users"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("GET", request[0], null)), refused(request[1]));
        }
    }

    @Test
    @DisplayName("readEntity: a key that is the id of a CMIS object is refused in Types, Users or Groups, and the object is not answered")
    void readEntityDoesNotAnswerTheObjectAKeyNames() {
        for (String[] request : new String[][] {{"/Types('D')", "Types"}, {"/Users('D')", "Users"}, {"/Groups('F')", "Groups"},
                {"/Users('REL1')", "Users"}, {"/Types('ITEM1')", "Types"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("GET", request[0], null));
            requests.assertRefused(answer, refused(request[1]));
            assertNull(answer.objectId(), answer.request() + " answered the object its key names");
        }
    }

    @Test
    @DisplayName("a navigation after the key of Types, Users or Groups is named before the entity set is")
    void aNavigationIsNamedBeforeTheEntitySet() {
        for (String path : List.of("/Users('admin')/groups('g')", "/Types('cmis:document')/childTypes('cmis:folder')")) {
            ODataWriteRequests requests = new ODataWriteRequests();
            requests.assertRefused(assertDoesNotThrow(() -> requests.send("GET", path, null)),
                    "a single entity is read only by its entity set and key — a navigation after the key is not read");
        }
    }

    @Test
    @DisplayName("readEntity: one entity of each entity set of CMIS objects is answered as before")
    void readEntityAnswersTheEntitySetsOfCmisObjects() {
        for (String[] request : new String[][] {{"/Documents('D')", "D"}, {"/Folders('F')", "F"}, {"/Objects('D')", "D"},
                {"/Relationships('REL1')", "REL1"}, {"/Policies('POL1')", "POL1"}, {"/Items('ITEM1')", "ITEM1"}}) {
            ODataWriteRequests requests = new ODataWriteRequests();
            Answer answer = assertDoesNotThrow(() -> requests.send("GET", request[0], null));
            assertEquals(200, answer.status(), answer.request() + " was answered " + answer.body());
            assertEquals(request[1], answer.objectId(), answer.request());
            assertEquals(List.of(request[1]), requests.reads(), answer.request());
        }
    }
}
