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

import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Aspect;
import jp.aegif.nemaki.model.AttachmentNode;
import jp.aegif.nemaki.model.Change;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Item;
import jp.aegif.nemaki.model.Policy;
import jp.aegif.nemaki.model.Property;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.Rendition;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.repository.info.NemakiRepositoryInfoImpl;
import jp.aegif.nemaki.repository.type.TypeManager;
import jp.aegif.nemaki.service.cmis.CompileObjectService;
import jp.aegif.nemaki.service.cmis.PermissionService;
import jp.aegif.nemaki.service.cmis.RepositoryService;
import jp.aegif.nemaki.service.node.ContentService;
import jp.aegif.nemaki.util.PropertyUtil;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.data.RepositoryCapabilities;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AllowableActionsImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ChangeEventInfoDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ObjectListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PolicyIdListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyBooleanImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyDateTimeImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIntegerImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RenditionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.server.ObjectInfoImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class CompileObjectServiceImpl implements CompileObjectService {

	private static final Log log = LogFactory
			.getLog(CompileObjectServiceImpl.class);

	private NemakiRepositoryInfoImpl repositoryInfo;
	private RepositoryService repositoryService;
	private ContentService contentService;
	private PermissionService permissionService;
	private TypeManager typeManager;
	private PropertyUtil propertyUtil;

	private Map<String, String> aliases;

	/**
	 * Builds a CMIS ObjectData from the given CouchDB content.
	 */
	@Override
	public ObjectData compileObjectData(CallContext context, Content content,
			String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includeAcl, Map<String, String> aliases) {
		Boolean iaa = (includeAllowableActions == null ? false
				: includeAllowableActions.booleanValue());
		Boolean iacl = (includeAcl == null ? false : includeAcl.booleanValue());
		IncludeRelationships irl = (includeRelationships == null ? IncludeRelationships.SOURCE
				: includeRelationships);
		this.aliases = aliases;

		ObjectDataImpl result = new ObjectDataImpl();
		ObjectInfoImpl objectInfo = new ObjectInfoImpl();
		result.setProperties(compileProperties(content, splitFilter(filter),
				objectInfo));

		// Set Allowable actions
		if (iaa) {
			result.setAllowableActions(compileAllowableActions(context, content));
		}

		// Set Acl
		if (iacl) {
			Acl acl = contentService.calculateAcl(content);
			result.setIsExactAcl(true);
			result.setAcl(propertyUtil.convertToCmisAcl(acl,
					content.isAclInherited(), false));
		}

		// Set Relationships
		if (!content.isRelationship()) {
			result.setRelationships(compileRelationships(context, content, irl));
		}

		// Set Renditions
		if (content.isDocument()
				&& repositoryInfo.getCapabilities().getRenditionsCapability() == CapabilityRenditions.READ) {
			result.setRenditions(compileRenditions(context, content));
		}

		aliases = null;

		return result;
	}

	@Override
	public <T> ObjectList compileObjectDataList(CallContext callContext,
			List<T> contents, String filter, Boolean includeAllowableActions,
			IncludeRelationships includeRelationships, String renditionFilter,
			Boolean includeAcl, BigInteger maxItems, BigInteger skipCount,
			Map<String, String> aliases) {
		ObjectListImpl list = new ObjectListImpl();
		list.setObjects(new ArrayList<ObjectData>());

		if (CollectionUtils.isEmpty(contents)) {
			list.setNumItems(BigInteger.ZERO);
			list.setHasMoreItems(false);
			return list;
		}

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
						IncludeRelationships.NONE, null, includeAcl, aliases);
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
			List<Change> changes, Holder<String> changeLogToken,
			Boolean includeProperties, String filter, Boolean includePolicyIds,
			Boolean includeAcl) {
		ObjectListImpl results = new ObjectListImpl();
		results.setObjects(new ArrayList<ObjectData>());

		Map<String, Content> cachedContents = new HashMap<String, Content>();
		if (changes != null && CollectionUtils.isNotEmpty(changes)) {
			for (Change change : changes) {
				// Retrieve the content(using caches)
				String objectId = change.getId();
				Content content = new Content();
				if (cachedContents.containsKey(objectId)) {
					content = cachedContents.get(objectId);
				} else {
					content = contentService.getContent(objectId);
					cachedContents.put(objectId, content);
				}
				// Compile a change object data depending on its type
				results.getObjects().add(
						compileChangeObjectData(change, content,
								includePolicyIds, includeAcl));
			}
		}

		results.setNumItems(BigInteger.valueOf(results.getObjects().size()));

		String latestInRepository = repositoryService.getRepositoryInfo()
				.getLatestChangeLogToken();
		String latestInResults = changeLogToken.getValue();
		if (latestInResults.equals(latestInRepository)) {
			results.setHasMoreItems(false);
		} else {
			results.setHasMoreItems(true);
		}
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
		if (change.isOnDocument()) {
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
			if (content != null) {
				Acl acl = contentService.calculateAcl(content);
				object.setAcl(propertyUtil.convertToCmisAcl(acl,
						content.isAclInherited(), false));
			}
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
	@Override
	public AllowableActions compileAllowableActions(CallContext callContext,
			Content content) {
		// Get parameters to calculate AllowableActions
		TypeDefinition tdf = typeManager.getTypeDefinition(content
				.getObjectType());
		jp.aegif.nemaki.model.Acl contentAcl = content.getAcl();
		if (tdf.isControllableAcl() && contentAcl == null)
			return null;
		Acl acl = contentService.calculateAcl(content);
		Map<String, PermissionMapping> permissionMap = repositoryInfo
				.getAclCapabilities().getPermissionMapping();
		String baseType = content.getType();

		// Calculate AllowableActions
		Set<Action> actionSet = new HashSet<Action>();
		for (Entry<String, PermissionMapping> mappingEntry : permissionMap
				.entrySet()) {
			String key = mappingEntry.getValue().getKey();
			// TODO WORKAROUND. implement class cast check

			// FIXME WORKAROUND: skip canCreatePolicy.Folder
			if (PermissionMapping.CAN_CREATE_POLICY_FOLDER.equals(key)) {
				continue;
			}

			// Additional check
			if (!isAllowableByCapability(key)) {
				continue;
			}
			if (!isAllowableByType(key, content, tdf)) {
				continue;
			}
			if (propertyUtil.isRoot(content)) {
				if (Action.CAN_MOVE_OBJECT == convertKeyToAction(key)) {
					continue;
				}
			}
			if (content.isDocument()) {
				Document d = (Document) content;
				DocumentTypeDefinition dtdf = (DocumentTypeDefinition) tdf;
				if (!isAllowableActionForVersionableDocument(callContext,
						mappingEntry.getKey(), d, dtdf)) {
					continue;
				}
			}

			// Add an allowable action
			boolean allowable = permissionService.checkPermission(callContext,
					mappingEntry.getKey(), acl, baseType, content);

			if (allowable) {
				actionSet.add(convertKeyToAction(key));
			}
		}
		AllowableActionsImpl allowableActions = new AllowableActionsImpl();
		allowableActions.setAllowableActions(actionSet);
		return allowableActions;
	}

	private boolean isAllowableActionForVersionableDocument(
			CallContext callContext, String permissionMappingKey,
			Document document, DocumentTypeDefinition dtdf) {
		
		VersionSeries vs = contentService.getVersionSeries(document
				.getVersionSeriesId());
		//Versioning action(checkOut / checkIn)
		if (permissionMappingKey
				.equals(PermissionMapping.CAN_CHECKOUT_DOCUMENT)) {
			return dtdf.isVersionable() && !vs.isVersionSeriesCheckedOut() && document.isLatestVersion();
		} else if (permissionMappingKey
				.equals(PermissionMapping.CAN_CHECKIN_DOCUMENT)) {
			return dtdf.isVersionable() && vs.isVersionSeriesCheckedOut() && document.isPrivateWorkingCopy();
		} else if (permissionMappingKey
				.equals(PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT)) {
			return dtdf.isVersionable() && vs.isVersionSeriesCheckedOut() && document.isPrivateWorkingCopy();
		}
		
		
		//Lock as an effect of checkOut
		if(dtdf.isVersionable()){
			if(isLockableAction(permissionMappingKey)){
				if(document.isLatestVersion()){
					//LocK only when checked out
					return !vs.isVersionSeriesCheckedOut();
				}else if(document.isPrivateWorkingCopy()){
					//Only owner can do actions on pwc
					return callContext.getUsername().equals(vs.getVersionSeriesCheckedOutBy());
				}else{
					return false;
				}
			}
		}

		return true;
	}

	private boolean isLockableAction(String key) {
		return key.equals(PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT)
				|| key.equals(PermissionMapping.CAN_SET_CONTENT_DOCUMENT)
				|| key.equals(PermissionMapping.CAN_ADD_POLICY_OBJECT)
				|| key.equals(PermissionMapping.CAN_ADD_TO_FOLDER_OBJECT)
				|| key.equals(PermissionMapping.CAN_APPLY_ACL_OBJECT)
				|| key.equals(PermissionMapping.CAN_DELETE_CONTENT_DOCUMENT)
				|| key.equals(PermissionMapping.CAN_DELETE_OBJECT)
				|| key.equals(PermissionMapping.CAN_MOVE_OBJECT)
				|| key.equals(PermissionMapping.CAN_REMOVE_FROM_FOLDER_OBJECT)
				|| key.equals(PermissionMapping.CAN_REMOVE_POLICY_OBJECT);
	}

	private boolean isAllowableByCapability(String key) {
		RepositoryCapabilities capabilities = repositoryInfo.getCapabilities();

		// Multifiling or Unfiling Capabilities
		if (PermissionMapping.CAN_ADD_TO_FOLDER_OBJECT.equals(key)
				|| PermissionMapping.CAN_ADD_TO_FOLDER_FOLDER.equals(key)) {
			// This is not a explicit spec, but it's plausible.
			return capabilities.isUnfilingSupported()
					|| capabilities.isMultifilingSupported();
		} else if (PermissionMapping.CAN_REMOVE_FROM_FOLDER_OBJECT.equals(key)
				|| PermissionMapping.CAN_REMOVE_FROM_FOLDER_FOLDER.equals(key)) {
			return capabilities.isUnfilingSupported();

			// GetDescendents or GetFolderTree Capabilities
		} else if (PermissionMapping.CAN_GET_DESCENDENTS_FOLDER.equals(key)) {
			return capabilities.isGetDescendantsSupported()
					|| capabilities.isGetFolderTreeSupported();
		} else {
			return true;
		}
	}

	private boolean isAllowableByType(String key, Content content,
			TypeDefinition tdf) {
		// ControllableACL
		if (PermissionMapping.CAN_APPLY_ACL_OBJECT.equals(key)) {
			// Default to FALSE
			boolean ctrlAcl = (tdf.isControllableAcl() == null) ? false : tdf
					.isControllableAcl();
			return ctrlAcl;

			// ControllablePolicy
		} else if (PermissionMapping.CAN_ADD_POLICY_OBJECT.equals(key)
				|| PermissionMapping.CAN_ADD_POLICY_POLICY.equals(key)
				|| PermissionMapping.CAN_REMOVE_POLICY_OBJECT.equals(key)
				|| PermissionMapping.CAN_REMOVE_POLICY_POLICY.equals(key)) {
			// Default to FALSE
			boolean ctrlPolicy = (tdf.isControllablePolicy() == null) ? false
					: tdf.isControllablePolicy();
			return ctrlPolicy;

			// setContent
		} else if (PermissionMapping.CAN_SET_CONTENT_DOCUMENT.equals(key)) {
			if (BaseTypeId.CMIS_DOCUMENT != tdf.getBaseTypeId())
				return true;

			DocumentTypeDefinition _tdf = (DocumentTypeDefinition) tdf;
			// Default to REQUIRED
			ContentStreamAllowed csa = (_tdf.getContentStreamAllowed() == null) ? ContentStreamAllowed.REQUIRED
					: _tdf.getContentStreamAllowed();
			return !(csa == ContentStreamAllowed.NOTALLOWED);

			// deleteContent
		} else if (PermissionMapping.CAN_DELETE_CONTENT_DOCUMENT.equals(key)) {
			if (BaseTypeId.CMIS_DOCUMENT != tdf.getBaseTypeId())
				return true;

			DocumentTypeDefinition _tdf = (DocumentTypeDefinition) tdf;
			// Default to REQUIRED
			ContentStreamAllowed csa = (_tdf.getContentStreamAllowed() == null) ? ContentStreamAllowed.REQUIRED
					: _tdf.getContentStreamAllowed();
			return !(csa == ContentStreamAllowed.REQUIRED);

		} else {
			return true;
		}
	}

	private List<ObjectData> compileRelationships(CallContext context,
			Content content, IncludeRelationships irl) {
		if (IncludeRelationships.NONE == irl) {
			return null;
		}

		RelationshipDirection rd;
		switch (irl) {
		case SOURCE:
			rd = RelationshipDirection.SOURCE;
			break;
		case TARGET:
			rd = RelationshipDirection.TARGET;
			break;
		case BOTH:
			rd = RelationshipDirection.EITHER;
			break;
		default:
			rd = RelationshipDirection.SOURCE;
			break;
		}
		List<Relationship> _rels = contentService.getRelationsipsOfObject(
				content.getId(), rd);

		List<ObjectData> rels = new ArrayList<ObjectData>();
		if (CollectionUtils.isNotEmpty(_rels)) {
			for (Relationship _rel : _rels) {
				ObjectData rel = compileObjectData(context, _rel, null, false,
						IncludeRelationships.NONE, null, false, aliases);
				rels.add(rel);
			}
		}

		return rels;
	}

	private List<RenditionData> compileRenditions(CallContext callContext,
			Content content) {
		List<RenditionData> renditions = new ArrayList<RenditionData>();

		List<Rendition> _renditions = contentService.getRenditions(content
				.getId());
		if (CollectionUtils.isNotEmpty(_renditions)) {
			for (Rendition _rd : _renditions) {
				RenditionDataImpl rd = new RenditionDataImpl();
				rd.setStreamId(_rd.getId());
				rd.setMimeType(_rd.getMimetype());
				rd.setBigLength(BigInteger.valueOf(_rd.getLength()));
				rd.setKind(_rd.getKind());
				rd.setTitle(_rd.getTitle());
				rd.setBigHeight(BigInteger.valueOf(_rd.getHeight()));
				rd.setBigWidth(BigInteger.valueOf(_rd.getWidth()));
				rd.setRenditionDocumentId(_rd.getRenditionDocumentId());

				renditions.add(rd);
			}
		}

		return renditions;
	}

	/**
	 * Compiles properties of a piece of content.
	 */
	@Override
	public Properties compileProperties(Content content, Set<String> filter,
			ObjectInfoImpl objectInfo) {

		PropertiesImpl properties = new PropertiesImpl();
		if (content.isFolder()) {
			Folder folder = (Folder) content;
			// Root folder
			if (propertyUtil.isRoot(folder)) {
				properties = compileRootFolderProperties(folder, properties,
						filter);
				// Other than root folder
			} else {
				properties = compileFolderProperties(folder, properties, filter);
			}
		} else if (content.isDocument()) {
			Document document = (Document) content;
			properties = compileDocumentProperties(document, properties, filter);
		} else if (content.isRelationship()) {
			Relationship relationship = (Relationship) content;
			properties = compileRelationshipProperties(relationship,
					properties, filter);
		} else if (content.isPolicy()) {
			Policy policy = (Policy) content;
			properties = compilePolicyProperties(policy, properties, filter);
		} else if (content.isItem()) {
			Item item = (Item) content;
			properties = compileItemProperties(item, properties, filter);
		}

		return properties;
	}

	private PropertiesImpl compileRootFolderProperties(Folder folder,
			PropertiesImpl properties, Set<String> filter) {
		String typeId = folder.getObjectType();
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
			PropertiesImpl properties, Set<String> filter) {
		String typeId = folder.getObjectType();
		setCmisBaseProperties(properties, typeId, filter, folder);
		addProperty(properties, typeId, filter, PropertyIds.PARENT_ID,
				folder.getParentId());
		setCmisFolderProperties(properties, typeId, filter, folder);

		return properties;
	}

	private PropertiesImpl compileDocumentProperties(Document document,
			PropertiesImpl properties, Set<String> filter) {
		String typeId = document.getObjectType();
		setCmisBaseProperties(properties, typeId, filter, document);
		setCmisDocumentProperties(properties, typeId, filter, document);

		AttachmentNode attachment = contentService.getAttachmentRef(document
				.getAttachmentNodeId());
		if (attachment != null) {
			setCmisAttachmentProperties(properties, typeId, filter, attachment,
					document);

		} else {
			// TODO Logging
		}

		return properties;
	}

	private PropertiesImpl compileRelationshipProperties(
			Relationship relationship, PropertiesImpl properties,
			Set<String> filter) {
		String typeId = relationship.getObjectType();
		setCmisBaseProperties(properties, typeId, filter, relationship);
		setCmisRelationshipProperties(properties, typeId, filter, relationship);
		return properties;
	}

	private PropertiesImpl compilePolicyProperties(Policy policy,
			PropertiesImpl properties, Set<String> filter) {
		String typeId = policy.getObjectType();
		setCmisBaseProperties(properties, typeId, filter, policy);
		setCmisPolicyProperties(properties, typeId, filter, policy);
		return properties;
	}

	private PropertiesImpl compileItemProperties(Item item,
			PropertiesImpl properties, Set<String> filter) {
		String typeId = item.getObjectType();
		setCmisBaseProperties(properties, typeId, filter, item);
		setCmisItemProperties(properties, typeId, filter, item);
		return properties;
	}

	private void setCmisBaseProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Content content) {
		addProperty(properties, typeId, filter, PropertyIds.NAME,
				content.getName());

		addProperty(properties, typeId, filter, PropertyIds.DESCRIPTION,
				content.getDescription());

		addProperty(properties, typeId, filter, PropertyIds.OBJECT_ID,
				content.getId());

		addProperty(properties, typeId, filter, PropertyIds.OBJECT_TYPE_ID,
				content.getObjectType());

		if (content.getCreated() != null)
			addProperty(properties, typeId, filter, PropertyIds.CREATION_DATE,
					content.getCreated());

		if (content.getCreator() != null)
			addProperty(properties, typeId, filter, PropertyIds.CREATED_BY,
					content.getCreator());

		if (content.getModified() != null) {
			addProperty(properties, typeId, filter,
					PropertyIds.LAST_MODIFICATION_DATE, content.getModified());
		} else {
			addProperty(properties, typeId, filter,
					PropertyIds.LAST_MODIFICATION_DATE, content.getCreated());
		}

		if (content.getModifier() != null) {
			addProperty(properties, typeId, filter,
					PropertyIds.LAST_MODIFIED_BY, content.getModifier());
		} else {
			addProperty(properties, typeId, filter,
					PropertyIds.LAST_MODIFIED_BY, content.getCreator());
		}

		addProperty(properties, typeId, filter, PropertyIds.CHANGE_TOKEN,
				String.valueOf(content.getChangeToken()));

		// TODO If subType properties is not registered in DB, return void
		// properties via CMIS
		// SubType properties
		List<PropertyDefinition<?>> specificPropertyDefinitions = typeManager
				.getSpecificPropertyDefinitions(typeId);
		if (!CollectionUtils.isEmpty(specificPropertyDefinitions)) {
			for (PropertyDefinition<?> propertyDefinition : specificPropertyDefinitions) {
				Property property = extractSubTypeProperty(content,
						propertyDefinition.getId());
				Object value = (property == null) ? null : property.getValue();
				addProperty(properties, content.getObjectType(), filter,
						propertyDefinition.getId(), value);
			}
		}

		// Secondary properties
		setCmisSecondaryTypes(properties, content, typeId, filter);
	}

	private Property extractSubTypeProperty(Content content, String propertyId) {
		List<Property> subTypeProperties = content.getSubTypeProperties();
		if (CollectionUtils.isNotEmpty(subTypeProperties)) {
			for (Property subTypeProperty : subTypeProperties) {
				if (subTypeProperty.getKey().equals(propertyId)) {
					return subTypeProperty;
				}
			}
		}

		return null;
	}

	private void setCmisFolderProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Folder folder) {

		addProperty(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_FOLDER.value());
		addProperty(properties, typeId, filter, PropertyIds.PATH,
				contentService.calculatePath(folder));

		if (checkAddProperty(properties, typeId, filter,
				PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS)) {
			List<String> values = new ArrayList<String>();
			// If not specified, all child types are allowed.
			if (!CollectionUtils.isEmpty(folder.getAllowedChildTypeIds())) {
				values = folder.getAllowedChildTypeIds();
			}
			PropertyData<String> pd = new PropertyIdImpl(
					PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS, values);
			properties.addProperty(pd);
		}
	}

	private void setCmisDocumentProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Document document) {

		addProperty(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_DOCUMENT.value());

		Boolean isImmutable = (document.isImmutable() == null) ? (Boolean) typeManager
				.getSingleDefaultValue(PropertyIds.IS_IMMUTABLE, typeId)
				: document.isImmutable();
		addProperty(properties, typeId, filter, PropertyIds.IS_IMMUTABLE,
				isImmutable);

		DocumentTypeDefinition type = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(typeId);
		if (type.isVersionable()) {
			addProperty(properties, typeId, filter,
					PropertyIds.IS_PRIVATE_WORKING_COPY,
					document.isPrivateWorkingCopy());
			addProperty(properties, typeId, filter,
					PropertyIds.IS_LATEST_VERSION, document.isLatestVersion());
			addProperty(properties, typeId, filter,
					PropertyIds.IS_MAJOR_VERSION, document.isMajorVersion());
			addProperty(properties, typeId, filter,
					PropertyIds.IS_LATEST_MAJOR_VERSION,
					document.isLatestMajorVersion());
			addProperty(properties, typeId, filter, PropertyIds.VERSION_LABEL,
					document.getVersionLabel());
			addProperty(properties, typeId, filter,
					PropertyIds.VERSION_SERIES_ID,
					document.getVersionSeriesId());
			addProperty(properties, typeId, filter,
					PropertyIds.CHECKIN_COMMENT, document.getCheckinComment());

			VersionSeries vs = contentService.getVersionSeries(document
					.getVersionSeriesId());
			addProperty(properties, typeId, filter,
					PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
					vs.isVersionSeriesCheckedOut());
			if (vs.isVersionSeriesCheckedOut()) {
				addProperty(properties, typeId, filter,
						PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
						vs.getVersionSeriesCheckedOutId());
				addProperty(properties, typeId, filter,
						PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
						vs.getVersionSeriesCheckedOutBy());
			} else {
				addProperty(properties, typeId, filter,
						PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, null);
				addProperty(properties, typeId, filter,
						PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, null);
			}

			// TODO comment
		} else {
			addProperty(properties, typeId, filter,
					PropertyIds.IS_PRIVATE_WORKING_COPY, false);
			addProperty(properties, typeId, filter,
					PropertyIds.IS_LATEST_VERSION, false);
			addProperty(properties, typeId, filter,
					PropertyIds.IS_MAJOR_VERSION, false);
			addProperty(properties, typeId, filter,
					PropertyIds.IS_LATEST_MAJOR_VERSION, false);
			addProperty(properties, typeId, filter, PropertyIds.VERSION_LABEL,
					"");
			addProperty(properties, typeId, filter,
					PropertyIds.VERSION_SERIES_ID, "");
			addProperty(properties, typeId, filter,
					PropertyIds.CHECKIN_COMMENT, "");
			addProperty(properties, typeId, filter,
					PropertyIds.IS_VERSION_SERIES_CHECKED_OUT, false);

			addProperty(properties, typeId, filter,
					PropertyIds.VERSION_SERIES_CHECKED_OUT_ID, "");
			addProperty(properties, typeId, filter,
					PropertyIds.VERSION_SERIES_CHECKED_OUT_BY, "");
		}
	}

	private void setCmisAttachmentProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, AttachmentNode attachment,
			Content document) {
		addProperty(properties, typeId, filter,
				PropertyIds.CONTENT_STREAM_LENGTH, attachment.getLength());
		addProperty(properties, typeId, filter,
				PropertyIds.CONTENT_STREAM_MIME_TYPE, attachment.getMimeType());
		addProperty(properties, typeId, filter,
				PropertyIds.CONTENT_STREAM_FILE_NAME, document.getName());
		addProperty(properties, typeId, filter, PropertyIds.CONTENT_STREAM_ID,
				attachment.getId());
	}

	private void setCmisRelationshipProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Relationship relationship) {
		addProperty(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_RELATIONSHIP.value());
		addProperty(properties, typeId, filter, PropertyIds.SOURCE_ID,
				relationship.getSourceId());
		addProperty(properties, typeId, filter, PropertyIds.TARGET_ID,
				relationship.getTargetId());
	}

	private void setCmisPolicyProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Policy policy) {
		addProperty(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_POLICY.value());
		addProperty(properties, typeId, filter, PropertyIds.POLICY_TEXT,
				policy.getPolicyText());
	}

	private void setCmisItemProperties(PropertiesImpl properties,
			String typeId, Set<String> filter, Item item) {
		addProperty(properties, typeId, filter, PropertyIds.BASE_TYPE_ID,
				BaseTypeId.CMIS_ITEM.value());
	}

	private void setCmisSecondaryTypes(PropertiesImpl props, Content content,
			String typeId, Set<String> filter) {
		List<Aspect> aspects = content.getAspects();
		List<String> secondaryIds = new ArrayList<String>();

		// cmis:secondaryObjectTypeIds
		if (CollectionUtils.isNotEmpty(content.getSecondaryIds())) {
			for (String secondaryId : content.getSecondaryIds()) {
				secondaryIds.add(secondaryId);
			}
		}

		new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondaryIds);
		addProperty(props, typeId, filter,
				PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondaryIds);

		// each secondary properties
		for (String secondaryId : secondaryIds) {
			List<PropertyDefinition<?>> secondaryPropertyDefinitions = typeManager
					.getSpecificPropertyDefinitions(secondaryId);
			if (CollectionUtils.isEmpty(secondaryPropertyDefinitions))
				continue;

			Aspect aspect = extractAspect(aspects, secondaryId);
			List<Property> properties = (aspect == null) ? new ArrayList<Property>()
					: aspect.getProperties();

			for (PropertyDefinition<?> secondaryPropertyDefinition : secondaryPropertyDefinitions) {
				Property property = extractProperty(properties,
						secondaryPropertyDefinition.getId());
				Object value = (property == null) ? null : property.getValue();
				addProperty(props, secondaryId, null,
						secondaryPropertyDefinition.getId(), value);
			}
		}
	}

	private Aspect extractAspect(List<Aspect> aspects, String aspectId) {
		for (Aspect aspect : aspects) {
			if (aspect.getName().equals(aspectId)) {
				return aspect;
			}
		}
		return null;
	}

	private Property extractProperty(List<Property> properties,
			String propertyId) {
		for (Property property : properties) {
			if (property.getKey().equals(propertyId)) {
				return property;
			}
		}
		return null;
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
			throw new IllegalArgumentException("Unknown type: " + type.getId());

		// TODO :performance
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
	// TODO if cast fails, continue the operation
	private void addProperty(PropertiesImpl props, String typeId,
			Set<String> filter, String id, Object value) {
		try {
			PropertyDefinition<?> pdf = repositoryService.getTypeManager()
					.getTypeDefinition(typeId).getPropertyDefinitions().get(id);

			if (!checkAddProperty(props, typeId, filter, id))
				return;

			switch (pdf.getPropertyType()) {
			case BOOLEAN:
				PropertyBooleanImpl propBoolean;
				if (value instanceof List<?>) {
					propBoolean = new PropertyBooleanImpl(id,
							(List<Boolean>) value);
				} else if (value instanceof Boolean) {
					propBoolean = new PropertyBooleanImpl(id, (Boolean) value);
				} else {
					Boolean _null = null;
					propBoolean = new PropertyBooleanImpl(id, _null);
					if (value != null) {
						String msg = buildCastErrMsg(typeId, id,
								pdf.getPropertyType(), value.getClass()
										.getName(), Boolean.class.getName());
						log.warn(msg);
					}
				}
				addPropertyBase(props, id, propBoolean, pdf);
				break;
			case INTEGER:
				PropertyIntegerImpl propInteger;
				if (value instanceof List<?>) {
					propInteger = new PropertyIntegerImpl(id,
							(List<BigInteger>) value);
				} else if (value instanceof Long || value instanceof Integer) {
					propInteger = new PropertyIntegerImpl(id,
							BigInteger.valueOf((Long) value));
				} else {
					BigInteger _null = null;
					propInteger = new PropertyIntegerImpl(id, _null);
					if (value != null) {
						String msg = buildCastErrMsg(typeId, id,
								pdf.getPropertyType(), value.getClass()
										.getName(), Long.class.getName());
						log.warn(msg);
					}
				}
				addPropertyBase(props, id, propInteger, pdf);
				break;
			case DATETIME:
				PropertyDateTimeImpl propDate;
				if (value instanceof List<?>) {
					propDate = new PropertyDateTimeImpl(id,
							(List<GregorianCalendar>) value);
				} else if (value instanceof GregorianCalendar) {
					propDate = new PropertyDateTimeImpl(id,
							(GregorianCalendar) value);
				} else {
					GregorianCalendar _null = null;
					propDate = new PropertyDateTimeImpl(id, _null);
					if (value != null) {
						String msg = buildCastErrMsg(typeId, id,
								pdf.getPropertyType(), value.getClass()
										.getName(),
								GregorianCalendar.class.getName());
						log.warn(msg);
					}
				}
				addPropertyBase(props, id, propDate, pdf);
				break;
			case STRING:
				PropertyStringImpl propString = new PropertyStringImpl();
				propString.setId(id);
				if (value instanceof List<?>) {
					propString.setValues((List<String>) value);
				} else if (value instanceof String) {
					propString.setValue(String.valueOf(value));
				} else {
					String _null = null;
					propString = new PropertyStringImpl(id, _null);
					if (value != null) {
						String msg = buildCastErrMsg(typeId, id,
								pdf.getPropertyType(), value.getClass()
										.getName(), String.class.getName());
						log.warn(msg);
					}
				}

				addPropertyBase(props, id, propString, pdf);
				break;
			case ID:
				PropertyIdImpl propId = new PropertyIdImpl();
				propId.setId(id);
				if (value instanceof List<?>) {
					propId.setValues((List<String>) value);
				} else if (value instanceof String) {
					propId.setValue(String.valueOf(value));

				} else {
					String _null = null;
					propId = new PropertyIdImpl(id, _null);
					if (value != null) {
						String msg = buildCastErrMsg(typeId, id,
								pdf.getPropertyType(), value.getClass()
										.getName(), String.class.getName());
						log.warn(msg);
					}
				}
				addPropertyBase(props, id, propId, pdf);
				break;
			default:
			}
		} catch (Exception e) {
			log.warn("typeId:" + typeId + ", propertyId:" + id
					+ " Faile to add a property!", e);
		}
	}

	private String buildCastErrMsg(String typeId, String propertyId,
			PropertyType propertyType, String sourceClass, String targetClass) {
		return "[typeId:" + typeId + ", propertyId:" + propertyId
				+ ", propertyType:" + propertyType.value() + "]Cannot convert "
				+ sourceClass + " to " + targetClass;
	}

	private <T> void addPropertyBase(PropertiesImpl props, String id,
			AbstractPropertyData<T> p, PropertyDefinition pdf) {
		p.setDisplayName(pdf.getDisplayName());
		p.setLocalName(id);
		if (MapUtils.isNotEmpty(aliases)
				&& StringUtils.isNotBlank(aliases.get(id))) {
			p.setQueryName(aliases.get(id));
		} else {
			p.setQueryName(pdf.getQueryName());
		}

		props.addProperty(p);
	}

	/**
	 * Separates filter string with ','. If filter is null or empty, it means
	 * all properties can go.
	 */
	// TODO implement CMIS filterNotValid exception?
	// NOTE: "not set" can mean "all properties" and invalid queryName should be
	// ignored.
	// NOTE: So, filterNotValid exception might not be needed.
	@Override
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
		// FIXME the constant already implemented?
		// if (PermissionMapping.CAN_CREATE_POLICY_FOLDER.equals(key))
		// return null;
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

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setPropertyUtil(PropertyUtil propertyUtil) {
		this.propertyUtil = propertyUtil;
	}
}
