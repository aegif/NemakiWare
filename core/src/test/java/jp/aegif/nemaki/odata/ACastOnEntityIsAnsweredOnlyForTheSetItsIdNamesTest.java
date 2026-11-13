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
import jp.aegif.nemaki.cmis.service.VersioningService;

/**
 * A type cast on {@code $entity} is answered only when it is the type of what its {@code $id} answers (R253, 3.4.2).
 *
 * <p>Olingo parses the {@code $id} of {@code $entity/NemakiWare.CMIS.Policy?$id=Objects} again and keeps the cast in
 * {@code UriInfo.getEntityTypeCast()} — the type the {@code $id} answers when none is written. It was not read, so every
 * entity of the set was answered: 100 documents for a type that has none (measured on nb33). The type a cast is held
 * against is the one at the end of the {@code $id}: the entity set, the navigation ({@code children} answers Object,
 * {@code parents} Folder) or the function's return type. A cast to any other type is refused; {@code $entity} without a
 * cast, or with that type, is answered as the {@code $id} is.
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntityCollection}, with the function
 * processor behind it as the servlet has them.
 */
class ACastOnEntityIsAnsweredOnlyForTheSetItsIdNamesTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";
    private static final String QUERY =
            "Query(statement='SELECT%20*%20FROM%20cmis:document',searchAllVersions=false,maxItems=3,skipCount=0)";

    @Test
    @DisplayName("a cast on $entity to another type than its $id answers is refused — nothing is read")
    void aCastOnEntityToAnotherTypeIsRefused() {
        assertRefused("Objects", "NemakiWare.CMIS.Policy");
        assertRefused("Objects", "NemakiWare.CMIS.Item");
        assertRefused("Objects", "NemakiWare.CMIS.Document");
        // Olingo does not hold the cast against what the $id answers, so these reach the processor.
        assertRefused("Documents", "NemakiWare.CMIS.Policy");
        assertRefused("Folders('F')/children", "NemakiWare.CMIS.Folder");
        assertRefused("Documents('D')/parents", "NemakiWare.CMIS.Document");
        assertRefused("Documents('D')/NemakiWare.CMIS.GetAllVersions()", "NemakiWare.CMIS.Policy");
        assertRefused(QUERY, "NemakiWare.CMIS.Policy");
    }

    @Test
    @DisplayName("$entity without a cast, or with the type its $id answers, is answered as the $id is")
    void aCastOnEntityToWhatItsIdAnswersIsAnswered() {
        // Objects is not listed (R265 — AListingOfEveryObjectIsRefusedTest); Documents and Folders are.
        assertEquals(new Fixture().statementFor("Documents", null), new Fixture().statementFor("$entity", "$id=Documents"));
        assertEquals(new Fixture().statementFor("Folders", null),
                new Fixture().statementFor("$entity/NemakiWare.CMIS.Folder", "$id=Folders"));
        assertEquals(new Fixture().statementFor("Documents", null),
                new Fixture().statementFor("$entity/NemakiWare.CMIS.Document", "$id=Documents"));
        assertEquals(List.of("c1", "c2", "c3"), new Fixture().idsFor("$entity", "$id=Folders('F')/children"));
        assertEquals(List.of("c1", "c2", "c3"),
                new Fixture().idsFor("$entity/NemakiWare.CMIS.Object", "$id=Folders('F')/children"));
        assertEquals(List.of("p1"), new Fixture().idsFor("$entity", "$id=Documents('D')/parents"));
        assertEquals(List.of("p1"), new Fixture().idsFor("$entity/NemakiWare.CMIS.Folder", "$id=Documents('D')/parents"));
        assertEquals(List.of("v1", "v2"),
                new Fixture().idsFor("$entity", "$id=Documents('D')/NemakiWare.CMIS.GetAllVersions()"));
        assertEquals(List.of("v1", "v2"),
                new Fixture().idsFor("$entity/NemakiWare.CMIS.Document", "$id=Documents('D')/NemakiWare.CMIS.GetAllVersions()"));
    }

    private static void assertRefused(String id, String cast) {
        Fixture f = new Fixture();
        String request = "$entity/" + cast + "?$id=" + id;
        ODataApplicationException refused = assertThrows(ODataApplicationException.class,
                () -> f.read("$entity/" + cast, "$id=" + id), request + " was answered");
        assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), request + ": " + refused.getMessage());
        assertEquals("a type cast on $entity is answered only for the type its $id answers, not for " + cast,
                refused.getMessage(), request + " was refused by another arm");
        verify(f.discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(f.navigation, never()).getChildren(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
        verify(f.navigation, never()).getObjectParents(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(f.versioning, never()).getAllVersions(any(), any(), any(), any(), any(), any(), any());
    }

    /** F is a folder with c1..c3, D a document filed in p1 with the versions v1 and v2. */
    private static final class Fixture {
        final DiscoveryService discovery = mock(DiscoveryService.class);
        final NavigationService navigation = mock(NavigationService.class);
        final ObjectService objects = mock(ObjectService.class);
        final VersioningService versioning = mock(VersioningService.class);

        Fixture() {
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
            children.setHasMoreItems(Boolean.FALSE);
            when(navigation.getChildren(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any())).thenReturn(children);
            ObjectParentDataImpl parent = new ObjectParentDataImpl();
            parent.setObject(object("p1", "cmis:folder"));
            List<ObjectParentData> parents = List.of(parent);
            when(navigation.getObjectParents(any(), eq("bedroom"), eq("D"), any(), any(), any(), any(), any(), any()))
                    .thenReturn(parents);
            List<ObjectData> versions = List.of(object("v1", "cmis:document"), object("v2", "cmis:document"));
            when(versioning.getAllVersions(any(), eq("bedroom"), eq("D"), any(), any(), any(), any())).thenReturn(versions);
        }

        String statementFor(String path, String query) {
            assertDoesNotThrow(() -> read(path, query), path + (query == null ? "" : "?" + query) + " was refused");
            ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
            verify(discovery).query(any(), eq("bedroom"), statement.capture(), any(), any(), any(), any(), any(), any(), any());
            return statement.getValue();
        }

        List<String> idsFor(String path, String query) {
            String body = assertDoesNotThrow(() -> read(path, query), path + "?" + query + " was refused");
            List<String> ids = new ArrayList<>();
            Matcher m = Pattern.compile("\"objectId\":\"([^\"]*)\"").matcher(body);
            while (m.find()) {
                ids.add(m.group(1));
            }
            return ids;
        }

        String read(String path, String query) throws Exception {
            OData odata = OData.newInstance();
            ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
            UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + path, query, null, BASE);
            CmisFunctionProcessor functions =
                    new CmisFunctionProcessor(null, objects, navigation, discovery, versioning, "bedroom", null);
            functions.init(odata, metadata);
            CmisEntityCollectionProcessor processor =
                    new CmisEntityCollectionProcessor(null, objects, navigation, discovery, "bedroom", null);
            processor.init(odata, metadata);
            processor.setFunctionProcessor(functions);
            ODataRequest request = mock(ODataRequest.class);
            when(request.getRawBaseUri()).thenReturn(BASE);
            when(request.getRawODataPath()).thenReturn("/" + path);
            when(request.getRawQueryPath()).thenReturn(query);
            ODataResponse response = new ODataResponse();
            processor.readEntityCollection(request, response, uriInfo, ContentType.JSON);
            try (InputStream in = response.getContent()) {
                return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
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
