package jp.aegif.nemaki.api.v1.exception;

public class ApiException extends RuntimeException {
    
    private final ProblemDetail problemDetail;
    
    public ApiException(ProblemDetail problemDetail) {
        super(problemDetail.getDetail());
        this.problemDetail = problemDetail;
    }
    
    public ApiException(ProblemDetail problemDetail, Throwable cause) {
        super(problemDetail.getDetail(), cause);
        this.problemDetail = problemDetail;
    }
    
    public ProblemDetail getProblemDetail() {
        return problemDetail;
    }
    
    public int getStatus() {
        return problemDetail.getStatus();
    }
    
    public static ApiException invalidArgument(String detail) {
        return new ApiException(ProblemDetail.invalidArgument(detail));
    }
    
    public static ApiException invalidArgument(String detail, String instance) {
        return new ApiException(ProblemDetail.invalidArgument(detail, instance));
    }
    
    public static ApiException unauthorized(String detail) {
        return new ApiException(ProblemDetail.unauthorized(detail));
    }
    
    public static ApiException unauthorized(String detail, String instance) {
        return new ApiException(ProblemDetail.unauthorized(detail, instance));
    }
    
    public static ApiException permissionDenied(String detail) {
        return new ApiException(ProblemDetail.permissionDenied(detail));
    }
    
    public static ApiException permissionDenied(String detail, String instance) {
        return new ApiException(ProblemDetail.permissionDenied(detail, instance));
    }
    
    public static ApiException objectNotFound(String objectId, String repositoryId) {
        return new ApiException(ProblemDetail.objectNotFound(objectId, repositoryId));
    }
    
    public static ApiException objectNotFound(String objectId, String repositoryId, String instance) {
        return new ApiException(ProblemDetail.objectNotFound(objectId, repositoryId, instance));
    }
    
    public static ApiException typeNotFound(String typeId, String repositoryId) {
        return new ApiException(ProblemDetail.typeNotFound(typeId, repositoryId));
    }
    
    public static ApiException repositoryNotFound(String repositoryId) {
        return new ApiException(ProblemDetail.repositoryNotFound(repositoryId));
    }
    
    public static ApiException userNotFound(String userId, String repositoryId) {
        return new ApiException(ProblemDetail.userNotFound(userId, repositoryId));
    }
    
    public static ApiException groupNotFound(String groupId, String repositoryId) {
        return new ApiException(ProblemDetail.groupNotFound(groupId, repositoryId));
    }
    
    public static ApiException conflict(String detail) {
        return new ApiException(ProblemDetail.conflict(detail));
    }
    
    public static ApiException conflict(String detail, String instance) {
        return new ApiException(ProblemDetail.conflict(detail, instance));
    }
    
    public static ApiException contentAlreadyExists(String objectId) {
        return new ApiException(ProblemDetail.contentAlreadyExists(objectId));
    }
    
    public static ApiException versionConflict(String objectId, String expectedToken, String actualToken) {
        return new ApiException(ProblemDetail.versionConflict(objectId, expectedToken, actualToken));
    }
    
    public static ApiException constraintViolation(String detail) {
        return new ApiException(ProblemDetail.constraintViolation(detail));
    }
    
    public static ApiException constraintViolation(String detail, String instance) {
        return new ApiException(ProblemDetail.constraintViolation(detail, instance));
    }
    
    public static ApiException internalError(String detail) {
        return new ApiException(ProblemDetail.internalError(detail));
    }
    
    public static ApiException internalError(String detail, Throwable cause) {
        return new ApiException(ProblemDetail.internalError(detail), cause);
    }
    
    public static ApiException serviceUnavailable(String detail) {
        return new ApiException(ProblemDetail.serviceUnavailable(detail));
    }

    public static ApiException tooManyRequests(String detail) {
        return new ApiException(ProblemDetail.tooManyRequests(detail));
    }
}
