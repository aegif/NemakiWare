package jp.aegif.nemaki.api.v1;

import java.util.logging.Logger;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spring.SpringLifecycleListener;
import org.glassfish.jersey.server.spring.SpringComponentProvider;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.jackson.JacksonFeature;

import io.swagger.v3.jaxrs2.integration.resources.OpenApiResource;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.ws.rs.ApplicationPath;

@ApplicationPath("/api/v1/cmis")
@OpenAPIDefinition(
    info = @Info(
        title = "NemakiWare CMIS REST API",
        version = "1.0.0",
        description = "OpenAPI 3.0 compliant REST API for NemakiWare CMIS Repository. " +
                      "This API provides full access to CMIS operations including object management, " +
                      "versioning, navigation, and query capabilities. " +
                      "Note: This API is served at /api/v1/cmis/* to avoid conflict with legacy /api/v1/repo/* endpoints.",
        contact = @Contact(
            name = "NemakiWare Project",
            url = "https://github.com/aegif/NemakiWare"
        ),
        license = @License(
            name = "GNU AGPL v3",
            url = "https://www.gnu.org/licenses/agpl-3.0.html"
        )
    ),
    servers = {
        // Kept in sync with the AUTHORITATIVE value in the programmatic SwaggerConfiguration
        // below — this annotation is NOT scanned by OpenApiResource (that was 3.3.1 #8's root
        // cause), so a divergent value here is dead but misleading metadata.
        @Server(url = "/core", description = "NemakiWare servlet context")
    },
    tags = {
        @Tag(name = "repositories", description = "Repository management operations"),
        @Tag(name = "objects", description = "Generic object operations (canonical endpoint)"),
        @Tag(name = "documents", description = "Document-specific operations including versioning"),
        @Tag(name = "folders", description = "Folder-specific operations and navigation"),
        @Tag(name = "types", description = "Type definition management"),
        @Tag(name = "acl", description = "Access control list operations"),
        @Tag(name = "query", description = "CMIS query operations"),
        @Tag(name = "users", description = "User management operations"),
        @Tag(name = "groups", description = "Group management operations"),
        @Tag(name = "auth", description = "Authentication operations"),
        @Tag(name = "audit", description = "Audit logging metrics and monitoring"),
        @Tag(name = "search-engine", description = "Search engine (Solr) management operations"),
        @Tag(name = "API Keys", description = "API key management for programmatic authentication")
    }
)
public class ApiV1Application extends ResourceConfig {
    
    private static final Logger logger = Logger.getLogger(ApiV1Application.class.getName());

    public ApiV1Application() {
        logger.info("=== Initializing NemakiWare API v1 Application ===");
        
        // Enable Jersey-Spring integration
        register(SpringLifecycleListener.class);
        register(SpringComponentProvider.class);
        
        // Enable multipart support for content stream uploads
        register(MultiPartFeature.class);
        
        // Enable JSON processing with Jackson
        register(JacksonFeature.class);
        
        // Register custom Jackson provider for unified ObjectMapper
        register(jp.aegif.nemaki.api.v1.ApiJacksonProvider.class);
        
        // Register OpenAPI/Swagger resource for /openapi.json endpoint
        register(OpenApiResource.class);

        // The @OpenAPIDefinition on this class is NOT scanned by OpenApiResource (its scanner
        // covers the resource packages, not the Application class), so the served openapi.json
        // carried no `servers` — and Swagger UI's "Try it out" resolved every request against
        // the page origin, hitting /api/v1/... WITHOUT the /core context and getting a 404.
        // Found by actually executing from the UI (3.3.1 #8): none of the 129 operations had
        // ever been executable. Configure the context programmatically instead.
        try {
            io.swagger.v3.oas.models.OpenAPI oas = new io.swagger.v3.oas.models.OpenAPI()
                    .info(new io.swagger.v3.oas.models.info.Info()
                            .title("NemakiWare CMIS REST API")
                            .version("1.0.0")
                            .description("OpenAPI 3.0 compliant REST API for NemakiWare CMIS Repository."))
                    // "/core" ONLY — the servlet context. swagger-jaxrs2 already prepends the
                    // @ApplicationPath (/api/v1/cmis) to every path in the spec, so a server URL
                    // carrying it too doubles the segment (…/core/api/v1/cmis/api/v1/cmis/…),
                    // which the auth filter then misparses (repositoryId="api") into a 401.
                    // Found by the Playwright execute test reading the actual request URL.
                    .addServersItem(new io.swagger.v3.oas.models.servers.Server()
                            .url("/core")
                            .description("NemakiWare servlet context"));
            new io.swagger.v3.jaxrs2.integration.JaxrsOpenApiContextBuilder<>()
                    .application(this)
                    .openApiConfiguration(new io.swagger.v3.oas.integration.SwaggerConfiguration()
                            .openAPI(oas)
                            .prettyPrint(true)
                            .resourcePackages(java.util.Set.of("jp.aegif.nemaki.api.v1.resource")))
                    .buildContext(true);
        } catch (io.swagger.v3.oas.integration.OpenApiConfigurationException e) {
            logger.warning("OpenAPI context configuration failed — /openapi.json will lack servers: " + e.getMessage());
        }
        
        // Register exception mappers for RFC 7807 compliant error responses
        register(jp.aegif.nemaki.api.v1.exception.ApiExceptionMapper.class);
        register(jp.aegif.nemaki.api.v1.exception.ValidationExceptionMapper.class);
        
        // Register CSRF filter (runs before authentication). The Spring MVC
        // CsrfInterceptor only guards /api/v1/admin/*; this protects the
        // Jersey-served /api/v1/cmis/* state-changing endpoints, which accept
        // ambient cookie/Basic credentials.
        register(jp.aegif.nemaki.api.v1.filter.ApiCsrfFilter.class);

        // Register authentication filter
        register(jp.aegif.nemaki.api.v1.filter.ApiAuthenticationFilter.class);
        
        // CORS is handled by SimpleCorsFilter (web.xml) exclusively. A second JAX-RS CORS
        // filter used to sit unregistered next to this class with a hardcoded "*"; it was
        // deleted in 3.3.0 rather than left as a loaded gun.
        
        // Enable Jersey-Spring bridge for automatic DI
        property("jersey.config.server.provider.classnames", 
                "org.glassfish.jersey.server.spring.SpringLifecycleListener," +
                "org.glassfish.jersey.server.spring.scope.RequestContextFilter");
        
        // Enable automatic package scanning for REST resources
        packages("jp.aegif.nemaki.api.v1.resource");
        
        logger.info("=== NemakiWare API v1 Application initialized successfully ===");
    }
}
