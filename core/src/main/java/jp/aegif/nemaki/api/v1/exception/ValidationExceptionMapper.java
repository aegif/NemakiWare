package jp.aegif.nemaki.api.v1.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {
    
    private static final String PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";
    
    @Override
    public Response toResponse(ConstraintViolationException exception) {
        List<Map<String, String>> violations = exception.getConstraintViolations().stream()
                .map(this::mapViolation)
                .collect(Collectors.toList());
        
        String detail = violations.stream()
                .map(v -> v.get("field") + ": " + v.get("message"))
                .collect(Collectors.joining("; "));
        
        ProblemDetail problemDetail = ProblemDetail.invalidArgument(detail);
        problemDetail.withExtension("violations", violations);
        
        return Response.status(400)
                .type(PROBLEM_JSON_MEDIA_TYPE)
                .entity(problemDetail)
                .build();
    }
    
    private Map<String, String> mapViolation(ConstraintViolation<?> violation) {
        String propertyPath = violation.getPropertyPath().toString();
        String field = propertyPath.contains(".") 
                ? propertyPath.substring(propertyPath.lastIndexOf('.') + 1)
                : propertyPath;
        
        return Map.of(
                "field", field,
                "message", violation.getMessage(),
                "invalidValue", String.valueOf(violation.getInvalidValue())
        );
    }
}
