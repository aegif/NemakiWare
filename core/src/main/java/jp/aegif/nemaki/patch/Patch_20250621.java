package jp.aegif.nemaki.patch;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ektorp.CouchDbConnector;
import org.ektorp.DocumentNotFoundException;
import org.ektorp.support.DesignDocument;
import org.ektorp.support.DesignDocument.View;
import org.ektorp.support.StdDesignDocumentFactory;
import org.springframework.stereotype.Component;

/**
 * Patch_20250621: CMIS Authentication and Repository Initialization Fix
 * 
 * This patch ensures compatibility with CMIS format user authentication
 * and fixes design document views for proper Spring initialization.
 * 
 * Applied automatically to existing environments to fix:
 * - admin view definition for CMIS format users
 * - Missing patch view in repositories
 * - Compatibility with both old and new user formats
 */
@Component
public class Patch_20250621 extends AbstractNemakiPatch {
	private static final Log log = LogFactory.getLog(Patch_20250621.class);

	@Override
	public String getName() {
		return "patch_20250621_cmis_auth_fix";
	}

	@Override
	protected void applySystemPatch() {
		// No system-wide changes needed
		log.info("Applying CMIS authentication compatibility patch system-wide");
	}

	@Override
	protected void applyPerRepositoryPatch(String repositoryId) {
		log.info("Applying CMIS authentication patch to repository: " + repositoryId);
		
		try {
			CouchDbConnector connector = patchUtil.getConnectorPool().get(repositoryId);
			if (connector == null) {
				log.warn("Connector not found for repository: " + repositoryId);
				return;
			}
			
			// Check if design document exists
			StdDesignDocumentFactory factory = new StdDesignDocumentFactory();
			DesignDocument designDoc = null;
			try {
				designDoc = factory.getFromDatabase(connector, "_design/_repo");
			} catch (DocumentNotFoundException e) {
				log.error("Design document _design/_repo not found in repository: " + repositoryId);
				// Cannot apply patch without design document
				return;
			}
			
			boolean updated = false;
			
			// Check patch view
			if (!designDoc.containsView("patch")) {
				log.info("Adding patch view to repository: " + repositoryId);
				designDoc.addView("patch", new View("function(doc) { if (doc.type == 'patch')  emit(doc.name, doc) }"));
				updated = true;
			} else {
				log.debug("Patch view already exists in repository: " + repositoryId);
			}
			
			// Check admin view and its current definition
			boolean needsAdminViewUpdate = false;
			if (designDoc.containsView("admin")) {
				// Get current admin view definition
				View currentAdminView = designDoc.getViews().get("admin");
				String currentMap = currentAdminView.getMap();
				
				// Check if it needs updating (doesn't support both formats)
				if (currentMap != null && 
				    !currentMap.contains("doc.type == 'cmis:item'") && 
				    currentMap.contains("doc.type == 'user'")) {
					log.info("Admin view exists but only supports old format, updating for CMIS compatibility");
					needsAdminViewUpdate = true;
				} else if (currentMap != null && 
				          currentMap.contains("doc.type == 'cmis:item'") && 
				          !currentMap.contains("doc.type == 'user'")) {
					log.info("Admin view exists but only supports new CMIS format, updating for backward compatibility");
					needsAdminViewUpdate = true;
				} else {
					log.debug("Admin view already supports both formats in repository: " + repositoryId);
				}
			} else {
				log.info("Admin view does not exist, creating with dual format support");
				needsAdminViewUpdate = true;
			}
			
			if (needsAdminViewUpdate) {
				// Update admin view to support both old and new user formats for backward compatibility
				String cmisCompatibleAdminView = 
					"function(doc) { " +
					"  if ((doc.type == 'cmis:item' && doc.objectType == 'nemaki:user' && doc.admin == true) || " +
					"      (doc.type == 'user' && doc.admin == true)) { " +
					"    emit(doc.userId, doc); " +
					"  } " +
					"}";
				
				log.info("Updating admin view for CMIS compatibility in repository: " + repositoryId);
				designDoc.addView("admin", new View(cmisCompatibleAdminView));
				updated = true;
			}
			
			// Only update if changes were made
			if (updated) {
				connector.update(designDoc);
				log.info("Successfully updated design document for repository: " + repositoryId);
			} else {
				log.info("No updates needed for repository: " + repositoryId + " - all views already exist with correct definitions");
			}
			
		} catch (Exception e) {
			log.error("Failed to apply CMIS authentication patch to repository: " + repositoryId, e);
			throw new RuntimeException("Patch application failed for repository: " + repositoryId, e);
		}
	}
}