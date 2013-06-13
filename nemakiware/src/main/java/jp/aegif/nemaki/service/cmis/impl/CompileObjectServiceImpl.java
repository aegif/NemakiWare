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
package jp.aegif.nemaki.service.cmis.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.model.constant.NemakiConstant;
import jp.aegif.nemaki.repository.NemakiRepositoryInfoImpl;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.PermissionService;
import jp.aegif.nemaki.service.cmis.RepositoryService;
import jp.aegif.nemaki.service.node.ContentService;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AllowableActionsImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChangeEventInfoDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyIdListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections.CollectionUtils;

public class CompileObjectServiceImpl implements CompileObjectService {

	private NemakiRepositoryInfoImpl repositoryInfo;
	private RepositoryService repositoryService;
	private ContentService contentService;
	private PermissionService permissionService;

	/**
	 * Builds a CMIS ObjectData from the given CouchDB content.
	 */
	public ObjectData compileObjectData(CallContext context, Content content,
			String filter, Boolean includeAllowableActions, Boolean includeAcl) {
		Boolean iaa = (includeAllowableActions == null ? false
				: includeAllowableActions.booleanValue());
		Boolean iacl = (includeAcl == null ? false : includeAcl.booleanValue());

		ObjectDataImpl result = new ObjectDataImpl();
		ObjectInfoImpl objectInfo = new ObjectInfoImpl();
		result.setProperties(compileProperties(content, splitFilter(filter),
				objectInfo));

		if (iaa) {
			result.setAllowableActions(compileAllowableActions(context, content));
		}

		iacl = true;
		if (iacl) {
			Acl acl = contentService.convertToCmisAcl(content, false);
			result.setAcl(acl);
			result.setIsExactAcl(true);
		}

		return result;
	}

	@Override
	public <T> ObjectList compileObjectDataList(CallContext callContext,
			List<T> contents, String filter, Boolean includeAllowableActions,
			Boolean includeAcl, BigInteger maxItems, BigInteger skipCount) {
		ObjectListImpl list = new ObjectListImpl();
		list.setObjects(new ArrayList<ObjectData>());

		// Convert skip and max to integer
		int skip = (skipCount == null ? 0 : skipCount.intValue());
		if (skip < 0) {
			skip = 0;
		}
		int max = (maxItems == null ? Integer.MAX_VALUE : maxItems.intValue());
		if (max < 0) {
			max = Integer.MAX_VALUE;
		}

		// Build ObjectList
		for (int i = skip; i < max; i++) {
			if (i == contents.size())
				break;

			T content = contents.get(i);
			if (content instanceof Content) {
				ObjectData o = compileObjectData(callContext,
						(Content) content, filter, includeAllowableActions,
						includeAcl);
				list.getObjects().add(o);
			} else {
				continue;
			}
		}

		list.setNumItems(BigInteger.valueOf(list.getObjects().size()));
		if (contents.size() != list.getObjects().size()) {
			list.setHasMoreItems(true);
		} else {
			list.setHasMoreItems(false);
		}

		return list;
	}

	@Override
	public ObjectList compileChangeDataList(CallContext context,
			List<Change> changes, Boolean includeProperties, String filter,
			Boolean includePolicyIds, Boolean includeAcl) {
		ObjectListImpl results = new ObjectListImpl();
		results.setObjects(new ArrayList<ObjectData>());

		Map<String, Content> cachedContents = new HashMap<String, Content>();
		if(changes != null && CollectionUtils.isNotEmpty(changes)){
			for (Change change : changes) {
				// Retrieve the content(using caches)
				String objectId = change.getId();
				Content content = new Content();
				if (cachedContents.containsKey(objectId)) {
					content = cachedContents.get(objectId);
				} else {
					content = contentService.getContentAsTheBaseType(objectId);
					cachedContents.put(objectId, content);
				}
				// Compile a change object data depending on its type
				results.getObjects().add(
						compileChangeObjectData(change, content, includePolicyIds,
								includeAcl));
			}
		}
		
		results.setNumItems(BigInteger.valueOf(results.getObjects().size()));

		return results;
	}

	private ObjectData compileChangeObjectData(Change change, Content content,
			Boolean includePolicyIds, Boolean includeAcl) {
		ObjectDataImpl o = new ObjectDataImpl();

		// Set Properties
		PropertiesImpl properties = new PropertiesImpl();
		setCmisBasicChangeProperties(properties, change);
		o.setProperties(properties);
		// Set PolicyIds
		setPolcyIds(o, change, includePolicyIds);
		// Set Acl
		if (!change.getChangeType().equals(ChangeType.DELETED)) {
			setAcl(o, content, includeAcl);
		}
		// Set Change Event
		setChangeEvent(o, change);

		return o;
	}

