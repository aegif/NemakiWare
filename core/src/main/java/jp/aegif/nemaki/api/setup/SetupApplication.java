package jp.aegif.nemaki.api.setup;

import java.util.logging.Logger;

import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spring.SpringLifecycleListener;
import org.glassfish.jersey.server.spring.scope.RequestContextFilter;

import jakarta.ws.rs.ApplicationPath;

/**
 * Jersey application for Setup Wizard API endpoints.
 * Mapped to /api/v1/setup/* in web.xml.
 */
@ApplicationPath("/api/v1/setup")
public class SetupApplication extends ResourceConfig {

    private static final Logger logger = Logger.getLogger(SetupApplication.class.getName());

    public SetupApplication() {
        logger.info("=== Initializing Setup Wizard API Application ===");

        // Jersey-Spring integration
        register(SpringLifecycleListener.class);
        register(RequestContextFilter.class);

        // JSON processing
        register(JacksonFeature.class);

        // Setup Mode guard filter
        register(jp.aegif.nemaki.api.setup.filter.SetupModeGuardFilter.class);

        // Package scanning for resource classes
        packages("jp.aegif.nemaki.api.setup.resource");

        logger.info("=== Setup Wizard API Application initialized ===");
    }
}
