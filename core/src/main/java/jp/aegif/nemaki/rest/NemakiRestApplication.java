package jp.aegif.nemaki.rest;

import java.util.logging.Logger;
import java.util.Set;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spring.SpringLifecycleListener;
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
        
        // Enable multipart support
        register(MultiPartFeature.class);
        
        // Enable JSON processing features
        register(JsonProcessingFeature.class);
        register(JacksonFeature.class);
        logger.info("=== JSON Features registered: JsonProcessingFeature, JacksonFeature ===");
        
        // Package scanning for REST resources
        packages("jp.aegif.nemaki.rest");
        
        // Log discovered resources
        Set<Class<?>> classes = getClasses();
        logger.info("=== Registered resource classes: " + classes.size() + " ===");
        for (Class<?> clazz : classes) {
            logger.info("  - " + clazz.getName());
        }
        
        // Log registered features
        Set<Object> instances = getSingletons();
        logger.info("=== Registered feature instances: " + instances.size() + " ===");
        for (Object instance : instances) {
            logger.info("  - " + instance.getClass().getName());
        }
        
        logger.info("=== NemakiRestApplication initialized successfully ===");
    }
}