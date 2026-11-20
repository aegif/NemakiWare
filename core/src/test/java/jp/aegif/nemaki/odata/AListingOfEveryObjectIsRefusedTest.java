/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
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
import jp.aegif.nemaki.cmis.service.NavigationService;
import jp.aegif.nemaki.cmis.service.ObjectService;
import jp.aegif.nemaki.cmis.service.VersioningService;

/**
 * A listing of the entity set {@code Objects} is refused (R265, 3.4.2).
 *
 * <p>{@code Objects} is the entity set of every object, but its listing was asked of documents alone:
 * {@code Objects?$filter=baseTypeId eq 'cmis:folder'&$count=true} answered no folder with a count of 0, and
 * {@code Objects?$count=true} counted the documents only — no folder, item, relationship or policy (measured on nb33). A
 * listing of every object is not translated; it is refused where the entity sets that are not translated are (R257), with
 * words that name the entity sets of each base type. One object read as {@code Objects('<id>')} is the entity processor's
 * and is not changed (its locks are {@code ASingleEntityIsAnsweredOnlyByAnEntitySetThatHoldsItTest} and the cast locks).
 *
 * <p>The requests go through Olingo's own parser and the processor's {@code readEntityCollection}, as the servlet has
 * them, and the statement is the one handed to {@code DiscoveryService.query}.
 */
class AListingOfEveryObjectIsRefusedTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";

    @Test
    @DisplayName("a listing of Objects is refused naming the entity sets of each base type, whatever it asks — nothing is queried")
    void aListingOfObjectsIsRefused() {
        for (String query : new String[] {null, "$count=true", "$filter=baseTypeId%20eq%20'cmis:folder'&$count=true", "$top=2",
                "$skip=2", "$orderby=name", "$filter=name%20eq%20'x'", "$select=name", "$search=x",
                "$apply=filter(name%20eq%20'x')"}) {
            assertRefused("Objects", query);
        }
    }

    @Test
    @DisplayName("Objects cast to its own type and $entity naming Objects are refused the same — nothing is queried")
    void theOtherWaysToListObjectsAreRefusedTheSame() {
        assertRefused("Objects/NemakiWare.CMIS.Object", "$count=true");
        assertRefused("$entity", "$id=Objects");
        assertRefused("$entity/NemakiWare.CMIS.Object", "$id=Objects");
    }

    @Test
    @DisplayName("the entity sets of each base type are listed as before (the positive control)")
    void theEntitySetsOfEachBaseTypeAreListedAsBefore() {
        assertEquals("SELECT * FROM cmis:document", assertDoesNotThrow(() -> new Fixture().statementFor("Documents", null)));
        assertEquals("SELECT * FROM cmis:folder", assertDoesNotThrow(() -> new Fixture().statementFor("Folders", null)));
        assertEquals("SELECT * FROM cmis:item", assertDoesNotThrow(() -> new Fixture().statementFor("Items", null)));
        assertEquals("SELECT * FROM cmis:relationship",
                assertDoesNotThrow(() -> new Fixture().statementFor("Relationships", null)));
        assertEquals("SELECT * FROM cmis:policy", assertDoesNotThrow(() -> new Fixture().statementFor("Policies", null)));
        assertEquals("SELECT * FROM cmis:document", assertDoesNotThrow(() -> new Fixture().statementFor("$entity", "$id=Documents")));
    }

    @Test
    @DisplayName("Types, Users and Groups keep their own words — the Objects words are Objects' alone")
    void theOtherEntitySetsThatAreNotTranslatedKeepTheirOwnWords() {
        for (String set : List.of("Types", "Users", "Groups")) {
            Fixture f = new Fixture();
            ODataApplicationException refused = assertThrows(ODataApplicationException.class, () -> f.read(set, null),
                    set + " was answered");
            assertEquals("the entity set " + set + " is not translated", refused.getMessage(), set);
        }
    }

    private static void assertRefused(String path, String query) {
        Fixture f = new Fixture();
        String request = path + (query == null ? "" : "?" + query);
        ODataApplicationException refused = assertThrows(ODataApplicationException.class, () -> f.read(path, query),
                request + " was answered");
        assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), refused.getStatusCode(), request + ": " + refused.getMessage());
        assertEquals(CmisEntityCollectionProcessor.OBJECTS_LISTING_NOT_TRANSLATED, refused.getMessage(),
                request + " was refused by another arm");
        verify(f.discovery, never()).query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(f.navigation, never()).getChildren(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    private static final class Fixture {
        final DiscoveryService discovery = mock(DiscoveryService.class);
        final NavigationService navigation = mock(NavigationService.class);
        final ObjectService objects = mock(ObjectService.class);
        final VersioningService versioning = mock(VersioningService.class);

        String statementFor(String path, String query) throws Exception {
            read(path, query);
            ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
            verify(discovery).query(any(), eq("bedroom"), statement.capture(), any(), any(), any(), any(), any(), any(), any());
            return statement.getValue();
        }

        void read(String path, String query) throws Exception {
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
            processor.readEntityCollection(request, new ODataResponse(), uriInfo, ContentType.JSON);
        }
    }
}
