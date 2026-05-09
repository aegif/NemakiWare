package jp.aegif.nemaki.rest;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Public endpoint for the React UI to register a SAML AuthnRequest ID
 * before redirecting the user to the IdP.
 *
 * <p>Mounted at {@code /rest/all/saml/register-request} so it bypasses
 * the authentication filter (the user is by definition unauthenticated
 * at the start of an SP-initiated SSO flow). The endpoint only accepts
 * a single short, NCName-shaped ID per call and stores it in the
 * in-memory {@link SamlAuthnRequestRegistry} with a 15-minute TTL,
 * bounded at {@link SamlAuthnRequestRegistry#MAX_ENTRIES} entries to
 * prevent memory abuse.
 *
 * <p>The registry is consulted later by {@link SamlSignatureVerifier}
 * when {@code saml.require.inResponseTo=true} is set; in the default
 * (non-strict) mode the registration succeeds but is never consulted.
 *
 * <p>Wire format:
 * <pre>
 * POST /core/rest/all/saml/register-request
 * Content-Type: application/json
 * { "requestId": "_e51b3a8d-..." }
 *
 * 200 OK { "status": "registered" }
 * 400    { "status": "error", "error": "invalid request id" }
 * </pre>
 */
public class SamlRequestRegistrationServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(SamlRequestRegistrationServlet.class);
    private static final int MAX_BODY_BYTES = 4096; // ID is ~256 chars, leave room for envelope

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("application/json; charset=UTF-8");

        // Read body (capped). The endpoint is publicly reachable, so we must
        // refuse oversized payloads cheaply.
        StringBuilder body = new StringBuilder();
        int total = 0;
        try (BufferedReader r = request.getReader()) {
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) != -1) {
                total += n;
                if (total > MAX_BODY_BYTES) {
                    // Jakarta Servlet 6.0 didn't backport the 413 constant; use the
                    // numeric status (RFC 7231) directly for portability.
                    fail(response, 413, "request body exceeds " + MAX_BODY_BYTES + " bytes");
                    return;
                }
                body.append(buf, 0, n);
            }
        }

        String requestId;
        try {
            JSONObject json = (JSONObject) new JSONParser().parse(body.toString());
            Object idObj = json.get("requestId");
            requestId = idObj == null ? null : idObj.toString();
        } catch (Exception e) {
            fail(response, HttpServletResponse.SC_BAD_REQUEST, "invalid JSON body");
            return;
        }

        if (requestId == null || requestId.isBlank()) {
            fail(response, HttpServletResponse.SC_BAD_REQUEST, "requestId is required");
            return;
        }

        boolean accepted = SamlAuthnRequestRegistry.getInstance().register(requestId);
        if (!accepted) {
            fail(response, HttpServletResponse.SC_BAD_REQUEST, "invalid request id");
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("SAML AuthnRequest registered (id length=" + requestId.length() + ")");
        }
        response.setStatus(HttpServletResponse.SC_OK);
        try (PrintWriter out = response.getWriter()) {
            out.print("{\"status\":\"registered\"}");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    private static void fail(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        try (PrintWriter out = response.getWriter()) {
            out.print("{\"status\":\"error\",\"error\":\"" + escape(error) + "\"}");
        }
    }

    /** Minimal JSON string escape for the few characters that can appear here. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }
}
