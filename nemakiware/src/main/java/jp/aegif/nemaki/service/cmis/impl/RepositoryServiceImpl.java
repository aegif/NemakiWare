package jp.aegif.nemaki.service.cmis.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.repository.NemakiRepositoryInfoImpl;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.RepositoryService;
import jp.aegif.nemaki.service.node.ContentService;
import jp.aegif.nemaki.util.YamlManager;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionList;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.springframework.beans.factory.InitializingBean;

public class RepositoryServiceImpl implements RepositoryService,
		InitializingBean {

	private NemakiRepositoryInfoImpl repositoryInfo;
	private ContentService contentService;
	private TypeManager typeManager;
	
	private final String ASPECT_FILE_PATH = "base_model.yml";
	
	public TypeManager getTypeManager() {
		return typeManager;
	}

	public boolean hasThisRepositoryId(String repositoryId) {
		return (repositoryId.equals(repositoryInfo.getId()));
	}

	public NemakiRepositoryInfoImpl getRepositoryInfo() {
		repositoryInfo.setLatestChangeLogToken(contentService.getLatestChangeToken());
		return repositoryInfo;
	}

	//CMIS Service method
	public TypeDefinitionList getTypeChildren(CallContext callContext,
			String typeId, Boolean includePropertyDefinitions,
			BigInteger maxItems, BigInteger skipCount) {

		return typeManager.getTypesChildren(callContext, typeId,
				includePropertyDefinitions, maxItems, skipCount);
	}
	
	//CMIS Service method
	public List<TypeDefinitionContainer> getTypeDescendants(
			CallContext callContext, String typeId, BigInteger depth,
			Boolean includePropertyDefinitions) {
		return typeManager.getTypesDescendants(typeId, depth, includePropertyDefinitions);
	}

	//CMIS Service method
	public TypeDefinition getTypeDefinition(CallContext callContext,
			String typeId) {
		return typeManager.getTypeDefinition(callContext, typeId);
	}

	/**
	 * Sets CMIS optional capabilities for Nemaki repository.
	 */
	public void afterPropertiesSet() throws Exception {
		repositoryInfo.setExtensions(buildAspectInfo());
	
	}

	@SuppressWarnings("unchecked")
	private List<CmisExtensionElement> buildAspectInfo() {
		YamlManager manager = new YamlManager(ASPECT_FILE_PATH);
		final String ns = repositoryInfo.getNameSpace();

		Map<String, Object> map = new HashMap<String, Object>();
		try{
			map = (Map<String, Object>) manager.loadYml();
		}catch(Exception e){
			//TODO logging
			e.printStackTrace();
		}
		Map<String, Object> aspects = (Map<String, Object>) map.get("aspects");

		List<CmisExtensionElement> aspectsList = new ArrayList<CmisExtensionElement>();
		// set aspect
		for (String aspectKey : aspects.keySet()) {
			Map<String, Object> aspect = (Map<String, Object>) aspects
					.get(aspectKey);

			// set attributes
			Map<String, Object> attributes = (Map<String, Object>) aspect
					.get("attributes");
			List<CmisExtensionElement> attributeList = new ArrayList<CmisExtensionElement>();

			for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
				CmisExtensionElement attributeExtension = new CmisExtensionElementImpl(
						ns, attribute.getKey(), null, attribute.getValue()
								.toString());
				attributeList.add(attributeExtension);
			}
			CmisExtensionElement attributesExtension = new CmisExtensionElementImpl(
					ns, "attributes", null, attributeList);

			// set properties
			Map<String, Object> properties = (Map<String, Object>) aspect
					.get("properties");
			List<CmisExtensionElement> propertyList = new ArrayList<CmisExtensionElement>();
			for (String propertyKey : properties.keySet()) {
				Map<String, Object> property = (Map<String, Object>) properties
						.get(propertyKey);
				List<CmisExtensionElement> propertyAttrsExtension = new ArrayList<CmisExtensionElement>();
				for (Map.Entry<String, Object> propertyAttr : property
						.entrySet()) {
					propertyAttrsExtension.add(new CmisExtensionElementImpl(ns,
							propertyAttr.getKey(), null, propertyAttr
									.getValue().toString()));
				}
				Map<String, String> propId = new HashMap<String, String>();
				propId.put("id", propertyKey);
				CmisExtensionElement propertyExtension = new CmisExtensionElementImpl(
						ns, "property", propId, propertyAttrsExtension);
				propertyList.add(propertyExtension);
			}
			CmisExtensionElement propertiesExtension = new CmisExtensionElementImpl(
					ns, "properties", null, propertyList);

			List<CmisExtensionElement> aspectList = new ArrayList<CmisExtensionElement>();
			aspectList.add(attributesExtension);
			aspectList.add(propertiesExtension);

			Map<String, String> aspectId = new HashMap<String, String>();
			aspectId.put("id", aspectKey);
			CmisExtensionElement aspectExtension = new CmisExtensionElementImpl(
					ns, "aspect", aspectId, aspectList);
			aspectsList.add(aspectExtension);
		}

		CmisExtensionElement aspectsExtension = new CmisExtensionElementImpl(
				ns, "aspects", null, aspectsList);

		List<CmisExtensionElement> result = new ArrayList<CmisExtensionElement>();
		result.add(aspectsExtension);

		return result;

	}

	public void setRepositoryInfo(NemakiRepositoryInfoImpl repositoryInfo) {
		this.repositoryInfo = repositoryInfo;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public ContentService getContentService() {
		return contentService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}
}
