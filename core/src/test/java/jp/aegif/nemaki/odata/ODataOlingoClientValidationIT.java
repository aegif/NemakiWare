package jp.aegif.nemaki.odata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.apache.olingo.client.api.ODataClient;
import org.apache.olingo.client.api.communication.request.retrieve.EdmMetadataRequest;
import org.apache.olingo.client.api.communication.request.retrieve.ODataEntityRequest;
import org.apache.olingo.client.api.communication.request.retrieve.ODataEntitySetRequest;
import org.apache.olingo.client.api.communication.request.retrieve.ODataServiceDocumentRequest;
import org.apache.olingo.client.api.domain.ClientEntity;
import org.apache.olingo.client.api.domain.ClientEntitySet;
import org.apache.olingo.client.api.domain.ClientServiceDocument;
import org.apache.olingo.client.core.ODataClientFactory;
import org.apache.olingo.commons.api.edm.Edm;
import org.apache.olingo.commons.api.edm.EdmEntityContainer;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.format.ContentType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * OData conformance validation driven by the Apache Olingo <em>client</em>
 * library (the Java OData 4.0 reference implementation). Instead of asserting on
 * raw HTTP shapes, these tests make Olingo's own client parse our service:
 *
 * <ul>
 *   <li>{@code $metadata} must parse into a valid {@link Edm} (entity container,
 *       entity sets, entity types, function imports resolvable).</li>
 *   <li>the service document must list the entity sets;</li>
 *   <li>an entity collection and a single entity must deserialize into the
 *       client domain objects.</li>
 * </ul>
 *
 * If Olingo — the reference consumer — can consume the service end to end, the
 * emitted CSDL/payloads are OData-conformant at the level Olingo enforces.
 *
 * Run against a live instance (same switches as the other *IT here):
 *   mvn test -Dtest=ODataOlingoClientValidationIT \
 *     -Dnemaki.test.baseUrl=http://localhost:8080/core \
 *     -Djunit.jupiter.conditions.deactivate='*'
 */
@Disabled("Requires a running NemakiWare instance - deactivate JUnit conditions to run")
public class ODataOlingoClientValidationIT extends ODataTestBase {

    private String serviceRootUrl() {
        return baseUrl + ODATA_PATH + "/" + repositoryId;
    }

    private void auth(org.apache.olingo.client.api.communication.request.ODataRequest req) {
        req.addCustomHeader("Authorization", createBasicAuthHeader(username, password));
    }

    @Test
    public void olingoClientParsesMetadataIntoValidEdm() {
        ODataClient client = ODataClientFactory.getClient();
        EdmMetadataRequest req = client.getRetrieveRequestFactory().getMetadataRequest(serviceRootUrl());
        auth(req);
        Edm edm = req.execute().getBody();

        assertNotNull(edm, "Olingo client must parse $metadata into an Edm");

        EdmEntityContainer container = edm.getEntityContainer();
        assertNotNull(container, "EDM must expose a default entity container");

        // Entity sets are resolvable through the container.
        for (String es : new String[] { "Objects", "Documents", "Folders",
                "Relationships", "Policies", "Items", "Types", "Users", "Groups" }) {
            EdmEntitySet set = container.getEntitySet(es);
            assertNotNull(set, "entity set must be resolvable: " + es);
            assertNotNull(set.getEntityType(), "entity set must resolve its type: " + es);
        }

        // Entity types (incl. inheritance Document/Folder -> Object) resolve.
        FullQualifiedName docFqn = new FullQualifiedName("NemakiWare.CMIS", "Document");
        EdmEntityType docType = edm.getEntityType(docFqn);
        assertNotNull(docType, "Document entity type must resolve");
        assertNotNull(docType.getBaseType(), "Document must inherit from Object");
        assertFalse(docType.getKeyPredicateNames().isEmpty(), "Document must expose a key");

        // Unbound function imports are declared in the container.
        assertNotNull(container.getFunctionImport("Query"), "Query function import must be declared");
        assertNotNull(container.getFunctionImport("GetObjectByPath"), "GetObjectByPath function import must be declared");
    }

