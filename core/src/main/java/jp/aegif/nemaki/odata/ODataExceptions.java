package jp.aegif.nemaki.odata;

import java.util.Locale;

import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisFilterNotValidException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNameConstraintViolationException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisPermissionDeniedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisStreamNotSupportedException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisUpdateConflictException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;

/**
 * Maps CMIS service exceptions to OData error responses with the correct HTTP
 * status code. Without this, every CMIS failure surfaced as a blanket HTTP 500
 * (e.g. a duplicate name became 500 instead of 409, a missing object 500
 * instead of 404), which is both misleading to clients and hides real server
 * errors among ordinary client-error conditions.
 */
final class ODataExceptions {

    private ODataExceptions() {
    }

    /**
     * Build an {@link ODataApplicationException} for {@code cause} with a status
     * code chosen from the CMIS exception type. {@code context} is a short human
     * description of the operation (e.g. "Error creating entity").
     */
    static ODataApplicationException map(String context, Exception cause) {
        HttpStatusCode status = statusFor(cause);
        String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return new ODataApplicationException(context + ": " + detail,
                status.getStatusCode(), Locale.ENGLISH, cause);
    }

    /** Choose the HTTP status for a CMIS (or generic) exception. */
    static HttpStatusCode statusFor(Throwable e) {
        if (e instanceof CmisObjectNotFoundException) {
            return HttpStatusCode.NOT_FOUND;                    // 404
        }
        if (e instanceof CmisPermissionDeniedException) {
            return HttpStatusCode.FORBIDDEN;                    // 403
        }
        if (e instanceof CmisContentAlreadyExistsException
                || e instanceof CmisNameConstraintViolationException
                || e instanceof CmisUpdateConflictException
                || e instanceof CmisVersioningException
                || e instanceof CmisConstraintException) {
            return HttpStatusCode.CONFLICT;                     // 409
        }
        if (e instanceof CmisInvalidArgumentException
                || e instanceof CmisFilterNotValidException) {
            return HttpStatusCode.BAD_REQUEST;                  // 400
        }
        if (e instanceof CmisNotSupportedException
                || e instanceof CmisStreamNotSupportedException) {
            return HttpStatusCode.METHOD_NOT_ALLOWED;           // 405
        }
        return HttpStatusCode.INTERNAL_SERVER_ERROR;            // 500 (genuine server error)
    }
}
