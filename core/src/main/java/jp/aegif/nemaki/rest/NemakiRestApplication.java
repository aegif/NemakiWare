package jp.aegif.nemaki.rest;

import java.util.logging.Logger;
import java.util.Set;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spring.SpringLifecycleListener;
import org.glassfish.jersey.server.spring.SpringComponentProvider;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.jsonp.JsonProcessingFeature;
import org.glassfish.jersey.jackson.JacksonFeature;

import jakarta.ws.rs.ApplicationPath;

@ApplicationPath("/rest")
public class NemakiRestApplication extends ResourceConfig {
    
    private static final Logger logger = Logger.getLogger(NemakiRestApplication.class.getName());

    public NemakiRestApplication() {
        logger.info("=== Initializing NemakiRestApplication ===");
        
        // Enable Jersey-Spring integration
        register(SpringLifecycleListener.class);
        register(SpringComponentProvider.class);
        
        // Enable multipart support
        register(MultiPartFeature.class);
        
        // Enable JSON processing features
        register(JsonProcessingFeature.class);
        register(JacksonFeature.class);
        
        // Register NemakiJacksonProvider for unified ObjectMapper
        register(jp.aegif.nemaki.rest.provider.NemakiJacksonProvider.class);
        logger.info("=== JSON Features registered: JsonProcessingFeature, JacksonFeature, NemakiJacksonProvider ===");
        
        // Enable Jersey-Spring bridge for automatic DI
        property("jersey.config.server.provider.classnames", 
                "org.glassfish.jersey.server.spring.SpringLifecycleListener," +
                "org.glassfish.jersey.server.spring.scope.RequestContextFilter");
        
        // Enable automatic package scanning for REST resources
        // Jersey-Spring integration will handle proper dependency injection
        packages("jp.aegif.nemaki.rest");
        
        logger.info("=== NemakiRestApplication initialized successfully ===");
    }
}