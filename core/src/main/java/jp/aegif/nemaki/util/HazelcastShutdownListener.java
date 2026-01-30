package jp.aegif.nemaki.util;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import java.util.Set;

/**
 * HazelcastShutdownListener - A ServletContextListener to properly shutdown Hazelcast
 * This ensures that all Hazelcast instances are properly cleaned up when the application stops.
 * Migrated from EHCacheShutdownListener for Hazelcast 5.x support.
 */
public class HazelcastShutdownListener implements ServletContextListener {
    private static final Logger log = LoggerFactory.getLogger(HazelcastShutdownListener.class);
    
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("HazelcastShutdownListener: Context initialized - Hazelcast will be configured by CacheService");
        
        // Set system properties for Hazelcast
        System.setProperty("hazelcast.logging.type", "slf4j");
        System.setProperty("hazelcast.phone.home.enabled", "false");
        System.setProperty("hazelcast.shutdownhook.enabled", "true");
    }
    
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("HazelcastShutdownListener: Context destroyed - cleaning up Hazelcast instances");
        
        try {
            // Get all running Hazelcast instances
            Set<HazelcastInstance> instances = Hazelcast.getAllHazelcastInstances();
            
            if (instances != null && !instances.isEmpty()) {
                log.info("HazelcastShutdownListener: Found {} Hazelcast instance(s) to shutdown", instances.size());
                
                for (HazelcastInstance instance : instances) {
                    try {
                        String instanceName = instance.getName();
                        if (instance.getLifecycleService().isRunning()) {
                            log.info("HazelcastShutdownListener: Shutting down Hazelcast instance: {}", instanceName);
                            instance.shutdown();
                            log.info("HazelcastShutdownListener: Hazelcast instance {} shutdown completed", instanceName);
                        }
                    } catch (Exception e) {
                        log.warn("HazelcastShutdownListener: Error shutting down Hazelcast instance: {}", e.getMessage());
                    }
                }
            } else {
                log.info("HazelcastShutdownListener: No Hazelcast instances found to shutdown");
            }
            
            // Also try to shutdown all instances via static method
            Hazelcast.shutdownAll();
            log.info("HazelcastShutdownListener: Hazelcast.shutdownAll() completed");
            
        } catch (Exception e) {
            log.warn("HazelcastShutdownListener: Could not shutdown Hazelcast properly: {}", e.getMessage());
        }
        
        // Clean up any remaining Hazelcast threads
        cleanupHazelcastThreads();
        
        log.info("HazelcastShutdownListener: Context cleanup completed");
    }
    
    private void cleanupHazelcastThreads() {
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
                if (name.contains("hazelcast") || 
                    name.contains("hz.") ||
                    name.contains("cached.thread") ||
                    name.contains("partition-operation")) {
                    log.debug("HazelcastShutdownListener: Interrupting thread: {}", thread.getName());
                    try {
                        thread.interrupt();
                        Thread.sleep(50);
                    } catch (Exception e) {
                        log.debug("HazelcastShutdownListener: Could not interrupt thread: {}", e.getMessage());
                    }
                }
            }
        }
    }
}
