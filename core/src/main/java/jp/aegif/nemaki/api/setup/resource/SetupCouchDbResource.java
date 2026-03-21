package jp.aegif.nemaki.api.setup.resource;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.filter.UrlValidator;
import jp.aegif.nemaki.api.setup.model.CouchDbConnectionRequest;
import jp.aegif.nemaki.api.setup.model.MigrationRequest;
import jp.aegif.nemaki.init.CouchDbConnectionResult;
import jp.aegif.nemaki.init.RepositoryOverview;
import jp.aegif.nemaki.init.StartupProbeService;

/**
 * CouchDB setup endpoints: test-connection, probe, migrate.
 */
@Component
@Path("/couchdb")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupCouchDbResource {

    @Autowired(required = false)
    private StartupProbeService startupProbeService;

    /**
     * POST /couchdb/test-connection — test CouchDB connectivity with given credentials.
     */
    @POST
    @Path("/test-connection")
    public Response testConnection(CouchDbConnectionRequest req) {
        if (startupProbeService == null) {
            return Response.serverError()
                    .entity("{\"error\":\"StartupProbeService not available\"}")
                    .build();
        }
        if (req == null || req.getUrl() == null || req.getUrl().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"url is required\"}")
                    .build();
        }

        // SSRF prevention: allow private networks in Setup Mode (localhost, Docker IPs)
        String urlError = UrlValidator.validate(req.getUrl(), true);
        if (urlError != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + CouchDbConfigWriter.escapeJson(urlError) + "\"}")
                    .build();
        }

        String user = req.getUsername() != null ? req.getUsername() : "admin";
        String pass = req.getPassword() != null ? req.getPassword() : "";

        CouchDbConnectionResult result = startupProbeService.testConnection(req.getUrl(), user, pass);

        // SSRF hardening: sanitise error details to not leak internal network topology
        if (!result.isReachable() && result.getErrorMessage() != null) {
            result.setErrorMessage("Connection failed");
        }
        return Response.ok(result).build();
    }

    /**
     * POST /couchdb/probe — return repository overviews with current/legacy/empty judgment.
     */
    @POST
    @Path("/probe")
    public Response probe(CouchDbConnectionRequest req) {
        if (startupProbeService == null) {
            return Response.serverError()
                    .entity("{\"error\":\"StartupProbeService not available\"}")
                    .build();
        }
        if (req == null || req.getUrl() == null || req.getUrl().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"url is required\"}")
                    .build();
        }

        // SSRF prevention: allow private networks in Setup Mode (localhost, Docker IPs)
        String urlError = UrlValidator.validate(req.getUrl(), true);
        if (urlError != null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + CouchDbConfigWriter.escapeJson(urlError) + "\"}")
                    .build();
        }

        String user = req.getUsername() != null ? req.getUsername() : "admin";
        String pass = req.getPassword() != null ? req.getPassword() : "";

        List<RepositoryOverview> repos = startupProbeService.probeRepositories(req.getUrl(), user, pass);
        return Response.ok(repos).build();
    }

    /**
     * POST /couchdb/migrate — placeholder for legacy → current migration.
     * Not yet implemented; returns 501.
     */
    @POST
    @Path("/migrate")
    public Response migrate(MigrationRequest req) {
        if (req == null || !req.isConfirmed()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"confirmed=true is required\"}")
                    .build();
        }
        return Response.status(501)
                .entity("{\"error\":\"Migration is not yet implemented\",\"implemented\":false}")
                .build();
    }
}
