/**
 * EHCacheThreadKiller - A utility to clean up EHCache threads on Tomcat shutdown
 * This prevents memory leaks from EHCache Statistics Thread and other cache-related threads.
 */
public class EHCacheThreadKiller {
    
    public static void main(String[] args) {
        System.out.println("EHCacheThreadKiller: Starting EHCache thread cleanup...");
        
        // Set system properties to disable EHCache statistics and threads
        System.setProperty("net.sf.ehcache.enableShutdownHook", "true");
        System.setProperty("net.sf.ehcache.statisticsEnabled", "false");
        System.setProperty("net.sf.ehcache.disabled", "false");
        System.setProperty("net.sf.ehcache.skipUpdateCheck", "true");
        System.setProperty("net.sf.ehcache.cache.statisticsEnabled", "false");
        System.setProperty("org.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES", "true");
        
        // Get all active threads
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentGroup;
        while ((parentGroup = rootGroup.getParent()) != null) {
            rootGroup = parentGroup;
        }
        
        Thread[] threads = new Thread[rootGroup.activeCount()];
        rootGroup.enumerate(threads);
        
        // Kill EHCache-related threads
        for (Thread thread : threads) {
            if (thread != null && thread.getName() != null) {
                String name = thread.getName().toLowerCase();
                if (name.contains("statistics thread") || 
                    name.contains("__default__") ||
                    name.contains("ehcache") || 
                    name.contains("cache") ||
                    name.contains("statistics") ||
                    name.contains("ektorp") || 
                    name.contains("idle") || 
                    name.contains("connection")) {
                    System.out.println("EHCacheThreadKiller: Interrupting thread: " + thread.getName());
                    try {
                        thread.interrupt();
                        // Give the thread time to stop gracefully
                        Thread.sleep(100);
                        // Force stop if still alive
                        if (thread.isAlive()) {
                            thread.stop();
                            System.out.println("EHCacheThreadKiller: Force stopped thread: " + thread.getName());
                        }
                    } catch (Exception e) {
                        System.out.println("EHCacheThreadKiller: Could not stop thread: " + thread.getName() + " - " + e.getMessage());
                    }
                }
            }
        }
        
        // Force garbage collection to clean up references
        System.gc();
        
        System.out.println("EHCacheThreadKiller: EHCache thread cleanup completed.");
    }
}