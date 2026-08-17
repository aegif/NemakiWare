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
package jp.aegif.nemaki.dao.impl.couch.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.ibm.cloud.cloudant.v1.Cloudant;
import com.ibm.cloud.cloudant.v1.model.Document;
import com.ibm.cloud.cloudant.v1.model.GetDocumentOptions;
import com.ibm.cloud.sdk.core.http.Response;
import com.ibm.cloud.sdk.core.http.ServiceCall;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * A document read as a {@code Map} must carry {@code _id} and {@code _rev}.
 *
 * <p>The Cloudant SDK models those two as {@code id}/{@code rev} bean properties, so a generic
 * Jackson conversion of its {@code Document} drops them. Anything that then hands the map back to
 * {@link CloudantClientWrapper#update(Map)} is rejected:
 *
 * <pre>IllegalArgumentException: Document must have '_id' field for update</pre>
 *
 * <p>Which is not hypothetical: every read-modify-write in the purview/lineage stores reads through
 * the three-argument overload, and it had omitted the "ensure _id/_rev" step that the two-argument
 * overload has always done. {@code LineageProjectionLoop} could not claim a single event — no
 * lineage event was ever projected to the catalog, and it retried the same one every 10 seconds
 * indefinitely. Deleting either {@code put} below reproduces that.
 */
public class CloudantClientWrapperGetIdTest {

    private static final String DB = "nemaki_lineage";
    private static final String DOC_ID = "lineage:e6e56141-79d0-4f36-ac3a-f5fffc532684";
    private static final String DOC_REV = "1-4cb7ce095d568cd6692379be2c69d105";

    @SuppressWarnings("unchecked")
    private CloudantClientWrapper wrapperReturning(Document doc) {
        Cloudant client = mock(Cloudant.class);
        ServiceCall<Document> call = mock(ServiceCall.class);
        Response<Document> response = mock(Response.class);
        when(response.getResult()).thenReturn(doc);
        when(call.execute()).thenReturn(response);
        when(client.getDocument(any(GetDocumentOptions.class))).thenReturn(call);
        return new CloudantClientWrapper(client, DB, ObjectMapperFactory.createDefaultObjectMapper());
    }

    private Document lineageEventDocument() {
        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setRev(DOC_REV);
        doc.put("type", "lineage_event");
        doc.put("eventKey", "bedroom:EXPORT_ZIP_FOLDER:-726561514:0");
        doc.put("publishStatusByTarget", Map.of("atlas", "PENDING"));
        return doc;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void mapReadThroughTheRevisionOverloadKeepsIdAndRev() {
        CloudantClientWrapper wrapper = wrapperReturning(lineageEventDocument());

        Map<String, Object> map = (Map<String, Object>) wrapper.get(Map.class, DOC_ID, null);

        assertNotNull(map, "the document was returned by the client, so it must not be dropped");
        assertEquals(DOC_ID, map.get("_id"),
                "without _id the caller cannot write the document back — update() throws");
        assertEquals(DOC_REV, map.get("_rev"),
                "without _rev the write is not a compare-and-set and CouchDB answers 409");
    }

    /** The document's own fields must still be there — the fix is additive, not a replacement. */
    @Test
    @SuppressWarnings("unchecked")
    public void theDocumentsOwnFieldsSurvive() {
        CloudantClientWrapper wrapper = wrapperReturning(lineageEventDocument());

        Map<String, Object> map = (Map<String, Object>) wrapper.get(Map.class, DOC_ID, null);

        assertEquals("lineage_event", map.get("type"));
        assertEquals("bedroom:EXPORT_ZIP_FOLDER:-726561514:0", map.get("eventKey"));
        assertNotNull(map.get("publishStatusByTarget"),
                "the projection loop reads this to decide whether an event still needs publishing");
    }

    /** Both overloads must produce the SAME map, not merely the same identity. */
    @Test
    @SuppressWarnings("unchecked")
    public void bothOverloadsProduceTheSameMap() {
        Map<String, Object> viaRevision =
                (Map<String, Object>) wrapperReturning(lineageEventDocument()).get(Map.class, DOC_ID, null);
        Map<String, Object> viaPlain =
                (Map<String, Object>) wrapperReturning(lineageEventDocument()).get(Map.class, DOC_ID);

        assertEquals(viaPlain, viaRevision,
                "the two overloads used to disagree on layout: one buried the document's fields "
                        + "under a nested `properties` key while the other did not");
    }

    /**
     * Nothing of the SDK's own bean shape may reach the map, because whatever is in it is what
     * update() writes back. `properties` in particular would nest one level deeper on every
     * read-modify-write and the document would grow without bound.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void noSdkInternalKeysLeakIntoTheMap() {
        Map<String, Object> map =
                (Map<String, Object>) wrapperReturning(lineageEventDocument()).get(Map.class, DOC_ID, null);

        for (String internal : new String[] { "properties", "propertyNames", "id", "rev",
                "attachments", "conflicts", "deleted", "deletedConflicts", "localSeq", "revsInfo" }) {
            org.junit.jupiter.api.Assertions.assertFalse(map.containsKey(internal),
                    "SDK-internal key '" + internal + "' would be persisted by update()");
        }
        assertEquals(Set.of("_id", "_rev", "type", "eventKey", "publishStatusByTarget"), map.keySet());
    }

    /** Read, modify, write, read again: the shape must be a fixed point, not grow each round. */
    @Test
    @SuppressWarnings("unchecked")
    public void readModifyWriteIsShapeStable() {
        Map<String, Object> first =
                (Map<String, Object>) wrapperReturning(lineageEventDocument()).get(Map.class, DOC_ID, null);

        // What a store would write back: the map it just read, with one field changed.
        Map<String, Object> modified = new java.util.LinkedHashMap<>(first);
        modified.put("publishStatusByTarget", Map.of("atlas", "PROJECTING"));

        // Round-tripping that through the SDK document is what the next read sees.
        Document persisted = new Document();
        persisted.setId((String) modified.remove("_id"));
        persisted.setRev((String) modified.remove("_rev"));
        modified.forEach(persisted::put);

        Map<String, Object> second =
                (Map<String, Object>) wrapperReturning(persisted).get(Map.class, DOC_ID, null);

        assertEquals(first.keySet(), second.keySet(),
                "a second read must see the same fields — no accumulating wrapper keys");
        assertEquals(Map.of("atlas", "PROJECTING"), second.get("publishStatusByTarget"));
    }

    /**
     * The read-modify-write boundary itself: what {@code update} actually PUTs must be the
     * document and nothing else. Asserting on the map alone leaves the write side unpinned —
     * this captures the request body the SDK is handed.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void updateWritesOnlyTheDocumentAndItsIdentity() throws Exception {
        Cloudant client = mock(Cloudant.class);
        ServiceCall<Document> getCall = mock(ServiceCall.class);
        Response<Document> getResponse = mock(Response.class);
        when(getResponse.getResult()).thenReturn(lineageEventDocument());
        when(getCall.execute()).thenReturn(getResponse);
        when(client.getDocument(any(GetDocumentOptions.class))).thenReturn(getCall);

        ServiceCall<com.ibm.cloud.cloudant.v1.model.DocumentResult> postCall = mock(ServiceCall.class);
        Response<com.ibm.cloud.cloudant.v1.model.DocumentResult> postResponse = mock(Response.class);
        com.ibm.cloud.cloudant.v1.model.DocumentResult docResult =
                mock(com.ibm.cloud.cloudant.v1.model.DocumentResult.class);
        when(docResult.getRev()).thenReturn("2-next");
        when(postResponse.getResult()).thenReturn(docResult);
        when(postCall.execute()).thenReturn(postResponse);
        org.mockito.ArgumentCaptor<com.ibm.cloud.cloudant.v1.model.PostDocumentOptions> captor =
                org.mockito.ArgumentCaptor.forClass(com.ibm.cloud.cloudant.v1.model.PostDocumentOptions.class);
        when(client.postDocument(captor.capture())).thenReturn(postCall);

        CloudantClientWrapper wrapper = new CloudantClientWrapper(client, DB, ObjectMapperFactory.createDefaultObjectMapper());

        Map<String, Object> doc = (Map<String, Object>) wrapper.get(Map.class, DOC_ID, null);
        doc.put("publishStatusByTarget", Map.of("atlas", "PROJECTING"));
        wrapper.update(doc);

        String written = new String(captor.getValue().body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> body = ObjectMapperFactory.createDefaultObjectMapper().readValue(written, Map.class);

        assertEquals(Set.of("_id", "_rev", "type", "eventKey", "publishStatusByTarget"), body.keySet(),
                "the request body must be the document, not the SDK's bean shape");
        assertEquals(DOC_ID, body.get("_id"));
        assertEquals(DOC_REV, body.get("_rev"));
        assertEquals(Map.of("atlas", "PROJECTING"), body.get("publishStatusByTarget"));
    }
}
