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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
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

/**
 * An answer the ACL scan limit cut short is not taken for the whole set (R258, R345, 3.4.2).
 *
 * <p>While the ACL propagation has not converged, {@code SolrQueryProcessor} answers a large query with what its scan window
 * could confirm: {@code hasMoreItems} goes false at the end of the window, {@code numItems} is a lower bound, and the answer
 * carries the extension {@code truncatedByAclScanLimit=true}. The entity set reader did not read it, so the lower bound was
 * written as {@code @odata.count} and the pages ended at the window — read in full, the count and the pages agreed and the
 * answer looked whole (R258). And the window is the first rows of the backend's own order ({@code modified desc}); the
 * request's {@code $orderby}, or the repository's default order, is applied inside it only, so even a page inside the window
 * is not a page of the whole set's order — {@code Documents?$orderby=name&$top=2} answered the window's first two names,
 * passing over the documents outside it that come first (R345, measured by a review with a stand-in that copies the window).
 * (Read from the code; the running product cannot be brought into that state with reads alone — the evidence is this
 * fixture.)
 *
 * <p>What is locked, on the product's {@code readEntityCollection} through Olingo's own parser, with the backend's answer
 * a stand-in: a marked answer refuses {@code $count=true}; it is answered only as {@code $top=0} without {@code $count} — no
 * row, no count — and every other page of it is refused, with no rows and no nextLink; an answer without the mark, or with
 * it not true, is answered as before.
 */
class AnAnswerTheAclScanLimitCutShortIsNotTakenForTheWholeTest {

    private static final String BASE = "http://localhost/core/odata/bedroom";

    @Test
    @DisplayName("a cut-short answer refuses $count=true — its count is a lower bound")
    void aCutShortAnswerRefusesTheCount() {
        assertRefused(new Fixture().listing(100, Boolean.TRUE, 300, "true"), "Documents", "$count=true",
                CmisEntityCollectionProcessor.CUT_SHORT_COUNT_NOT_KNOWN);
        assertRefused(new Fixture().listing(40, Boolean.FALSE, 40, "true"), "Documents", "$top=10&$count=true",
                CmisEntityCollectionProcessor.CUT_SHORT_COUNT_NOT_KNOWN);
        assertRefused(new Fixture().listing(40, Boolean.FALSE, 40, "true"), "Documents", "$top=0&$count=true",
                CmisEntityCollectionProcessor.CUT_SHORT_COUNT_NOT_KNOWN);
    }

    @Test
    @DisplayName("a cut-short answer ordered by $orderby is refused, not answered with the window's first page (R345)")
    void aCutShortOrderedPageIsNotAnsweredFromTheWindow() {
        // The window holds C, D, E, F (the last modified); A and B come first by name but are outside it. Inside the window,
        // ordered by name, the first two are C and D — the page the backend answers.
        Fixture marked = new Fixture().listing(List.of("C", "D"), Boolean.TRUE, 4, "true");
        assertRefused(marked, "Documents", "$orderby=name&$top=2", CmisEntityCollectionProcessor.CUT_SHORT_NOT_ANSWERED);
        // The positive control: without the mark the same backend page is answered, so the refusal is the mark's.
        Answer unmarked = assertDoesNotThrow(() -> new Fixture().listing(List.of("C", "D"), Boolean.TRUE, 4, null)
                .read("Documents", "$orderby=name&$top=2"));
        assertEquals(List.of("C", "D"), unmarked.ids);
    }

    @Test
    @DisplayName("every other page of a cut-short answer is refused — one inside a $top, one with more inside the window, the last")
    void everyOtherPageOfACutShortAnswerIsRefused() {
        assertRefused(new Fixture().listing(10, Boolean.FALSE, 10, "true"), "Documents", "$top=10",
                CmisEntityCollectionProcessor.CUT_SHORT_NOT_ANSWERED);
        assertRefused(new Fixture().listing(10, Boolean.TRUE, 300, "true"), "Documents", "$top=10",
                CmisEntityCollectionProcessor.CUT_SHORT_NOT_ANSWERED);
        assertRefused(new Fixture().listing(100, Boolean.TRUE, 300, "true"), "Documents", null,
                CmisEntityCollectionProcessor.CUT_SHORT_NOT_ANSWERED);
        assertRefused(new Fixture().listing(5, Boolean.TRUE, 300, "true"), "Documents", "$top=20",
                CmisEntityCollectionProcessor.CUT_SHORT_NOT_ANSWERED);
        assertRefused(new Fixture().listing(40, Boolean.FALSE, 40, "true"), "Documents", null,
                CmisEntityCollectionProcessor.CUT_SHORT_NOT_ANSWERED);
        assertRefused(new Fixture().listing(40, Boolean.FALSE, 140, "true"), "Documents", "$skip=100",
                CmisEntityCollectionProcessor.CUT_SHORT_NOT_ANSWERED);
    }

