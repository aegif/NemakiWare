package jp.aegif.nemaki.api.v1.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.util.Map;

@Schema(description = "RFC 7807 Problem Details for HTTP APIs")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProblemDetail {
    
    private static final String BASE_TYPE_URI = "https://nemakiware.org/errors/";
    
    @Schema(description = "A URI reference that identifies the problem type",
            example = "https://nemakiware.org/errors/object-not-found")
    @JsonProperty("type")
    private String type;
    
    @Schema(description = "A short, human-readable summary of the problem type",
            example = "Object Not Found")
    @JsonProperty("title")
    private String title;
    
    @Schema(description = "The HTTP status code",
            example = "404")
    @JsonProperty("status")
    private int status;
    
    @Schema(description = "A human-readable explanation specific to this occurrence of the problem",
            example = "The object with ID 'OBJECT_ID' was not found in repository 'bedroom'")
    @JsonProperty("detail")
    private String detail;
    
    @Schema(description = "A URI reference that identifies the specific occurrence of the problem",
            example = "/api/v1/repositories/bedroom/objects/OBJECT_ID")
    @JsonProperty("instance")
    private String instance;
    
    @Schema(description = "Additional properties specific to the error type")
    @JsonProperty("extensions")
    private Map<String, Object> extensions;
    
    public ProblemDetail() {
    }
    
    public ProblemDetail(String type, String title, int status, String detail) {
        this.type = BASE_TYPE_URI + type;
        this.title = title;
        this.status = status;
        this.detail = detail;
    }
    
    public ProblemDetail(String type, String title, int status, String detail, String instance) {
        this(type, title, status, detail);
        this.instance = instance;
    }
    
    public static ProblemDetail invalidArgument(String detail) {
        return new ProblemDetail("invalid-argument", "Invalid Argument", 400, detail);
    }
    
    public static ProblemDetail invalidArgument(String detail, String instance) {
        return new ProblemDetail("invalid-argument", "Invalid Argument", 400, detail, instance);
    }
    
    public static ProblemDetail unauthorized(String detail) {
        return new ProblemDetail("unauthorized", "Unauthorized", 401, detail);
    }
    
    public static ProblemDetail unauthorized(String detail, String instance) {
        return new ProblemDetail("unauthorized", "Unauthorized", 401, detail, instance);
    }
    
    public static ProblemDetail permissionDenied(String detail) {
        return new ProblemDetail("permission-denied", "Permission Denied", 403, detail);
    }
    
    public static ProblemDetail permissionDenied(String detail, String instance) {
        return new ProblemDetail("permission-denied", "Permission Denied", 403, detail, instance);
    }
    
    public static ProblemDetail objectNotFound(String objectId, String repositoryId) {
        String detail = String.format("The object with ID '%s' was not found in repository '%s'", objectId, repositoryId);
        return new ProblemDetail("object-not-found", "Object Not Found", 404, detail);
    }
    
    public static ProblemDetail objectNotFound(String objectId, String repositoryId, String instance) {
        String detail = String.format("The object with ID '%s' was not found in repository '%s'", objectId, repositoryId);
        return new ProblemDetail("object-not-found", "Object Not Found", 404, detail, instance);
    }
    
    public static ProblemDetail typeNotFound(String typeId, String repositoryId) {
        String detail = String.format("The type with ID '%s' was not found in repository '%s'", typeId, repositoryId);
        return new ProblemDetail("type-not-found", "Type Not Found", 404, detail);
    }
    
    public static ProblemDetail repositoryNotFound(String repositoryId) {
        String detail = String.format("The repository '%s' was not found", repositoryId);
        return new ProblemDetail("repository-not-found", "Repository Not Found", 404, detail);
    }
    
    public static ProblemDetail userNotFound(String userId, String repositoryId) {
        String detail = String.format("The user with ID '%s' was not found in repository '%s'", userId, repositoryId);
        return new ProblemDetail("user-not-found", "User Not Found", 404, detail);
    }
    
    public static ProblemDetail groupNotFound(String groupId, String repositoryId) {
        String detail = String.format("The group with ID '%s' was not found in repository '%s'", groupId, repositoryId);
        return new ProblemDetail("group-not-found", "Group Not Found", 404, detail);
    }
    
    public static ProblemDetail conflict(String detail) {
        return new ProblemDetail("conflict", "Conflict", 409, detail);
    }
    
    public static ProblemDetail conflict(String detail, String instance) {
        return new ProblemDetail("conflict", "Conflict", 409, detail, instance);
    }
    
    public static ProblemDetail contentAlreadyExists(String objectId) {
        String detail = String.format("Content already exists for object '%s'", objectId);
        return new ProblemDetail("content-already-exists", "Content Already Exists", 409, detail);
    }
    
    public static ProblemDetail versionConflict(String objectId, String expectedToken, String actualToken) {
        String detail = String.format("Version conflict for object '%s': expected changeToken '%s' but found '%s'", 
                objectId, expectedToken, actualToken);
        return new ProblemDetail("version-conflict", "Version Conflict", 409, detail);
    }
    
    public static ProblemDetail constraintViolation(String detail) {
        return new ProblemDetail("constraint-violation", "Constraint Violation", 422, detail);
    }
    
    public static ProblemDetail constraintViolation(String detail, String instance) {
        return new ProblemDetail("constraint-violation", "Constraint Violation", 422, detail, instance);
    }
    
    public static ProblemDetail internalError(String detail) {
        return new ProblemDetail("internal-error", "Internal Server Error", 500, detail);
    }
    
    public static ProblemDetail internalError(String detail, String instance) {
        return new ProblemDetail("internal-error", "Internal Server Error", 500, detail, instance);
    }
    
    public static ProblemDetail serviceUnavailable(String detail) {
        return new ProblemDetail("service-unavailable", "Service Unavailable", 503, detail);
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public int getStatus() {
        return status;
    }
    
    public void setStatus(int status) {
        this.status = status;
    }
    
    public String getDetail() {
        return detail;
    }
    
    public void setDetail(String detail) {
        this.detail = detail;
    }
    
    public String getInstance() {
        return instance;
    }
    
    public void setInstance(String instance) {
        this.instance = instance;
    }
    
    public Map<String, Object> getExtensions() {
        return extensions;
    }
    
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
    
    public ProblemDetail withExtension(String key, Object value) {
        if (this.extensions == null) {
            this.extensions = new java.util.HashMap<>();
        }
        this.extensions.put(key, value);
        return this;
    }
}
