package jp.aegif.nemaki.odata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.RestAssured;
import io.restassured.response.Response;

import java.net.URI;
import java.util.List;

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
import org.junit.jupiter.api.BeforeAll;
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

    // A dedicated, distinct-by-construction seed set. The paging / $orderby
    // regression tests operate ONLY on documents whose name starts with this
    // prefix, so they are deterministic regardless of whatever else lives in the
    // repository — in particular they do NOT skip when unrelated documents happen
    // to share a name (CMIS allows same names in different folders). Assertions,
    // not assumptions, so the CI gate cannot pass by silently skipping.
    private static final String SEED_PREFIX = "odata-ci-seed-";
    private static final String[] SEED_NAMES = {
            SEED_PREFIX + "a.txt", SEED_PREFIX + "b.txt", SEED_PREFIX + "c.txt" };

    /**
     * Ensure the distinct seed documents exist and are queryable before the
     * regression tests run. Idempotent (409 = already exists is fine), so it
     * co-operates with the CI seed script (scripts/ci-seed-odata-docs.sh) and also
     * makes a bare local run self-sufficient. Uses the CMIS Browser Binding, which
     * accepts a header-less POST (no Origin / Sec-Fetch-Site) under the new CSRF
     * policy.
     */
    @BeforeAll
    public static void seedDistinctDocuments() {
        String browser = baseUrl + "/browser/" + repositoryId;
        String authHeader = createBasicAuthHeader(username, password);

        String rootId = RestAssured.given().header("Authorization", authHeader)
                .get(browser + "?cmisselector=repositoryInfo")
                .jsonPath().getString("'" + repositoryId + "'.rootFolderId");
        if (rootId == null || rootId.isEmpty()) {
            // Cannot seed — leave it to the assertions in the tests to fail clearly.
            return;
        }

        for (String name : SEED_NAMES) {
            RestAssured.given().header("Authorization", authHeader)
                    .multiPart("cmisaction", "createDocument")
                    .multiPart("folderId", rootId)
                    .multiPart("propertyId[0]", "cmis:objectTypeId")
                    .multiPart("propertyValue[0]", "cmis:document")
                    .multiPart("propertyId[1]", "cmis:name")
                    .multiPart("propertyValue[1]", name)
                    .post(browser); // 201 (created) or 409 (already exists) — both fine
        }

        // Wait for Solr to index the seeds (the OData /Documents collection reads
        // through the Solr query path, which indexes asynchronously).
        for (int i = 0; i < 40; i++) {
            Response r = RestAssured.given().header("Authorization", authHeader)
                    .queryParam("cmisselector", "query")
                    .queryParam("q", "SELECT cmis:name FROM cmis:document WHERE cmis:name LIKE '"
                            + SEED_PREFIX + "%'")
                    .queryParam("maxItems", "100")
                    .get(browser);
            List<String> names = r.jsonPath().getList("results.properties.'cmis:name'.value");
            if (names != null && names.size() >= SEED_NAMES.length) {
                return;
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
        ClientEntitySet set = readSet(client, null, -1, -1, false);
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

        // Operate on the dedicated seed set only, so the assertions below are hard
        // (never skipped) and deterministic regardless of other repository content.
        ClientEntitySet all = readSet(client, SEED_FILTER, -1, -1, true);
        assertNotNull(all.getCount(), "@odata.count must be present with $count=true");
        int total = all.getCount();
        assertEquals(all.getEntities().size(), total,
                "unpaged $count must equal the number of returned entities");
        assertTrue(total >= SEED_NAMES.length,
                "the " + SEED_NAMES.length + " seed documents must be present and queryable; "
                        + "count=" + total + " (did scripts/ci-seed-odata-docs.sh / @BeforeAll seed run?)");

        ClientEntitySet top1 = readSet(client, SEED_FILTER, 1, -1, true);
        assertEquals(total, top1.getCount().intValue(),
                "$count must be the authorized total, not the current page size");
        assertEquals(1, top1.getEntities().size(),
                "$top=1 must return a full authorized page (1), not an empty page");

        ClientEntitySet skip1 = readSet(client, SEED_FILTER, 1, 1, true);
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
     * order. It runs over the dedicated seed set (distinct by construction), so it
     * asserts — it never skips on unrelated same-named documents.
     */
    @Test
    public void olingoClientOrderByIsAppliedAndOrderedPagingMatches() {
        ODataClient client = ODataClientFactory.getClient();

        List<String> asc = names(readSetOrdered(client, SEED_FILTER, "name asc", -1, -1, false));
        List<String> desc = names(readSetOrdered(client, SEED_FILTER, "name desc", -1, -1, false));

        assertTrue(asc.size() >= SEED_NAMES.length,
                "the " + SEED_NAMES.length + " distinct seed documents must be present; have " + asc.size());
        assertEquals(asc.size(), new java.util.HashSet<>(asc).size(),
                "seed document names must be distinct: " + asc);

        java.util.List<String> ascReversed = new java.util.ArrayList<>(asc);
        java.util.Collections.reverse(ascReversed);
        assertEquals(ascReversed, desc,
                "$orderby=name desc must be the exact reverse of asc (proves $orderby is applied, "
                        + "not silently dropped to the default order)");

        // Slice one item per page and concatenate; it must equal the unpaged order.
        java.util.List<String> pagedAsc = new java.util.ArrayList<>();
        for (int s = 0; s < asc.size(); s++) {
            List<String> page = names(readSetOrdered(client, SEED_FILTER, "name asc", 1, s, false));
            assertEquals(1, page.size(),
                    "$orderby + $top=1 must return a full authorized page at skip=" + s);
            pagedAsc.add(page.get(0));
        }
        assertEquals(asc, pagedAsc,
                "ordered $top=1 paging must reproduce the unpaged $orderby order "
                        + "(page must be sliced AFTER the full authorized set is sorted)");
    }

    /** OData $filter that restricts a read to the dedicated seed set. */
    private static final String SEED_FILTER = "startswith(name,'" + SEED_PREFIX + "')";

    /** Collect the {@code name} property of every entity in the set, in order. */
    private List<String> names(ClientEntitySet set) {
        List<String> out = new java.util.ArrayList<>();
        for (ClientEntity e : set.getEntities()) {
            out.add(e.getProperty("name").getPrimitiveValue().toString());
        }
        return out;
    }

    /** Read the Documents entity set with an optional $filter/$orderby/$top/$skip/$count. */
    private ClientEntitySet readSetOrdered(ODataClient client, String filter, String orderBy,
            int top, int skip, boolean count) {
        org.apache.olingo.client.api.uri.URIBuilder b = client.newURIBuilder(serviceRootUrl())
                .appendEntitySetSegment("Documents");
        if (filter != null) {
            b = b.filter(filter);
        }
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

    /** Read the Documents entity set with an optional $filter plus $top/$skip/$count. */
    private ClientEntitySet readSet(ODataClient client, String filter, int top, int skip, boolean count) {
        return readSetOrdered(client, filter, null, top, skip, count);
    }
}
