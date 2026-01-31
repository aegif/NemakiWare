package jp.aegif.nemaki.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Property value with type information for dynamic CMIS properties")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PropertyValue {
    
    @Schema(description = "Property value. Can be string, number, boolean, or array for multi-valued properties",
            example = "invoice-2026-001.pdf")
    @JsonProperty("value")
    private Object value;
    
    @Schema(description = "Property type identifier",
            allowableValues = {"string", "boolean", "integer", "decimal", "datetime", "uri", "id", "html"},
            example = "string")
    @JsonProperty("type")
    private String type;
    
    @Schema(description = "Human-readable display name for the property",
            example = "Name")
    @JsonProperty("displayName")
    private String displayName;
    
    @Schema(description = "Cardinality of the property",
            allowableValues = {"single", "multi"},
            example = "single")
    @JsonProperty("cardinality")
    private String cardinality;
    
    @Schema(description = "Property definition ID",
            example = "cmis:name")
    @JsonProperty("propertyDefinitionId")
    private String propertyDefinitionId;
    
    @Schema(description = "Local name of the property",
            example = "name")
    @JsonProperty("localName")
    private String localName;
    
    @Schema(description = "Query name for use in CMIS queries",
            example = "cmis:name")
    @JsonProperty("queryName")
    private String queryName;
    
    public PropertyValue() {
    }
    
    public PropertyValue(Object value, String type) {
        this.value = value;
        this.type = type;
        this.cardinality = (value instanceof List) ? "multi" : "single";
    }
    
    public PropertyValue(Object value, String type, String displayName) {
        this(value, type);
        this.displayName = displayName;
    }
    
    public static PropertyValue ofString(String value) {
        return new PropertyValue(value, "string");
    }
    
    public static PropertyValue ofString(String value, String displayName) {
        return new PropertyValue(value, "string", displayName);
    }
    
    public static PropertyValue ofBoolean(Boolean value) {
        return new PropertyValue(value, "boolean");
    }
    
    public static PropertyValue ofBoolean(Boolean value, String displayName) {
        return new PropertyValue(value, "boolean", displayName);
    }
    
    public static PropertyValue ofInteger(Long value) {
        return new PropertyValue(value, "integer");
    }
    
    public static PropertyValue ofInteger(Long value, String displayName) {
        return new PropertyValue(value, "integer", displayName);
    }
    
    public static PropertyValue ofDecimal(Double value) {
        return new PropertyValue(value, "decimal");
    }
    
    public static PropertyValue ofDecimal(Double value, String displayName) {
        return new PropertyValue(value, "decimal", displayName);
    }
    
    public static PropertyValue ofDatetime(String isoDateTime) {
        return new PropertyValue(isoDateTime, "datetime");
    }
    
    public static PropertyValue ofDatetime(String isoDateTime, String displayName) {
        return new PropertyValue(isoDateTime, "datetime", displayName);
    }
    
    public static PropertyValue ofId(String id) {
        return new PropertyValue(id, "id");
    }
    
    public static PropertyValue ofId(String id, String displayName) {
        return new PropertyValue(id, "id", displayName);
    }
    
    public static PropertyValue ofUri(String uri) {
        return new PropertyValue(uri, "uri");
    }
    
    public static PropertyValue ofUri(String uri, String displayName) {
        return new PropertyValue(uri, "uri", displayName);
    }
    
    public static PropertyValue ofHtml(String html) {
        return new PropertyValue(html, "html");
    }
    
    public static PropertyValue ofHtml(String html, String displayName) {
        return new PropertyValue(html, "html", displayName);
    }
    
    public static PropertyValue ofMultiString(List<String> values) {
        PropertyValue pv = new PropertyValue(values, "string");
        pv.setCardinality("multi");
        return pv;
    }
    
    public static PropertyValue ofMultiString(List<String> values, String displayName) {
        PropertyValue pv = new PropertyValue(values, "string", displayName);
        pv.setCardinality("multi");
        return pv;
    }
    
    public Object getValue() {
        return value;
    }
    
    public void setValue(Object value) {
        this.value = value;
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public String getCardinality() {
        return cardinality;
    }
    
    public void setCardinality(String cardinality) {
        this.cardinality = cardinality;
    }
    
    public String getPropertyDefinitionId() {
        return propertyDefinitionId;
    }
    
    public void setPropertyDefinitionId(String propertyDefinitionId) {
        this.propertyDefinitionId = propertyDefinitionId;
    }
    
    public String getLocalName() {
        return localName;
    }
    
    public void setLocalName(String localName) {
        this.localName = localName;
    }
    
    public String getQueryName() {
        return queryName;
    }
    
    public void setQueryName(String queryName) {
        this.queryName = queryName;
    }
    
    @SuppressWarnings("unchecked")
    public String getStringValue() {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            return list.isEmpty() ? null : String.valueOf(list.get(0));
        }
        return String.valueOf(value);
    }
    
    public Long getIntegerValue() {
        if (value == null) return null;
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        return null;
    }
    
    public Double getDecimalValue() {
        if (value == null) return null;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) return Double.parseDouble((String) value);
        return null;
    }
    
    /**
     * Gets the value as a Boolean.
     * @return the Boolean value, or null if value is null or cannot be converted to Boolean
     */
    public Boolean getBooleanValue() {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return null;
    }
    
    @SuppressWarnings("unchecked")
    public List<String> getMultiStringValue() {
        if (value == null) return null;
        if (value instanceof List) return (List<String>) value;
        return List.of(String.valueOf(value));
    }
}
