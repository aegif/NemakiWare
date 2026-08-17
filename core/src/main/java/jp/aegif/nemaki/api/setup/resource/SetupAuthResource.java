package jp.aegif.nemaki.api.setup.resource;

import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.filter.UrlValidator;
import jp.aegif.nemaki.init.CouchDbConnectionResult;
import jp.aegif.nemaki.init.StartupProbeService;
import jp.aegif.nemaki.api.setup.model.AuthConfigRequest;
import jp.aegif.nemaki.api.setup.model.OidcTestRequest;
import jp.aegif.nemaki.util.PropertyManager;

/**
 * Authentication setup endpoints: state, test-oidc, apply.
 */
@Component
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupAuthResource {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StartupProbeService startupProbeService;

    private static final Logger logger = Logger.getLogger(SetupAuthResource.class.getName());

    @Autowired(required = false)
    private PropertyManager propertyManager;

    /**
     * GET /auth/state -- current authentication configuration.
     */
    @GET
    @Path("/state")
    public Response getState() {
        AuthConfigRequest current = new AuthConfigRequest();
        if (propertyManager != null) {
            current.setPasswordEnabled(!"false".equals(propertyManager.readValue("auth.password.enabled")));
            current.setGoogleEnabled("true".equals(propertyManager.readValue("cloud.auth.google.enabled")));
            current.setMicrosoftEnabled("true".equals(propertyManager.readValue("cloud.auth.microsoft.enabled")));
            current.setKeycloakOidcEnabled("true".equals(propertyManager.readValue("sso.oidc.enabled")));
            current.setSamlEnabled("true".equals(propertyManager.readValue("sso.saml.enabled")));
            String issuer = propertyManager.readValue("oidc.issuer");
            if (issuer != null) current.setKeycloakIssuerUrl(issuer);
            String oidcClientId = propertyManager.readValue("oidc.clientId");
            if (oidcClientId != null) current.setKeycloakClientId(oidcClientId);
            // SAML configuration
            String samlSsoUrl = propertyManager.readValue("saml.idp.sso.url");
            if (samlSsoUrl != null) current.setSamlIdpSsoUrl(samlSsoUrl);
            String samlSpEntityId = propertyManager.readValue("saml.sp.entity.id");
            if (samlSpEntityId != null) current.setSamlSpEntityId(samlSpEntityId);
            // Return "[configured]" placeholder for certificate (never expose PEM)
            String samlCert = propertyManager.readValue("saml.idp.certificate");
            if (samlCert != null && !samlCert.trim().isEmpty()) current.setSamlIdpCertificate("[configured]");
            String samlSloUrl = propertyManager.readValue("saml.slo.url");
            if (samlSloUrl != null) current.setSamlSloUrl(samlSloUrl);
            String samlAttrMapping = propertyManager.readValue("saml.attribute.mapping");
            if (samlAttrMapping != null) current.setSamlAttributeMapping(samlAttrMapping);
        } else {
            current.setPasswordEnabled(true);
        }
        return Response.ok(current).build();
    }

    /**
     * POST /auth/test-oidc -- test OIDC discovery endpoint.
     */
    @POST
    @Path("/test-oidc")
    public Response testOidc(OidcTestRequest req) {
        if (req == null || req.getIssuerUrl() == null || req.getIssuerUrl().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"issuerUrl is required\"}")
                    .build();
        }

        String urlError = UrlValidator.validate(req.getIssuerUrl(), true);
        if (urlError != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + CouchDbConfigWriter.escapeJson(urlError) + "\"}")
                    .build();
        }

        try {
            String discoveryUrl = req.getIssuerUrl().replaceAll("/+$", "")
                    + "/.well-known/openid-configuration";
            java.net.URL url = new java.net.URL(discoveryUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();

            if (code != 200) {
                conn.disconnect();
                return Response.ok("{\"reachable\":false,\"error\":\"OIDC discovery endpoint not available\"}").build();
            }

            // Parse discovery document to extract token_endpoint. Cap the body so
            // a hostile/misbehaving OIDC endpoint cannot exhaust the heap.
            String responseBody;
            try (var is = conn.getInputStream()) {
                responseBody = new String(
                        jp.aegif.nemaki.util.io.BoundedIO.readBounded(is, 1024 * 1024, "OIDC discovery document"),
                        StandardCharsets.UTF_8);
            }
            conn.disconnect();

            String authorizationEndpoint = extractJsonString(responseBody, "authorization_endpoint");

            // If clientId is provided, probe the authorization endpoint to validate it.
            // The token endpoint approach doesn't work because Google/Microsoft return
            // "invalid_grant" (code invalid) before checking the client_id, making all
            // client IDs appear valid.
            if (authorizationEndpoint != null) {
                String authEndpointError = UrlValidator.validate(authorizationEndpoint, true);
                if (authEndpointError != null) {
                    logger.warning("Authorization endpoint URL blocked by SSRF filter: " + authorizationEndpoint);
                    authorizationEndpoint = null;
                }
            }
            String clientId = req.getClientId();
            if (clientId != null && !clientId.trim().isEmpty() && authorizationEndpoint != null) {
                try {
                    Boolean clientIdValid = probeAuthorizationEndpoint(authorizationEndpoint, clientId.trim());
                    String clientIdJson = clientIdValid != null
                            ? ",\"clientIdValid\":" + clientIdValid
                            : ",\"clientIdIndeterminate\":true,\"clientIdError\":\"Unable to determine client ID validity\"";
                    return Response.ok("{\"reachable\":true,\"discoveryUrl\":\"" + CouchDbConfigWriter.escapeJson(discoveryUrl) + "\"" + clientIdJson + "}").build();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Authorization endpoint probe failed", e);
                    return Response.ok("{\"reachable\":true,\"discoveryUrl\":\"" + CouchDbConfigWriter.escapeJson(discoveryUrl) + "\",\"clientIdError\":\"" + CouchDbConfigWriter.escapeJson(e.getMessage()) + "\"}").build();
                }
            }

            return Response.ok("{\"reachable\":true,\"discoveryUrl\":\"" + CouchDbConfigWriter.escapeJson(discoveryUrl) + "\"}").build();
        } catch (Exception e) {
            return Response.ok("{\"reachable\":false,\"error\":\"Connection failed\"}").build();
        }
    }

    /**
     * POST /auth/apply -- persist authentication settings to nemaki_conf.
     */
    @POST
    @Path("/apply")
    public Response apply(AuthConfigRequest req) {
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"request body is required\"}")
                    .build();
        }

        // Lockout prevention: at least one auth method must remain enabled
        if (!req.isPasswordEnabled() && !req.isGoogleEnabled() && !req.isMicrosoftEnabled()
                && !req.isKeycloakOidcEnabled() && !req.isSamlEnabled()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"At least one authentication method must be enabled\"}")
                    .build();
        }

        // Require clientId when OIDC provider is enabled
        if (req.isGoogleEnabled() && (req.getGoogleClientId() == null || req.getGoogleClientId().trim().isEmpty())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Google Client ID is required when Google authentication is enabled\"}")
                    .build();
        }
        if (req.isMicrosoftEnabled() && (req.getMicrosoftClientId() == null || req.getMicrosoftClientId().trim().isEmpty())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Microsoft Client ID is required when Microsoft authentication is enabled\"}")
                    .build();
        }
        if (req.isKeycloakOidcEnabled()) {
            if (req.getKeycloakIssuerUrl() == null || req.getKeycloakIssuerUrl().trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Issuer URL is required when Keycloak OIDC is enabled\"}")
                        .build();
            }
            if (req.getKeycloakClientId() == null || req.getKeycloakClientId().trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Client ID is required when Keycloak OIDC is enabled\"}")
                        .build();
            }
        }

        // Resolve CouchDB connection from system properties or defaults.
        // Keys match PropertyManager / StartupProbeService lookup keys.
        String couchUrl = System.getProperty("db.couchdb.url", "http://couchdb:5984");
        String couchUser = System.getProperty("db.couchdb.auth.username", "admin");
        String couchPass = System.getProperty("db.couchdb.auth.password", "password");
        String authHeader = CouchDbConfigWriter.basicAuth(couchUser, couchPass);

        // Same CouchDB floor the main /apply enforces. This endpoint writes nemaki_conf, and
        // Setup Mode is exactly the situation where the startup gate never ran (CouchDB was down
        // at boot) — so an unsupported server would otherwise take writes here. Unreachable is
        // left to the writer's own error handling; only a reachable-but-unsupported server is
        // refused, mirroring the startup semantics.
        if (startupProbeService != null) {
            CouchDbConnectionResult conn = startupProbeService.testConnection(couchUrl, couchUser, couchPass);
            if (conn != null && conn.isReachable()) {
                String versionRefusal = startupProbeService.couchDbVersionRefusal(conn);
                if (versionRefusal != null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"error\":\"" + CouchDbConfigWriter.escapeJson(versionRefusal) + "\"}")
                            .build();
                }
            }
        }

        try {
            CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "auth.password.enabled", String.valueOf(req.isPasswordEnabled()));
            CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.google.enabled", String.valueOf(req.isGoogleEnabled()));
            CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.microsoft.enabled", String.valueOf(req.isMicrosoftEnabled()));
            // Mirror to system properties for PropertyManager's System.getProperty() path.
            // ContentDaoServiceImpl.getConfiguration() is currently a stub, so the CouchDB
            // dynamic layer in PropertyManager does not read these keys at runtime.
            // System properties ensure immediate availability.
            System.setProperty("auth.password.enabled", String.valueOf(req.isPasswordEnabled()));
            System.setProperty("cloud.auth.google.enabled", String.valueOf(req.isGoogleEnabled()));
            System.setProperty("cloud.auth.microsoft.enabled", String.valueOf(req.isMicrosoftEnabled()));

            // Google OIDC settings
            if (req.isGoogleEnabled() && req.getGoogleClientId() != null) {
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.google.clientId", req.getGoogleClientId());
                System.setProperty("cloud.auth.google.clientId", req.getGoogleClientId());
            }
            // Microsoft OIDC settings
            if (req.isMicrosoftEnabled()) {
                if (req.getMicrosoftClientId() != null) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.microsoft.clientId", req.getMicrosoftClientId());
                    System.setProperty("cloud.auth.microsoft.clientId", req.getMicrosoftClientId());
                }
                if (req.getMicrosoftTenantId() != null) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.microsoft.tenantId", req.getMicrosoftTenantId());
                    System.setProperty("cloud.auth.microsoft.tenantId", req.getMicrosoftTenantId());
                }
            }

            // Keycloak OIDC settings
            CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "sso.oidc.enabled", String.valueOf(req.isKeycloakOidcEnabled()));
            System.setProperty("sso.oidc.enabled", String.valueOf(req.isKeycloakOidcEnabled()));
            if (req.isKeycloakOidcEnabled()) {
                if (req.getKeycloakIssuerUrl() != null) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "oidc.issuer", req.getKeycloakIssuerUrl().trim());
                    System.setProperty("oidc.issuer", req.getKeycloakIssuerUrl().trim());
                }
                if (req.getKeycloakClientId() != null) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "oidc.clientId", req.getKeycloakClientId().trim());
                    System.setProperty("oidc.clientId", req.getKeycloakClientId().trim());
                }
            }

            // SAML settings
            CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "sso.saml.enabled", String.valueOf(req.isSamlEnabled()));
            System.setProperty("sso.saml.enabled", String.valueOf(req.isSamlEnabled()));
            if (req.isSamlEnabled()) {
                // Validate required SAML fields
                if (req.getSamlIdpSsoUrl() == null || req.getSamlIdpSsoUrl().trim().isEmpty()) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"error\":\"IdP SSO URL is required when SAML is enabled\"}")
                            .build();
                }
                if (req.getSamlIdpCertificate() == null || req.getSamlIdpCertificate().trim().isEmpty()
                        || "[configured]".equals(req.getSamlIdpCertificate())) {
                    // "[configured]" means the certificate was not changed — check if already stored
                    String existing = propertyManager != null ? propertyManager.readValue("saml.idp.certificate") : null;
                    if ((existing == null || existing.trim().isEmpty()) && !"[configured]".equals(req.getSamlIdpCertificate())) {
                        return Response.status(Response.Status.BAD_REQUEST)
                                .entity("{\"error\":\"IdP signing certificate is required when SAML is enabled\"}")
                                .build();
                    }
                }
                // Persist SAML configuration
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.idp.sso.url", req.getSamlIdpSsoUrl().trim());
                System.setProperty("saml.idp.sso.url", req.getSamlIdpSsoUrl().trim());

                String spEntityId = req.getSamlSpEntityId() != null && !req.getSamlSpEntityId().trim().isEmpty()
                        ? req.getSamlSpEntityId().trim() : "nemakiware-sp";
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.sp.entity.id", spEntityId);
                System.setProperty("saml.sp.entity.id", spEntityId);

                // Only persist certificate if it's a real PEM (not the "[configured]" placeholder)
                if (req.getSamlIdpCertificate() != null && !"[configured]".equals(req.getSamlIdpCertificate())
                        && !req.getSamlIdpCertificate().trim().isEmpty()) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.idp.certificate", req.getSamlIdpCertificate().trim());
                    System.setProperty("saml.idp.certificate", req.getSamlIdpCertificate().trim());
                }

                if (req.getSamlSloUrl() != null && !req.getSamlSloUrl().trim().isEmpty()) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.slo.url", req.getSamlSloUrl().trim());
                    System.setProperty("saml.slo.url", req.getSamlSloUrl().trim());
                }
                if (req.getSamlAttributeMapping() != null && !req.getSamlAttributeMapping().trim().isEmpty()) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.attribute.mapping", req.getSamlAttributeMapping().trim());
                    System.setProperty("saml.attribute.mapping", req.getSamlAttributeMapping().trim());
                }
            }

            return Response.ok("{\"success\":true}").build();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to persist auth config", e);
            return Response.serverError()
                    .entity("{\"error\":\"Failed to persist authentication configuration\"}")
                    .build();
        }
    }

    /**
     * POST /auth/test-saml-certificate -- validate a PEM certificate.
     * Returns subject, issuer, validity dates on success.
     */
    @POST
    @Path("/test-saml-certificate")
    public Response testSamlCertificate(java.util.Map<String, String> req) {
        if (req == null || req.get("certificate") == null || req.get("certificate").trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"valid\":false,\"error\":\"certificate is required\"}")
                    .build();
        }
        try {
            // "[configured]" placeholder: resolve the actual certificate from CouchDB
            String certPem = req.get("certificate");
            if ("[configured]".equals(certPem)) {
                if (propertyManager == null) {
                    return Response.ok("{\"valid\":false,\"error\":\"PropertyManager not available\"}").build();
                }
                String stored = propertyManager.readValue("saml.idp.certificate");
                if (stored == null || stored.trim().isEmpty()) {
                    return Response.ok("{\"valid\":false,\"error\":\"No certificate stored in configuration\"}").build();
                }
                certPem = stored;
            }
            java.security.cert.X509Certificate cert =
                    jp.aegif.nemaki.rest.SamlSignatureVerifier.parseCertificate(certPem);
            String subject = cert.getSubjectX500Principal().getName();
            String issuer = cert.getIssuerX500Principal().getName();
            String notBefore = cert.getNotBefore().toInstant().toString();
            String notAfter = cert.getNotAfter().toInstant().toString();

            // Check if certificate is currently valid
            boolean expired;
            try {
                cert.checkValidity();
                expired = false;
            } catch (java.security.cert.CertificateExpiredException | java.security.cert.CertificateNotYetValidException e) {
                expired = true;
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"valid\":true");
            json.append(",\"subject\":\"").append(CouchDbConfigWriter.escapeJson(subject)).append("\"");
            json.append(",\"issuer\":\"").append(CouchDbConfigWriter.escapeJson(issuer)).append("\"");
            json.append(",\"notBefore\":\"").append(notBefore).append("\"");
            json.append(",\"notAfter\":\"").append(notAfter).append("\"");
            if (expired) {
                json.append(",\"warning\":\"Certificate is expired or not yet valid\"");
            }
            json.append("}");
            return Response.ok(json.toString()).build();
        } catch (Exception e) {
            return Response.ok("{\"valid\":false,\"error\":\"" + CouchDbConfigWriter.escapeJson(e.getMessage()) + "\"}").build();
        }
    }

    /**
     * Probe the authorization endpoint to validate the client ID.
     *
     * Sends a GET request to the authorization endpoint with the client_id.
     * OIDC providers return distinct errors for unknown client IDs:
     * - Google: HTTP 400 with "OAuth client was not found"
     * - Microsoft: HTTP 302 to error page with AADSTS700016 or HTTP 200 error page
     *
     * For valid client IDs, providers return HTTP 200 (login page) or HTTP 302
     * (redirect to login).
     *
     * @return true if client ID is valid, false if invalid, null if indeterminate
     */
    private Boolean probeAuthorizationEndpoint(String authorizationEndpoint, String clientId) throws Exception {
        String probeUrl = authorizationEndpoint
                + "?response_type=code"
                + "&client_id=" + java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&redirect_uri=" + java.net.URLEncoder.encode("http://localhost:0/callback", StandardCharsets.UTF_8)
                + "&scope=openid";

        java.net.URL url = new java.net.URL(probeUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);  // SSRF: prevent redirect to internal IPs
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");

        int code = conn.getResponseCode();
        String location = conn.getHeaderField("Location");

        // Read response body (limit to 8KB to avoid OOM from large login pages)
        String body;
        try (var is = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            if (is != null) {
                byte[] buf = is.readNBytes(8192);
                body = new String(buf, StandardCharsets.UTF_8);
            } else {
                body = "";
            }
        }
        conn.disconnect();

        // HTTP 400: check body to distinguish "invalid client" from "redirect_uri_mismatch".
        // Google returns 400 for both cases; a redirect_uri_mismatch means the client IS valid
        // but the dummy redirect_uri we used for probing is not registered.
        if (code == 400) {
            String bodyLower400 = body.toLowerCase();
            if (bodyLower400.contains("redirect_uri_mismatch") || bodyLower400.contains("redirect_uri")) {
                logger.info("Google: client ID valid (redirect_uri_mismatch confirms client exists)");
                return true;
            }
            return false;
        }

        // HTTP 302: check redirect URL for error indicators
        if (code == 302 && location != null) {
            // Google: redirects to /signin/oauth/error?authError=<base64-protobuf>
            // The authError contains the error type. We must distinguish:
            //   "invalid_client" (client not found) → false
            //   "redirect_uri_mismatch" (client IS valid, dummy redirect_uri not registered) → true
            if (location.contains("authError=")) {
                String decoded = decodeGoogleAuthError(location);
                if (decoded != null && (decoded.contains("redirect_uri_mismatch") || decoded.contains("redirect_uri"))) {
                    logger.info("Google: client ID valid (authError=redirect_uri_mismatch confirms client exists)");
                    return true;
                }
                // Other auth errors (invalid_client, etc.) → client ID is not valid
                return false;
            }
            if (location.contains("/error")) {
                return false;
            }
            // Microsoft: redirects with error= parameter for invalid clients (specific tenant)
            if (location.contains("error=unauthorized_client")
                    || location.contains("error=invalid_client")
                    || location.contains("AADSTS700016")) {
                return false;
            }
            // Microsoft defers client_id validation until after user authentication,
            // so a redirect to login page does NOT confirm client_id validity.
            if (authorizationEndpoint.contains("login.microsoftonline.com")) {
                logger.info("Microsoft: client ID validation deferred by provider");
                return null;
            }
            // Redirect to login page (no error) = client_id is recognized
            return true;
        }

        // HTTP 200: could be login page (valid) or error page (invalid)
        if (code == 200) {
            // Check for known error indicators in the response body
            String bodyLower = body.toLowerCase();
            if (bodyLower.contains("oauth client was not found")
                    || bodyLower.contains("aadsts700016")
                    || bodyLower.contains("application was not found")
                    || bodyLower.contains("invalid_client")
                    || bodyLower.contains("client_id is not valid")
                    || bodyLower.contains("autherror")) {
                return false;
            }
            // Microsoft returns login page for ANY client_id
            if (authorizationEndpoint.contains("login.microsoftonline.com")) {
                logger.info("Microsoft: client ID validation deferred by provider");
                return null;
            }
            // Login page displayed = client_id recognized by the provider
            return true;
        }

        // Other status codes: indeterminate
        logger.info("Authorization endpoint probe returned HTTP " + code + " — indeterminate");
        return null;
    }

    /**
     * Decode Google's authError query parameter from a redirect URL.
     * Google encodes the error as a base64 protobuf containing readable strings
     * like "invalid_client" or "redirect_uri_mismatch".
     *
     * @return decoded string containing error info, or null on failure
     */
    private static String decodeGoogleAuthError(String locationUrl) {
        try {
            int idx = locationUrl.indexOf("authError=");
            if (idx < 0) return null;
            String value = locationUrl.substring(idx + "authError=".length());
            int ampIdx = value.indexOf('&');
            if (ampIdx >= 0) value = value.substring(0, ampIdx);
            value = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(value);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to decode Google authError parameter", e);
            return null;
        }
    }

    /**
     * Simple JSON string value extractor (no dependency on JSON library).
     * Note: does not handle escaped quotes within values. Sufficient for
     * well-known OIDC fields (token_endpoint URLs, error codes).
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }
}
