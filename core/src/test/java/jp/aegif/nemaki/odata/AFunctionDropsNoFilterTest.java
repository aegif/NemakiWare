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

import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
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
import jp.aegif.nemaki.cmis.service.VersioningService;

/**
 * A function that is given a $filter refuses it rather than dropping it (R252, 3.4.2).
 *
 * <p>{@code CmisFunctionProcessor} did not read $filter: {@code Query(...)?$filter=name eq 'zz-none'} answered the same 3 as
 * without it (measured on nb33). The filter is not translated into CMIS; a function given one refuses the request.
 *
 * <p>The requests go through Olingo's own parser and the function processor's {@code readEntityCollection}, as the
 * servlet hands a function's collection to it.
 */
class AFunctionDropsNoFilterTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";
    private static final String QUERY =
            "Query(statement='SELECT%20*%20FROM%20cmis:document',searchAllVersions=false,maxItems=3,skipCount=0)";
    private static final String REFUSED = "$filter is not translated on a function — put the condition in the statement";

    @Test
    @DisplayName("a function import given a $filter refuses it — nothing is queried")
    void aFunctionImportGivenAFilterRefusesIt() {
        DiscoveryService discovery = discovery();
        assertRefused(QUERY, "$filter=name%20eq%20'zz-none'", discovery, mock(VersioningService.class));
        verify(discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a bound function given a $filter refuses it — no version is read")
    void aBoundFunctionGivenAFilterRefusesIt() {
        VersioningService versioning = mock(VersioningService.class);
        assertRefused("Documents('D')/NemakiWare.CMIS.GetAllVersions()", "$filter=name%20eq%20'zz-none'", discovery(), versioning);
        verify(versioning, never()).getAllVersions(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a function without a $filter is answered as before")
    void aFunctionWithoutAFilterIsAnsweredAsBefore() {
        DiscoveryService discovery = discovery();
        assertDoesNotThrow(() -> read(QUERY, null, discovery, mock(VersioningService.class)));
        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        verify(discovery).query(any(), eq("bedroom"), statement.capture(), any(), any(), any(), any(), any(), any(), any());
        assertEquals("SELECT * FROM cmis:document", statement.getValue());
        VersioningService versioning = mock(VersioningService.class);
        assertDoesNotThrow(() -> read("Documents('D')/NemakiWare.CMIS.GetAllVersions()", null, discovery(), versioning));
        verify(versioning).getAllVersions(any(), eq("bedroom"), eq("D"), any(), any(), any(), any());
    }

    private static DiscoveryService discovery() {
        DiscoveryService discovery = mock(DiscoveryService.class);
        when(discovery.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new ObjectListImpl());
        return discovery;
    }

    private static void assertRefused(String path, String query, DiscoveryService discovery, VersioningService versioning) {
        String request = path + "?" + query;
        ODataApplicationException refused = assertThrows(ODataApplicationException.class,
                () -> read(path, query, discovery, versioning), request + " was answered");
        assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), request + ": " + refused.getMessage());
        assertEquals(REFUSED, refused.getMessage(), request + " was refused by another arm");
    }

    private static void read(String path, String query, DiscoveryService discovery, VersioningService versioning)
            throws Exception {
        OData odata = OData.newInstance();
        ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
        UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + path, query, null, BASE);
        CmisFunctionProcessor processor =
                new CmisFunctionProcessor(null, null, null, discovery, versioning, "bedroom", null);
        processor.init(odata, metadata);
        ODataRequest request = mock(ODataRequest.class);
        when(request.getRawBaseUri()).thenReturn(BASE);
        processor.readEntityCollection(request, new ODataResponse(), uriInfo, ContentType.JSON);
    }
}
