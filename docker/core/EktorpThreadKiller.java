/**
 * EktorpThreadKiller - A utility to clean up Ektorp threads on Tomcat shutdown
 * This prevents memory leaks when redeploying the application.
 */
public class EktorpThreadKiller {
    
    public static void main(String[] args) {
        System.out.println("EktorpThreadKiller: Starting thread cleanup...");
        
        // Set system properties to disable Ektorp threads
        System.setProperty("org.ektorp.support.AutoUpdateViewOnChange", "false");
        System.setProperty("org.ektorp.http.IdleConnectionMonitor.enabled", "false");
        System.setProperty("org.ektorp.support.DesignDocument.UPDATE_ON_DIFF", "false");
        System.setProperty("org.ektorp.support.DesignDocument.ALLOW_AUTO_UPDATE", "true");
        System.setProperty("org.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES", "true");
        System.setProperty("org.apache.tomcat.util.scan.StandardJarScanFilter.jarsToSkip", "*.jar");
        
        // Get all active threads
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentGroup;
        while ((parentGroup = rootGroup.getParent()) != null) {
            rootGroup = parentGroup;
        }
        
        Thread[] threads = new Thread[rootGroup.activeCount()];
        rootGroup.enumerate(threads);
        
        // Kill Ektorp-related threads
        for (Thread thread : threads) {
            if (thread != null && thread.getName() != null) {
                String name = thread.getName().toLowerCase();
                if (name.contains("ektorp") || name.contains("idle") || name.contains("connection")) {
                    System.out.println("EktorpThreadKiller: Interrupting thread: " + thread.getName());
                    try {
                        thread.interrupt();
                    } catch (Exception e) {
                        System.out.println("EktorpThreadKiller: Could not interrupt thread: " + e.getMessage());
                    }
                }
            }
        }
        
        System.out.println("EktorpThreadKiller: Thread cleanup completed.");
    }
}
