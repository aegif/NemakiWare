package jp.aegif.nemaki.patch;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.TypeService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.SystemCallContext;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.dao.ContentDaoService;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientPool;
import jp.aegif.nemaki.dao.impl.couch.connector.CloudantClientWrapper;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.PatchHistory;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.PropertyManager;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringDefinitionImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
// Removed Ektorp imports - functionality temporarily disabled

import java.util.List;
import java.util.Map;

public class PatchUtil {
	protected PropertyManager propertyManager;
	protected CloudantClientPool connectorPool;
	protected ContentService contentService;
	protected ContentDaoService contentDaoService;
	protected RepositoryInfoMap repositoryInfoMap;
	protected RepositoryService repositoryService;
	protected TypeService typeService;
	protected TypeManager typeManager;

	public PatchUtil() {
		System.out.println("=== PATCH DEBUG: PatchUtil constructor called ===");
		org.apache.commons.logging.LogFactory.getLog(PatchUtil.class).info("=== PATCH DEBUG: PatchUtil constructor called ===");
	}

	protected boolean isApplied(String repositoryId, String name){
		// During Cloudant migration, assume all patches are not applied
		// This allows patches to run during startup
		// TODO: Implement proper patch history tracking with Cloudant SDK
		System.out.println("isApplied for patch '" + name + "' in repository '" + repositoryId + "' - returning false (patches will always run during Cloudant migration)");
		return false;
	}

	protected void createPathHistory(String repositoryId, String name){
		// During Cloudant migration, skip patch history creation
		// TODO: Implement proper patch history tracking with Cloudant SDK
		System.out.println("createPathHistory for patch '" + name + "' in repository '" + repositoryId + "' - skipping during Cloudant migration");
	}

	protected void addDb(String dbName){
		// Check if database already exists using Cloudant SDK
		try {
			CloudantClientWrapper client = connectorPool.getClient(dbName);
			if (client != null) {
				// Database already exists - no need to create
				System.out.println("Database '" + dbName + "' already exists, skipping creation");
				return;
			}
		} catch (Exception e) {
			// Database doesn't exist or connection failed
			System.out.println("Database '" + dbName + "' doesn't exist or connection failed: " + e.getMessage());
		}
		
		// For Docker environments, databases are already created during initialization
		// This method is primarily for non-Docker deployments
		System.out.println("addDb for '" + dbName + "' - assuming database exists in Docker environment");
	}

	protected void addView(String repositoryId, String viewName, String map){
		addView(repositoryId, viewName, map, false);
	}

	protected void addView(String repositoryId, String viewName, String map, boolean force){
		// ViewQuery functionality temporarily disabled during Cloudant migration
		// Views are assumed to be already created in the database initialization
		System.out.println("addView for '" + viewName + "' in repository '" + repositoryId + "' - assuming view exists in Docker environment");
		// TODO: Implement view creation with Cloudant SDK when full ViewQuery support is restored
	}

	protected void deleteView(String repositoryId, String viewName){
		// ViewQuery functionality temporarily disabled during Cloudant migration
		System.out.println("deleteView for '" + viewName + "' in repository '" + repositoryId + "' - skipping during Cloudant migration");
		// TODO: Implement view deletion with Cloudant SDK when full ViewQuery support is restored
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

	public CloudantClientPool getConnectorPool() {
		return connectorPool;
	}

	public void setConnectorPool(CloudantClientPool connectorPool) {
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

	public TypeService getTypeService() {
		return typeService;
	}

	public void setTypeService(TypeService typeService) {
		this.typeService = typeService;
	}

	public TypeManager getTypeManager() {
		return typeManager;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}
}
