package jp.aegif.nemaki.api.v1.jmx;

import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.util.spring.SpringContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class RepositoryStats implements RepositoryStatsMBean {

    private static final Log log = LogFactory.getLog(RepositoryStats.class);

    private String repositoryId = "default";
    private ContentDaoService contentDaoService;

    @Override
    public long getTotalNodes() {
        ContentDaoService dao = getContentDaoService();
        if (dao == null) return 0;
        try {
            return dao.getObjectCount(repositoryId, null);
        } catch (Exception e) {
            log.warn("Failed to get total node count: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public long getTotalFiles() {
        ContentDaoService dao = getContentDaoService();
        if (dao == null) return 0;
        try {
            return dao.getObjectCount(repositoryId, "cmis:document");
        } catch (Exception e) {
            log.warn("Failed to get total file count: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public long getTotalFolders() {
        ContentDaoService dao = getContentDaoService();
        if (dao == null) return 0;
        try {
            return dao.getObjectCount(repositoryId, "cmis:folder");
        } catch (Exception e) {
            log.warn("Failed to get total folder count: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public String getRepositoryId() {
        return repositoryId;
    }

    @Override
    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    @Override
    public void reloadConfiguration() {
        // Clear cached service reference to force re-lookup
        this.contentDaoService = null;
    }

    public void setContentDaoService(ContentDaoService contentDaoService) {
        this.contentDaoService = contentDaoService;
    }

    private ContentDaoService getContentDaoService() {
        if (contentDaoService != null) {
            return contentDaoService;
        }
        try {
            contentDaoService = SpringContext.getApplicationContext()
                    .getBean("contentDaoService", ContentDaoService.class);
            return contentDaoService;
        } catch (Exception e) {
            log.debug("ContentDaoService not available from SpringContext: " + e.getMessage());
            return null;
        }
    }
}