    @Test
    public void olingoClientReadsServiceDocument() {
        ODataClient client = ODataClientFactory.getClient();
        ODataServiceDocumentRequest req =
                client.getRetrieveRequestFactory().getServiceDocumentRequest(serviceRootUrl());
        req.setAccept(ContentType.JSON.toContentTypeString());
        auth(req);
        ClientServiceDocument doc = req.execute().getBody();

        assertNotNull(doc, "service document must deserialize");
        assertTrue(doc.getEntitySetNames().contains("Documents"),
                "service document must advertise the Documents entity set");
        assertTrue(doc.getEntitySetNames().contains("Folders"),
                "service document must advertise the Folders entity set");
    }

    @Test
    public void olingoClientReadsEntityCollection() {
        ODataClient client = ODataClientFactory.getClient();
        URI uri = client.newURIBuilder(serviceRootUrl())
                .appendEntitySetSegment("Documents")
                .build();
        ODataEntitySetRequest<ClientEntitySet> req =
                client.getRetrieveRequestFactory().getEntitySetRequest(uri);
        req.setAccept(ContentType.JSON.toContentTypeString());
        auth(req);
        ClientEntitySet set = req.execute().getBody();

        assertNotNull(set, "Documents collection must deserialize into a ClientEntitySet");
        // Entities (if any) must expose the key property so the client can address them.
        if (!set.getEntities().isEmpty()) {
            ClientEntity first = set.getEntities().get(0);
            assertNotNull(first.getProperty("objectId"),
                    "each entity must carry its key property objectId");
        }
    }

    @Test
    public void olingoClientReadsSingleEntity() {
        ODataClient client = ODataClientFactory.getClient();
        ClientEntitySet set = readSet(client, -1, -1, false);
        assertNotNull(set, "Documents collection must deserialize");
        assertFalse(set.getEntities().isEmpty(),
                "the test repository must contain at least one document");

        String objectId = set.getEntities().get(0).getProperty("objectId")
                .getPrimitiveValue().toString();
        URI entityUri = client.newURIBuilder(serviceRootUrl())
                .appendEntitySetSegment("Documents")
                .appendKeySegment(objectId)
                .build();
        ODataEntityRequest<ClientEntity> entityReq =
                client.getRetrieveRequestFactory().getEntityRequest(entityUri);
        entityReq.setAccept(ContentType.JSON.toContentTypeString());
        auth(entityReq);
        ClientEntity entity = entityReq.execute().getBody();

        assertNotNull(entity, "single entity must deserialize");
        assertNotNull(entity.getProperty("objectId"), "entity must carry objectId");
    }

    /**
     * Regression guard for the "page in Solr, then ACL-filter" defect where
     * {@code /Documents?$count=true&$top=1} returned count=0 with an empty page.
     * $count MUST be the authorized total (not the current page's survivor
     * count) and $top/$skip MUST return a full authorized page. An earlier
     * version of this test early-returned on an empty page and so passed on the
     * broken behavior — do NOT reintroduce that.
     */
    @Test
    public void olingoClientPagingReportsAuthorizedTotalAndFullPage() {
        ODataClient client = ODataClientFactory.getClient();

        ClientEntitySet all = readSet(client, -1, -1, true);
        assertNotNull(all.getCount(), "@odata.count must be present with $count=true");
        int total = all.getCount();
        assertEquals(all.getEntities().size(), total,
                "unpaged $count must equal the number of returned entities");
        org.junit.jupiter.api.Assumptions.assumeTrue(total >= 2,
                "need >= 2 documents to test paging; repository has " + total);

        ClientEntitySet top1 = readSet(client, 1, -1, true);
        assertEquals(total, top1.getCount().intValue(),
                "$count must be the authorized total, not the current page size");
        assertEquals(1, top1.getEntities().size(),
                "$top=1 must return a full authorized page (1), not an empty page");

        ClientEntitySet skip1 = readSet(client, 1, 1, true);
        assertEquals(total, skip1.getCount().intValue(),
                "$count must stay the authorized total under $skip");
        assertEquals(1, skip1.getEntities().size(),
                "$skip=1&$top=1 must return exactly one authorized item");
    }

