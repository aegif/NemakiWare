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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.core.uri.parser.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;

/**
 * An answer that is cut short says where it continues, $top=0 answers none, a $skiptoken is refused and a count that is
 * not known is not written (R256, 3.4.2).
 *
 * <p>A listing without $top was cut at 100 with no {@code @odata.nextLink}, so it read as the whole set — the children of
 * a root with 383 read as 100 (measured on nb33); {@code $top=0} answered the backend's default page, a {@code $skiptoken}
 * was dropped and the first page answered for it, and a backend that does not know the total had the page's size written
 * as the count. Both readers are measured: a folder's children ({@code readNavigation}) and an entity set
 * ({@code getData}).
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntityCollection}; the answer is read
 * from the serialized response.
 */
class APagedAnswerSaysWhereItContinuesTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";
    private static final String SKIPTOKEN_REFUSED = "$skiptoken is not translated — page with $skip";
    private static final String COUNT_NOT_KNOWN = "the number of entities is not known, so $count=true is not answered";

    @Test
    @DisplayName("a folder's children cut at the default page say where they continue")
    void aCutListingOfAFoldersChildrenSaysWhereItContinues() {
        Answer a = assertDoesNotThrow(() -> new Fixture().children(100, Boolean.TRUE, 383).read("Folders('F')/children", null));
        assertEquals(100, a.ids.size());
        assertEquals(BASE + "/Folders('F')/children?$skip=100", a.next);
        Answer counted = assertDoesNotThrow(
                () -> new Fixture().children(100, Boolean.TRUE, 383).read("Folders('F')/children", "$count=true"));
        assertEquals(BASE + "/Folders('F')/children?$count=true&$skip=100", counted.next);
        assertEquals(383, counted.count);
    }

    @Test
    @DisplayName("an entity set cut at the default page says where it continues")
    void aCutEntitySetSaysWhereItContinues() {
        Answer a = assertDoesNotThrow(() -> new Fixture().listing(100, Boolean.TRUE, 194).read("Folders", null));
        assertEquals(100, a.ids.size());
        assertEquals(BASE + "/Folders?$skip=100", a.next);
        Answer later = assertDoesNotThrow(() -> new Fixture().listing(100, Boolean.TRUE, 194).read("Folders", "$skip=50"));
        assertEquals(BASE + "/Folders?$skip=150", later.next);
    }

    @Test
    @DisplayName("a $top the backend cut short continues with what is left of it")
    void aTopTheBackendCutShortContinues() {
        Answer a = assertDoesNotThrow(
                () -> new Fixture().children(5, Boolean.TRUE, 383).read("Folders('F')/children", "$top=20000"));
        assertEquals(5, a.ids.size());
        assertEquals(BASE + "/Folders('F')/children?$skip=5&$top=19995", a.next);
    }

    @Test
    @DisplayName("$top=0 answers no entity, with the count when it is asked for")
    void aTopOfZeroAnswersNone() {
        Answer children = assertDoesNotThrow(
                () -> new Fixture().children(3, Boolean.FALSE, 3).read("Folders('F')/children", "$top=0&$count=true"));
        assertEquals(List.of(), children.ids);
        assertEquals(3, children.count);
        assertNull(children.next);
        Answer set = assertDoesNotThrow(() -> new Fixture().listing(100, Boolean.TRUE, 194).read("Folders", "$top=0&$count=true"));
        assertEquals(List.of(), set.ids);
        assertEquals(194, set.count);
        assertNull(set.next);
    }

    @Test
    @DisplayName("a $skiptoken is refused, not dropped for the first page — nothing is listed")
    void aSkipTokenIsRefused() {
        Fixture children = new Fixture().children(3, Boolean.FALSE, 3);
        assertRefused(children, "Folders('F')/children", "$skiptoken=opaque", SKIPTOKEN_REFUSED);
        verify(children.navigation, never()).getChildren(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        Fixture set = new Fixture().listing(2, Boolean.FALSE, 2);
        assertRefused(set, "Folders", "$top=2&$skiptoken=opaque", SKIPTOKEN_REFUSED);
        verify(set.discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a count the backend does not know is not written as the page's size")
    void anUnknownCountIsNotWrittenAsThePagesSize() {
        assertRefused(new Fixture().children(10, Boolean.TRUE, null), "Folders('F')/children", "$top=10&$count=true",
                COUNT_NOT_KNOWN);
        Answer uncounted = assertDoesNotThrow(
                () -> new Fixture().children(10, Boolean.TRUE, null).read("Folders('F')/children", "$top=10"));
        assertEquals(10, uncounted.ids.size());
        assertNull(uncounted.count);
    }

    @Test
    @DisplayName("an answer that is the whole set is answered as before — no nextLink, the count it has")
    void aWholeAnswerIsAnsweredAsBefore() {
        Answer children = assertDoesNotThrow(
                () -> new Fixture().children(3, Boolean.FALSE, 3).read("Folders('F')/children", "$count=true"));
        assertEquals(3, children.ids.size());
        assertNull(children.next);
        assertEquals(3, children.count);
        Answer set = assertDoesNotThrow(() -> new Fixture().listing(2, Boolean.FALSE, null).read("Folders", "$count=true"));
        assertEquals(2, set.ids.size());
        assertNull(set.next);
        assertEquals(2, set.count);
        Answer asked = assertDoesNotThrow(() -> new Fixture().listing(2, Boolean.TRUE, 194).read("Folders", "$top=2"));
        assertEquals(2, asked.ids.size());
        assertNull(asked.next, "the 2 that were asked for were answered");
    }

    private static void assertRefused(Fixture f, String path, String query, String message) {
        String request = path + "?" + query;
        ODataApplicationException refused = assertThrows(ODataApplicationException.class, () -> f.read(path, query),
                request + " was answered");
        assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), request + ": " + refused.getMessage());
        assertEquals(message, refused.getMessage(), request + " was refused by another arm");
    }

    private static final class Answer {
        final List<String> ids = new ArrayList<>();
        String next;
        Integer count;
    }

    private static final class Fixture {
        final DiscoveryService discovery = mock(DiscoveryService.class);
        final NavigationService navigation = mock(NavigationService.class);
        final ObjectService objects = mock(ObjectService.class);

        Fixture() {
            doReturn(object("F", "cmis:folder")).when(objects)
                    .getObject(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(), any());
        }

        /** F's children: {@code n} of them in this page, with what the backend says about the rest. */
        Fixture children(int n, Boolean hasMoreItems, Integer numItems) {
            ObjectInFolderListImpl list = new ObjectInFolderListImpl();
            List<ObjectInFolderData> kids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ObjectInFolderDataImpl kid = new ObjectInFolderDataImpl();
                kid.setObject(object("c" + i, "cmis:document"));
                kids.add(kid);
            }
            list.setObjects(kids);
            list.setHasMoreItems(hasMoreItems);
            list.setNumItems(numItems == null ? null : BigInteger.valueOf(numItems));
            when(navigation.getChildren(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any())).thenReturn(list);
            return this;
        }

        /** An entity set's query: {@code n} folders in this page, with what the backend says about the rest. */
        Fixture listing(int n, Boolean hasMoreItems, Integer numItems) {
            ObjectListImpl list = new ObjectListImpl();
            List<ObjectData> found = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                found.add(object("f" + i, "cmis:folder"));
            }
            list.setObjects(found);
            list.setHasMoreItems(hasMoreItems);
            list.setNumItems(numItems == null ? null : BigInteger.valueOf(numItems));
            when(discovery.query(any(), eq("bedroom"), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(list);
            return this;
        }

        Answer read(String path, String query) throws Exception {
            OData odata = OData.newInstance();
            ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
            UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + path, query, null, BASE);
            CmisEntityCollectionProcessor processor =
                    new CmisEntityCollectionProcessor(null, objects, navigation, discovery, "bedroom", null);
            processor.init(odata, metadata);
            ODataRequest request = mock(ODataRequest.class);
            when(request.getRawBaseUri()).thenReturn(BASE);
            when(request.getRawODataPath()).thenReturn("/" + path);
            when(request.getRawQueryPath()).thenReturn(query);
            ODataResponse response = new ODataResponse();
            processor.readEntityCollection(request, response, uriInfo, ContentType.JSON);
            String body;
            try (InputStream in = response.getContent()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Answer answer = new Answer();
            Matcher ids = Pattern.compile("\"objectId\":\"([^\"]*)\"").matcher(body);
            while (ids.find()) {
                answer.ids.add(ids.group(1));
            }
            Matcher next = Pattern.compile("\"@odata.nextLink\":\"([^\"]*)\"").matcher(body);
            if (next.find()) {
                answer.next = next.group(1);
            }
            Matcher count = Pattern.compile("\"@odata.count\":(\\d+)").matcher(body);
            if (count.find()) {
                answer.count = Integer.valueOf(count.group(1));
            }
            return answer;
        }
    }

    private static ObjectData object(String id, String baseTypeId) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl("cmis:objectId", id));
        properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", baseTypeId));
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", baseTypeId));
        properties.addProperty(new PropertyStringImpl("cmis:name", id + ".name"));
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }
}
