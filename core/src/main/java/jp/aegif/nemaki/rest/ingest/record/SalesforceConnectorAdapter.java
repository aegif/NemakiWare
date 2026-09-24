package jp.aegif.nemaki.rest.ingest.record;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Salesforce REST API connector adapter — reads the result of a SOQL query batch by batch, and
 * single records.
 *
 * <p>Uses Salesforce REST API v59.0 with OAuth2 Bearer token.
 */
public class SalesforceConnectorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SalesforceConnectorAdapter.class);
    private static final String API_VERSION = "v59.0";
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultObjectMapper();
    /** How many batches {@link #query} reads before it calls the result too long to read whole. */
    static final int MAX_QUERY_BATCHES = 50;

    private final String instanceUrl;
    private final String accessToken;
    private final HttpClient httpClient;

    /**
     * @param instanceUrl Salesforce instance URL (e.g. https://myorg.salesforce.com)
     * @param accessToken OAuth2 access token
     */
    public SalesforceConnectorAdapter(String instanceUrl, String accessToken) {
        this(instanceUrl, accessToken, jp.aegif.nemaki.rest.ingest.AdapterHttpClient.shared());
    }

    public SalesforceConnectorAdapter(String instanceUrl, String accessToken, HttpClient httpClient) {
        this.instanceUrl = instanceUrl.replaceAll("/+$", "");
        this.accessToken = accessToken;
        this.httpClient = httpClient;
    }

    public record SalesforceRecord(String id, String type, String name, Map<String, Object> fields) {}

    /**
     * One batch of a query's result: its records, and the path of the next batch
     * ({@code nextRecordsUrl}), or null when the result is complete ({@code done}).
     */
    public record QueryPage(List<SalesforceRecord> records, String nextRecordsUrl) {}

    /**
     * The first batch of the result of a SOQL query. SOQL is passed as a query parameter to the
     * Salesforce REST API, which handles its own validation; obviously mutating patterns are
     * rejected here as well.
     */
    public QueryPage queryPage(String soql) throws Exception {
        rejectMutations(soql);
        return page(instanceUrl + "/services/data/" + API_VERSION + "/query?q="
                + java.net.URLEncoder.encode(soql, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * A later batch of a result, by the {@code nextRecordsUrl} Salesforce answered — read as a path
     * on THIS instance, never as a URL: an answer does not choose the host the token is sent to.
     */
    public QueryPage nextPage(String nextRecordsUrl) throws Exception {
        if (nextRecordsUrl == null || !nextRecordsUrl.startsWith("/services/data/") || nextRecordsUrl.contains("://")
                || nextRecordsUrl.contains("..")) {
            throw new IllegalStateException("Salesforce answered a nextRecordsUrl that is not a query path on this instance ('"
                    + nextRecordsUrl + "'), so the rest of the result cannot be read");
        }
        return page(instanceUrl + nextRecordsUrl);
    }

    /**
     * Every batch of the result of a SOQL query. A result longer than {@link #MAX_QUERY_BATCHES}
     * batches is refused rather than cut: a caller given part of it would read the rest as absent.
     */
    public List<SalesforceRecord> query(String soql) throws Exception {
        List<SalesforceRecord> all = new ArrayList<>();
        QueryPage page = queryPage(soql);
        all.addAll(page.records());
        int batches = 1;
        while (page.nextRecordsUrl() != null) {
            if (++batches > MAX_QUERY_BATCHES) {
                throw new IllegalStateException("the query result runs past " + MAX_QUERY_BATCHES + " batches; it was not read whole");
            }
            page = nextPage(page.nextRecordsUrl());
            all.addAll(page.records());
        }
        return all;
    }

    /**
     * One batch. Refused rather than read around: an answer without a {@code records} array, one
     * without {@code done}, and one that says {@code done: false} without a {@code nextRecordsUrl} —
     * each would read the records it does not carry as absent.
     */
    private QueryPage page(String url) throws Exception {
        HttpResponse<String> response = get(url);
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode records = root.get("records");
        if (records == null || !records.isArray()) {
            throw new IllegalStateException("Salesforce answered a query without a records array, so the batch cannot be read");
        }
        JsonNode done = root.get("done");
        if (done == null || !done.isBoolean()) {
            throw new IllegalStateException("Salesforce answered a query without done, so whether the result is complete cannot be told");
        }
        String next = null;
        if (!done.asBoolean()) {
            JsonNode nextNode = root.get("nextRecordsUrl");
            if (nextNode == null || !nextNode.isTextual() || nextNode.asText().isBlank()) {
                throw new IllegalStateException("Salesforce answered done=false without a nextRecordsUrl, so the rest of the result cannot be read");
            }
            next = nextNode.asText();
        }
        List<SalesforceRecord> result = new ArrayList<>();
        for (JsonNode rec : records) {
            result.add(record(rec, rec.path("attributes").path("type").asText(), rec.path("Id").asText("")));
        }
        return new QueryPage(result, next);
    }

    private static SalesforceRecord record(JsonNode rec, String type, String id) {
        String name = rec.has("Name") ? rec.path("Name").asText() : id;
        Map<String, Object> fields = new LinkedHashMap<>();
        rec.properties().forEach(f -> {
            if (!"attributes".equals(f.getKey())) {
                fields.put(f.getKey(), f.getValue().isTextual() ? f.getValue().asText() : f.getValue().toString());
            }
        });
        return new SalesforceRecord(id, type, name, fields);
    }

    private static void rejectMutations(String soql) {
        // Basic safety: reject SOQL with suspicious patterns
        if (soql != null) {
            String upper = soql.toUpperCase();
            if (upper.contains("DELETE") || upper.contains("UPDATE") || upper.contains("INSERT")
                    || upper.contains("--") || upper.contains(";")) {
                throw new IllegalArgumentException("SOQL contains prohibited keywords");
            }
        }
    }

    /**
     * Get a single record by type and ID.
     */
    public SalesforceRecord getRecord(String objectType, String recordId) throws Exception {
        String url = instanceUrl + "/services/data/" + API_VERSION + "/sobjects/" + objectType + "/" + recordId;
        HttpResponse<String> response = get(url);
        JsonNode rec = MAPPER.readTree(response.body());
        return record(rec, objectType, recordId);
    }

    /**
     * List attachments for a record.
     */
    public List<SalesforceRecord> getAttachments(String parentId) throws Exception {
        String soql = "SELECT Id, Name, ContentType, BodyLength FROM Attachment WHERE ParentId = '" + validateSalesforceId(parentId) + "'";
        return query(soql);
    }

    /** Validate that a value is a Salesforce ID (15 or 18 alphanumeric chars). */
    static String validateSalesforceId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9]{15,18}")) {
            throw new IllegalArgumentException("Invalid Salesforce ID format: " + (value != null ? value.substring(0, Math.min(value.length(), 20)) : "null"));
        }
        return value;
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = jp.aegif.nemaki.rest.ingest.AdapterHttpClient.sendWithRetry(
                httpClient, request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new RuntimeException("Salesforce API rate limited (HTTP 429) after retries");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("Salesforce API error " + response.statusCode() + ": " + jp.aegif.nemaki.rest.ingest.AdapterHttpClient.truncateBody(response.body()));
        }
        return response;
    }
}
