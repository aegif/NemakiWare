package jp.aegif.nemaki.rest.purview.journal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Apache Atlas implementation of {@link LineageTargetSink}.
 *
 * <p>Publishes lineage events to Atlas REST API v2 using the entity bulk endpoint.
 * Uses {@link SimpleHttpClient} with Basic Auth (no external dependencies).
 */
@Component
public class AtlasLineageSink implements LineageTargetSink {

    private static final Logger logger = LoggerFactory.getLogger(AtlasLineageSink.class);

    @Autowired
    private AtlasConfig atlasConfig;

    private SimpleHttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = new SimpleHttpClient(Duration.ofSeconds(30));
    }

    @PreDestroy
    void destroy() {
        if (httpClient != null) httpClient.close();
    }

    @Override
    public String targetName() {
        return "atlas";
    }

    @Override
    public LineageTargetSinkResult publish(LineageRecord record) throws Exception {
        if (!isAvailable()) {
            return LineageTargetSinkResult.failure("Atlas sink not available");
        }
        String unresolved = LineageSinkAssets.firstUnresolvedReason(record);
        if (unresolved != null) {
            return LineageTargetSinkResult.failure(unresolved);
        }

        SimpleHttpClient client = this.httpClient
                .withBasicAuth(atlasConfig.getUsername(), atlasConfig.getPassword());

        String endpoint = atlasConfig.getEndpoint().replaceAll("/+$", "");
        validateEndpoint(endpoint);
        String url = endpoint + "/api/atlas/v2/entity/bulk";

        // Build Atlas entity payload
        Map<String, Object> payload = buildAtlasPayload(record);
        HttpResponse<String> response = client.postJson(url, payload);

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            logger.debug("Published lineage record to Atlas: processIdentity={}, status={}",
                    record.processIdentity(), response.statusCode());
            return LineageTargetSinkResult.success(1, "Atlas OK");
        } else {
            String body = response.body();
            logger.warn("Atlas API returned {}: {}", response.statusCode(), body);
            return LineageTargetSinkResult.failure("Atlas API " + response.statusCode() + ": " + body);
        }
    }

    @Override
    public boolean isAvailable() {
        return atlasConfig.isEnabled()
                && atlasConfig.getEndpoint() != null
                && !atlasConfig.getEndpoint().isBlank();
    }

    Map<String, Object> buildAtlasPayload(LineageRecord record) {
        List<Map<String, Object>> entities = new ArrayList<>();

        // Process entity
        Map<String, Object> processEntity = new LinkedHashMap<>();
        processEntity.put("typeName", "Process");
        Map<String, Object> processAttrs = new LinkedHashMap<>();
        // processIdentity is the eventKey on a v1 record and the processKey on a v2 one, which is
        // what the design's §3 says the Process qualifiedName is built from in each case. The two
        // rules produce different names for one business operation, and §3 accepts that: the v1
        // Process stays as an audit fact and the v2 compensation gets its own.
        processAttrs.put("qualifiedName", "nemakiware:" + record.repositoryId() + ":" +
                (record.processType() != null ? record.processType().name().toLowerCase() : "unknown") +
                ":" + record.processIdentity());
        processAttrs.put("name", record.processType() != null ? record.processType().name() : "UNKNOWN");
        processAttrs.put("description", "NemakiWare lineage event: " + record.processIdentity());
        processEntity.put("attributes", processAttrs);

        // Input and output entities.
        //
        // These reference objects that the catalog sync has already published, and it publishes
        // them under the qualifiedName the event carries verbatim — "nemaki://{repo}/objects/{id}".
        // Prefixing it again produced "nemakiware:{repo}:nemaki://{repo}/objects/{id}", which
        // matches no entity in Atlas, so every Process was linked to nothing. The prefix belongs
        // to the Process's OWN qualifiedName (above), which has no other source; an input already
        // is a qualifiedName.
        processAttrs.put("inputs", entityReferences(record.inputs()));
        processAttrs.put("outputs", entityReferences(record.outputs()));

        entities.add(processEntity);

        return Map.of("entities", entities);
    }

    /**
     * References to already-published catalog entities, by their own qualifiedName.
     *
     * <p>A legacy (v1) reference has no kind, so it can only be referenced as {@code DataSet} —
     * which is what {@code nemaki_document} extends, and is why a v1 event whose input is a folder
     * links to nothing ({@code nemaki_folder} extends {@code Referenceable}). A typed reference
     * knows its own Atlas type and says so, which is how that stops being true after A-2.
     */
    private static List<Map<String, Object>> entityReferences(List<LineageAssetRef> refs) {
        List<Map<String, Object>> references = new ArrayList<>();
        for (LineageAssetRef ref : refs) {
            String typeName = switch (ref) {
                case LineageAssetRef.Typed typed -> typed.kind().atlasTypeName();
                case LineageAssetRef.LegacyName ignored -> "DataSet";
                // Unreachable: publish() returns before building a payload when any reference is
                // unresolved. Enumerated rather than defaulted so that a fourth case is a compile
                // error here instead of a silent DataSet.
                case LineageAssetRef.Unresolved unresolved -> throw new IllegalStateException(
                        "unresolved asset must not reach the payload: " + unresolved);
            };
            references.add(Map.of("typeName", typeName, "uniqueAttributes",
                    Map.of("qualifiedName", ref.qualifiedName())));
        }
        return references;
    }

    private static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IllegalArgumentException("Atlas endpoint must use http or https scheme: " + endpoint);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Atlas endpoint URL: " + endpoint, e);
        }
    }
}
