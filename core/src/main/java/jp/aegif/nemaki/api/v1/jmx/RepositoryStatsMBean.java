package jp.aegif.nemaki.api.v1.jmx;

public interface RepositoryStatsMBean {
    
    long getTotalNodes();
    
    long getTotalFiles();
    
    long getTotalFolders();
    
    String getRepositoryId();
    
    void setRepositoryId(String repositoryId);
    
    void reloadConfiguration();
}
