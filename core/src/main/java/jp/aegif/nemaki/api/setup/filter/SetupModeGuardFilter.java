package jp.aegif.nemaki.api.setup.filter;

import java.io.IOException;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import jp.aegif.nemaki.init.StartupProbeService;

/**
 * Guard filter for Setup API.
 *
 * <p><b>Fail-closed</b> design:</p>
 * <ul>
 *   <li>If StartupProbeService is not available (null): block ALL endpoints</li>
 *   <li>Normal Mode (setupRequired=false): only {@code /state} is accessible</li>
 *   <li>Setup Mode (setupRequired=true): all Setup endpoints are accessible</li>
 * </ul>
 */
@Provider
@Component
public class SetupModeGuardFilter implements ContainerRequestFilter {

    private static final Logger logger = Logger.getLogger(SetupModeGuardFilter.class.getName());

    @Autowired(required = false)
    private StartupProbeService startupProbeService;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();

        // Fail-closed: if StartupProbeService is unavailable, block everything.
        // This prevents Setup endpoints from being open due to wiring failures.
        if (startupProbeService == null) {
            logger.severe("Setup API blocked: StartupProbeService not available (fail-closed)");
            requestContext.abortWith(
                    Response.status(Response.Status.SERVICE_UNAVAILABLE)
                            .type(MediaType.APPLICATION_JSON)
                            .entity("{\"error\":\"Setup service is not available\"}")
                            .build());
            return;
        }

        // Only the top-level /state endpoint is accessible without token.
        // It only reveals setupRequired flag and version (no sensitive config).
        if ("state".equals(path)) {
            return;
        }

        // If setup is not required (Normal Mode), block mutating Setup endpoints
        if (!startupProbeService.isSetupRequired()) {
            logger.warning("Setup API blocked in Normal Mode: " + path);
            requestContext.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                            .type(MediaType.APPLICATION_JSON)
                            .entity("{\"error\":\"Setup API is not available in Normal Mode\"}")
                            .build());
            return;
        }

        // Setup Mode: require X-Setup-Token header to prevent unauthenticated network scanning.
        // The token is generated at startup and logged to the console (Jupyter-style).
        String token = requestContext.getHeaderString("X-Setup-Token");
        String expectedToken = startupProbeService.getSetupToken();
        if (expectedToken == null || !expectedToken.equals(token)) {
            logger.warning("Setup API request rejected: invalid or missing X-Setup-Token for " + path);
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .type(MediaType.APPLICATION_JSON)
                            .entity("{\"error\":\"Valid X-Setup-Token header is required\"}")
                            .build());
        }
    }
}
