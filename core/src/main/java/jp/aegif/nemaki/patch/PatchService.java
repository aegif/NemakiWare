package jp.aegif.nemaki.patch;

import org.ektorp.CouchDbConnector;
import org.ektorp.support.DesignDocument;
import org.ektorp.support.DesignDocument.View;
import org.ektorp.support.StdDesignDocumentFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.ConnectorPool;
import jp.aegif.nemaki.model.PatchHistory;

public class PatchService {
	private static Logger logger = LoggerFactory.getLogger(PatchService.class);
	private RepositoryInfoMap repositoryInfoMap;
	private ConnectorPool connectorPool;
	
	private Patch_20160815 patch_20160815;

	public void apply(){
		createPathView();
		patch_20160815.apply();
	}
	
	private void createPathView(){
		for(String repositoryId : repositoryInfoMap.keys()){
			// create view
			CouchDbConnector connector = connectorPool.get(repositoryId);
			StdDesignDocumentFactory factory = new StdDesignDocumentFactory();
			DesignDocument designDoc = factory.getFromDatabase(connector, "_design/_repo");
			if(!designDoc.containsView("patch")){
				designDoc.addView("patch", new View("function(doc) { if (doc.type == 'patch')  emit(doc.name, doc) }"));
				connector.update(designDoc);
			}
		}
	}
	
	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public void setConnectorPool(ConnectorPool connectorPool) {
		this.connectorPool = connectorPool;
	}

	public void setPatch_20160815(Patch_20160815 patch_20160815) {
		this.patch_20160815 = patch_20160815;
	}
}
