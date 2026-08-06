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
package jp.aegif.nemaki.rest.purview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.rest.purview.payload.PurviewEntityPayloadFactory;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * §6-a's live-Atlas release gates E-19 and E-20, executable.
 *
 * <p>These two cannot be settled by unit tests or by reasoning about the design, and the
 * design says so: a unit test inspects the payload this build SENDS, while E-19 asks what the
 * backend actually STORED, and E-20 asks a question about Atlas's own semantics —
 * "whether a null clears a previously published property is backend-dependent and cannot be
 * decided on paper".
 *
 * <p>So this IT drives the REAL {@link PurviewEntityPayloadFactory} against a REAL Atlas over
 * its REST API and reads the entity back:
 *
 * <ul>
 *   <li><b>E-19</b>: a document whose {@code nemaki:cloudFileUrl} carries a token — both
 *       shapes the design names, {@code ?authkey=} and the SharePoint path-token
 *       {@code /:x:/g/…} — is published, and the STORED entity is fetched and searched, in
 *       full, for the token text. Not "the attribute we expected is null": <em>no attribute,
 *       anywhere in the stored entity, may contain it</em>.</li>
 *   <li><b>E-20</b>: an entity is first created in the pre-A-1g shape (a raw token URL in
 *       {@code cloudFileUrl}), then republished by today's factory (which sends
 *       {@code cloudFileUrl: null}), and the stored value is read back. Whatever Atlas does
 *       is recorded as fact: if the value survives, the release procedure needs the explicit
 *       purge / entity-recreate runbook the design demands, and this test says so in its
 *       failure message rather than pretending the null was enough.</li>
 * </ul>
 *
 * <p>Enabled by {@code NEMAKI_ATLAS_IT_URL} (+ {@code NEMAKI_ATLAS_IT_USER} /
 * {@code NEMAKI_ATLAS_IT_PASSWORD}); locally, {@code http://localhost:21000} against the
 * docker-compose Atlas overlay. As with the CouchDB IT, {@code -Datlas.it.required=true}
 * turns a missing URL into a FAILURE so the gate cannot pass by being skipped.
 */
public class PurviewLiveAtlasSecretsIT {

    /** The two token shapes §10 names; both must be absent from anything Atlas stored. */
    private static final String QUERY_TOKEN = "AUTHKEYTOKEN" + "abcdef0123456789";
    private static final String PATH_TOKEN = "PATHTOKEN" + "0123456789abcdef";
    private static final String TOKEN_URL =
            "https://contoso.sharepoint.com/:x:/g/personal/user/" + PATH_TOKEN
                    + "?authkey=" + QUERY_TOKEN;

    private static final String DOCUMENT_TYPE = "nemaki_document";
    private static final ObjectMapper JSON = ObjectMapperFactory.createDefaultObjectMapper();

    private static String atlasUrl;
    private static String authorization;
    private static HttpClient http;

    @BeforeAll
    static void requireAtlas() {
        atlasUrl = System.getenv("NEMAKI_ATLAS_IT_URL");
        if (atlasUrl == null || atlasUrl.isBlank()) {
            if (Boolean.getBoolean("atlas.it.required")) {
                throw new IllegalStateException("atlas.it.required=true but"
                        + " NEMAKI_ATLAS_IT_URL is not set — E-19/E-20 must actually run");
            }
            org.junit.jupiter.api.Assumptions.abort(
                    "NEMAKI_ATLAS_IT_URL not set — live-Atlas gates skipped locally");
        }
        String user = System.getenv().getOrDefault("NEMAKI_ATLAS_IT_USER", "admin");
        String password = System.getenv().getOrDefault("NEMAKI_ATLAS_IT_PASSWORD", "admin");
        authorization = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        http = HttpClient.newHttpClient();
        installTypeDefinitionsIfAbsent();
    }

    /**
     * A FRESH Atlas has none of the nemaki types, so a gate that assumed them would fail for
     * the wrong reason (and would not be re-runnable in CI). Register them with the REAL
     * {@link jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory}, so the gate
     * also exercises the schema this build actually publishes.
     */
    private static void installTypeDefinitionsIfAbsent() {
        try {
            HttpRequest probe = HttpRequest.newBuilder(URI.create(atlasUrl
                    + "/api/atlas/v2/types/typedef/name/" + DOCUMENT_TYPE))
                    .header("Authorization", authorization).GET().build();
            HttpResponse<String> response = http.send(probe,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                return;
            }
            Map<String, Object> typedefs =
                    new jp.aegif.nemaki.rest.purview.payload.PurviewSchemaPayloadFactory()
                            .buildTypeDefinitionsPayload(
                                    new jp.aegif.nemaki.rest.purview.payload
                                            .PurviewSchemaManifest("it", "it",
                                            List.of(), List.of(), List.of()));
            post("/api/atlas/v2/types/typedefs", JSON.writeValueAsString(typedefs));
        } catch (Exception e) {
            throw new IllegalStateException("could not install the nemaki type definitions"
                    + " into Atlas — E-19/E-20 cannot run without them", e);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Document cloudDocument(String objectId, String cloudUrl) {
        Document document = new Document();
        document.setId(objectId);
        document.setName("e19-" + objectId + ".xlsx");
        document.setObjectType("cmis:document");
        document.setParentId("root");
        document.setCreator("admin");
        document.setModifier("admin");
        List<Property> properties = new ArrayList<>();
        properties.add(new Property("nemaki:cloudProvider", "microsoft"));
        properties.add(new Property("nemaki:cloudFileId", "01ABCDEF" + objectId));
        properties.add(new Property("nemaki:cloudFileUrl", cloudUrl));
        properties.add(new Property("nemaki:cloudLastSyncedAt", "2026-08-03T00:00:00Z"));
        document.setAspects(List.of(new Aspect("nemaki:cloudDriveMetadata", properties)));
        return document;
    }

    // ---------------------------------------------------------------- Atlas REST

    private static String post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(atlasUrl + path))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "Atlas POST " + path + " -> " + response.statusCode() + " " + response.body());
        return response.body();
    }

    /** The STORED entity, fetched by qualifiedName — the only thing E-19/E-20 may trust. */
    private static Map<String, Object> storedEntity(String qualifiedName) throws Exception {
        String path = "/api/atlas/v2/entity/uniqueAttribute/type/" + DOCUMENT_TYPE
                + "?attr:qualifiedName=" + java.net.URLEncoder.encode(qualifiedName,
                        StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(atlasUrl + path))
                .header("Authorization", authorization)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(),
                "Atlas GET " + path + " -> " + response.statusCode() + " " + response.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(response.body(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) body.get("entity");
        assertNotNull(entity, "Atlas returned no entity for " + qualifiedName);
        return entity;
    }

    private static void publish(Map<String, Object> entity) throws Exception {
        Map<String, Object> bulk = new LinkedHashMap<>();
        bulk.put("entities", List.of(entity));
        post("/api/atlas/v2/entity/bulk", JSON.writeValueAsString(bulk));
    }

    // ---------------------------------------------------------------- E-19

    /**
     * E-19: no token reaches the catalog. The assertion is deliberately on the ENTIRE stored
     * entity, serialized — an attribute we never thought of is exactly what this gate exists
     * to catch.
     */
    @Test
    public void e19_noTokenSurvivesIntoTheStoredEntity() throws Exception {
        String objectId = "e19-" + UUID.randomUUID().toString().replace("-", "");
        Document document = cloudDocument(objectId, TOKEN_URL);
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Map<String, Object> entity = factory.buildDocumentEntity("bedroom", document);

        // The payload itself must already be clean (the unit-level property, restated here so
        // a failure tells us WHICH side broke).
        String payloadJson = JSON.writeValueAsString(entity);
        assertFalse(payloadJson.contains(QUERY_TOKEN),
                "the published payload carried the query token: " + payloadJson);
        assertFalse(payloadJson.contains(PATH_TOKEN),
                "the published payload carried the path token: " + payloadJson);

        publish(entity);

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) entity.get("attributes");
        String qualifiedName = (String) attributes.get("qualifiedName");
        Map<String, Object> stored = storedEntity(qualifiedName);
        String storedJson = JSON.writeValueAsString(stored);
        assertFalse(storedJson.contains(QUERY_TOKEN),
                "E-19 FAILED: the entity Atlas stored contains the query token."
                        + " Stored entity: " + storedJson);
        assertFalse(storedJson.contains(PATH_TOKEN),
                "E-19 FAILED: the entity Atlas stored contains the path token."
                        + " Stored entity: " + storedJson);
    }

    // ---------------------------------------------------------------- E-20

    /**
     * E-20: does republishing with {@code cloudFileUrl: null} actually remove a value
     * published before A-1g existed? The design refuses to guess, and so does this test — it
     * records what Atlas does, and fails with the exact remediation the design requires if
     * the value survives.
     */
    @Test
    public void e20_republishRemovesAPreA1gRawUrl() throws Exception {
        String objectId = "e20-" + UUID.randomUUID().toString().replace("-", "");
        Document document = cloudDocument(objectId, TOKEN_URL);
        PurviewEntityPayloadFactory factory = new PurviewEntityPayloadFactory();
        Map<String, Object> current = factory.buildDocumentEntity("bedroom", document);
        @SuppressWarnings("unchecked")
        Map<String, Object> currentAttributes =
                (Map<String, Object>) current.get("attributes");
        String qualifiedName = (String) currentAttributes.get("qualifiedName");

        // 1. The pre-A-1g shape: the same entity, but with the raw token URL stored, exactly
        //    as builds before A-1g published it.
        Map<String, Object> legacyAttributes = new LinkedHashMap<>(currentAttributes);
        legacyAttributes.put("cloudFileUrl", TOKEN_URL);
        Map<String, Object> legacy = new LinkedHashMap<>(current);
        legacy.put("attributes", legacyAttributes);
        publish(legacy);

        Map<String, Object> beforeRepublish = storedEntity(qualifiedName);
        assertTrue(JSON.writeValueAsString(beforeRepublish).contains(PATH_TOKEN),
                "the legacy fixture did not actually store a raw URL, so E-20 would be"
                        + " vacuous: " + JSON.writeValueAsString(beforeRepublish));
        String guidBefore = (String) beforeRepublish.get("guid");
        assertNotNull(guidBefore);

        // 2. Republish with today's factory — cloudFileUrl is null by A-1g's rule.
        publish(current);

        Map<String, Object> afterRepublish = storedEntity(qualifiedName);
        // The claim under test is that the SAME entity was updated — a republish that quietly
        // created a new entity would "remove" the token only by abandoning the old one.
        assertEquals(guidBefore, afterRepublish.get("guid"),
                "E-20 must observe an UPDATE of the same entity, not a replacement");
        @SuppressWarnings("unchecked")
        Map<String, Object> afterAttributes =
                (Map<String, Object>) afterRepublish.get("attributes");
        assertEquals(null, afterAttributes.get("cloudFileUrl"),
                "the previously published cloudFileUrl must be cleared, not merely hidden");
        String storedJson = JSON.writeValueAsString(afterRepublish);
        assertFalse(storedJson.contains(QUERY_TOKEN) || storedJson.contains(PATH_TOKEN),
                "E-20 RESULT: republishing with cloudFileUrl=null did NOT remove the"
                        + " previously published raw URL from Atlas. Per §4 and the §6-a"
                        + " release gate, this is then the specification, and activation"
                        + " (Slice 4b) requires an explicit purge / entity-recreate runbook"
                        + " in the release procedure before it may proceed."
                        + " Stored entity: " + storedJson);
    }
}
