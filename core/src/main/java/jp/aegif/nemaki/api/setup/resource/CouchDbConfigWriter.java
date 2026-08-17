package jp.aegif.nemaki.api.setup.resource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Shared utility for direct CouchDB HTTP operations during Setup.
 *
 * <p>Spring DAO stack may not be initialized during Setup Mode, so these
 * methods use raw {@link HttpURLConnection} to read/write config documents
 * in {@code nemaki_conf} and user documents in {@code bedroom}.
 */
final class CouchDbConfigWriter {

    private static final ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();

    private CouchDbConfigWriter() {}

    /**
     * Write a configuration value to nemaki_conf DB.
     * Performs a GET to check for an existing doc (preserves _rev), then PUTs.
     */
    static void putConfigValue(String couchUrl, String authHeader, String key, String value) throws Exception {
        String dbUrl = couchUrl + "/nemaki_conf";

        // Search for existing config doc with this key.
        // nemaki_conf design doc has a "configuration" view: emit(doc.key, doc)
        String viewUrl = dbUrl + "/_design/_repo/_view/configuration?key=\"" + key + "\"&reduce=false&include_docs=true";
        HttpURLConnection conn = openGet(viewUrl, authHeader);
        int code = conn.getResponseCode();
        String existingId = null;
        String existingRev = null;

        if (code == 200) {
            String body = readResponse(conn);
            JsonNode result = mapper.readTree(body);
            if (result.has("rows") && result.get("rows").size() > 0) {
                JsonNode doc = result.get("rows").get(0).get("doc");
                if (doc != null) {
                    existingId = doc.get("_id").asText();
                    existingRev = doc.get("_rev").asText();
                }
            }
        }
        conn.disconnect();

        // Build config document
        String docId = existingId != null ? existingId : "config_" + key.replace(".", "_");
        StringBuilder json = new StringBuilder();
        json.append("{\"_id\":\"").append(escapeJson(docId)).append("\"");
        if (existingRev != null) {
            json.append(",\"_rev\":\"").append(escapeJson(existingRev)).append("\"");
        }
        json.append(",\"type\":\"configuration\"");
        json.append(",\"key\":\"").append(escapeJson(key)).append("\"");
        json.append(",\"value\":\"").append(escapeJson(value)).append("\"");
        json.append("}");

        // PUT document
        URL url = new URL(dbUrl + "/" + docId);
        HttpURLConnection putConn = (HttpURLConnection) url.openConnection();
        putConn.setRequestProperty("Authorization", authHeader);
        putConn.setRequestProperty("Content-Type", "application/json");
        putConn.setRequestMethod("PUT");
        putConn.setDoOutput(true);
        try (OutputStreamWriter out = new OutputStreamWriter(putConn.getOutputStream(), StandardCharsets.UTF_8)) {
            out.write(json.toString());
        }
        int putCode = putConn.getResponseCode();
        putConn.disconnect();

        if (putCode != 201 && putCode != 200) {
            throw new RuntimeException("Failed to put config " + key + ": HTTP " + putCode);
        }
    }

    /**
     * Read a configuration value from nemaki_conf DB.
     * Returns null if the key does not exist or has an empty value.
     */
    static String getConfigValue(String couchUrl, String authHeader, String key) throws Exception {
        String viewUrl = couchUrl + "/nemaki_conf/_design/_repo/_view/configuration?key=\"" + key + "\"&reduce=false&include_docs=true";
        HttpURLConnection conn = openGet(viewUrl, authHeader);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return null;
        }
        String body = readResponse(conn);
        conn.disconnect();
        JsonNode result = mapper.readTree(body);
        if (result.has("rows") && result.get("rows").size() > 0) {
            JsonNode doc = result.get("rows").get(0).get("doc");
            if (doc != null && doc.has("value")) {
                String value = doc.get("value").asText();
                return (value != null && !value.trim().isEmpty()) ? value : null;
            }
        }
        return null;
    }

    static HttpURLConnection openGet(String urlStr, String authHeader) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Authorization", authHeader);
        conn.setRequestMethod("GET");
        return conn;
    }

    static String readResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    static String basicAuth(String user, String pass) {
        String credentials = user + ":" + pass;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }
}
