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
 * A type cast on a path that reads one entity by its key is answered only when there is none or it is the entity set's
 * own type (R254, 3.4.2).
 *
 * <p>{@code CmisEntityProcessor.readEntity} read the key and none of the casts Olingo keeps for it — after the key
 * ({@code Objects('D')/NemakiWare.CMIS.Policy}), before it ({@code Objects/NemakiWare.CMIS.Policy('D')}) and on
 * {@code $entity} ({@code $entity/NemakiWare.CMIS.Policy?$id=Objects('D')}) — so it answered the document for a cast to a
 * type it is not (measured on nb33). A cast to any other type is refused; the type of the entity read does not decide the
 * answer.
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntity}, as the servlet has them.
 */
class ACastOnASingleEntityIsAnsweredOnlyForItsEntitySetsOwnTypeTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";
    private static final String REFUSED = "a type cast on one entity is answered only for its entity set's own type, not for ";

    @Test
    @DisplayName("a cast to another type than the entity set's own is refused wherever it is written — nothing is read")
    void aCastToAnotherTypeIsRefused() {
        for (String[] c : new String[][] {
                {"Objects('D')/NemakiWare.CMIS.Policy", null, "NemakiWare.CMIS.Policy"},
                {"Objects('D')/NemakiWare.CMIS.Folder", null, "NemakiWare.CMIS.Folder"},
                // D is a document, but the rule is the entity set's own type, not the type of the entity read.
                {"Objects('D')/NemakiWare.CMIS.Document", null, "NemakiWare.CMIS.Document"},
                {"Objects/NemakiWare.CMIS.Policy('D')", null, "NemakiWare.CMIS.Policy"},
                {"$entity/NemakiWare.CMIS.Policy", "$id=Objects('D')", "NemakiWare.CMIS.Policy"},
                // The cast on $entity replaces the one after the key in what Olingo reports; the one after the key is read too.
                {"$entity/NemakiWare.CMIS.Object", "$id=Objects('D')/NemakiWare.CMIS.Policy", "NemakiWare.CMIS.Policy"}}) {
            String request = c[0] + (c[1] == null ? "" : "?" + c[1]);
            ObjectService objects = objects();
            ODataApplicationException refused = assertThrows(ODataApplicationException.class,
                    () -> idOf(c[0], c[1], objects), request + " was answered");
            assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), request + ": " + refused.getMessage());
            assertEquals(REFUSED + c[2], refused.getMessage(), request + " was refused by another arm");
            verify(objects, never()).getObject(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("no cast, or a cast to the entity set's own type, is answered as before")
    void noCastOrTheOwnTypeIsAnsweredAsBefore() {
        for (String[] c : new String[][] {
                {"Objects('D')", null, "D"}, {"Objects('D')/NemakiWare.CMIS.Object", null, "D"},
                {"Objects/NemakiWare.CMIS.Object('D')", null, "D"}, {"Folders('F')/NemakiWare.CMIS.Folder", null, "F"},
                {"$entity", "$id=Objects('D')", "D"}, {"$entity/NemakiWare.CMIS.Object", "$id=Objects('D')", "D"},
                {"Documents('D')", null, "D"}}) {
            String request = c[0] + (c[1] == null ? "" : "?" + c[1]);
            assertEquals(c[2], assertDoesNotThrow(() -> idOf(c[0], c[1], objects()), request + " was refused"), request);
        }
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
    private static String idOf(String path, String query, ObjectService objects) throws Exception {
        OData odata = OData.newInstance();
        ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
        UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + path, query, null, BASE);
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
