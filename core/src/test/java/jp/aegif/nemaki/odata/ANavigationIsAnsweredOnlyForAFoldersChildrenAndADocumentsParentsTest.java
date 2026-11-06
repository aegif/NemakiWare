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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
import org.apache.chemistry.opencmis.commons.data.ObjectParentData;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectInFolderListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectParentDataImpl;
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
import org.mockito.ArgumentCaptor;

import jp.aegif.nemaki.cmis.service.DiscoveryService;
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;

/**
 * A path with a key or a navigation segment is answered only for a folder's children and a document's parents (R251,
 * 3.4.2).
 *
 * <p>{@code readEntityCollection} read the entity set of the path and neither its key nor the navigation after it:
 * {@code Folders('F')/children} answered every folder, {@code Documents('D')/parents} every document, and a key that
 * names nothing the same (measured on nb33). The two navigations CMIS answers are read; any other path with a key or a
 * navigation segment is refused, and a key that names no entity of the set is not found.
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntityCollection}, as the servlet has
 * them; the answer is read from the serialized response.
 */
class ANavigationIsAnsweredOnlyForAFoldersChildrenAndADocumentsParentsTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";
    private static final String REFUSED =
            "a path with a key or a navigation is answered only for Folders('<id>')/children and Documents('<id>')/parents";
    private static final String NOT_TRANSLATED =
            "$filter, $search, $orderby, $apply and $expand are not translated on a navigation path";

    @Test
    @DisplayName("Folders('F')/children answers the children of F, not every folder")
    void aFoldersChildrenAreTheChildrenOfThatFolder() {
        Fixture f = new Fixture();
        assertEquals(List.of("c1", "c2", "c3"), assertDoesNotThrow(() -> f.idsFor("Folders('F')/children", null)));
        verify(f.navigation).getChildren(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(),
                eq(BigInteger.valueOf(100)), eq(BigInteger.ZERO), any(), any());
        verify(f.discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        Fixture paged = new Fixture();
        assertDoesNotThrow(() -> paged.idsFor("Folders('F')/children", "$top=2&$skip=1"));
        verify(paged.navigation).getChildren(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(),
                eq(BigInteger.valueOf(2)), eq(BigInteger.ONE), any(), any());

        // A cast to the navigation's own type, or to the entity set's own type on the entry, changes nothing.
        assertEquals(List.of("c1", "c2", "c3"),
                assertDoesNotThrow(() -> new Fixture().idsFor("Folders('F')/children/NemakiWare.CMIS.Object", null)));
        assertEquals(List.of("c1", "c2", "c3"),
                assertDoesNotThrow(() -> new Fixture().idsFor("Folders('F')/NemakiWare.CMIS.Folder/children", null)));
    }

    @Test
    @DisplayName("Documents('D')/parents answers the parents of D, not every document")
    void aDocumentsParentsAreTheParentsOfThatDocument() {
        Fixture f = new Fixture();
        assertEquals(List.of("p1"), assertDoesNotThrow(() -> f.idsFor("Documents('D')/parents", null)));
        verify(f.navigation).getObjectParents(any(), eq("bedroom"), eq("D"), any(), any(), any(), any(), any(), any());
        verify(f.discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a key that names no entity of the set is not found, not answered with every entity")
    void aKeyThatNamesNothingIsNotFound() {
        assertNotFound("Folders('zz-no-such-id')/children");
        assertNotFound("Documents('zz-no-such-id')/parents");
        // A key of the other base type names no entity of the set either.
        assertNotFound("Folders('D')/children");
        assertNotFound("Documents('F')/parents");
    }

    @Test
    @DisplayName("any other path with a key or a navigation is refused — nothing is read")
    void anyOtherPathWithAKeyOrANavigationIsRefused() {
        assertRefused("Types('cmis:document')/childTypes", null, REFUSED);
        assertRefused("Users('admin')/groups", null, REFUSED);
        assertRefused("Groups('g')/users", null, REFUSED);
        assertRefused("Groups('g')/nestedGroups", null, REFUSED);
        assertRefused("Objects('x')/NemakiWare.CMIS.Folder/children", null, REFUSED);
        assertRefused("Folders('F')/children/NemakiWare.CMIS.Policy", null, REFUSED);
    }

    @Test
    @DisplayName("a navigation path does not drop a $filter, $search, $orderby or $expand — it is refused")
    void aNavigationPathDoesNotDropWhatItCannotTranslate() {
        assertRefused("Folders('F')/children", "$filter=name%20eq%20'zz-none'", NOT_TRANSLATED);
        assertRefused("Folders('F')/children", "$orderby=name%20desc", NOT_TRANSLATED);
        assertRefused("Folders('F')/children", "$search=zz", NOT_TRANSLATED);
        assertRefused("Documents('D')/parents", "$filter=name%20eq%20'zz-none'", NOT_TRANSLATED);
        assertRefused("Documents('D')/parents", "$expand=parent", NOT_TRANSLATED);
    }

    @Test
    @DisplayName("an entity set without a key or a navigation is answered as before")
    void anEntitySetWithoutAKeyIsAnsweredAsBefore() {
        assertEquals("SELECT * FROM cmis:folder", assertDoesNotThrow(() -> new Fixture().statementFor("Folders", null)));
        assertEquals("SELECT * FROM cmis:document WHERE cmis:name = 'x'",
                assertDoesNotThrow(() -> new Fixture().statementFor("Documents", "$filter=name%20eq%20'x'")));
    }

    private static void assertNotFound(String path) {
        Fixture f = new Fixture();
        ODataApplicationException refused = assertThrows(ODataApplicationException.class, () -> f.idsFor(path, null),
                path + " was answered");
        assertEquals(HttpStatusCode.NOT_FOUND.getStatusCode(), refused.getStatusCode(), path + ": " + refused.getMessage());
        f.assertNothingListed(path);
    }

    private static void assertRefused(String path, String query, String message) {
        Fixture f = new Fixture();
        String request = path + (query == null ? "" : "?" + query);
        ODataApplicationException refused = assertThrows(ODataApplicationException.class, () -> f.idsFor(path, query),
                request + " was answered");
        assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), request + ": " + refused.getMessage());
        assertEquals(message, refused.getMessage(), request + " was refused by another arm");
        verify(f.objects, never()).getObject(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        f.assertNothingListed(request);
    }

    /** The services behind the processor: F is a folder with c1..c3, D a document filed in p1, anything else is missing. */
    private static final class Fixture {
        final DiscoveryService discovery = mock(DiscoveryService.class);
        final NavigationService navigation = mock(NavigationService.class);
        final ObjectService objects = mock(ObjectService.class);

        Fixture() {
            // The general stub first: a later stub wins for the invocations it matches.
            doThrow(new CmisObjectNotFoundException("not found")).when(objects)
                    .getObject(any(), eq("bedroom"), any(), any(), any(), any(), any(), any(), any(), any());
            doReturn(object("F", "cmis:folder")).when(objects)
                    .getObject(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(), any());
            doReturn(object("D", "cmis:document")).when(objects)
                    .getObject(any(), eq("bedroom"), eq("D"), any(), any(), any(), any(), any(), any(), any());
            ObjectInFolderListImpl children = new ObjectInFolderListImpl();
            List<ObjectInFolderData> kids = new ArrayList<>();
            for (String id : List.of("c1", "c2", "c3")) {
                ObjectInFolderDataImpl kid = new ObjectInFolderDataImpl();
                kid.setObject(object(id, "cmis:document"));
                kids.add(kid);
            }
            children.setObjects(kids);
            children.setNumItems(BigInteger.valueOf(3));
            when(navigation.getChildren(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any())).thenReturn(children);
            ObjectParentDataImpl parent = new ObjectParentDataImpl();
            parent.setObject(object("p1", "cmis:folder"));
            List<ObjectParentData> parents = List.of(parent);
            when(navigation.getObjectParents(any(), eq("bedroom"), eq("D"), any(), any(), any(), any(), any(), any()))
                    .thenReturn(parents);
        }

        void assertNothingListed(String request) {
            verify(navigation, never()).getChildren(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any());
            verify(navigation, never()).getObjectParents(any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        /** The objectIds of the answer, in order. */
        List<String> idsFor(String path, String query) throws Exception {
            ODataResponse response = new ODataResponse();
            read(path, query, response);
            String body;
            try (InputStream in = response.getContent()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            List<String> ids = new ArrayList<>();
            Matcher m = Pattern.compile("\"objectId\":\"([^\"]*)\"").matcher(body);
            while (m.find()) {
                ids.add(m.group(1));
            }
            return ids;
        }

        String statementFor(String path, String query) throws Exception {
            read(path, query, new ODataResponse());
            ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
            verify(discovery).query(any(), eq("bedroom"), statement.capture(), any(), any(), any(), any(), any(), any(), any());
            return statement.getValue();
        }

        private void read(String path, String query, ODataResponse response) throws Exception {
            OData odata = OData.newInstance();
            ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
            UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + path, query, null, BASE);
            CmisEntityCollectionProcessor processor =
                    new CmisEntityCollectionProcessor(null, objects, navigation, discovery, "bedroom", null);
            processor.init(odata, metadata);
            ODataRequest request = mock(ODataRequest.class);
            when(request.getRawBaseUri()).thenReturn(BASE);
            processor.readEntityCollection(request, response, uriInfo, ContentType.JSON);
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
