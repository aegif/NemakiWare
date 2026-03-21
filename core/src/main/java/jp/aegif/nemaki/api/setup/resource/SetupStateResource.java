package jp.aegif.nemaki.api.setup.resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import jp.aegif.nemaki.api.setup.model.SetupStateResponse;
import jp.aegif.nemaki.init.StartupProbeService;

/**
 * GET /state — returns setupRequired, currentState, serverVersion.
 */
@Component
@Path("/state")
@Produces(MediaType.APPLICATION_JSON)
public class SetupStateResource {

    static final String SERVER_VERSION = loadVersion();

    @Autowired(required = false)
    private StartupProbeService startupProbeService;

    @GET
    public Response getState() {
        SetupStateResponse resp = new SetupStateResponse();

        if (startupProbeService != null) {
            resp.setSetupRequired(startupProbeService.isSetupRequired());
            resp.setState(startupProbeService.getCurrentState().name());
        } else {
            resp.setSetupRequired(false);
            resp.setState("UNKNOWN");
        }

        resp.setServerVersion(SERVER_VERSION);
        resp.setCompletedSteps(new ArrayList<>());
        return Response.ok(resp).build();
    }

    private static String loadVersion() {
        try (InputStream is = SetupStateResource.class.getClassLoader()
                .getResourceAsStream("version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty("nemakiware.version");
                if (v != null && !v.trim().isEmpty() && !v.contains("${")) {
                    return v.trim();
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return "unknown";
    }
}