    /**
     * Regression guard for ORDER BY + paging. The pre-fix query path sliced the
     * page in Solr's native order and then sorted only that page, so with a page
     * size of 1 the sort was a no-op and page N disagreed with the unpaged order;
     * $orderby applied to a single-item page was effectively ignored. This test
     * proves (a) $orderby is actually applied (desc is the exact reverse of asc,
     * collation-independent) and (b) ordered $top=1 paging reproduces the unpaged
     * order.
     */
    @Test
    public void olingoClientOrderByIsAppliedAndOrderedPagingMatches() {
        ODataClient client = ODataClientFactory.getClient();

        java.util.List<String> asc = names(readSetOrdered(client, "name asc", -1, -1, false));
        java.util.List<String> desc = names(readSetOrdered(client, "name desc", -1, -1, false));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                asc.size() >= 2 && new java.util.HashSet<>(asc).size() == asc.size(),
                "need >= 2 distinct document names to test $orderby; have " + asc.size());

        java.util.List<String> ascReversed = new java.util.ArrayList<>(asc);
        java.util.Collections.reverse(ascReversed);
        assertEquals(ascReversed, desc,
                "$orderby=name desc must be the exact reverse of asc (proves $orderby is applied, "
                        + "not silently dropped to the default order)");

        // Slice one item per page and concatenate; it must equal the unpaged order.
        java.util.List<String> pagedAsc = new java.util.ArrayList<>();
        for (int s = 0; s < asc.size(); s++) {
            java.util.List<String> page = names(readSetOrdered(client, "name asc", 1, s, false));
            assertEquals(1, page.size(),
                    "$orderby + $top=1 must return a full authorized page at skip=" + s);
            pagedAsc.add(page.get(0));
        }
        assertEquals(asc, pagedAsc,
                "ordered $top=1 paging must reproduce the unpaged $orderby order "
                        + "(page must be sliced AFTER the full authorized set is sorted)");
    }

    /** Collect the {@code name} property of every entity in the set, in order. */
    private java.util.List<String> names(ClientEntitySet set) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ClientEntity e : set.getEntities()) {
            out.add(e.getProperty("name").getPrimitiveValue().toString());
        }
        return out;
    }

    /** Read the Documents entity set with an $orderby plus optional $top/$skip/$count. */
    private ClientEntitySet readSetOrdered(ODataClient client, String orderBy, int top, int skip, boolean count) {
        org.apache.olingo.client.api.uri.URIBuilder b = client.newURIBuilder(serviceRootUrl())
                .appendEntitySetSegment("Documents");
        if (count) {
            b = b.count(true);
        }
        if (orderBy != null) {
            b = b.orderBy(orderBy);
        }
        if (top >= 0) {
            b = b.top(top);
        }
        if (skip >= 0) {
            b = b.skip(skip);
        }
        ODataEntitySetRequest<ClientEntitySet> req =
                client.getRetrieveRequestFactory().getEntitySetRequest(b.build());
        req.setAccept(ContentType.JSON.toContentTypeString());
        auth(req);
        return req.execute().getBody();
    }

    /** Read the Documents entity set with optional $top / $skip / $count. */
    private ClientEntitySet readSet(ODataClient client, int top, int skip, boolean count) {
        org.apache.olingo.client.api.uri.URIBuilder b = client.newURIBuilder(serviceRootUrl())
                .appendEntitySetSegment("Documents");
        if (count) {
            b = b.count(true);
        }
        if (top >= 0) {
            b = b.top(top);
        }
        if (skip >= 0) {
            b = b.skip(skip);
        }
        ODataEntitySetRequest<ClientEntitySet> req =
                client.getRetrieveRequestFactory().getEntitySetRequest(b.build());
        req.setAccept(ContentType.JSON.toContentTypeString());
        auth(req);
        return req.execute().getBody();
    }
}
