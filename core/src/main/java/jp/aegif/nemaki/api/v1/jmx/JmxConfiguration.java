package jp.aegif.nemaki.api.v1.jmx;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class JmxConfiguration {
    
    private static final Log log = LogFactory.getLog(JmxConfiguration.class);
    
    private static final String DOMAIN = "jp.aegif.nemaki";
    
    private RepositoryStats repositoryStats;
    private JobManager jobManager;
    
    public void init() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            
            repositoryStats = new RepositoryStats();
            ObjectName repositoryStatsName = new ObjectName(DOMAIN + ":type=RepositoryStats");
            if (!mbs.isRegistered(repositoryStatsName)) {
                mbs.registerMBean(repositoryStats, repositoryStatsName);
                log.info("Registered JMX MBean: " + repositoryStatsName);
            }
            
            jobManager = new JobManager();
            ObjectName jobManagerName = new ObjectName(DOMAIN + ":type=JobManager");
            if (!mbs.isRegistered(jobManagerName)) {
                mbs.registerMBean(jobManager, jobManagerName);
                log.info("Registered JMX MBean: " + jobManagerName);
            }
            
        } catch (Exception e) {
            log.error("Failed to register JMX MBeans", e);
        }
    }
    
    public void destroy() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            
            ObjectName repositoryStatsName = new ObjectName(DOMAIN + ":type=RepositoryStats");
            if (mbs.isRegistered(repositoryStatsName)) {
                mbs.unregisterMBean(repositoryStatsName);
                log.info("Unregistered JMX MBean: " + repositoryStatsName);
            }
            
            ObjectName jobManagerName = new ObjectName(DOMAIN + ":type=JobManager");
            if (mbs.isRegistered(jobManagerName)) {
                mbs.unregisterMBean(jobManagerName);
                log.info("Unregistered JMX MBean: " + jobManagerName);
            }
            
        } catch (Exception e) {
            log.error("Failed to unregister JMX MBeans", e);
        }
    }
    
    public RepositoryStats getRepositoryStats() {
        return repositoryStats;
    }
    
    public JobManager getJobManager() {
        return jobManager;
    }
}
