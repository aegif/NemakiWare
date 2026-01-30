package jp.aegif.nemaki.util;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * EhcacheShutdownListener - Properly closes Ehcache 3.x CacheManagers on shutdown.
 */
public class EhcacheShutdownListener implements ServletContextListener {
    private static final Log log = LogFactory.getLog(EhcacheShutdownListener.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("EhcacheShutdownListener: Context initialized");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("EhcacheShutdownListener: Closing all Ehcache CacheManagers");
        try {
            NemakiCachePool cachePool = SpringContext.getApplicationContext()
                    .getBean("nemakiCachePool", NemakiCachePool.class);
            if (cachePool != null) {
                cachePool.closeAll();
                log.info("EhcacheShutdownListener: All CacheManagers closed successfully");
            }
        } catch (Exception e) {
            log.warn("EhcacheShutdownListener: Failed to close CacheManagers: " + e.getMessage());
        }
    }
}
