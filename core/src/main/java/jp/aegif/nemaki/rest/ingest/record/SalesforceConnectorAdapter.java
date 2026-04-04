package jp.aegif.nemaki.rest.ingest.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Salesforce REST API connector adapter — fetches records and attachments.
 *
 * <p>Uses Salesforce REST API v59.0 with OAuth2 Bearer token.
 */
public class SalesforceConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SalesforceConnectorAdapter.class);
    private static final String API_VERSION = "v59.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String instanceUrl;
    private final String accessToken;
    private final HttpClient httpClient;

    /**
     * @param instanceUrl Salesforce instance URL (e.g. https://myorg.salesforce.com)
     * @param accessToken OAuth2 access token
     */
    public SalesforceConnectorAdapter(String instanceUrl, String accessToken) {
        this.instanceUrl = instanceUrl.replaceAll("/+$", "");
        this.accessToken = accessToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record SalesforceRecord(String id, String type, String name, Map<String, Object> fields) {}

    /**
     * Query records using SOQL.
     */
    public List<SalesforceRecord> query(String soql) throws Exception {
        String url = instanceUrl + "/services/data/" + API_VERSION + "/query?q=" +
                java.net.URLEncoder.encode(soql, java.nio.charset.StandardCharsets.UTF_8);

        HttpResponse<String> response = get(url);
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode records = root.get("records");
        if (records == null || !records.isArray()) return List.of();

        List<SalesforceRecord> result = new ArrayList<>();
        for (JsonNode rec : records) {
            String id = rec.path("Id").asText();
            String type = rec.path("attributes").path("type").asText();
            String name = rec.has("Name") ? rec.path("Name").asText() : id;
            Map<String, Object> fields = new LinkedHashMap<>();
            rec.fields().forEachRemaining(f -> {
                if (!"attributes".equals(f.getKey())) {
                    fields.put(f.getKey(), f.getValue().isTextual() ? f.getValue().asText() : f.getValue().toString());
                }
            });
            result.add(new SalesforceRecord(id, type, name, fields));
        }
        return result;
    }

    /**
     * Get a single record by type and ID.
     */
    public SalesforceRecord getRecord(String objectType, String recordId) throws Exception {
        String url = instanceUrl + "/services/data/" + API_VERSION + "/sobjects/" + objectType + "/" + recordId;
        HttpResponse<String> response = get(url);
        JsonNode rec = MAPPER.readTree(response.body());
        String name = rec.has("Name") ? rec.path("Name").asText() : recordId;
        Map<String, Object> fields = new LinkedHashMap<>();
        rec.fields().forEachRemaining(f -> {
            if (!"attributes".equals(f.getKey())) {
                fields.put(f.getKey(), f.getValue().isTextual() ? f.getValue().asText() : f.getValue().toString());
            }
        });
        return new SalesforceRecord(recordId, objectType, name, fields);
    }

    /**
     * List attachments for a record.
     */
    public List<SalesforceRecord> getAttachments(String parentId) throws Exception {
        String soql = "SELECT Id, Name, ContentType, BodyLength FROM Attachment WHERE ParentId = '" + escapeSoql(parentId) + "'";
        return query(soql);
    }

    /** Escape single quotes in SOQL string literals to prevent injection. */
    private static String escapeSoql(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Salesforce API error " + response.statusCode() + ": " + response.body());
        }
        return response;
    }
}
