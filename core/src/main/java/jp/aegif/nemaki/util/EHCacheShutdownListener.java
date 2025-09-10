package jp.aegif.nemaki.util;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * EHCacheShutdownListener - A ServletContextListener to properly shutdown EHCache
 * This ensures that all EHCache threads are properly cleaned up when the application stops.
 */
public class EHCacheShutdownListener implements ServletContextListener {
    private static final Log log = LogFactory.getLog(EHCacheShutdownListener.class);
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (log.isDebugEnabled()) {
            log.debug("EHCacheShutdownListener: Context initialized - setting EHCache properties");
        }
        
        // Set system properties to disable EHCache background operations
        System.setProperty("net.sf.ehcache.enableShutdownHook", "true");
        System.setProperty("net.sf.ehcache.statisticsEnabled", "false");
        System.setProperty("net.sf.ehcache.cache.statisticsEnabled", "false");
        System.setProperty("net.sf.ehcache.skipUpdateCheck", "true");
        System.setProperty("net.sf.ehcache.disabled", "false");
    }
    
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (log.isDebugEnabled()) {
            log.debug("EHCacheShutdownListener: Context destroyed - cleaning up EHCache");
        }
        
        try {
            // Force shutdown of EHCache Manager
            Class<?> cacheManagerClass = Class.forName("net.sf.ehcache.CacheManager");
            Object instance = cacheManagerClass.getMethod("getInstance").invoke(null);
            if (instance != null) {
                cacheManagerClass.getMethod("shutdown").invoke(instance);
                if (log.isDebugEnabled()) {
                    log.debug("EHCacheShutdownListener: EHCache CacheManager shutdown completed");
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("EHCacheShutdownListener: Could not shutdown EHCache properly: " + e.getMessage());
            }
        }
        
        // Force interrupt any remaining EHCache threads
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentGroup;
        while ((parentGroup = rootGroup.getParent()) != null) {
            rootGroup = parentGroup;
        }
        
        Thread[] threads = new Thread[rootGroup.activeCount()];
        rootGroup.enumerate(threads);
        
        for (Thread thread : threads) {
            if (thread != null && thread.getName() != null) {
                String name = thread.getName().toLowerCase();
                if (name.contains("statistics thread") || 
                    name.contains("__default__") ||
                    name.contains("ehcache") || 
                    name.contains("cache") ||
                    name.contains("disk") ||
                    name.contains("expiry")) {
                    if (log.isDebugEnabled()) {
                        log.debug("EHCacheShutdownListener: Interrupting thread: " + thread.getName());
                    }
                    try {
                        thread.interrupt();
                        Thread.sleep(50);
                        if (thread.isAlive()) {
                            thread.stop();
                        }
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug("EHCacheShutdownListener: Could not stop thread: " + e.getMessage());
                        }
                    }
                }
            }
        }
        
        if (log.isDebugEnabled()) {
            log.debug("EHCacheShutdownListener: Context cleanup completed");
        }
    }
}
