import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class to forcibly kill Ektorp idle connection monitor threads
 * that are causing memory leaks in Tomcat.
 */
public class EktorpThreadKiller {
    private static final Logger logger = Logger.getLogger(EktorpThreadKiller.class.getName());
    
    /**
     * Kill the Ektorp idle connection monitor thread
     */
    public static void killEktorpThreads() {
        try {
            System.setProperty("org.ektorp.http.IdleConnectionMonitor.enabled", "false");
            System.setProperty("org.apache.http.impl.conn.PoolingClientConnectionManager.idleConnectionMonitor", "false");
            System.setProperty("org.apache.http.impl.conn.PoolingHttpClientConnectionManager.idleConnectionMonitor", "false");
            System.setProperty("org.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES", "true");
            
            try {
                Class<?> idleMonitorClass = Class.forName("org.ektorp.http.IdleConnectionMonitor");
                
                try {
                    Field schedulerField = idleMonitorClass.getDeclaredField("scheduler");
                    schedulerField.setAccessible(true);
                    Object scheduler = schedulerField.get(null);
                    if (scheduler != null) {
                        Method shutdownNowMethod = scheduler.getClass().getMethod("shutdownNow");
                        shutdownNowMethod.invoke(scheduler);
                        schedulerField.set(null, null);
                        logger.info("Successfully shutdown IdleConnectionMonitor scheduler");
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to shutdown IdleConnectionMonitor scheduler: " + e.getMessage(), e);
                }
                
                try {
                    Field instanceField = idleMonitorClass.getDeclaredField("INSTANCE");
                    instanceField.setAccessible(true);
                    Object instance = instanceField.get(null);
                    if (instance != null) {
                        Method shutdownMethod = idleMonitorClass.getDeclaredMethod("shutdown");
                        shutdownMethod.setAccessible(true);
                        shutdownMethod.invoke(instance);
                        instanceField.set(null, null);
                        logger.info("Successfully disabled Ektorp IdleConnectionMonitor instance");
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to disable IdleConnectionMonitor instance: " + e.getMessage(), e);
                }
                
                try {
                    Field enabledField = idleMonitorClass.getDeclaredField("MONITOR_ENABLED");
                    enabledField.setAccessible(true);
                    enabledField.set(null, false);
                    logger.info("Successfully disabled IdleConnectionMonitor MONITOR_ENABLED flag");
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to disable IdleConnectionMonitor MONITOR_ENABLED flag: " + e.getMessage(), e);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to disable Ektorp IdleConnectionMonitor: " + e.getMessage(), e);
            }
            
            Thread[] threads = findAllThreads();
            for (Thread thread : threads) {
                if (thread != null && thread.getName().toLowerCase().contains("ektorp")) {
                    try {
                        logger.info("Attempting to interrupt Ektorp thread: " + thread.getName());
                        thread.interrupt();
                        
                        try {
                            Method stopMethod = Thread.class.getDeclaredMethod("stop");
                            stopMethod.setAccessible(true);
                            stopMethod.invoke(thread);
                            logger.info("Successfully stopped Ektorp thread: " + thread.getName());
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Failed to forcibly stop Ektorp thread: " + e.getMessage(), e);
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to interrupt Ektorp thread: " + e.getMessage(), e);
                    }
                }
            }
            
            try {
                File markerFile = new File("/usr/local/tomcat/temp/ektorp_threads_killed");
                markerFile.createNewFile();
                try (FileOutputStream fos = new FileOutputStream(markerFile)) {
                    fos.write("Ektorp threads killed at ".getBytes());
                    fos.write(String.valueOf(System.currentTimeMillis()).getBytes());
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to create marker file: " + e.getMessage(), e);
            }
            
            logger.info("EktorpThreadKiller completed successfully");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in EktorpThreadKiller: " + e.getMessage(), e);
        }
    }
    
    /**
     * Find all threads in the JVM
     */
    private static Thread[] findAllThreads() {
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentGroup;
        while ((parentGroup = rootGroup.getParent()) != null) {
            rootGroup = parentGroup;
        }
        
        Thread[] threads = new Thread[rootGroup.activeCount() * 2];
        rootGroup.enumerate(threads, true);
        return threads;
    }
    
    /**
     * Main method for testing
     */
    public static void main(String[] args) {
        killEktorpThreads();
    }
}
