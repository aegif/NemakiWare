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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * A property with a type cast is translated only when the cast is the entity set's own type (R249, 3.4.2).
 *
 * <p>Olingo keeps a leading cast ({@code NemakiWare.CMIS.Policy/name}) in {@code Member.getStartTypeFilter()} and leaves
 * the property as the resource path; the translation read the path alone, so the cast was dropped and the condition was
 * asked of every entity — {@code Objects?$filter=contains(NemakiWare.CMIS.Policy/name,'')} answered every document
 * (measured on nb33). Olingo itself refuses a cast its entity set's type is not a base of, so only an entity set whose
 * type is a base type (Objects) let one through.
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntityCollection}, so the cast is where
 * Olingo puts it and the entity set's type is the one the request names — not a mock of either.
 */
class ATypeCastIsTranslatedOnlyToTheEntitySetsOwnTypeTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";

    @Test
    @DisplayName("a cast to another type is refused in a comparison, on either side — nothing is queried")
    void aCastToAnotherTypeIsRefusedInAComparison() {
        assertRefused("Objects", "NemakiWare.CMIS.Policy/name eq 'x'", "NemakiWare.CMIS.Policy");
        assertRefused("Objects", "'acltest-probe-x1.txt' eq NemakiWare.CMIS.Folder/name", "NemakiWare.CMIS.Folder");
    }

    @Test
    @DisplayName("a cast to another type is refused in contains, startswith and endswith — nothing is queried")
    void aCastToAnotherTypeIsRefusedInEachLikeMethod() {
        assertRefused("Objects", "contains(NemakiWare.CMIS.Policy/name,'')", "NemakiWare.CMIS.Policy");
        assertRefused("Objects", "startswith(NemakiWare.CMIS.Folder/name,'acltest')", "NemakiWare.CMIS.Folder");
        // The type Objects queries is cmis:document, but the rule is the entity set's own type, not the query's.
        assertRefused("Objects", "endswith(NemakiWare.CMIS.Document/name,'.txt')", "NemakiWare.CMIS.Document");
    }

    @Test
    @DisplayName("in a comparison a cast to the entity set's own type is answered as the same filter without it")
    void aCastToTheEntitySetsOwnTypeIsAnsweredInAComparison() {
        String plain = assertDoesNotThrow(() -> statementSentFor("Documents", "name eq 'x'"),
                "the filter without a cast was refused");
        assertTrue(plain.endsWith(" WHERE cmis:name = 'x'"), plain);
        assertAnsweredAsWithout("Documents", "NemakiWare.CMIS.Document/name eq 'x'", "name eq 'x'");
        assertAnsweredAsWithout("Documents", "'x' eq NemakiWare.CMIS.Document/name", "'x' eq name");
        assertAnsweredAsWithout("Objects", "NemakiWare.CMIS.Object/name ne 'x'", "name ne 'x'");
    }

    @Test
    @DisplayName("in contains, startswith and endswith a cast to the entity set's own type is answered as without it")
    void aCastToTheEntitySetsOwnTypeIsAnsweredInEachLikeMethod() {
        assertAnsweredAsWithout("Documents", "contains(NemakiWare.CMIS.Document/name,'test')", "contains(name,'test')");
        assertAnsweredAsWithout("Folders", "startswith(NemakiWare.CMIS.Folder/name,'Test')", "startswith(name,'Test')");
        assertAnsweredAsWithout("Objects", "endswith(NemakiWare.CMIS.Object/name,'.txt')", "endswith(name,'.txt')");
    }

    private static void assertAnsweredAsWithout(String entitySet, String withTheCast, String withoutIt) {
        String without = assertDoesNotThrow(() -> statementSentFor(entitySet, withoutIt), withoutIt + " was refused");
        assertTrue(without.contains(" WHERE "), without);
        String with = assertDoesNotThrow(() -> statementSentFor(entitySet, withTheCast),
                entitySet + "?$filter=" + withTheCast + " was refused");
        assertEquals(without, with, entitySet + "?$filter=" + withTheCast);
    }

    private static void assertRefused(String entitySet, String filter, String cast) {
        DiscoveryService discovery = mock(DiscoveryService.class);
        ODataApplicationException refused = assertThrows(ODataApplicationException.class,
                () -> statementSentFor(entitySet, filter, discovery), entitySet + "?$filter=" + filter + " was answered");
        assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), refused.getMessage());
        assertEquals("Error converting filter expression: a type cast is translated only to the entity set's own type, not to "
                + cast, refused.getMessage(), entitySet + "?$filter=" + filter + " was refused by another arm");
        verify(discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static String statementSentFor(String entitySet, String filter) throws Exception {
        return statementSentFor(entitySet, filter, mock(DiscoveryService.class));
    }

    /** The CMIS statement readEntityCollection sends for the request, parsed by Olingo as the servlet has it parsed. */
    private static String statementSentFor(String entitySet, String filter, DiscoveryService discovery) throws Exception {
        OData odata = OData.newInstance();
        ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
        UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + entitySet,
                "$filter=" + filter.replace(" ", "%20"), null, BASE);
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
