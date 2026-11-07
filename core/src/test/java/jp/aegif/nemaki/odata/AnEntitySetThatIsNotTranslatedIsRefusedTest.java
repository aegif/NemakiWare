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
 * An entity set the processor does not translate is refused, not answered empty (R257, 3.4.2).
 *
 * <p>{@code Types}, {@code Users} and {@code Groups} have no CMIS query behind them: the listing made none and answered an
 * empty set with a count of 0, though the repository has types, users and groups (measured on nb33). Their contents are
 * not translated; the listing is refused.
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntityCollection}, as the servlet has
 * them.
 */
class AnEntitySetThatIsNotTranslatedIsRefusedTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";

    @Test
    @DisplayName("Types, Users and Groups are refused, not answered empty — nothing is queried")
    void anEntitySetThatIsNotTranslatedIsRefused() {
        for (String set : List.of("Types", "Users", "Groups")) {
            for (String query : new String[] {null, "$count=true", "$top=5"}) {
                DiscoveryService discovery = mock(DiscoveryService.class);
                String request = set + (query == null ? "" : "?" + query);
                ODataApplicationException refused = assertThrows(ODataApplicationException.class,
                        () -> statementFor(set, query, discovery), request + " was answered");
                assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(),
                        request + ": " + refused.getMessage());
                assertEquals("the entity set " + set + " is not translated", refused.getMessage(),
                        request + " was refused by another arm");
                verify(discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            }
        }
    }

    @Test
    @DisplayName("an entity set with a CMIS query behind it is answered as before")
    void anEntitySetWithAQueryIsAnsweredAsBefore() {
        assertEquals("SELECT * FROM cmis:folder", assertDoesNotThrow(() -> statementFor("Folders", null, mock(DiscoveryService.class))));
        assertEquals("SELECT * FROM cmis:document", assertDoesNotThrow(() -> statementFor("Objects", null, mock(DiscoveryService.class))));
        assertEquals("SELECT * FROM cmis:policy", assertDoesNotThrow(() -> statementFor("Policies", null, mock(DiscoveryService.class))));
        assertEquals("SELECT * FROM cmis:item", assertDoesNotThrow(() -> statementFor("Items", null, mock(DiscoveryService.class))));
        assertEquals("SELECT * FROM cmis:relationship",
                assertDoesNotThrow(() -> statementFor("Relationships", null, mock(DiscoveryService.class))));
    }

    /** The CMIS statement readEntityCollection sends for the request, parsed by Olingo as the servlet has it parsed. */
    private static String statementFor(String set, String query, DiscoveryService discovery) throws Exception {
        OData odata = OData.newInstance();
        ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
        UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + set, query, null, BASE);
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
