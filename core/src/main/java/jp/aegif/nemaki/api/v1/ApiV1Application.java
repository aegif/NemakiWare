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

@ApplicationPath("/api/v1")
@OpenAPIDefinition(
    info = @Info(
        title = "NemakiWare REST API",
        version = "1.0.0",
        description = "OpenAPI 3.0 compliant REST API for NemakiWare CMIS Repository. " +
                      "This API provides full access to CMIS operations including object management, " +
                      "versioning, navigation, and query capabilities.",
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
        @Server(url = "/core", description = "NemakiWare Server")
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
        @Tag(name = "auth", description = "Authentication operations")
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
        
        // Register exception mappers for RFC 7807 compliant error responses
        register(jp.aegif.nemaki.api.v1.exception.ApiExceptionMapper.class);
        register(jp.aegif.nemaki.api.v1.exception.ValidationExceptionMapper.class);
        
        // Register authentication filter
        register(jp.aegif.nemaki.api.v1.filter.ApiAuthenticationFilter.class);
        
        // Register CORS filter
        register(jp.aegif.nemaki.api.v1.filter.ApiCorsFilter.class);
        
        // Enable Jersey-Spring bridge for automatic DI
        property("jersey.config.server.provider.classnames", 
                "org.glassfish.jersey.server.spring.SpringLifecycleListener," +
                "org.glassfish.jersey.server.spring.scope.RequestContextFilter");
        
        // Enable automatic package scanning for REST resources
        packages("jp.aegif.nemaki.api.v1.resource");
        
        logger.info("=== NemakiWare API v1 Application initialized successfully ===");
    }
}
