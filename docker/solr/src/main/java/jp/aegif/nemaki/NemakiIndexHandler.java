/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     linzhixing(https://github.com/linzhixing) - initial API and implementation
 ******************************************************************************/
package jp.aegif.nemaki;

import jp.aegif.nemaki.tracker.CoreTracker;
import jp.aegif.nemaki.util.Constant;
import jp.aegif.nemaki.util.PropertyKey;
import jp.aegif.nemaki.util.impl.PropertyManagerImpl;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom Solr request handler for NemakiWare indexing operations
 * Replaces the deprecated CoreAdminHandler in Solr 9.x
 */
public class NemakiIndexHandler extends RequestHandlerBase {
    private static final Logger logger = LoggerFactory.getLogger(NemakiIndexHandler.class);
    
    private static Map<String, CoreTracker> trackers = new ConcurrentHashMap<>();
    
    public NemakiIndexHandler() {
        super();
    }
    
    @Override
    public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
        String action = req.getParams().get("action");
        String repositoryId = req.getParams().get("repositoryId");
        
        logger.info("NemakiIndexHandler called with action: {}, repositoryId: {}", action, repositoryId);
        
        try {
            if (action != null) {
                doAction(req, rsp, action, repositoryId);
            } else {
                rsp.add("error", "Missing required parameter: action");
            }
        } catch (Exception e) {
            logger.error("Error in NemakiIndexHandler", e);
            rsp.setException(e);
        }
    }
    
    private void doAction(SolrQueryRequest req, SolrQueryResponse rsp, String action, String repositoryId) {
        switch (action.toUpperCase()) {
            case "INDEX":
                index(req, rsp, repositoryId);
                break;
            case "INIT":
                init(req, rsp, repositoryId);
                break;
            default:
                rsp.add("error", "Unknown action: " + action);
        }
    }
    
    private void index(SolrQueryRequest req, SolrQueryResponse rsp, String repositoryId) {
        try {
            CoreTracker tracker = getOrCreateTracker(req.getCore());
            if (repositoryId != null) {
                tracker.index(Constant.MODE_DELTA, repositoryId);
            } else {
                tracker.index(Constant.MODE_DELTA);
            }
            rsp.add("Result", "Successfully tracked!");
        } catch (Exception e) {
            logger.error("Error during indexing", e);
            rsp.setException(e);
        }
    }
    
    private void init(SolrQueryRequest req, SolrQueryResponse rsp, String repositoryId) {
        try {
            CoreTracker tracker = getOrCreateTracker(req.getCore());
            if (repositoryId != null) {
                tracker.initCore(repositoryId);
            } else {
                tracker.initCore();
            }
            rsp.add("Result", "Successfully initialized!");
        } catch (Exception e) {
            logger.error("Error during initialization", e);
            rsp.setException(e);
        }
    }
    
    private CoreTracker getOrCreateTracker(SolrCore core) {
        String coreName = core.getName();
        return trackers.computeIfAbsent(coreName, k -> {
            try {
                PropertyManagerImpl propMgr = new PropertyManagerImpl("nemakisolr.properties");
                String tokenCoreName = propMgr.readValue(PropertyKey.SOLR_CORE_TOKEN);
                
                CoreContainer coreContainer = core.getCoreContainer();
                SolrClient indexServer = new EmbeddedSolrServer(coreContainer, coreName);
                SolrClient tokenServer = new EmbeddedSolrServer(coreContainer, tokenCoreName);
                
                return new CoreTracker(this, core, indexServer, tokenServer);
            } catch (Exception e) {
                logger.error("Failed to create CoreTracker for core: " + coreName, e);
                throw new RuntimeException("Failed to create CoreTracker", e);
            }
        });
    }
    
    @Override
    public String getDescription() {
        return "NemakiWare Index Handler for automatic indexing";
    }
    
    @Override
    public PermissionNameProvider.Name getPermissionName(AuthorizationContext ctx) {
        return PermissionNameProvider.Name.READ_PERM;
    }
}