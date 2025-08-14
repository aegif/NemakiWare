package jp.aegif.nemaki.init;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.xml.ws.spi.Provider;

/**
 * Custom ServletContextListener to initialize JAX-WS Provider before OpenCMIS initialization.
 * 
 * This class addresses the OSGi service loader interference issue where the default
 * Provider.provider() method fails to discover the Metro implementation due to
 * system property access restrictions in the container environment.
 * 
 * By programmatically setting the correct provider implementation early in the
 * application lifecycle, we bypass the problematic service discovery mechanism.
 */
@WebListener
public class JaxWsProviderInitializer implements ServletContextListener {

    private static final Logger LOG = LoggerFactory.getLogger(JaxWsProviderInitializer.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        LOG.info("Starting JAX-WS Provider initialization for CMIS Web Services...");
        
        try {
            // Set the Jakarta XML Web Services provider system property directly
            // This is the correct approach for Jakarta EE 10 environments
            System.setProperty("jakarta.xml.ws.spi.Provider", "com.sun.xml.ws.spi.ProviderImpl");
            LOG.info("✅ JAX-WS Provider system property set: jakarta.xml.ws.spi.Provider=com.sun.xml.ws.spi.ProviderImpl");
            
            // Verify the provider can be loaded
            Provider testProvider = Provider.provider();
            LOG.info("✅ Provider verification successful: {}", testProvider.getClass().getName());
            
        } catch (Exception e) {
            LOG.error("❌ Failed to initialize JAX-WS Provider", e);
            LOG.error("OpenCMIS Web Services may fail to initialize properly");
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        LOG.info("JAX-WS Provider initializer context destroyed");
    }
}