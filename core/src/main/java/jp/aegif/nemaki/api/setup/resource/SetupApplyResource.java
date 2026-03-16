package jp.aegif.nemaki.api.setup.resource;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.filter.UrlValidator;
import jp.aegif.nemaki.api.setup.model.SetupApplyRequest;
import jp.aegif.nemaki.api.setup.model.CouchDbConnectionRequest;
import jp.aegif.nemaki.api.setup.model.AuthConfigRequest;
import jp.aegif.nemaki.api.setup.model.VectorTestRequest;
import jp.aegif.nemaki.init.CMISPostInitializer;
import jp.aegif.nemaki.init.CouchDbConnectionResult;
import jp.aegif.nemaki.init.DatabasePreInitializer;
import jp.aegif.nemaki.init.StartupProbeService;
import jp.aegif.nemaki.patch.PatchService;

/**
 * POST /apply -- one-shot setup: validate settings, run DB init, persist config, clear setupRequired.
 */
@Component
@Path("/apply")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupApplyResource {

    private static final Logger logger = Logger.getLogger(SetupApplyResource.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private StartupProbeService startupProbeService;

    @Autowired(required = false)
    private DatabasePreInitializer databasePreInitializer;

    @Autowired(required = false)
    private CMISPostInitializer cmisPostInitializer;

    @Autowired(required = false)
    private PatchService patchService;

    /**
     * POST /apply/mark-complete -- explicitly mark setup as complete.
     * Called by UI when warnings were present but user chooses to proceed.
     *
     * Uses reprobeState() (not reprobe()) to verify DB readiness WITHOUT
     * flipping setupRequired. This keeps Setup Mode open if deferred init
     * fails, allowing the user to retry. Only markSetupComplete() at the
     * end transitions to Normal Mode.
     */
    @POST
    @Path("/mark-complete")
    public Response markComplete() {
        if (startupProbeService == null) {
            return error(500, "StartupProbeService not available");
        }
        startupProbeService.reprobeState();

        // Verify reprobe result: only complete setup if DB is actually ready
        StartupProbeService.StartupState state = startupProbeService.getCurrentState();
        if (state != StartupProbeService.StartupState.DB_CONNECTED_CURRENT) {
            logger.warning("mark-complete: reprobe returned " + state + " — refusing to mark complete");
            return error(400, "Cannot complete setup: database state is " + state
                    + ". Ensure CouchDB is running and databases are properly initialized.");
        }

        // Trigger deferred Phase 2/3 initialization BEFORE marking complete.
        // If patches fail, do not mark setup complete — user can retry.
        List<String> initFailures = triggerDeferredInitialization();
        if (!initFailures.isEmpty()) {
            logger.warning("mark-complete: deferred initialization failed — " + initFailures);
            StringBuilder json = new StringBuilder();
            json.append("{\"success\":false,\"error\":\"Deferred initialization failed\",\"failures\":[");
            for (int i = 0; i < initFailures.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(CouchDbConfigWriter.escapeJson(initFailures.get(i))).append("\"");
            }
            json.append("]}");
            return Response.status(500).entity(json.toString()).build();
        }

        startupProbeService.markSetupComplete();
        logger.info("Setup explicitly marked complete by user (deferred reprobe + init + markComplete)");

        return Response.ok("{\"success\":true}").build();
    }

    @POST
    public Response apply(SetupApplyRequest req) {
        logger.info("Setup apply requested");

        if (startupProbeService == null) {
            return error(500, "StartupProbeService not available");
        }

        // 1. Validate CouchDB config (required)
        if (req == null || req.getCouchdb() == null) {
            return error(400, "CouchDB configuration is required");
        }
        CouchDbConnectionRequest couch = req.getCouchdb();
        if (couch.getUrl() == null || couch.getUrl().trim().isEmpty()) {
            return error(400, "CouchDB URL is required");
        }
        String couchUrl = couch.getUrl().trim();
        String couchUser = couch.getUsername() != null ? couch.getUsername() : "admin";
        String couchPass = couch.getPassword() != null ? couch.getPassword() : "";

        // 1b. SSRF prevention (same policy as /couchdb/test-connection)
        String urlError = UrlValidator.validate(couchUrl, true);
        if (urlError != null) {
            return error(400, urlError);
        }

        // 2. Test CouchDB connection
        CouchDbConnectionResult connResult = startupProbeService.testConnection(couchUrl, couchUser, couchPass);
        if (!connResult.isReachable()) {
            return error(400, "CouchDB is not reachable: " + connResult.getErrorMessage());
        }
        logger.info("CouchDB connection verified: " + connResult.getCouchDbVersion());

        // 3. Set system properties for CouchDB (runtime override).
        // Keys MUST match PropertyManager / StartupProbeService lookup keys:
        //   db.couchdb.url, db.couchdb.auth.username, db.couchdb.auth.password
        // PropertyManager.readValue() checks System.getProperty(key) first,
        // so these overrides propagate to all downstream consumers.
        System.setProperty("db.couchdb.url", couchUrl);
        System.setProperty("db.couchdb.auth.username", couchUser);
        System.setProperty("db.couchdb.auth.password", couchPass);

        // 4. Initialize databases
        if (databasePreInitializer != null) {
            try {
                databasePreInitializer.initializeDatabases(couchUrl, couchUser, couchPass);
                logger.info("Database initialization completed");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Database initialization failed", e);
                return error(500, "Database initialization failed: " + e.getMessage());
            }
        } else {
            logger.warning("DatabasePreInitializer not available, skipping DB init");
        }

        String authHeader = CouchDbConfigWriter.basicAuth(couchUser, couchPass);
        List<String> warnings = new ArrayList<>();

        // 5. Persist auth settings to nemaki_conf
        AuthConfigRequest auth = req.getAuth();
        if (auth != null) {
            // Lockout prevention: same guard as /auth/apply
            if (!auth.isPasswordEnabled() && !auth.isGoogleEnabled() && !auth.isMicrosoftEnabled()
                    && !auth.isKeycloakOidcEnabled() && !auth.isSamlEnabled()) {
                return error(400, "At least one authentication method must be enabled");
            }
            // Require clientId when OIDC provider is enabled (mirrors /auth/apply)
            if (auth.isGoogleEnabled() && (auth.getGoogleClientId() == null || auth.getGoogleClientId().trim().isEmpty())) {
                return error(400, "Google Client ID is required when Google authentication is enabled");
            }
            if (auth.isMicrosoftEnabled() && (auth.getMicrosoftClientId() == null || auth.getMicrosoftClientId().trim().isEmpty())) {
                return error(400, "Microsoft Client ID is required when Microsoft authentication is enabled");
            }
            if (auth.isKeycloakOidcEnabled()) {
                if (auth.getKeycloakIssuerUrl() == null || auth.getKeycloakIssuerUrl().trim().isEmpty()) {
                    return error(400, "Issuer URL is required when Keycloak OIDC is enabled");
                }
                if (auth.getKeycloakClientId() == null || auth.getKeycloakClientId().trim().isEmpty()) {
                    return error(400, "Client ID is required when Keycloak OIDC is enabled");
                }
            }
            if (auth.isSamlEnabled()) {
                if (auth.getSamlIdpSsoUrl() == null || auth.getSamlIdpSsoUrl().trim().isEmpty()) {
                    return error(400, "IdP SSO URL is required when SAML is enabled");
                }
                if (auth.getSamlIdpCertificate() == null || auth.getSamlIdpCertificate().trim().isEmpty()) {
                    return error(400, "IdP signing certificate is required when SAML is enabled");
                }
                // "[configured]" placeholder: verify that a real certificate is already stored
                if ("[configured]".equals(auth.getSamlIdpCertificate())) {
                    try {
                        String stored = CouchDbConfigWriter.getConfigValue(couchUrl, authHeader, "saml.idp.certificate");
                        if (stored == null) {
                            return error(400, "IdP signing certificate placeholder sent but no certificate is stored");
                        }
                    } catch (Exception e) {
                        logger.log(java.util.logging.Level.WARNING, "Failed to verify stored SAML certificate", e);
                        return error(400, "Unable to verify stored SAML certificate: " + e.getMessage());
                    }
                }
            }
            try {
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "auth.password.enabled", String.valueOf(auth.isPasswordEnabled()));
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.google.enabled", String.valueOf(auth.isGoogleEnabled()));
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.microsoft.enabled", String.valueOf(auth.isMicrosoftEnabled()));
                // Mirror to system properties for PropertyManager's System.getProperty() path
                System.setProperty("auth.password.enabled", String.valueOf(auth.isPasswordEnabled()));
                System.setProperty("cloud.auth.google.enabled", String.valueOf(auth.isGoogleEnabled()));
                System.setProperty("cloud.auth.microsoft.enabled", String.valueOf(auth.isMicrosoftEnabled()));
                // Google OIDC settings
                if (auth.isGoogleEnabled() && auth.getGoogleClientId() != null) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.google.clientId", auth.getGoogleClientId());
                    System.setProperty("cloud.auth.google.clientId", auth.getGoogleClientId());
                }
                // Microsoft OIDC settings
                if (auth.isMicrosoftEnabled()) {
                    if (auth.getMicrosoftClientId() != null) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.microsoft.clientId", auth.getMicrosoftClientId());
                        System.setProperty("cloud.auth.microsoft.clientId", auth.getMicrosoftClientId());
                    }
                    if (auth.getMicrosoftTenantId() != null) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "cloud.auth.microsoft.tenantId", auth.getMicrosoftTenantId());
                        System.setProperty("cloud.auth.microsoft.tenantId", auth.getMicrosoftTenantId());
                    }
                }
                // Keycloak OIDC settings
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "sso.oidc.enabled", String.valueOf(auth.isKeycloakOidcEnabled()));
                System.setProperty("sso.oidc.enabled", String.valueOf(auth.isKeycloakOidcEnabled()));
                if (auth.isKeycloakOidcEnabled()) {
                    if (auth.getKeycloakIssuerUrl() != null) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "oidc.issuer", auth.getKeycloakIssuerUrl().trim());
                        System.setProperty("oidc.issuer", auth.getKeycloakIssuerUrl().trim());
                    }
                    if (auth.getKeycloakClientId() != null) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "oidc.clientId", auth.getKeycloakClientId().trim());
                        System.setProperty("oidc.clientId", auth.getKeycloakClientId().trim());
                    }
                }
                // SAML settings
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "sso.saml.enabled", String.valueOf(auth.isSamlEnabled()));
                System.setProperty("sso.saml.enabled", String.valueOf(auth.isSamlEnabled()));
                if (auth.isSamlEnabled()) {
                    if (auth.getSamlIdpSsoUrl() != null && !auth.getSamlIdpSsoUrl().trim().isEmpty()) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.idp.sso.url", auth.getSamlIdpSsoUrl().trim());
                        System.setProperty("saml.idp.sso.url", auth.getSamlIdpSsoUrl().trim());
                    }
                    String spEntityId = auth.getSamlSpEntityId() != null && !auth.getSamlSpEntityId().trim().isEmpty()
                            ? auth.getSamlSpEntityId().trim() : "nemakiware-sp";
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.sp.entity.id", spEntityId);
                    System.setProperty("saml.sp.entity.id", spEntityId);
                    if (auth.getSamlIdpCertificate() != null && !"[configured]".equals(auth.getSamlIdpCertificate())
                            && !auth.getSamlIdpCertificate().trim().isEmpty()) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.idp.certificate", auth.getSamlIdpCertificate().trim());
                        System.setProperty("saml.idp.certificate", auth.getSamlIdpCertificate().trim());
                    }
                    if (auth.getSamlSloUrl() != null && !auth.getSamlSloUrl().trim().isEmpty()) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.slo.url", auth.getSamlSloUrl().trim());
                        System.setProperty("saml.slo.url", auth.getSamlSloUrl().trim());
                    }
                    if (auth.getSamlAttributeMapping() != null && !auth.getSamlAttributeMapping().trim().isEmpty()) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "saml.attribute.mapping", auth.getSamlAttributeMapping().trim());
                        System.setProperty("saml.attribute.mapping", auth.getSamlAttributeMapping().trim());
                    }
                }
                logger.info("Auth configuration persisted");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to persist auth config", e);
                warnings.add("Auth configuration not saved: " + e.getMessage());
            }
        }

        // 6. Persist vector settings to nemaki_conf
        VectorTestRequest vector = req.getVector();
        if (vector != null) {
            // 6a. Validate required fields before persisting (mirrors SetupVectorResource.apply())
            if ("tei".equals(vector.getType())) {
                if (vector.getUrl() == null || vector.getUrl().trim().isEmpty()) {
                    return error(400, "url is required for TEI provider");
                }
                String vectorUrlError = UrlValidator.validate(vector.getUrl(), true);
                if (vectorUrlError != null) {
                    return error(400, "Vector URL: " + vectorUrlError);
                }
            }
            if ("bedrock".equals(vector.getType())) {
                if (vector.getRegion() == null || vector.getRegion().trim().isEmpty()) {
                    return error(400, "region is required for Bedrock provider");
                }
            }
            try {
                boolean ragEnabled = "tei".equals(vector.getType()) || "bedrock".equals(vector.getType());
                CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.enabled", String.valueOf(ragEnabled));
                System.setProperty("rag.enabled", String.valueOf(ragEnabled));

                if (ragEnabled) {
                    String provider = "bedrock".equals(vector.getType()) ? "bedrock" : "tei";
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.embedding.provider", provider);
                    System.setProperty("rag.embedding.provider", provider);
                }

                // TEI settings
                if (vector.getUrl() != null && !vector.getUrl().isEmpty()) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.tei.url", vector.getUrl());
                    System.setProperty("rag.tei.url", vector.getUrl());
                }

                // When switching away from Bedrock, clear residual Bedrock credentials
                // from CouchDB and rag.bedrock.* system properties.
                // Note: we deliberately do NOT touch aws.accessKeyId / aws.secretAccessKey
                // because those are JVM-global and may be set by the operator for other
                // AWS integrations (e.g. S3StorageAdapter). BedrockEmbeddingService reads
                // rag.bedrock.* directly via RAGConfig, so aws.* is never needed here.
                if (!"bedrock".equals(vector.getType())) {
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.access.key.id", "");
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.secret.access.key", "");
                    System.clearProperty("rag.bedrock.access.key.id");
                    System.clearProperty("rag.bedrock.secret.access.key");
                }

                // Bedrock settings
                if ("bedrock".equals(vector.getType())) {
                    if (vector.getRegion() != null && !vector.getRegion().isEmpty()) {
                        CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.region", vector.getRegion());
                        System.setProperty("rag.bedrock.region", vector.getRegion());
                    }
                    String modelId = vector.getModelId();
                    if (modelId == null || modelId.isEmpty()) {
                        modelId = "amazon.titan-embed-text-v2:0";
                    }
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.model.id", modelId);
                    System.setProperty("rag.bedrock.model.id", modelId);

                    // Always persist credential values (even empty) so that clearing
                    // the fields in the UI reverts to IAM role / default credential chain.
                    String accessKeyId = vector.getAccessKeyId() != null ? vector.getAccessKeyId().trim() : "";
                    String secretAccessKey = vector.getSecretAccessKey() != null ? vector.getSecretAccessKey().trim() : "";

                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.access.key.id", accessKeyId);
                    CouchDbConfigWriter.putConfigValue(couchUrl, authHeader, "rag.bedrock.secret.access.key", secretAccessKey);

                    if (!accessKeyId.isEmpty()) {
                        System.setProperty("rag.bedrock.access.key.id", accessKeyId);
                    } else {
                        System.clearProperty("rag.bedrock.access.key.id");
                    }
                    if (!secretAccessKey.isEmpty()) {
                        System.setProperty("rag.bedrock.secret.access.key", secretAccessKey);
                    } else {
                        System.clearProperty("rag.bedrock.secret.access.key");
                    }
                }

                logger.info("Vector configuration persisted");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to persist vector config", e);
                warnings.add("Vector configuration not saved: " + e.getMessage());
            }
        }

        // 7. Change admin password if requested
        if (req.getAdminPassword() != null && !req.getAdminPassword().trim().isEmpty()) {
            String adminPass = req.getAdminPassword().trim();
            if (adminPass.length() < 8) {
                return error(400, "Admin password must be at least 8 characters");
            }
            try {
                changeAdminPassword(couchUrl, authHeader, adminPass);
                logger.info("Admin password changed");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to change admin password", e);
                warnings.add("Admin password not changed: " + e.getMessage());
            }
        }

        // 8. Verify DB state + deferred init + mark complete only when there are NO warnings.
        // Uses reprobeState() (not reprobe()) to avoid flipping setupRequired prematurely.
        // Setup Mode stays open until markSetupComplete() is explicitly called after all
        // initialization phases succeed, so the user can retry if deferred init fails.
        boolean setupComplete;
        if (warnings.isEmpty()) {
            startupProbeService.reprobeState();
            // Verify reprobe result before completing
            StartupProbeService.StartupState state = startupProbeService.getCurrentState();
            if (state == StartupProbeService.StartupState.DB_CONNECTED_CURRENT) {
                // Trigger deferred Phase 2/3 initialization BEFORE marking complete.
                List<String> initFailures = triggerDeferredInitialization();
                if (initFailures.isEmpty()) {
                    startupProbeService.markSetupComplete();
                    setupComplete = true;
                } else {
                    logger.warning("apply: deferred initialization failed — " + initFailures);
                    warnings.addAll(initFailures);
                    setupComplete = false;
                }
            } else {
                logger.warning("apply: reprobe after DB init returned " + state + " — not marking complete");
                warnings.add("Database state after initialization: " + state + ". Setup not auto-completed.");
                setupComplete = false;
            }
        } else {
            setupComplete = false;
        }
        logger.info("Setup apply completed" + (warnings.isEmpty() ? " successfully" : " with warnings (setup NOT marked complete): " + warnings));

        // Build response — include warnings so UI can surface partial failures
        StringBuilder json = new StringBuilder();
        json.append("{\"success\":true");
        json.append(",\"setupComplete\":").append(setupComplete);
        if (warnings.isEmpty()) {
            json.append(",\"message\":\"Setup completed successfully\"");
        } else {
            json.append(",\"message\":\"Setup completed with warnings\"");
            json.append(",\"warnings\":[");
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(CouchDbConfigWriter.escapeJson(warnings.get(i))).append("\"");
            }
            json.append("]");
        }
        json.append("}");
        return Response.ok(json.toString()).build();
    }

    // --- Admin password change (unique to this resource) ---

    /**
     * Change admin password in all main repository DBs.
     * Discovers repositories dynamically via StartupProbeService, then finds
     * and updates the admin user doc in each main repository.
     */
    private void changeAdminPassword(String couchUrl, String authHeader, String newPassword) throws Exception {
        String bcryptHash = org.mindrot.jbcrypt.BCrypt.hashpw(
                newPassword, org.mindrot.jbcrypt.BCrypt.gensalt());

        // Discover main repository DBs dynamically
        List<String> mainDbs = discoverMainRepositoryDbs(couchUrl, authHeader);
        logger.info("changeAdminPassword: discovered main repos: " + mainDbs);

        int updatedCount = 0;
        Exception lastException = null;

        for (String dbName : mainDbs) {
            try {
                if (updateAdminInDb(couchUrl, dbName, authHeader, bcryptHash)) {
                    updatedCount++;
                }
            } catch (Exception e) {
                logger.warning("changeAdminPassword: failed in " + dbName + ": " + e.getMessage());
                lastException = e;
            }
        }

        if (updatedCount == 0) {
            throw lastException != null ? lastException
                    : new RuntimeException("Admin user document not found in any repository");
        }
        logger.info("changeAdminPassword: updated admin in " + updatedCount + " repositories");
    }

    /**
     * Find and update admin user in a single DB. Returns true if admin was found and updated.
     */
    private boolean updateAdminInDb(String couchUrl, String dbName, String authHeader, String bcryptHash) throws Exception {
        String dbUrl = couchUrl + "/" + dbName;
        String viewUrl = dbUrl + "/_design/_repo/_view/admin?key=\"admin\"&reduce=false";
        HttpURLConnection conn = CouchDbConfigWriter.openGet(viewUrl, authHeader);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return false;
        }
        String body = CouchDbConfigWriter.readResponse(conn);
        conn.disconnect();
        JsonNode result = mapper.readTree(body);

        String adminDocId = null;
        String adminDocBody = null;

        if (result.has("rows")) {
            for (JsonNode row : result.get("rows")) {
                JsonNode doc = row.get("value");
                if (doc != null && doc.has("userId") && "admin".equals(doc.get("userId").asText())) {
                    adminDocId = doc.get("_id").asText();
                    adminDocBody = mapper.writeValueAsString(doc);
                    break;
                }
            }
        }

        if (adminDocId == null) {
            return false;
        }

        JsonNode adminDoc = mapper.readTree(adminDocBody);
        com.fasterxml.jackson.databind.node.ObjectNode mutable = adminDoc.deepCopy();
        mutable.put("passwordHash", bcryptHash);

        URL url = new URL(dbUrl + "/" + adminDocId);
        HttpURLConnection putConn = (HttpURLConnection) url.openConnection();
        putConn.setRequestProperty("Authorization", authHeader);
        putConn.setRequestProperty("Content-Type", "application/json");
        putConn.setRequestMethod("PUT");
        putConn.setDoOutput(true);
        try (OutputStreamWriter out = new OutputStreamWriter(putConn.getOutputStream(), StandardCharsets.UTF_8)) {
            out.write(mapper.writeValueAsString(mutable));
        }
        int putCode = putConn.getResponseCode();
        putConn.disconnect();

        if (putCode != 201 && putCode != 200) {
            throw new RuntimeException("Failed to update admin password in " + dbName + ": HTTP " + putCode);
        }
        return true;
    }

    /**
     * Discover main repository DB names using repositories.yml via StartupProbeService.
     */
    private List<String> discoverMainRepositoryDbs(String couchUrl, String authHeader) {
        if (startupProbeService != null) {
            String user = System.getProperty("db.couchdb.auth.username", "admin");
            String pass = System.getProperty("db.couchdb.auth.password", "password");
            List<String> allDbs = startupProbeService.discoverNemakiDatabases(couchUrl, user, pass);
            return allDbs.stream()
                    .filter(db -> startupProbeService.isMainRepository(db))
                    .toList();
        }
        return List.of("bedroom", "canopy");
    }

    /**
     * Trigger Phase 2 (CMISPostInitializer) and Phase 3 (PatchService)
     * initialization that was deferred during Setup Mode.
     * These phases were skipped at startup because setupRequired=true,
     * and their one-shot guards were reset to allow re-execution.
     *
     * @return list of failure descriptions (empty = all succeeded)
     */
    private List<String> triggerDeferredInitialization() {
        logger.info("Triggering deferred Phase 2/3 initialization after setup completion");
        List<String> failures = new ArrayList<>();
        if (cmisPostInitializer == null) {
            logger.warning("CMISPostInitializer bean is not available — Phase 2 patches skipped");
            failures.add("Phase 2 (CMIS patches) skipped: CMISPostInitializer not available");
        } else {
            try {
                if (!cmisPostInitializer.executePatches()) {
                    failures.add("Phase 2 (CMIS patches) completed with failures");
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Deferred Phase 2 (CMIS patches) failed", e);
                failures.add("Phase 2 (CMIS patches) failed: " + e.getMessage());
            }
        }
        if (patchService == null) {
            logger.warning("PatchService bean is not available — Phase 3 patches skipped");
            failures.add("Phase 3 (PatchService) skipped: PatchService not available");
        } else {
            try {
                if (!patchService.executePatchService()) {
                    failures.add("Phase 3 (PatchService) completed with failures");
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Deferred Phase 3 (PatchService) failed", e);
                failures.add("Phase 3 (PatchService) failed: " + e.getMessage());
            }
        }
        return failures;
    }

    private Response error(int status, String message) {
        return Response.status(status)
                .entity("{\"error\":\"" + CouchDbConfigWriter.escapeJson(message) + "\"}")
                .build();
    }
}
