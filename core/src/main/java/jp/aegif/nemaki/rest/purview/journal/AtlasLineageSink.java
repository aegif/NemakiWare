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

    /**
     * Atlas can be read back, so it can verify.
     *
     * <p>Structural and immutable, as the contract requires: the entity API this uses is part of
     * the Atlas v2 API every supported version has. Answering false here left D-rest unable to
     * sequence a single v2 row for this target — a finalized v2 row is an ordered barrier, and a
     * barrier no sink can drain strands everything behind it.
     */
    @Override
    public boolean supportsVerification() {
        return true;
    }

    /**
     * Whether the Process this record produced is durably readable from Atlas.
     *
     * <h2>What each answer means, and what must not collapse into another</h2>
     *
     * <ul>
     *   <li>{@code VERIFIED} — the Process is there, under the qualified name this record's
     *       identity produces.</li>
     *   <li>{@code RETRYABLE} — not there yet, or the request failed. Atlas indexes
     *       asynchronously, so "not found" moments after a write is ordinary read lag; the
     *       caller retries inside its own budget. Calling it a mismatch would burn a record
     *       that was written correctly.</li>
     *   <li>{@code MISMATCH} — reserved for a deterministic semantic disagreement, which a
     *       read by unique attribute cannot produce: either the name is there or it is not.
     *       Never returned rather than returned speculatively.</li>
     * </ul>
     *
     * <p>The qualified name is rebuilt by the same expression the payload uses, so a verify can
     * only fail because the entity is absent — never because two pieces of code disagreed about
     * what to call it.
     */
    @Override
    public VerifyResult verify(LineageRecord record, Duration deadline) {
        if (!isAvailable() || record == null) {
            return VerifyResult.RETRYABLE;
        }
        String endpoint = atlasConfig.getEndpoint().replaceAll("/+$", "");
        try {
            validateEndpoint(endpoint);
        } catch (RuntimeException badEndpoint) {
            return VerifyResult.RETRYABLE;
        }
        String qualifiedName = processQualifiedName(record);
        String url = endpoint + "/api/atlas/v2/entity/uniqueAttribute/type/Process?attr:"
                + "qualifiedName=" + java.net.URLEncoder.encode(qualifiedName,
                        java.nio.charset.StandardCharsets.UTF_8);
        try {
            HttpResponse<String> response = this.httpClient
                    .withBasicAuth(atlasConfig.getUsername(), atlasConfig.getPassword())
                    .getJson(url);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return VerifyResult.VERIFIED;
            }
            if (status == 404) {
                // Atlas indexes asynchronously; absent now is not absent for ever.
                return VerifyResult.RETRYABLE;
            }
            // Any other status is the server having trouble, not the record being wrong.
            return VerifyResult.RETRYABLE;
        } catch (Exception e) {
            // Class name only: an Atlas error body echoes the qualified name.
            logger.debug("Atlas verify could not answer: {}", e.getClass().getSimpleName());
            return VerifyResult.RETRYABLE;
        }
    }

    /**
     * The Process qualified name for a record.
     *
     * <p>One expression, used by both the payload and the verify, so the two cannot drift into
     * disagreeing about what was written.
     */
    static String processQualifiedName(LineageRecord record) {
        return "nemakiware:" + record.repositoryId() + ":"
                + (record.processType() != null ? record.processType().name().toLowerCase()
                        : "unknown")
                + ":" + record.processIdentity();
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
        processAttrs.put("qualifiedName", processQualifiedName(record));
        processAttrs.put("name", record.processType() != null ? record.processType().name() : "UNKNOWN");
        processAttrs.put("description", "NemakiWare lineage event: " + record.processIdentity());

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

        // Sealed LAST: the references carry an external asset's qualifiedName, which is exactly
        // where a stored sharing link would ride. Sealing before the puts covered only the three
        // constants above and let the one caller-bearing value through unchecked.
        processEntity.put("attributes",
                jp.aegif.nemaki.rest.purview.payload.CatalogSecretBoundary.sealed(processAttrs));

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
