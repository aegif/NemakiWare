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
 * An entity set listing given {@code $apply} refuses the request (R267 — 3.4.2). The listing translated $filter and $search but
 * neither read nor refused $apply: {@code Documents?$apply=filter(name eq 'zz-none')&$count=true} answered every document with the
 * count of all of them (measured on nb33). $apply is not translated: the listing is refused with a 400 before any CMIS service is
 * called. A listing without it is answered as before, and the refusals that come before it — a navigation path (R251), an entity
 * set that is not translated (R257) — answer as they did.
 *
 * <p>The requests go through Olingo's own handler — its parser, its validator and its dispatcher — to the processors, registered
 * as {@code ODataServlet} registers them ({@link ODataWriteRequests}).
 */
class AnEntitySetListingGivenApplyRefusesItTest {

    @Test
    void aListingGivenApplyIsRefusedBeforeAnythingIsRead() throws Exception {
        for (String set : new String[] { "Documents", "Folders", "Relationships", "Policies", "Items" }) {
            for (String apply : new String[] { "$apply=filter(name%20eq%20'zz-none')", "$apply=aggregate($count%20as%20n)",
                    "$apply=filter(name%20eq%20'zz-none')&$count=true&$top=2" }) {
                ODataWriteRequests requests = new ODataWriteRequests();
                requests.assertRefused(requests.send("GET", "/" + set + "?" + apply, null),
                        CmisEntityCollectionProcessor.APPLY_NOT_TRANSLATED);
            }
        }
    }

    @Test
    void theRefusalsBeforeItAnswerAsTheyDid() throws Exception {
        ODataWriteRequests requests = new ODataWriteRequests();
        ODataWriteRequests.Answer navigation = requests.send("GET", "/Folders('F')/children?$apply=filter(name%20eq%20'zz')", null);
        assertEquals(400, navigation.status(), navigation.body());
        assertEquals("$filter, $search, $orderby, $apply and $expand are not translated on a navigation path", navigation.message(),
                navigation.body());
        ODataWriteRequests types = new ODataWriteRequests();
        // Type has no property name, so a filter on it would be refused by Olingo before any processor; aggregate names none.
        ODataWriteRequests.Answer notTranslated = types.send("GET", "/Types?$apply=aggregate($count%20as%20n)", null);
        assertEquals(400, notTranslated.status(), notTranslated.body());
        assertEquals("the entity set Types is not translated", notTranslated.message(), notTranslated.body());
        ODataWriteRequests objects = new ODataWriteRequests();
        // Objects is not listed (R265), so its refusal comes before $apply is looked at.
        ODataWriteRequests.Answer everyObject = objects.send("GET", "/Objects?$apply=filter(name%20eq%20'zz-none')", null);
        assertEquals(400, everyObject.status(), everyObject.body());
        assertEquals(CmisEntityCollectionProcessor.OBJECTS_LISTING_NOT_TRANSLATED, everyObject.message(), everyObject.body());
    }

    @Test
    void aListingWithoutApplyIsAnsweredAsBefore() throws Exception {
        ODataWriteRequests requests = new ODataWriteRequests();
        ObjectListImpl one = new ObjectListImpl();
        one.setObjects(List.<ObjectData>of(ODataWriteRequests.object("D")));
        one.setNumItems(BigInteger.ONE);
        one.setHasMoreItems(Boolean.FALSE);
        when(requests.discovery.query(any(), eq("bedroom"), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(one);
        ODataWriteRequests.Answer answer = assertDoesNotThrow(() -> requests.send("GET", "/Documents?$top=2&$count=true", null));
        assertEquals(200, answer.status(), answer.body());
        assertEquals("D", answer.objectId(), answer.body());
        verify(requests.discovery).query(eq(requests.callContext), eq("bedroom"), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
