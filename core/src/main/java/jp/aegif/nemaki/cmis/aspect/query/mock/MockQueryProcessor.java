package jp.aegif.nemaki.cmis.aspect.query.mock;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.query.QueryProcessor;

import org.apache.chemistry.opencmis.commons.data.ExtensionsData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.math.BigInteger;
import java.util.ArrayList;

/**
 * Mock QueryProcessor for development environment without Solr.
 * This implementation provides basic CMIS query functionality without actual search.
 * Returns empty results but allows CMIS endpoints to function properly.
 */
public class MockQueryProcessor implements QueryProcessor {
    
    private static final Log log = LogFactory.getLog(MockQueryProcessor.class);
    
    private ContentService contentService;
    private PermissionService permissionService;
    private CompileService compileService;

    public MockQueryProcessor() {
        if (log.isDebugEnabled()) {
            log.debug("MockQueryProcessor initialized - Solr disabled for development");
        }
        log.info("MockQueryProcessor initialized - providing basic query functionality without Solr");
    }

    @Override
    public ObjectList query(CallContext callContext, String repositoryId, 
                           String statement, Boolean searchAllVersions,
                           Boolean includeAllowableActions, 
                           IncludeRelationships includeRelationships, 
                           String renditionFilter, BigInteger maxItems, 
                           BigInteger skipCount, ExtensionsData extension) {
        
        log.info("MockQueryProcessor.query called with statement: " + statement);
        if (log.isDebugEnabled()) {
            log.debug("MockQueryProcessor executing query: " + statement);
        }
        
        ObjectListImpl result = new ObjectListImpl();
        
        try {
            // For development: Return empty results but allow query to succeed
            result.setObjects(new ArrayList<>());
            result.setNumItems(BigInteger.ZERO);
            result.setHasMoreItems(false);
            
            log.info("MockQueryProcessor returning empty result set (Solr disabled for development)");
            if (log.isDebugEnabled()) {
                log.debug("MockQueryProcessor: Returning 0 results (Solr disabled)");
            }
            
        } catch (Exception e) {
            log.error("Error in MockQueryProcessor.query: " + e.getMessage(), e);
            if (log.isDebugEnabled()) {
                log.debug("MockQueryProcessor error: " + e.getMessage());
            }
        }
        
        return result;
    }

    // Setters for Spring dependency injection
    public void setContentService(ContentService contentService) {
        this.contentService = contentService;
    }

    public void setPermissionService(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public void setCompileService(CompileService compileService) {
        this.compileService = compileService;
    }
}
