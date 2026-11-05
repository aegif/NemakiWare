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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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

/**
 * A type cast on the entity set of the path is answered only when it is the entity set's own type (R250, 3.4.2).
 *
 * <p>Olingo keeps the cast of {@code Objects/NemakiWare.CMIS.Policy} on the entity set segment
 * ({@code getTypeFilterOnCollection()}); {@code readEntityCollection} read the entity set alone, so the cast was dropped
 * and every entity of the set was answered — {@code Objects/NemakiWare.CMIS.Policy?$count=true} and
 * {@code Objects/NemakiWare.CMIS.Item?$count=true} answered every document (measured on nb33). Only Objects lets a cast
 * to another type through: Olingo refuses one its entity set's type is not a base of.
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntityCollection}, as the servlet has
 * them, and the statement is the one handed to {@code DiscoveryService.query}.
 */
class ACastOnTheEntitySetIsAnsweredOnlyForItsOwnTypeTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";

    @Test
    @DisplayName("Objects cast to Policy is refused, not answered with every entity — nothing is queried")
    void objectsCastToPolicyIsRefused() {
        assertRefused("Objects/NemakiWare.CMIS.Policy", "$count=true", "NemakiWare.CMIS.Policy");
        assertRefused("Objects/NemakiWare.CMIS.Policy", null, "NemakiWare.CMIS.Policy");
    }

    @Test
    @DisplayName("Objects cast to Item is refused, not answered with every entity — nothing is queried")
    void objectsCastToItemIsRefused() {
        assertRefused("Objects/NemakiWare.CMIS.Item", "$count=true", "NemakiWare.CMIS.Item");
        assertRefused("Objects/NemakiWare.CMIS.Item", null, "NemakiWare.CMIS.Item");
    }

    @Test
    @DisplayName("the rule is the entity set's own type, not the type Objects happens to query — a Document cast is refused")
    void objectsCastToDocumentIsRefused() {
        assertRefused("Objects/NemakiWare.CMIS.Document", null, "NemakiWare.CMIS.Document");
    }

    @Test
    @DisplayName("Objects without a cast is answered as before")
    void objectsWithoutACastIsAnsweredAsBefore() {
        assertEquals("SELECT * FROM cmis:document",
                assertDoesNotThrow(() -> statementSentFor("Objects", "$count=true", mock(DiscoveryService.class)),
                        "Objects?$count=true was refused"));
        assertEquals("SELECT * FROM cmis:document",
                assertDoesNotThrow(() -> statementSentFor("Objects", null, mock(DiscoveryService.class)),
                        "Objects was refused"));
        assertEquals("SELECT * FROM cmis:document WHERE cmis:name = 'x'",
                assertDoesNotThrow(() -> statementSentFor("Objects", "$filter=name%20eq%20'x'", mock(DiscoveryService.class)),
                        "Objects?$filter=name eq 'x' was refused"));
    }

    @Test
    @DisplayName("a cast to the entity set's own type is answered as the same request without it")
    void aCastToTheEntitySetsOwnTypeIsAnsweredAsWithout() {
        assertAnsweredAsWithout("Objects/NemakiWare.CMIS.Object", "Objects", "$count=true");
        assertAnsweredAsWithout("Documents/NemakiWare.CMIS.Document", "Documents", null);
        assertAnsweredAsWithout("Folders/NemakiWare.CMIS.Folder", "Folders", "$filter=name%20eq%20'x'");
    }

    private static void assertAnsweredAsWithout(String withTheCast, String withoutIt, String query) {
        String without = assertDoesNotThrow(() -> statementSentFor(withoutIt, query, mock(DiscoveryService.class)),
                withoutIt + " was refused");
        String with = assertDoesNotThrow(() -> statementSentFor(withTheCast, query, mock(DiscoveryService.class)),
                withTheCast + " was refused");
        assertEquals(without, with, withTheCast + (query == null ? "" : "?" + query));
    }

    private static void assertRefused(String path, String query, String cast) {
        DiscoveryService discovery = mock(DiscoveryService.class);
        String request = path + (query == null ? "" : "?" + query);
        ODataApplicationException refused = assertThrows(ODataApplicationException.class,
                () -> statementSentFor(path, query, discovery), request + " was answered");
        assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), refused.getMessage());
        assertEquals("a type cast on the entity set is answered only for the entity set's own type, not for " + cast,
                refused.getMessage(), request + " was refused by another arm");
        verify(discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** The CMIS statement readEntityCollection sends for the request, parsed by Olingo as the servlet has it parsed. */
    private static String statementSentFor(String path, String query, DiscoveryService discovery) throws Exception {
        OData odata = OData.newInstance();
        ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
        UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + path, query, null, BASE);
        CmisEntityCollectionProcessor processor =
                new CmisEntityCollectionProcessor(null, null, null, discovery, "bedroom", null);
        processor.init(odata, metadata);
        ODataRequest request = mock(ODataRequest.class);
        when(request.getRawBaseUri()).thenReturn(BASE);
        processor.readEntityCollection(request, new ODataResponse(), uriInfo, ContentType.JSON);
        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        verify(discovery).query(any(), eq("bedroom"), statement.capture(), any(), any(), any(), any(), any(), any(), any());
        return statement.getValue();
    }
}
