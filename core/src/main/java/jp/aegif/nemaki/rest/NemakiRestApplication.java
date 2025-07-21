package jp.aegif.nemaki.rest;

import java.util.logging.Logger;
import java.util.Set;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.spring.SpringLifecycleListener;
import org.glassfish.jersey.media.multipart.MultiPartFeature;

import jakarta.ws.rs.ApplicationPath;

@ApplicationPath("/rest")
public class NemakiRestApplication extends ResourceConfig {
    
    private static final Logger logger = Logger.getLogger(NemakiRestApplication.class.getName());

    public NemakiRestApplication() {
        logger.info("=== Initializing NemakiRestApplication ===");
        
        // Package scanning for REST resources
        packages("jp.aegif.nemaki.rest");
        
        // Enable Jersey-Spring integration
        register(SpringLifecycleListener.class);
        
        // Enable multipart support
        register(MultiPartFeature.class);
        
        // Log discovered resources
        Set<Class<?>> classes = getClasses();
        logger.info("=== Registered resource classes: " + classes.size() + " ===");
        for (Class<?> clazz : classes) {
            logger.info("  - " + clazz.getName());
        }
        
        logger.info("=== NemakiRestApplication initialized successfully ===");
    }
}