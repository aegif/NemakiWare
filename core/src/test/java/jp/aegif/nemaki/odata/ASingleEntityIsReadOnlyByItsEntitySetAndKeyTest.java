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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
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

import jp.aegif.nemaki.cmis.service.ObjectService;

/**
 * A single entity is read only by its entity set and key; a navigation segment after the key is refused (R255, 3.4.2).
 *
 * <p>{@code CmisEntityProcessor.readEntity} read the key of the first segment and nothing after it:
 * {@code Folders('F')/parent} answered F itself, {@code Documents('D')/parent} D itself, and
 * {@code Folders('F')/children('R')} F for an R that is not F's child (measured on nb33). A navigation after the key is not
 * translated; it is refused.
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntity}, as the servlet has them.
 */
class ASingleEntityIsReadOnlyByItsEntitySetAndKeyTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";
    private static final String REFUSED = "a single entity is read only by its entity set and key — a navigation after the key is not read";

    @Test
    @DisplayName("a navigation after the key is refused, not answered with the keyed entity — nothing is read")
    void aNavigationAfterTheKeyIsRefused() {
        for (String path : List.of("Folders('F')/parent", "Documents('D')/parent", "Folders('F')/children('D')",
                "Documents('D')/parents('F')", "Folders('F')/children('R')", "Relationships('r')/source",
                "Types('cmis:document')/parentType")) {
            ObjectService objects = objects();
            ODataApplicationException refused = assertThrows(ODataApplicationException.class, () -> idOf(path, objects),
                    path + " was answered");
            assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), path + ": " + refused.getMessage());
            assertEquals(REFUSED, refused.getMessage(), path + " was refused by another arm");
            verify(objects, never()).getObject(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("an entity by its entity set and key is answered as before")
    void anEntityByItsKeyIsAnsweredAsBefore() {
        ObjectService folder = objects();
        assertEquals("F", assertDoesNotThrow(() -> idOf("Folders('F')", folder)));
        verify(folder).getObject(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(), any());
        assertEquals("D", assertDoesNotThrow(() -> idOf("Documents('D')", objects())));
    }

    private static ObjectService objects() {
        ObjectService objects = mock(ObjectService.class);
        doReturn(object("F", "cmis:folder")).when(objects)
                .getObject(any(), eq("bedroom"), eq("F"), any(), any(), any(), any(), any(), any(), any());
        doReturn(object("D", "cmis:document")).when(objects)
                .getObject(any(), eq("bedroom"), eq("D"), any(), any(), any(), any(), any(), any(), any());
        return objects;
    }

    /** The objectId of the entity readEntity answers for the request. */
    private static String idOf(String path, ObjectService objects) throws Exception {
        OData odata = OData.newInstance();
        ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
        UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + path, null, null, BASE);
        CmisEntityProcessor processor = new CmisEntityProcessor(null, objects, null, "bedroom", null);
        processor.init(odata, metadata);
        ODataRequest request = mock(ODataRequest.class);
        when(request.getRawBaseUri()).thenReturn(BASE);
        ODataResponse response = new ODataResponse();
        processor.readEntity(request, response, uriInfo, ContentType.JSON);
        String body;
        try (InputStream in = response.getContent()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("\"objectId\":\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
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