    @Test
    @DisplayName("$top=0 without $count is answered with no row, no count and no nextLink")
    void aTopOfZeroIsAnsweredWithNoRow() {
        Answer none = assertDoesNotThrow(() -> new Fixture().listing(10, Boolean.TRUE, 300, "true").read("Documents", "$top=0"),
                "$top=0 of a cut-short answer was refused");
        assertEquals(List.of(), none.ids);
        assertNull(none.count);
        assertNull(none.next);
    }

    @Test
    @DisplayName("an answer without the mark, or with it not true, is answered as before — the count it has, where it continues")
    void anAnswerWithoutTheMarkIsAnsweredAsBefore() {
        for (String mark : new String[] {null, "false"}) {
            Answer a = assertDoesNotThrow(() -> new Fixture().listing(40, Boolean.FALSE, 40, mark).read("Documents", "$count=true"),
                    "mark " + mark);
            assertEquals(40, a.ids.size(), "mark " + mark);
            assertEquals(40, a.count, "mark " + mark);
            assertNull(a.next, "mark " + mark);
            Answer paged = assertDoesNotThrow(() -> new Fixture().listing(100, Boolean.TRUE, 300, mark).read("Documents", "$skip=100"),
                    "mark " + mark);
            assertEquals(100, paged.ids.size(), "mark " + mark);
            assertEquals(BASE + "/Documents?$skip=200", paged.next, "mark " + mark);
        }
    }

    private static void assertRefused(Fixture f, String path, String query, String message) {
        String request = path + (query == null ? "" : "?" + query);
        ODataApplicationException refused = assertThrows(ODataApplicationException.class, () -> f.read(path, query),
                () -> request + " was answered: " + f.last);
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
        /** What the last read answered, for the message of a refusal that did not happen. */
        String last;

        /** The query's answer: {@code n} objects, what the backend says of the rest, and the mark (null: none). */
        Fixture listing(int n, Boolean hasMoreItems, Integer numItems, String mark) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ids.add("o" + i);
            }
            return listing(ids, hasMoreItems, numItems, mark);
        }

        /** The query's answer: the objects named, what the backend says of the rest, and the mark (null: none). */
        Fixture listing(List<String> ids, Boolean hasMoreItems, Integer numItems, String mark) {
            ObjectListImpl list = new ObjectListImpl();
            List<ObjectData> found = new ArrayList<>();
            for (String id : ids) {
                found.add(object(id));
            }
            list.setObjects(found);
            list.setHasMoreItems(hasMoreItems);
            list.setNumItems(numItems == null ? null : BigInteger.valueOf(numItems));
            if (mark != null) {
                List<CmisExtensionElement> extensions = new ArrayList<>();
                extensions.add(new CmisExtensionElementImpl(null, "truncatedByAclScanLimit", null, mark));
                list.setExtensions(extensions);
            }
            when(discovery.query(any(), eq("bedroom"), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(list);
            return this;
        }

        Answer read(String path, String query) throws Exception {
            OData odata = OData.newInstance();
            ServiceMetadata metadata = odata.createServiceMetadata(new CmisEdmProvider(), List.of());
            UriInfo uriInfo = new Parser(metadata.getEdm(), odata).parseUri("/" + path, query, null, BASE);
            CmisEntityCollectionProcessor processor = new CmisEntityCollectionProcessor(null, null, null, discovery, "bedroom", null);
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
            last = body;
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

    private static ObjectData object(String id) {
        PropertiesImpl properties = new PropertiesImpl();
        properties.addProperty(new PropertyIdImpl("cmis:objectId", id));
        properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:document"));
        properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:document"));
        properties.addProperty(new PropertyStringImpl("cmis:name", id + ".name"));
        ObjectDataImpl object = new ObjectDataImpl();
        object.setProperties(properties);
        return object;
    }
}
