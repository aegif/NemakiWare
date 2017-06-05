package jp.aegif.nemaki.patch;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
	private static final Log log = LogFactory.getLog(PatchService.class);
	private RepositoryInfoMap repositoryInfoMap;
	private ConnectorPool connectorPool;

	private List<AbstractNemakiPatch> patchList;

	public void apply(){
		createPathView();
		for(AbstractNemakiPatch patch : patchList){
			patch.apply();
		}
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

	public void setPatchList(List<AbstractNemakiPatch> patchList) {
		this.patchList = patchList;
	}



}
