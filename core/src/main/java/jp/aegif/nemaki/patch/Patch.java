package jp.aegif.nemaki.patch;

import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ObjectUtils;
import org.ektorp.CouchDbConnector;
import org.ektorp.support.DesignDocument;
import org.ektorp.support.DesignDocument.View;
import org.ektorp.support.StdDesignDocumentFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.ConnectorPool;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.PatchHistory;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;

public class Patch {
	protected PropertyManager propertyManager;
	protected ConnectorPool connectorPool;
	protected ContentService contentService;
	protected ContentDaoService contentDaoService;
	protected RepositoryInfoMap repositoryInfoMap;
	protected RepositoryService repositoryService;

	protected boolean isApplied(String repositoryId, String name){
		PatchHistory patchHistory = contentDaoService.getPatchHistoryByName(repositoryId, name);
		return patchHistory != null && patchHistory.isApplied();
	}

	protected void createPathHistory(String repositoryId, String name){
		PatchHistory patchHistory = new PatchHistory(name, true);
		contentDaoService.create(repositoryId, patchHistory);
	}

	protected void addDb(String dbName){
		// add connector (or create if not exist)
		CouchDbConnector connector = connectorPool.add(dbName);

		// add design doc
		StdDesignDocumentFactory factory = new StdDesignDocumentFactory();

		DesignDocument designDoc = factory.getFromDatabase(connector, "_design/_repo");
		if(designDoc == null){
			designDoc = factory.newDesignDocumentInstance();
			designDoc.setId("_design/_repo");
			connector.create(designDoc);
		}
	}

	protected void addView(String repositoryId, String viewName, String map){
		addView(repositoryId, viewName, map, false);
	}

	protected void addView(String repositoryId, String viewName, String map, boolean force){
		CouchDbConnector connector = connectorPool.get(repositoryId);
		StdDesignDocumentFactory factory = new StdDesignDocumentFactory();
		DesignDocument designDoc = factory.getFromDatabase(connector, "_design/_repo");

		if(force || !designDoc.containsView(viewName)){
			designDoc.addView(viewName, new View(map));
			connector.update(designDoc);
		}
	}

	protected void addSimpleProperty(Map<String, PropertyDefinition<?>> props, String id, Cardinality cardinality, Updatability updatability, boolean required, boolean orderable){
		PropertyStringDefinitionImpl pdf = new PropertyStringDefinitionImpl();
		pdf.setId(id);
		pdf.setPropertyType(PropertyType.STRING);
		pdf.setDisplayName(id);
		pdf.setQueryName(id);
		pdf.setDescription(null);
		pdf.setIsInherited(false);
		pdf.setCardinality(cardinality);
		pdf.setUpdatability(updatability);
		pdf.setIsRequired(required);
		pdf.setIsOrderable(orderable);
		pdf.setIsQueryable(true);
		pdf.setLocalName(id);
		pdf.setLocalNamespace(DataUtil.NAMESPACE);

		pdf.setIsOpenChoice(false);
		pdf.setChoices(null);
		pdf.setDefaultValue(null);
		props.put(id, pdf);
	}

	protected Folder getOrCreateSystemSubFolder(String repositoryId, String name){
		Folder systemFolder = contentService.getSystemFolder(repositoryId);

		// check existing folder
		List<Content> children = contentService.getChildren(repositoryId, systemFolder.getId());
		if(CollectionUtils.isNotEmpty(children)){
			for(Content child : children){
				if(ObjectUtils.equals(name, child.getName())){
					return (Folder)child;
				}
			}
		}

		// create
		PropertiesImpl properties = new PropertiesImpl();
		properties.addProperty(new PropertyStringImpl("cmis:name", name));
		properties.addProperty(new PropertyIdImpl("cmis:objectTypeId", "cmis:folder"));
		properties.addProperty(new PropertyIdImpl("cmis:baseTypeId", "cmis:folder"));
		Folder _target = contentService.createFolder(new SystemCallContext(repositoryId), repositoryId, properties, systemFolder, null, null, null, null);
		return _target;
	}

	public PropertyManager getPropertyManager() {
		return propertyManager;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}

	public ConnectorPool getConnectorPool() {
		return connectorPool;
	}

	public void setConnectorPool(ConnectorPool connectorPool) {
		this.connectorPool = connectorPool;
	}

	public ContentService getContentService() {
		return contentService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public ContentDaoService getContentDaoService() {
		return contentDaoService;
	}

	public void setContentDaoService(ContentDaoService contentDaoService) {
		this.contentDaoService = contentDaoService;
	}

	public RepositoryInfoMap getRepositoryInfoMap() {
		return repositoryInfoMap;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}

	public RepositoryService getRepositoryService() {
		return repositoryService;
	}

	public void setRepositoryService(RepositoryService repositoryService) {
		this.repositoryService = repositoryService;
	}
}
