package jp.aegif.nemaki.rest.purview.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import jp.aegif.nemaki.rest.purview.atlas.AtlasDirectClient.AtlasResponse;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifest;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaManifestFactory;
import jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory;

/**
 * Verifies that the NemakiWare schema payload (entityDefs, relationshipDefs,
 * businessMetadataDefs) is accepted by a real Apache Atlas 2.3 instance.
 */
@Tag("atlas-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
public class AtlasSchemaCompatibilityTest {

    private static AtlasDirectClient client;
    private static Map<String, Object> schemaPayload;

    @BeforeAll
    static void setUp() {
        client = new AtlasDirectClient(
                AtlasContainer.getEndpoint(),
                AtlasContainer.getBasicAuthHeader());

        PurviewSchemaManifestFactory manifestFactory = new PurviewSchemaManifestFactory();
        PurviewSchemaPayloadFactory payloadFactory = new PurviewSchemaPayloadFactory();
        PurviewSchemaManifest manifest = manifestFactory.buildManifest();
        schemaPayload = payloadFactory.buildTypeDefinitionsPayload(manifest);
    }

    @Test
    @Order(1)
    void probeConnectionReturns200() throws Exception {
        int status = client.probeConnection();
        assertEquals(200, status, "Atlas health endpoint should return 200");
    }

    @Test
    @Order(2)
    void applyNemakiSchemaAccepted() throws Exception {
        AtlasResponse response = AtlasTestHelper.applySchemaWithFallback(client, schemaPayload);

        assertTrue(response.isSuccess() || response.statusCode() == 409,
                "Schema payload should be accepted by Atlas. Status: " + response.statusCode()
                        + ", Body: " + response.body());
    }

    @Test
    @Order(3)
    void applyNemakiSchemaIdempotent() throws Exception {
        // Second apply of the same schema should also succeed (idempotent PUT semantics)
        // Atlas may return 409 for types that already exist, which is acceptable
        AtlasResponse response = AtlasTestHelper.applySchemaWithFallback(client, schemaPayload);

        assertTrue(response.isSuccess() || response.statusCode() == 409,
                "Idempotent schema apply should succeed. Status: " + response.statusCode()
                        + ", Body: " + response.body());
    }
}
