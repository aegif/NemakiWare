package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Patch to add standard CMIS views that are required by CMIS 1.1 specification
 * but are missing from the initial repository initialization.
 * 
 * This patch adds the following standard views:
 * - documents: All cmis:document objects
 * - folders: All cmis:folder objects  
 * - items: All cmis:item objects
 * - policies: All cmis:policy objects
 * - contentsById: All content objects by ID
 */
public class Patch_StandardCmisViews extends AbstractNemakiPatch {
    private static final Log log = LogFactory.getLog(Patch_StandardCmisViews.class);
    private static final String PATCH_NAME = "StandardCmisViews";
    
    @Override
    protected void applySystemPatch() {
        // No system-wide changes needed
        log.info("[patch=" + PATCH_NAME + "] System patch - no changes needed");
    }

    @Override
    protected void applyPerRepositoryPatch(String repositoryId) {
        log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Adding standard CMIS views");
        
        try {
            CloudantClientWrapper client = patchUtil.getConnectorPool().getClient(repositoryId);
            if (client == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Could not get client for repository");
                return;
            }
            
            // Get current design document
            String designDocId = "_design/_repo";
            ObjectMapper mapper = new ObjectMapper();
            
            // Read current design document
            JsonNode currentDoc = client.get(JsonNode.class, designDocId);
            if (currentDoc == null) {
                log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Design document not found");
                return;
            }
            
            // Clone the document as ObjectNode for modification
            ObjectNode updatedDoc = currentDoc.deepCopy();
            ObjectNode views = (ObjectNode) updatedDoc.get("views");
            if (views == null) {
                views = mapper.createObjectNode();
                updatedDoc.set("views", views);
            }
            
            // Add missing standard CMIS views
            addStandardViewIfMissing(views, "documents", 
                "function(doc) { if (doc.type == 'cmis:document')  emit(doc._id, doc) }", repositoryId);
            
            addStandardViewIfMissing(views, "folders", 
                "function(doc) { if (doc.type == 'cmis:folder')  emit(doc._id, doc) }", repositoryId);
            
            addStandardViewIfMissing(views, "items", 
                "function(doc) { if (doc.type == 'cmis:item')  emit(doc._id, doc) }", repositoryId);
            
            addStandardViewIfMissing(views, "policies", 
                "function(doc) { if (doc.type == 'cmis:policy')  emit(doc._id, doc) }", repositoryId);
            
            addStandardViewIfMissing(views, "contentsById", 
                "function(doc) { if (['cmis:document', 'cmis:folder', 'cmis:relationship', 'cmis:policy', 'cmis:item','cmis:secondary'].indexOf(doc.type) >= 0)  emit(doc._id, doc) }", repositoryId);
            
            // Update the design document
            client.update(updatedDoc);
            
            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Successfully updated design document with standard CMIS views");
            
        } catch (Exception e) {
            log.error("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Failed to add standard CMIS views", e);
            throw new RuntimeException("Failed to apply standard CMIS views patch", e);
        }
    }
    
    private void addStandardViewIfMissing(ObjectNode views, String viewName, String mapFunction, String repositoryId) {
        if (!views.has(viewName)) {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode viewDef = mapper.createObjectNode();
            viewDef.put("map", mapFunction);
            views.set(viewName, viewDef);
            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] Added missing view: " + viewName);
        } else {
            log.info("[patch=" + PATCH_NAME + ", repositoryId=" + repositoryId + "] View already exists: " + viewName);
        }
    }

    @Override
    public String getName() {
        return PATCH_NAME;
    }
}