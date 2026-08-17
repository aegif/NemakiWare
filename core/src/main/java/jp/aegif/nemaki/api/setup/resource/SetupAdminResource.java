package jp.aegif.nemaki.api.setup.resource;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.model.AdminSetupRequest;
import jp.aegif.nemaki.init.StartupProbeService;
import jp.aegif.nemaki.config.ObjectMapperFactory;

/**
 * Admin account setup endpoints.
 */
@Component
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupAdminResource {

    private static final Logger logger = Logger.getLogger(SetupAdminResource.class.getName());
    private static final ObjectMapper mapper = ObjectMapperFactory.createDefaultObjectMapper();

    @Autowired(required = false)
    private StartupProbeService startupProbeService;

    /**
     * POST /admin/change-password -- change admin password (BCrypt hash).
     * Updates admin user in all dynamically discovered main repository DBs.
     */
    @POST
    @Path("/change-password")
    public Response changePassword(AdminSetupRequest req) {
        if (req == null || req.getNewPassword() == null || req.getNewPassword().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"newPassword is required\"}")
                    .build();
        }

        String newPassword = req.getNewPassword().trim();
        if (newPassword.length() < 8) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Password must be at least 8 characters\"}")
                    .build();
        }

        // Keys match PropertyManager / StartupProbeService lookup keys.
        String couchUrl = System.getProperty("db.couchdb.url", "http://couchdb:5984");
        String couchUser = System.getProperty("db.couchdb.auth.username", "admin");
        String couchPass = System.getProperty("db.couchdb.auth.password", "password");
        String authHeader = CouchDbConfigWriter.basicAuth(couchUser, couchPass);

        // Same CouchDB floor the main /apply enforces — this endpoint writes admin documents
        // into every main repository DB. Reachable-but-unsupported is refused; unreachable is
        // left to the discovery/update calls' own error handling (startup semantics).
        if (startupProbeService != null) {
            jp.aegif.nemaki.init.CouchDbConnectionResult conn =
                    startupProbeService.testConnection(couchUrl, couchUser, couchPass);
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
            String bcryptHash = org.mindrot.jbcrypt.BCrypt.hashpw(
                    newPassword, org.mindrot.jbcrypt.BCrypt.gensalt());

            // Discover main repository DBs dynamically
            List<String> mainDbs = discoverMainRepositoryDbs(couchUrl, couchUser, couchPass);
            logger.info("changePassword: discovered main repos: " + mainDbs);

            int updatedCount = 0;
            StringBuilder perDb = new StringBuilder();
            StringBuilder failed = new StringBuilder();
            for (String dbName : mainDbs) {
                boolean ok = updateAdminInDb(couchUrl, dbName, authHeader, bcryptHash);
                if (ok) {
                    updatedCount++;
                } else if (failed.length() < 512) {
                    if (failed.length() > 0) failed.append(", ");
                    failed.append(dbName);
                }
                if (perDb.length() > 0) perDb.append(",");
                perDb.append("{\"db\":\"").append(CouchDbConfigWriter.escapeJson(dbName))
                        .append("\",\"updated\":").append(ok).append("}");
            }

            if (updatedCount == 0) {
                return Response.serverError()
                        .entity("{\"error\":\"Admin user document not found in any repository\"}")
                        .build();
            }

            logger.info("Admin password updated in " + updatedCount + "/" + mainDbs.size() + " repositories");
            // A partial update used to be reported as a bare success, silently — one repo could
            // keep the OLD admin password with nothing telling the operator. Success semantics
            // are unchanged (>= 1 updated), but the response now carries the per-DB outcome and
            // an explicit warning naming what was left behind.
            StringBuilder body = new StringBuilder("{\"success\":true,\"results\":[").append(perDb).append("]");
            if (failed.length() > 0) {
                body.append(",\"warning\":\"admin password NOT updated in: ")
                        .append(CouchDbConfigWriter.escapeJson(failed.toString()))
                        .append(" — these repositories still accept the previous password\"");
            }
            body.append("}");
            return Response.ok(body.toString()).build();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to change admin password", e);
            return Response.serverError()
                    .entity("{\"error\":\"Failed to change admin password\"}")
                    .build();
        }
    }

    /**
     * Find and update admin user in a single DB. Returns true if admin was found and updated.
     */
    // Protected so the gate test can stub the HTTP edges without a container.
    protected boolean updateAdminInDb(String couchUrl, String dbName, String authHeader, String bcryptHash) {
        try {
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
            JsonNode adminDoc = null;

            if (result.has("rows")) {
                for (JsonNode row : result.get("rows")) {
                    JsonNode doc = row.get("value");
                    if (doc != null && doc.has("userId") && "admin".equals(doc.get("userId").asText())) {
                        adminDocId = doc.get("_id").asText();
                        adminDoc = doc;
                        break;
                    }
                }
            }

            if (adminDocId == null || adminDoc == null) {
                return false;
            }

            ObjectNode mutable = (ObjectNode) adminDoc.deepCopy();
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
                logger.warning("Failed to update admin in " + dbName + ": HTTP " + putCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.warning("Error updating admin in " + dbName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Discover main repository DB names using repositories.yml via StartupProbeService.
     */
    protected List<String> discoverMainRepositoryDbs(String couchUrl, String user, String pass) {
        if (startupProbeService != null) {
            List<String> allDbs = startupProbeService.discoverNemakiDatabases(couchUrl, user, pass);
            return allDbs.stream()
                    .filter(db -> startupProbeService.isMainRepository(db))
                    .toList();
        }
        return List.of("bedroom", "canopy");
    }
}
