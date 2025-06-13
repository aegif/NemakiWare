import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * EHCacheShutdownListener - A ServletContextListener to properly shutdown EHCache
 * This ensures that all EHCache threads are properly cleaned up when the application stops.
 */
public class EHCacheShutdownListener implements ServletContextListener {
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("EHCacheShutdownListener: Context initialized - setting EHCache properties");
        
        // Set system properties to disable EHCache background operations
        System.setProperty("net.sf.ehcache.enableShutdownHook", "true");
        System.setProperty("net.sf.ehcache.statisticsEnabled", "false");
        System.setProperty("net.sf.ehcache.cache.statisticsEnabled", "false");
        System.setProperty("net.sf.ehcache.skipUpdateCheck", "true");
        System.setProperty("net.sf.ehcache.disabled", "false");
    }
    
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        System.out.println("EHCacheShutdownListener: Context destroyed - cleaning up EHCache");
        
        try {
            // Force shutdown of EHCache Manager
            Class<?> cacheManagerClass = Class.forName("net.sf.ehcache.CacheManager");
            Object instance = cacheManagerClass.getMethod("getInstance").invoke(null);
            if (instance != null) {
                cacheManagerClass.getMethod("shutdown").invoke(instance);
                System.out.println("EHCacheShutdownListener: EHCache CacheManager shutdown completed");
            }
        } catch (Exception e) {
            System.out.println("EHCacheShutdownListener: Could not shutdown EHCache properly: " + e.getMessage());
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
                    System.out.println("EHCacheShutdownListener: Interrupting thread: " + thread.getName());
                    try {
                        thread.interrupt();
                        Thread.sleep(50);
                        if (thread.isAlive()) {
                            thread.stop();
                        }
                    } catch (Exception e) {
                        System.out.println("EHCacheShutdownListener: Could not stop thread: " + e.getMessage());
                    }
                }
            }
        }
        
        System.out.println("EHCacheShutdownListener: Context cleanup completed");
    }
}