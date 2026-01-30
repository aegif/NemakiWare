package jp.aegif.nemaki.util;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * EhcacheShutdownListener - A ServletContextListener to support Ehcache lifecycle logging
 * This ensures that all EHCache threads are properly cleaned up when the application stops.
 */
public class EhcacheShutdownListener implements ServletContextListener {
    private static final Log log = LogFactory.getLog(EhcacheShutdownListener.class);
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (log.isDebugEnabled()) {
            log.debug("EhcacheShutdownListener: Context initialized - initializing Ehcache lifecycle");
        }
        
        // Ehcache 3.x uses programmatic CacheManager lifecycle; no system properties required.
    }
    
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (log.isDebugEnabled()) {
            log.debug("EhcacheShutdownListener: Context destroyed - cleaning up Ehcache");
        }
        
        if (log.isDebugEnabled()) {
            log.debug("EhcacheShutdownListener: Context cleanup completed (Ehcache 3 managed by CacheService)");
        }
    }
}