	private void setCmisBasicChangeProperties(PropertiesImpl props,
			Change change) {
		props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_ID, change
				.getObjectId()));
		props.addProperty(new PropertyIdImpl(PropertyIds.BASE_TYPE_ID, change
				.getBaseType()));
		props.addProperty(new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, change
				.getObjectType()));
		props.addProperty(new PropertyIdImpl(PropertyIds.NAME, change.getName()));
		if (change.isDocument()) {
			props.addProperty(new PropertyIdImpl(PropertyIds.VERSION_SERIES_ID,
					change.getVersionSeriesId()));
			props.addProperty(new PropertyStringImpl(PropertyIds.VERSION_LABEL,
					change.getVersionLabel()));
		}
	}

	private void setPolcyIds(ObjectDataImpl object, Change change,
			Boolean includePolicyids) {
		boolean iplc = (includePolicyids == null ? false : includePolicyids
				.booleanValue());
		if (iplc) {
			List<String> policyIds = change.getPolicyIds();
			PolicyIdListImpl plist = new PolicyIdListImpl();
			plist.setPolicyIds(policyIds);
			object.setPolicyIds(plist);
		}
	}

	private void setAcl(ObjectDataImpl object, Content content,
			Boolean includeAcl) {
		boolean iacl = (includeAcl == null ? false : includeAcl.booleanValue());
		if (iacl) {
			if (content != null)
				object.setAcl(contentService.convertToCmisAcl(content, false));
		}
	}

	private void setChangeEvent(ObjectDataImpl object, Change change) {
		// Set ChangeEventInfo
		ChangeEventInfoDataImpl ce = new ChangeEventInfoDataImpl();
		ce.setChangeType(change.getChangeType());
		ce.setChangeTime(change.getTime());
		object.setChangeEventInfo(ce);
	}

	/**
	 * Sets allowable action for the content
	 * 
	 * @param content
	 */
	public AllowableActions compileAllowableActions(CallContext callContext,
			Content content) {
		// Get parameters to calculate AllowableActions
		jp.aegif.nemaki.model.Acl contentAcl = content.getAcl();
		if (contentAcl == null)
			return null;
		Acl acl = contentService.convertToCmisAcl(content, false);
		Map<String, PermissionMapping> permissionMap = repositoryInfo
				.getAclCapabilities().getPermissionMapping();
		String baseType = content.getType();

		// Calculate AllowableActions
		Set<Action> actionSet = new HashSet<Action>();
		for (Entry<String, PermissionMapping> mappingEntry : permissionMap
				.entrySet()) {
			Boolean allowable = permissionService.checkPermission(callContext,
					mappingEntry.getKey(), acl, baseType, content);
			if (allowable) {
				String key = mappingEntry.getValue().getKey();
				actionSet.add(convertKeyToAction(key));
			}
		}
		AllowableActionsImpl allowableActions = new AllowableActionsImpl();
		allowableActions.setAllowableActions(actionSet);
		return allowableActions;
	}

	/**
	 * Compiles properties of a piece of content.
	 */
	public Properties compileProperties(Content content, Set<String> filter,
			ObjectInfoImpl objectInfo) {

		String typeId = null;
		PropertiesImpl properties = new PropertiesImpl();
		if (content.isFolder()) {
			Folder folder = (Folder) content;
			// Root folder
			if (folder.isRoot()) {
				properties = compileRootFolderProperties(folder, properties,
						typeId, filter);
				// Other than root folder
			} else {
				properties = compileFolderProperties(folder, properties,
						typeId, filter);
			}
		} else if (content.isDocument()) {
			Document document = (Document) content;
			properties = compileDocumentProperties(document, properties,
					typeId, filter);
		} else if (content.isRelationship()) {
			Relationship relationship = (Relationship) content;
			properties = compileRelationshipProperties(relationship,
					properties, typeId, filter);
		} else if (content.isPolicy()) {
			Policy policy = (Policy) content;
			properties = compilePolicyProperties(policy, properties, typeId,
					filter);
		}

		// Set Extension
		List<CmisExtensionElement> extensions = new ArrayList<CmisExtensionElement>();
		extensions.add(buildAspectsExtension(content.getAspects()));
		properties.setExtensions(extensions);

		return properties;
	}

	private PropertiesImpl compileRootFolderProperties(Folder folder,
			PropertiesImpl properties, String typeId, Set<String> filter) {
		typeId = TypeManager.FOLDER_TYPE_ID;
		setCmisBaseProperties(properties, typeId, filter, folder);
		// Add parentId property without value
		PropertyIdImpl parentId = new PropertyIdImpl();
		parentId.setId(PropertyIds.PARENT_ID);
		parentId.setValue(null);
		properties.addProperty(parentId);

		setCmisFolderProperties(properties, typeId, filter, folder);

		return properties;
	}

	private PropertiesImpl compileFolderProperties(Folder folder,
			PropertiesImpl properties, String typeId, Set<String> filter) {
		typeId = TypeManager.FOLDER_TYPE_ID;
		setCmisBaseProperties(properties, typeId, filter, folder);
		addPropertyBase(properties, typeId, filter, PropertyIds.PARENT_ID,
				folder.getParentId());
		setCmisFolderProperties(properties, typeId, filter, folder);

		return properties;
	}

	private PropertiesImpl compileDocumentProperties(Document document,
			PropertiesImpl properties, String typeId, Set<String> filter) {
		typeId = TypeManager.DOCUMENT_TYPE_ID;
		setCmisBaseProperties(properties, typeId, filter, document);
		setCmisDocumentProperties(properties, typeId, filter, document);

		AttachmentNode attachment = contentService.getAttachment(document
				.getAttachmentNodeId());
		if(attachment != null){
			setCmisAttachmentProperties(properties, typeId, filter, attachment,
					document);
		}else{
			//TODO Logging
		}

		return properties;
	}

	// TODO make enable to cope with dynamic sub type
	private PropertiesImpl compileRelationshipProperties(
			Relationship relationship, PropertiesImpl properties,
			String typeId, Set<String> filter) {
		typeId = TypeManager.RELATIONSHIP_TYPE_ID;
		setCmisBaseProperties(properties, typeId, filter, relationship);
		setCmisRelationshipProperties(properties, typeId, filter, relationship);
		return properties;
	}

	private PropertiesImpl compilePolicyProperties(Policy policy,
			PropertiesImpl properties, String typeId, Set<String> filter) {
		typeId = TypeManager.RELATIONSHIP_TYPE_ID;
		setCmisBaseProperties(properties, typeId, filter, policy);
		setCmisPolicyProperties(properties, typeId, filter, policy);
		return properties;
	}

	// TODO Add/Remove multiple value of a property

	/**
	 * Adds specified property in property set.
	 * 
	 * @param props
	 *            property set
	 * @param typeId
	 *            object type (e.g. cmis:document)
	 * @param filter
	 *            filter string set
	 * @param id
	 *            property ID
	 * @param value
	 *            actual property value
	 */
	private void addPropertyBase(PropertiesImpl props, String typeId,
			Set<String> filter, String id, Object value) {
		if (!checkAddProperty(props, typeId, filter, id))
			return;

		switch (repositoryService.getTypeManager().getTypeDefinition(typeId)
				.getPropertyDefinitions().get(id).getPropertyType()) {
		case BOOLEAN:
			props.addProperty(new PropertyBooleanImpl(id, (Boolean) value));
			break;
		case INTEGER:
			props.addProperty(new PropertyIntegerImpl(id, BigInteger
					.valueOf((Long) value)));
			break;
		case DATETIME:
			props.addProperty(new PropertyDateTimeImpl(id,
					(GregorianCalendar) value));
			break;
		case STRING:
			if(value == null){
				props.addProperty(new PropertyStringImpl(id, ""));
			}else{
				props.addProperty(new PropertyStringImpl(id, String.valueOf(value)));
			}
			break;
		case ID:
			props.addProperty(new PropertyIdImpl(id, String.valueOf(value)));
			break;

		default:
		}
	}

	/**
	 * Verifies that parameters are safe.
	 */
	private boolean checkAddProperty(Properties properties, String typeId,
			Set<String> filter, String id) {

		if ((properties == null) || (properties.getProperties() == null))
			throw new IllegalArgumentException("Properties must not be null!");

		if (id == null)
			throw new IllegalArgumentException("ID must not be null!");

		TypeDefinition type = repositoryService.getTypeManager()
				.getTypeDefinition(typeId);

		if (type == null)
			throw new IllegalArgumentException("Unknown type: " + typeId);

		if (!type.getPropertyDefinitions().containsKey(id))
			throw new IllegalArgumentException("Unknown property: " + id);

		String queryName = type.getPropertyDefinitions().get(id).getQueryName();

		if ((queryName != null) && (filter != null)) {
			if (!filter.contains(queryName)) {
				return false;
			} else {
				filter.remove(queryName);
			}
		}
		return true;
	}

	private void setCmisBaseProperties(
			PropertiesImpl properties, String typeId, Set<String> filter,
			Content content) {
		addPropertyBase(properties, typeId, filter, PropertyIds.NAME,
				content.getName());

		addPropertyBase(properties, typeId, filter, PropertyIds.DESCRIPTION,
				content.getDescription());

		addPropertyBase(properties, typeId, filter, PropertyIds.OBJECT_ID,
				content.getId());

		if (content.getCreated() != null)
			addPropertyBase(properties, typeId, filter,
					PropertyIds.CREATION_DATE, content.getCreated());

		if (content.getCreator() != null)
			addPropertyBase(properties, typeId, filter, PropertyIds.CREATED_BY,
					content.getCreator());

		if (content.getModified() != null) {
			addPropertyBase(properties, typeId, filter,
					PropertyIds.LAST_MODIFICATION_DATE, content.getModified());
		} else {
			addPropertyBase(properties, typeId, filter,
					PropertyIds.LAST_MODIFICATION_DATE, content.getCreated());
		}

		if (content.getModifier() != null) {
			addPropertyBase(properties, typeId, filter,
					PropertyIds.LAST_MODIFIED_BY, content.getModifier());
		} else {
			addPropertyBase(properties, typeId, filter,
					PropertyIds.LAST_MODIFIED_BY, content.getCreator());
		}

		addPropertyBase(properties, typeId, filter, PropertyIds.CHANGE_TOKEN,
				String.valueOf(content.getChangeToken()));
	}

	private void setCmisFolderProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Folder folder) {

		addPropertyBase(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_FOLDER.value());

		addPropertyBase(properties, typeId, filter, PropertyIds.OBJECT_TYPE_ID,
				TypeManager.FOLDER_TYPE_ID);

		addPropertyBase(properties, typeId, filter, PropertyIds.PATH,
				contentService.getPath(folder));

		if (checkAddProperty(properties, typeId, filter,
				PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS)) {
			List<String> values = new ArrayList<String>();
			if (CollectionUtils.isEmpty(folder.getAllowedChildTypeIds())) {
				values.add(BaseTypeId.CMIS_DOCUMENT.value());
				values.add(BaseTypeId.CMIS_FOLDER.value());
			} else {
				values = folder.getAllowedChildTypeIds();
			}
			PropertyData<String> pd = new PropertyIdImpl(
					PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS, values);
			properties.addProperty(pd);
		}
	}

	private void setCmisDocumentProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Document document) {

		addPropertyBase(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_DOCUMENT.value());
		addPropertyBase(properties, typeId, filter, PropertyIds.OBJECT_TYPE_ID,
				TypeManager.DOCUMENT_TYPE_ID);
		addPropertyBase(properties, typeId, filter, PropertyIds.IS_IMMUTABLE,
				false);
		addPropertyBase(properties, typeId, filter,
				PropertyIds.IS_LATEST_VERSION, document.isLatestVersion());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.IS_MAJOR_VERSION, document.isMajorVersion());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.IS_LATEST_MAJOR_VERSION,
				document.isLatestMajorVersion());
		addPropertyBase(properties, typeId, filter, PropertyIds.VERSION_LABEL,
				document.getVersionLabel());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.VERSION_SERIES_ID, document.getVersionSeriesId());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.CHECKIN_COMMENT, document.getCheckinComment());

		VersionSeries vs = contentService.getVersionSeries(document
				.getVersionSeriesId());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
				vs.isVersionSeriesCheckedOut());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
				vs.getVersionSeriesCheckedOutId());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
				vs.getVersionSeriesCheckedOutBy());
	}

	private void setCmisAttachmentProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, AttachmentNode attachment,
			Content document) {
		addPropertyBase(properties, typeId, filter,
				PropertyIds.CONTENT_STREAM_LENGTH, attachment.getLength());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.CONTENT_STREAM_MIME_TYPE, attachment.getMimeType());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.CONTENT_STREAM_FILE_NAME, document.getName());
		addPropertyBase(properties, typeId, filter,
				PropertyIds.CONTENT_STREAM_ID, attachment.getId());
	}

	private void setCmisRelationshipProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Relationship relationship) {

		addPropertyBase(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_RELATIONSHIP.value());

		addPropertyBase(properties, typeId, filter, PropertyIds.OBJECT_TYPE_ID,
				TypeManager.RELATIONSHIP_TYPE_ID);

		addPropertyBase(properties, typeId, filter, PropertyIds.SOURCE_ID,
				relationship.getSourceId());
		addPropertyBase(properties, typeId, filter, PropertyIds.TARGET_ID,
				relationship.getSourceId());
	}

	private void setCmisPolicyProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Policy policy) {
		addPropertyBase(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_POLICY.value());

		addPropertyBase(properties, typeId, filter, PropertyIds.OBJECT_TYPE_ID,
				TypeManager.POLICY_TYPE_ID);

		addPropertyBase(properties, typeId, filter, PropertyIds.POLICY_TEXT,
				policy.getPolicyText());
	}

	/**
	 * Separates filter string with ','. If filter is null or empty, it means
	 * all properties can go.
	 */
	// TODO implement CMIS filterNotValid exception?
	// NOTE: "not set" can mean "all properties" and invalid queryName should be
	// ignored.
	// NOTE: So, filterNotValid exception might not be needed.
	public Set<String> splitFilter(String filter) {
		final String ASTERISK = "*";
		final String COMMA = ",";

		if (filter == null || filter.trim().length() == 0) {
			return null;
		}
		Set<String> filters = new HashSet<String>();
		for (String s : filter.split(COMMA)) {
			s = s.trim();
			if (s.equals(ASTERISK)) {
				return null;
			} else if (s.length() > 0) {
				filters.add(s);
			}
		}
		// set a few base properties
		// query name == id (for base type properties)
		filters.add(PropertyIds.OBJECT_ID);
		filters.add(PropertyIds.OBJECT_TYPE_ID);
		filters.add(PropertyIds.BASE_TYPE_ID);
		return filters;
	}

	// FIXME move to AspectService
	/**
	 * Build extension for outputting aspects
	 */
	private CmisExtensionElement buildAspectsExtension(List<Aspect> aspects) {
		final String ns = NemakiConstant.NAMESPACE_ASPECTS;

		List<CmisExtensionElement> aspectsExtension = new ArrayList<CmisExtensionElement>();

		for (int i = 0; i < aspects.size(); i++) {
			Aspect aspect = aspects.get(i);
			Map<String, String> aspectAttr = new HashMap<String, String>();
			aspectAttr.put(NemakiConstant.EXTATTR_ASPECT_ID, aspect.getName());

			List<CmisExtensionElement> propsExtension = new ArrayList<CmisExtensionElement>();
			List<Property> props = aspect.getProperties();
			for (Property prop : props) {
				Map<String, String> propAttr = new HashMap<String, String>();
				propAttr.put(NemakiConstant.EXTATTR_ASPECT_ID, prop.getKey());
				propsExtension.add(new CmisExtensionElementImpl(ns,
						NemakiConstant.EXTNAME_ASPECT_PROPERTY, propAttr, prop
								.getValue().toString()));
			}

			aspectsExtension.add(new CmisExtensionElementImpl(ns,
					NemakiConstant.EXTNAME_ASPECT, aspectAttr, propsExtension));

		}

		return new CmisExtensionElementImpl(ns, NemakiConstant.EXTNAME_ASPECTS,
				null, aspectsExtension);
	}

	private Action convertKeyToAction(String key) {
		// NavigationServices
		if (PermissionMapping.CAN_GET_DESCENDENTS_FOLDER.equals(key))
			return Action.CAN_GET_DESCENDANTS;
		if (PermissionMapping.CAN_GET_CHILDREN_FOLDER.equals(key))
			return Action.CAN_GET_CHILDREN;
		if (PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT.equals(key))
			return Action.CAN_GET_FOLDER_PARENT;
		if (PermissionMapping.CAN_GET_PARENTS_FOLDER.equals(key))
			return Action.CAN_GET_OBJECT_PARENTS;
		// Object Services
		if (PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER.equals(key))
			return Action.CAN_CREATE_DOCUMENT;
		if (PermissionMapping.CAN_CREATE_FOLDER_FOLDER.equals(key))
			return Action.CAN_CREATE_FOLDER;
		if ("canCreatePolicy.Folder".equals(key))
			return null; // FIXME the constant already implemented?
		if (PermissionMapping.CAN_CREATE_RELATIONSHIP_SOURCE.equals(key))
			return Action.CAN_CREATE_RELATIONSHIP;
		if (PermissionMapping.CAN_CREATE_RELATIONSHIP_TARGET.equals(key))
			return Action.CAN_CREATE_RELATIONSHIP;
		if (PermissionMapping.CAN_GET_PROPERTIES_OBJECT.equals(key))
			return Action.CAN_GET_PROPERTIES;
		if (PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT.equals(key))
			return Action.CAN_UPDATE_PROPERTIES;
		if (PermissionMapping.CAN_MOVE_OBJECT.equals(key))
			return Action.CAN_MOVE_OBJECT;
		if (PermissionMapping.CAN_MOVE_TARGET.equals(key))
			return Action.CAN_MOVE_OBJECT;
		if (PermissionMapping.CAN_MOVE_SOURCE.equals(key))
			return Action.CAN_MOVE_OBJECT;
		if (PermissionMapping.CAN_DELETE_OBJECT.equals(key))
			return Action.CAN_DELETE_OBJECT;
		if (PermissionMapping.CAN_VIEW_CONTENT_OBJECT.equals(key))
			return Action.CAN_GET_CONTENT_STREAM;
		if (PermissionMapping.CAN_SET_CONTENT_DOCUMENT.equals(key))
			return Action.CAN_SET_CONTENT_STREAM;
		if (PermissionMapping.CAN_DELETE_CONTENT_DOCUMENT.equals(key))
			return Action.CAN_DELETE_CONTENT_STREAM;
		if (PermissionMapping.CAN_DELETE_TREE_FOLDER.equals(key))
			return Action.CAN_DELETE_TREE;
		// Filing Services
		if (PermissionMapping.CAN_ADD_TO_FOLDER_OBJECT.equals(key))
			return Action.CAN_ADD_OBJECT_TO_FOLDER;
		if (PermissionMapping.CAN_ADD_TO_FOLDER_OBJECT.equals(key))
			return Action.CAN_ADD_OBJECT_TO_FOLDER;
		if (PermissionMapping.CAN_REMOVE_FROM_FOLDER_OBJECT.equals(key))
			return Action.CAN_REMOVE_OBJECT_FROM_FOLDER;
		if (PermissionMapping.CAN_REMOVE_FROM_FOLDER_FOLDER.equals(key))
			return Action.CAN_REMOVE_OBJECT_FROM_FOLDER;
		// Versioning Services
		if (PermissionMapping.CAN_CHECKOUT_DOCUMENT.equals(key))
			return Action.CAN_CHECK_OUT;
		if (PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT.equals(key))
			return Action.CAN_CANCEL_CHECK_OUT;
		if (PermissionMapping.CAN_CHECKIN_DOCUMENT.equals(key))
			return Action.CAN_CHECK_IN;
		if (PermissionMapping.CAN_GET_ALL_VERSIONS_VERSION_SERIES.equals(key))
			return Action.CAN_GET_ALL_VERSIONS;
		// Relationship Services
		if (PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT.equals(key))
			return Action.CAN_GET_OBJECT_RELATIONSHIPS;
		// Policy Services
		if (PermissionMapping.CAN_ADD_POLICY_OBJECT.equals(key))
			return Action.CAN_APPLY_POLICY;
		if (PermissionMapping.CAN_ADD_POLICY_POLICY.equals(key))
			return Action.CAN_APPLY_POLICY;
		if (PermissionMapping.CAN_REMOVE_POLICY_OBJECT.equals(key))
			return Action.CAN_REMOVE_POLICY;
		if (PermissionMapping.CAN_REMOVE_POLICY_POLICY.equals(key))
			return Action.CAN_REMOVE_POLICY;
		if (PermissionMapping.CAN_GET_APPLIED_POLICIES_OBJECT.equals(key))
			return Action.CAN_GET_APPLIED_POLICIES;
		// ACL Services
		if (PermissionMapping.CAN_GET_ACL_OBJECT.equals(key))
			return Action.CAN_GET_ACL;
		if (PermissionMapping.CAN_APPLY_ACL_OBJECT.equals(key))
			return Action.CAN_APPLY_ACL;

		return null;
	}

	public void setRepositoryInfo(NemakiRepositoryInfoImpl repositoryInfo) {
		this.repositoryInfo = repositoryInfo;
	}

	public void setRepositoryService(RepositoryService repositoryService) {
		this.repositoryService = repositoryService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}

	public void setPermissionService(PermissionService permissionService) {
		this.permissionService = permissionService;
	}
}
