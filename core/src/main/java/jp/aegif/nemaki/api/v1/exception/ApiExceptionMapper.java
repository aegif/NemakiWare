package jp.aegif.nemaki.api.v1.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUnauthorizedException;

import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {
    
    private static final Logger logger = Logger.getLogger(ApiExceptionMapper.class.getName());
    private static final String PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";
    
    @Override
    public Response toResponse(Throwable exception) {
        ProblemDetail problemDetail;
        
        if (exception instanceof ApiException) {
            problemDetail = ((ApiException) exception).getProblemDetail();
            // Resources catch broadly and wrap into internalError(detail, cause). When the cause
            // is a bounded-lock 503, presenting it as a 500 destroys the one thing the exception
            // exists to say — "temporary, retry" — and integration clients that retry on 503 will
            // not retry a problem+json internal-error. Walk the cause chain and restore the
            // status. (Wrap sites that drop the cause cannot be rescued here; most pass it.)
            if (problemDetail != null && problemDetail.getStatus() == 500) {
                // Depth-bounded: a malformed Throwable graph with a cause cycle must degrade to
                // "no match", not hang the exception mapper of all places.
                Throwable t = exception.getCause();
                for (int depth = 0; t != null && depth < 16; t = t.getCause(), depth++) {
                    if (t instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisServiceUnavailableException) {
                        problemDetail = ProblemDetail.serviceUnavailable(t.getMessage());
                        break;
                    }
                }
            }
        } else if (exception instanceof org.apache.chemistry.opencmis.commons.exceptions.CmisServiceUnavailableException) {
            // Cannot rely on mapCmisException: CmisServiceUnavailableException(String) leaves
            // getCode() at ZERO, which the code-based mapping reads as "not a client error" and
            // turns into a 500. The type, not the code, carries the meaning here.
            problemDetail = ProblemDetail.serviceUnavailable(exception.getMessage());
        } else if (exception instanceof CmisObjectNotFoundException) {
            problemDetail = ProblemDetail.objectNotFound(
                    extractObjectId((CmisObjectNotFoundException) exception),
                    "unknown");
        } else if (exception instanceof CmisPermissionDeniedException) {
            problemDetail = ProblemDetail.permissionDenied(exception.getMessage());
        } else if (exception instanceof CmisUnauthorizedException) {
            problemDetail = ProblemDetail.unauthorized(exception.getMessage());
        } else if (exception instanceof CmisInvalidArgumentException) {
            problemDetail = ProblemDetail.invalidArgument(exception.getMessage());
        } else if (exception instanceof CmisConstraintException) {
            problemDetail = ProblemDetail.constraintViolation(exception.getMessage());
        } else if (exception instanceof CmisContentAlreadyExistsException) {
            problemDetail = ProblemDetail.contentAlreadyExists("unknown");
            problemDetail.setDetail(exception.getMessage());
        } else if (exception instanceof CmisUpdateConflictException) {
            problemDetail = ProblemDetail.conflict(exception.getMessage());
        } else if (exception instanceof CmisBaseException) {
            CmisBaseException cmisEx = (CmisBaseException) exception;
            problemDetail = mapCmisException(cmisEx);
        } else if (exception instanceof IllegalArgumentException) {
            problemDetail = ProblemDetail.invalidArgument(exception.getMessage());
        } else {
            logger.log(Level.SEVERE, "Unexpected exception in API", exception);
            problemDetail = ProblemDetail.internalError(
                    "An unexpected error occurred: " + exception.getMessage());
        }
        
        return Response.status(problemDetail.getStatus())
                .type(PROBLEM_JSON_MEDIA_TYPE)
                .entity(problemDetail)
                .build();
    }
    
    private ProblemDetail mapCmisException(CmisBaseException cmisEx) {
        int httpStatus = cmisEx.getCode() != null ? cmisEx.getCode().intValue() : 500;
        String errorName = cmisEx.getExceptionName();
        
        if (httpStatus >= 400 && httpStatus < 500) {
            if (httpStatus == 400) {
                return ProblemDetail.invalidArgument(cmisEx.getMessage());
            } else if (httpStatus == 401) {
                return ProblemDetail.unauthorized(cmisEx.getMessage());
            } else if (httpStatus == 403) {
                return ProblemDetail.permissionDenied(cmisEx.getMessage());
            } else if (httpStatus == 404) {
                return new ProblemDetail("not-found", "Not Found", 404, cmisEx.getMessage());
            } else if (httpStatus == 409) {
                return ProblemDetail.conflict(cmisEx.getMessage());
            } else {
                return new ProblemDetail("client-error", errorName, httpStatus, cmisEx.getMessage());
            }
        } else {
            return ProblemDetail.internalError(cmisEx.getMessage());
        }
    }
    
    private String extractObjectId(CmisObjectNotFoundException ex) {
        String message = ex.getMessage();
        if (message != null && message.contains("'")) {
            int start = message.indexOf("'");
            int end = message.indexOf("'", start + 1);
            if (end > start) {
                return message.substring(start + 1, end);
            }
        }
        return "unknown";
    }
}
