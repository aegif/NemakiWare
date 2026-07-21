package jp.aegif.nemaki.odata;

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
    public void olingoClientReadsSingleEntityAndQueryOptions() {
        ODataClient client = ODataClientFactory.getClient();

        // $count + $top through the client URI builder.
        URI listUri = client.newURIBuilder(serviceRootUrl())
                .appendEntitySetSegment("Documents")
                .top(1)
                .count(true)
                .build();
        ODataEntitySetRequest<ClientEntitySet> listReq =
                client.getRetrieveRequestFactory().getEntitySetRequest(listUri);
        listReq.setAccept(ContentType.JSON.toContentTypeString());
        auth(listReq);
        ClientEntitySet set = listReq.execute().getBody();
        assertNotNull(set, "collection with $top/$count must deserialize");

        if (set.getEntities().isEmpty()) {
            return; // no documents to address; nothing further to validate
        }

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
}
