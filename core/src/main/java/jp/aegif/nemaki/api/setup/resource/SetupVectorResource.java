package jp.aegif.nemaki.api.setup.resource;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.filter.UrlValidator;
import jp.aegif.nemaki.api.setup.model.VectorTestRequest;
import jp.aegif.nemaki.util.PropertyManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Vector search setup endpoints: state, test-connection, apply.
 */
@Component
@Path("/vector")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupVectorResource {

    private static final Logger logger = Logger.getLogger(SetupVectorResource.class.getName());

    @Autowired(required = false)
    private PropertyManager propertyManager;

    private final ObjectMapper stateMapper = ObjectMapperFactory.createDefaultObjectMapper();

    /**
     * GET /vector/state -- current vector search configuration.
     */
    @GET
    @Path("/state")
    public Response getState() {
        String type = "none";
        String url = "";
        String region = "";
        String modelId = "";
        if (propertyManager != null) {
            String ragEnabled = propertyManager.readValue("rag.enabled");
            if ("true".equals(ragEnabled)) {
                String provider = propertyManager.readValue("rag.embedding.provider");
                type = "bedrock".equals(provider) ? "bedrock" : "tei";
            }
            String configuredUrl = propertyManager.readValue("rag.tei.url");
            if (configuredUrl != null) {
                url = configuredUrl;
            }
            String configuredRegion = propertyManager.readValue("rag.bedrock.region");
            if (configuredRegion != null) {
                region = configuredRegion;
            }
            String configuredModelId = propertyManager.readValue("rag.bedrock.model.id");
            if (configuredModelId != null) {
                modelId = configuredModelId;
            }
        }
        try {
            ObjectNode result = stateMapper.createObjectNode();
            result.put("type", type);
            result.put("url", url);
            result.put("region", region);
            result.put("modelId", modelId);
            return Response.ok(stateMapper.writeValueAsString(result)).build();
        } catch (Exception e) {
            return Response.serverError().entity("{\"error\":\"Failed to serialize state\"}").build();
        }
    }

    /**
     * POST /vector/test-connection -- test TEI or Bedrock connection.
     */
    @POST
    @Path("/test-connection")
    public Response testConnection(VectorTestRequest req) {
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"request body is required\"}")
                    .build();
        }

        if ("bedrock".equals(req.getType())) {
            return testBedrockConnection(req);
        }

        // TEI test
        if (req.getUrl() == null || req.getUrl().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"url is required\"}")
                    .build();
        }

        String urlError = UrlValidator.validate(req.getUrl(), true);
        if (urlError != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + CouchDbConfigWriter.escapeJson(urlError) + "\"}")
                    .build();
        }

        try {
            java.net.URL url = new java.net.URL(req.getUrl());
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            conn.disconnect();

            boolean reachable = (code >= 200 && code < 400);
            return Response.ok("{\"reachable\":" + reachable + "}").build();
        } catch (Exception e) {
            return Response.ok("{\"reachable\":false,\"error\":\"Connection failed\"}").build();
        }
    }

    /**
     * Test Bedrock embedding by invoking a lightweight embed call.
     */
    private Response testBedrockConnection(VectorTestRequest req) {
        if (req.getRegion() == null || req.getRegion().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"region is required for Bedrock\"}")
                    .build();
        }

        String modelId = req.getModelId();
        if (modelId == null || modelId.trim().isEmpty()) {
            modelId = "amazon.titan-embed-text-v2:0";
        }

        BedrockRuntimeClient client = null;
        try {
            BedrockRuntimeClientBuilder builder = BedrockRuntimeClient.builder()
                    .region(Region.of(req.getRegion().trim()))
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))
                            .build());

            // Use explicit credentials if provided, otherwise fall back to default chain
            if (req.getAccessKeyId() != null && !req.getAccessKeyId().trim().isEmpty()
                    && req.getSecretAccessKey() != null && !req.getSecretAccessKey().trim().isEmpty()) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(req.getAccessKeyId().trim(), req.getSecretAccessKey().trim())));
            }

            client = builder.build();

            ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("inputText", "test");

            InvokeModelRequest invokeReq = InvokeModelRequest.builder()
                    .modelId(modelId.trim())
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(mapper.writeValueAsString(requestBody)))
                    .build();

            InvokeModelResponse response = client.invokeModel(invokeReq);
            String responseBody = response.body().asUtf8String();

            JsonNode root = mapper.readTree(responseBody);
            JsonNode embeddingNode = root.get("embedding");
            int dimension = (embeddingNode != null && embeddingNode.isArray()) ? embeddingNode.size() : 0;

            if (dimension == 0) {
                // Model responded but did not return an embedding array — not an embedding model
                return Response.ok("{\"reachable\":false,\"error\":\"Model did not return an embedding array. "
                        + "Ensure the model ID refers to an embedding model (e.g. amazon.titan-embed-text-v2:0).\"}").build();
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("reachable", true);
            result.put("dimension", dimension);
            if (dimension != 1024) {
                result.put("dimensionWarning",
                        "Model returned " + dimension + "-dimensional vectors. "
                        + "The Solr schema expects 1024 dimensions (knn_vector_1024). "
                        + "Indexing may fail unless the schema is updated.");
            }
            return Response.ok(mapper.writeValueAsString(result)).build();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Bedrock test connection failed", e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Connection failed";
            return Response.ok("{\"reachable\":false,\"error\":\"" + CouchDbConfigWriter.escapeJson(errorMsg) + "\"}").build();
        } finally {
            if (client != null) {
                try { client.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * POST /vector/apply -- persist vector search settings to nemaki_conf.
     */
    @POST
    @Path("/apply")
    public Response apply(VectorTestRequest req) {
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"request body is required\"}")
                    .build();
        }

        // Keys match PropertyManager / StartupProbeService lookup keys.
        String couchUrl = System.getProperty("db.couchdb.url", "http://couchdb:5984");
        String couchUser = System.getProperty("db.couchdb.auth.username", "admin");
        String couchPass = System.getProperty("db.couchdb.auth.password", "password");
        String authHeader = CouchDbConfigWriter.basicAuth(couchUser, couchPass);

        // Validate required fields before persisting (mirrors test-connection and UI gates)
        if ("tei".equals(req.getType())) {
            if (req.getUrl() == null || req.getUrl().trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"url is required for TEI provider\"}")
                        .build();
            }
            String vectorUrlError = UrlValidator.validate(req.getUrl(), true);
            if (vectorUrlError != null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + CouchDbConfigWriter.escapeJson(vectorUrlError) + "\"}")
                        .build();
            }
        }
        if ("bedrock".equals(req.getType())) {
            if (req.getRegion() == null || req.getRegion().trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"region is required for Bedrock provider\"}")
                        .build();
            }
        }

        try {
            boolean ragEnabled = "tei".equals(req.getType()) || "bedrock".equals(req.getType());
            CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.enabled", String.valueOf(ragEnabled));
            System.setProperty("rag.enabled", String.valueOf(ragEnabled));

            if (ragEnabled) {
                String provider = "bedrock".equals(req.getType()) ? "bedrock" : "tei";
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.embedding.provider", provider);
                System.setProperty("rag.embedding.provider", provider);
            }

            // TEI settings
            if (req.getUrl() != null && !req.getUrl().isEmpty()) {
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.tei.url", req.getUrl());
                System.setProperty("rag.tei.url", req.getUrl());
            }

            // When switching away from Bedrock, clear residual Bedrock credentials
            // from CouchDB and rag.bedrock.* system properties.
            // Note: we deliberately do NOT touch aws.accessKeyId / aws.secretAccessKey
            // because those are JVM-global and may be set by the operator for other
            // AWS integrations (e.g. S3StorageAdapter). BedrockEmbeddingService reads
            // rag.bedrock.* directly via RAGConfig, so aws.* is never needed here.
            if (!"bedrock".equals(req.getType())) {
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.access.key.id", "");
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.secret.access.key", "");
                System.clearProperty("rag.bedrock.access.key.id");
                System.clearProperty("rag.bedrock.secret.access.key");
            }

            // Bedrock settings
            if ("bedrock".equals(req.getType())) {
                if (req.getRegion() != null && !req.getRegion().isEmpty()) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.region", req.getRegion());
                    System.setProperty("rag.bedrock.region", req.getRegion());
                }
                String modelId = req.getModelId();
                if (modelId == null || modelId.isEmpty()) {
                    modelId = "amazon.titan-embed-text-v2:0";
                }
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.model.id", modelId);
                System.setProperty("rag.bedrock.model.id", modelId);

                // Always persist credential values (even empty) so that clearing
                // the fields in the UI reverts to IAM role / default credential chain.
                String accessKeyId = req.getAccessKeyId() != null ? req.getAccessKeyId().trim() : "";
                String secretAccessKey = req.getSecretAccessKey() != null ? req.getSecretAccessKey().trim() : "";

                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.access.key.id", accessKeyId);
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.secret.access.key", secretAccessKey);

                if (!accessKeyId.isEmpty()) {
                    System.setProperty("rag.bedrock.access.key.id", accessKeyId);
                } else {
                    System.clearProperty("rag.bedrock.access.key.id");
                }
                if (!secretAccessKey.isEmpty()) {
                    System.setProperty("rag.bedrock.secret.access.key", secretAccessKey);
                } else {
                    System.clearProperty("rag.bedrock.secret.access.key");
                }
            }

            return Response.ok("{\"success\":true}").build();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to persist vector config", e);
            return Response.serverError()
                    .entity("{\"error\":\"Failed to persist vector configuration\"}")
                    .build();
        }
    }
}
