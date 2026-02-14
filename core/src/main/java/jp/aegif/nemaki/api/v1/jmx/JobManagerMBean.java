package jp.aegif.nemaki.api.v1.jmx;

public interface JobManagerMBean {
    
    int getPendingJobs();
    
    int getRunningJobs();
    
    boolean isPaused();
    
    void pause();
    
    void resume();
}
