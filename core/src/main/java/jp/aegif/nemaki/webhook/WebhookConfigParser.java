package jp.aegif.nemaki.webhook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * Parser for the nemaki:webhookConfigs JSON property.
 * 
 * Handles serialization and deserialization of WebhookConfig objects
 * to/from JSON format stored in the CMIS property.
 * 
 * JSON Format:
 * [
 *   {
 *     "id": "webhook-1",
 *     "enabled": true,
 *     "url": "https://example.com/webhook",
 *     "events": ["CREATED", "UPDATED"],
 *     "authType": "bearer",
 *     "authCredential": "token",
 *     "secret": "hmac-secret",
 *     "headers": {"X-Custom": "value"},
 *     "includeChildren": true,
 *     "maxDepth": 5,
 *     "retryCount": 3
 *   }
 * ]
 */
public class WebhookConfigParser {
    
    private static final Log log = LogFactory.getLog(WebhookConfigParser.class);
    
    /**
     * Parse a JSON string into a list of WebhookConfig objects.
     * 
     * @param json The JSON string to parse (should be a JSON array)
     * @return List of WebhookConfig objects, empty list if parsing fails
     */
    public List<WebhookConfig> parse(String json) {
        List<WebhookConfig> configs = new ArrayList<>();
        
        if (json == null || json.trim().isEmpty()) {
            return configs;
        }
        
        try {
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(json);
            
            if (!(parsed instanceof JSONArray)) {
                log.warn("Webhook configs JSON is not an array");
                return configs;
            }
            
            JSONArray jsonArray = (JSONArray) parsed;
            
            for (Object item : jsonArray) {
                if (item instanceof JSONObject) {
                    JSONObject jsonObj = (JSONObject) item;
                    WebhookConfig config = parseConfig(jsonObj);
                    configs.add(config);
                }
            }
        } catch (ParseException e) {
            log.warn("Failed to parse webhook configs JSON: " + e.getMessage());
            return new ArrayList<>();
        }
        
        return configs;
    }
    
    /**
     * Parse a single JSONObject into a WebhookConfig.
     */
    private WebhookConfig parseConfig(JSONObject json) {
        WebhookConfig config = new WebhookConfig();
        
        // Required fields
        config.setId(getStringOrNull(json, "id"));
        config.setEnabled(getBooleanOrDefault(json, "enabled", false));
        config.setUrl(getStringOrNull(json, "url"));
        config.setEvents(parseStringArray(json, "events"));
        
        // Optional fields
        config.setAuthType(getStringOrNull(json, "authType"));
        config.setAuthCredential(getStringOrNull(json, "authCredential"));
        config.setSecret(getStringOrNull(json, "secret"));
        config.setHeaders(parseHeaders(json, "headers"));
        config.setIncludeChildren(getBooleanOrDefault(json, "includeChildren", false));
        config.setMaxDepth(getIntegerOrNull(json, "maxDepth"));
        config.setRetryCount(getIntegerOrNull(json, "retryCount"));
        
        return config;
    }
    
    /**
     * Get a string value from JSON, returning null if not present or null.
     */
    private String getStringOrNull(JSONObject json, String key) {
        Object value = json.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }
    
    /**
     * Get a boolean value from JSON, returning default if not present.
     */
    private boolean getBooleanOrDefault(JSONObject json, String key, boolean defaultValue) {
        Object value = json.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
    
    /**
     * Get an Integer value from JSON, returning null if not present or null.
     */
    private Integer getIntegerOrNull(JSONObject json, String key) {
        Object value = json.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Parse a JSON array of strings.
     */
    private List<String> parseStringArray(JSONObject json, String key) {
        List<String> result = new ArrayList<>();
        
        Object value = json.get(key);
        if (value == null) {
            return result;
        }
        
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (Object item : array) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        }
        
        return result;
    }
    
    /**
     * Parse a JSON object as a map of string headers.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(JSONObject json, String key) {
        Map<String, String> result = new HashMap<>();
        
        Object value = json.get(key);
        if (value == null) {
            return result;
        }
        
        if (value instanceof JSONObject) {
            JSONObject headersObj = (JSONObject) value;
            for (Object headerKey : headersObj.keySet()) {
                Object headerValue = headersObj.get(headerKey);
                if (headerKey != null && headerValue != null) {
                    result.put(headerKey.toString(), headerValue.toString());
                }
            }
        }
        
        return result;
    }
    
    /**
     * Serialize a list of WebhookConfig objects to JSON string.
     * 
     * @param configs List of WebhookConfig objects
     * @return JSON string representation
     */
    @SuppressWarnings("unchecked")
    public String serialize(List<WebhookConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return "[]";
        }
        
        JSONArray jsonArray = new JSONArray();
        
        for (WebhookConfig config : configs) {
            JSONObject jsonObj = serializeConfig(config);
            jsonArray.add(jsonObj);
        }
        
        return jsonArray.toJSONString();
    }
    
    /**
     * Serialize a single WebhookConfig to JSONObject.
     */
    @SuppressWarnings("unchecked")
    private JSONObject serializeConfig(WebhookConfig config) {
        JSONObject json = new JSONObject();
        
        // Required fields
        json.put("id", config.getId());
        json.put("enabled", config.isEnabled());
        json.put("url", config.getUrl());
        
        JSONArray eventsArray = new JSONArray();
        if (config.getEvents() != null) {
            eventsArray.addAll(config.getEvents());
        }
        json.put("events", eventsArray);
        
        // Optional fields - only include if not null
        if (config.getAuthType() != null) {
            json.put("authType", config.getAuthType());
        }
        if (config.getAuthCredential() != null) {
            json.put("authCredential", config.getAuthCredential());
        }
        if (config.getSecret() != null) {
            json.put("secret", config.getSecret());
        }
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            JSONObject headersObj = new JSONObject();
            headersObj.putAll(config.getHeaders());
            json.put("headers", headersObj);
        }
        if (config.isIncludeChildren()) {
            json.put("includeChildren", config.isIncludeChildren());
        }
        if (config.getMaxDepth() != null) {
            json.put("maxDepth", config.getMaxDepth());
        }
        if (config.getRetryCount() != null) {
            json.put("retryCount", config.getRetryCount());
        }
        
        return json;
    }
}
