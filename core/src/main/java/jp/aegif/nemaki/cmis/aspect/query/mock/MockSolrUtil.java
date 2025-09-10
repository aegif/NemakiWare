package jp.aegif.nemaki.cmis.aspect.query.mock;

import jp.aegif.nemaki.cmis.aspect.query.solr.SolrUtil;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.model.Content;
import org.apache.solr.client.solrj.SolrClient;
import org.springframework.stereotype.Component;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Mock implementation of SolrUtil for Jetty development environment
 * Disables Solr functionality while maintaining interface compatibility
 */
@Component("mockSolrUtil")
public class MockSolrUtil extends SolrUtil {
    private static final Log log = LogFactory.getLog(MockSolrUtil.class);
    
    public MockSolrUtil() {
        super();
        if (log.isDebugEnabled()) {
            log.debug("MockSolrUtil initialized - Solr functionality disabled for development");
        }
    }
    
    @Override
    public SolrClient getSolrClient() {
        if (log.isDebugEnabled()) {
            log.debug("MockSolrUtil.getSolrClient() - Returning null (Solr disabled)");
        }
        return null;
    }
    
    @Override
    public String getPropertyNameInSolr(String repositoryId, String cmisColName) {
        if (log.isDebugEnabled()) {
            log.debug("MockSolrUtil.getPropertyNameInSolr() - Mock mapping for: " + cmisColName);
        }
        // Return mock property name or original name for fallback
        return cmisColName;
    }
    
    @Override
    public void indexDocument(String repositoryId, Content content) {
        if (log.isDebugEnabled()) {
            log.debug("MockSolrUtil.indexDocument() - Skipping Solr indexing for content: " + 
                (content != null ? content.getId() : "null"));
        }
        // No-op for mock
    }
    
    @Override
    public void deleteDocument(String repositoryId, String documentId) {
        System.out.println("MockSolrUtil.deleteDocument() - Skipping Solr deletion for document: " + documentId);
        // No-op for mock
    }
    
    @Override
    public String getSolrUrl() {
        System.out.println("MockSolrUtil.getSolrUrl() - Mock URL (Solr disabled)");
        return "http://localhost:8983/solr/nemaki";
    }
    
    @Override
    public void setPropertyManager(PropertyManager propertyManager) {
        super.setPropertyManager(propertyManager);
        System.out.println("MockSolrUtil.setPropertyManager() - PropertyManager set for mock");
    }
    
    @Override
    public void setTypeService(TypeService typeService) {
        super.setTypeService(typeService);
        System.out.println("MockSolrUtil.setTypeService() - TypeService set for mock");
    }
}
