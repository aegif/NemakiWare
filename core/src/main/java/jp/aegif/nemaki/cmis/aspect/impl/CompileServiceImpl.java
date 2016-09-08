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
package jp.aegif.nemaki.cmis.aspect.impl;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.ObjectList;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.Principal;
import org.apache.chemistry.opencmis.commons.data.Properties;
import org.apache.chemistry.opencmis.commons.data.PropertyData;
import org.apache.chemistry.opencmis.commons.data.RenditionData;
import org.apache.chemistry.opencmis.commons.data.RepositoryCapabilities;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.SecondaryTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.CapabilityRenditions;
import org.apache.chemistry.opencmis.commons.enums.ChangeType;
import org.apache.chemistry.opencmis.commons.enums.ContentStreamAllowed;
import org.apache.chemistry.opencmis.commons.enums.IncludeRelationships;
import org.apache.chemistry.opencmis.commons.enums.PropertyType;
import org.apache.chemistry.opencmis.commons.enums.RelationshipDirection;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AbstractPropertyData;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
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
import org.apache.chemistry.opencmis.commons.impl.dataobjects.RenditionDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.chemistry.opencmis.commons.spi.Holder;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import com.rits.cloning.Cloner;

import jp.aegif.nemaki.AppConfig;
import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.cmis.aspect.CompileService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.SortUtil;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.AclCapabilities;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.cmis.service.RepositoryService;
import jp.aegif.nemaki.model.Ace;
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
import jp.aegif.nemaki.plugin.action.ActionTriggerBase;
import jp.aegif.nemaki.plugin.action.JavaBackedAction;
import jp.aegif.nemaki.plugin.action.UserButtonActionTrigger;
import jp.aegif.nemaki.util.DataUtil;
import jp.aegif.nemaki.util.action.NemakiActionPlugin;
import jp.aegif.nemaki.util.cache.NemakiCachePool;
import jp.aegif.nemaki.util.constant.CmisExtensionToken;
import net.sf.ehcache.Element;

public class CompileServiceImpl implements CompileService {

	private static final Log log = LogFactory
			.getLog(CompileServiceImpl.class);

	private RepositoryInfoMap repositoryInfoMap;
	private RepositoryService repositoryService;
	private ContentService contentService;
	private PermissionService permissionService;
	private TypeManager typeManager;
	private AclCapabilities aclCapabilities;
	private NemakiCachePool nemakiCachePool;
	private SortUtil sortUtil;

	private boolean includeRelationshipsEnabled = true;

	/**
	 * Builds a CMIS ObjectData from the given CouchDB content.
	 */
	@Override
	public ObjectData compileObjectData(CallContext callContext, String repositoryId,
			Content content, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includeAcl) {

		ObjectDataImpl _result = new ObjectDataImpl();

		ObjectData v = nemakiCachePool.get(repositoryId).getObjectDataCache().get(content.getId());
		if(v == null){
			_result = compileObjectDataWithFullAttributes(callContext, repositoryId, content);
		}else{
			_result = (ObjectDataImpl)v;
		}

		ObjectData result = filterObjectData(repositoryId, _result, filter, includeAllowableActions, includeRelationships, renditionFilter, includeAcl);



		setPluginExtentionData(result);

		return result;
	}

	private ObjectDataImpl compileObjectDataWithFullAttributes(CallContext callContext, String repositoryId, Content content){
		ObjectDataImpl result = new ObjectDataImpl();

		//Filter(any property filter MUST be done here
		PropertiesImpl properties = compileProperties(callContext, repositoryId, content);
		result.setProperties(properties);

		// Set Allowable actions
		result.setAllowableActions(compileAllowableActions(callContext, repositoryId, content));

		// Set Acl
		Acl acl = contentService.calculateAcl(repositoryId, content);
		result.setIsExactAcl(true);
		result.setAcl(compileAcl(acl, contentService.getAclInheritedWithDefault(repositoryId, content), false));

		//Set Relationship(BOTH)
		if (!content.isRelationship()) {
			if(includeRelationshipsEnabled){
				result.setRelationships(compileRelationships(callContext, repositoryId, content, IncludeRelationships.BOTH));
			}
		}

		// Set Renditions
		if (content.isDocument()){
			result.setRenditions(compileRenditions(callContext, repositoryId, content));
		}

		nemakiCachePool.get(repositoryId).getObjectDataCache().put(new Element(content.getId(), result));

		return result;
	}

	private ObjectData filterObjectData(String repositoryId,
			ObjectData fullObjectData, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includeAcl){

		//Deep copy ObjectData
		//Shallow copy will cause a destructive effect after filtering some attributes
		Cloner cloner=new Cloner();
		ObjectDataImpl result = DataUtil.convertObjectDataImpl(cloner.deepClone(fullObjectData));

		Properties filteredProperties = filterProperties(result.getProperties(), splitFilter(filter));
		result.setProperties(filteredProperties);

		// Filter Allowable actions
		Boolean iaa = includeAllowableActions == null ? false: includeAllowableActions.booleanValue();
		if (!iaa) {
			result.setAllowableActions(null);
		}

		// Filter Acl
		Boolean iacl = includeAcl == null ? false : includeAcl.booleanValue();
		if (!iacl) {
			result.setAcl(null);
		}

		// Filter Relationships
		IncludeRelationships irl = includeRelationships == null ? IncludeRelationships.SOURCE : includeRelationships;
		if (fullObjectData.getBaseTypeId() == BaseTypeId.CMIS_RELATIONSHIP) {
			result.setRelationships(filterRelationships(result.getId(), result.getRelationships(), irl));
		}

		// Filter Renditions
		if (fullObjectData.getBaseTypeId() == BaseTypeId.CMIS_DOCUMENT
				&& repositoryInfoMap.get(repositoryId).getCapabilities().getRenditionsCapability() == CapabilityRenditions.NONE) {
			result.setRenditions(null);
		}

		return result;
	}

	private ObjectData filterObjectDataInList(CallContext callContext, String repositoryId,
			ObjectData fullObjectData, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includeAcl){
		boolean allowed = permissionService.checkPermission(callContext, Action.CAN_GET_PROPERTIES, fullObjectData);
		if(allowed){
			return filterObjectData(repositoryId, fullObjectData, filter, includeAllowableActions, includeRelationships, renditionFilter, includeAcl);
		}else{
			return null;
		}
	}

	private Properties filterProperties(Properties properties, Set<String> filter){
		PropertiesImpl result = new PropertiesImpl();

		//null filter as NO FILTER: do nothing
		if(filter == null){
			return properties;
		}else{
			for(String key : properties.getProperties().keySet()){
				PropertyData<?> pd = properties.getProperties().get(key);
				if(filter.contains(pd.getQueryName())){
					result.addProperty(pd);
				}
			}
		}
		return result;
	}

	private List<ObjectData> filterRelationships(String objectId, List<ObjectData> bothRelationships, IncludeRelationships includeRelationships){
		String propertyId;
		switch(includeRelationships){
		case NONE:
			return null;
		case SOURCE:
			propertyId = PropertyIds.SOURCE_ID;
		case TARGET:
			propertyId = PropertyIds.TARGET_ID;
		default:
		}

		List<ObjectData> filtered = new ArrayList<ObjectData>();
		if(CollectionUtils.isNotEmpty(bothRelationships)){
			for(ObjectData rel : bothRelationships){
				PropertyData<?> filterId = rel.getProperties().
						getProperties().get(PropertyIds.SOURCE_ID);
				if(objectId.equals(filterId)){
					filtered.add(rel);
				}
			}

			return filtered;
		}else{
			return null;
		}
	}

	@Override
	public <T extends Content> ObjectList compileObjectDataList(CallContext callContext,
			String repositoryId, List<T> contents, String filter,
			Boolean includeAllowableActions, IncludeRelationships includeRelationships,
			String renditionFilter, Boolean includeAcl, BigInteger maxItems,
			BigInteger skipCount, boolean folderOnly, String orderBy) {
		if (CollectionUtils.isEmpty(contents)) {
			//Empty list
			ObjectListImpl list = new ObjectListImpl();
			list.setObjects(new ArrayList<ObjectData>());
			list.setNumItems(BigInteger.ZERO);
			list.setHasMoreItems(false);
			return list;
		}else{
			List<ObjectData>ods = new ArrayList<ObjectData>();
			for(T c : contents){
				//Filter by folderOnly
				if(folderOnly && !c.isFolder()){
					continue;
				}

				//Get each ObjectData
				ObjectData _od;
				ObjectData v = nemakiCachePool.get(repositoryId).getObjectDataCache().get(c.getId());
				if(v == null){
					_od = compileObjectDataWithFullAttributes(callContext, repositoryId, c);
				}else{
					_od = (ObjectDataImpl)v;
				}

				ObjectData od = filterObjectDataInList(callContext, repositoryId, _od, filter, includeAllowableActions, includeRelationships, renditionFilter, includeAcl);

				setPluginExtentionData(od);
				if(od != null){
					ods.add(od);
				}
			}

			//Sort
			sortUtil.sort(repositoryId, ods, orderBy);

			//Set metadata
			ObjectListImpl list = new ObjectListImpl();
			Integer _skipCount = skipCount.intValue();
			Integer _maxItems = maxItems.intValue();

			if(_skipCount >= ods.size()){
				list.setHasMoreItems(false);
				list.setObjects(new ArrayList<ObjectData>());
			}else{
				//hasMoreItems
				Boolean hasMoreItems = _skipCount + _maxItems < ods.size();
				list.setHasMoreItems(hasMoreItems);
				//paged list
				Integer toIndex = Math.min(_skipCount + _maxItems, ods.size());
				list.setObjects(new ArrayList<>(ods.subList(_skipCount, toIndex)));
			}
			//totalNumItem
			list.setNumItems(BigInteger.valueOf(ods.size()));

			return list;
		}
	}

	@Override
	public ObjectList compileChangeDataList(CallContext context,
			String repositoryId, List<Change> changes,
			Holder<String> changeLogToken, Boolean includeProperties, String filter,
			Boolean includePolicyIds, Boolean includeAcl) {
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
					content = contentService.getContent(repositoryId, objectId);
					cachedContents.put(objectId, content);
				}
				// Compile a change object data depending on its type
				results.getObjects().add(
						compileChangeObjectData(repositoryId, change,
								content, includePolicyIds, includeAcl));
			}
		}

		results.setNumItems(BigInteger.valueOf(results.getObjects().size()));

		String latestInRepository = repositoryService.getRepositoryInfo(repositoryId)
				.getLatestChangeLogToken();
		String latestInResults = changeLogToken.getValue();
		if (latestInResults != null && latestInResults.equals(latestInRepository)) {
			results.setHasMoreItems(false);
		} else {
			results.setHasMoreItems(true);
		}
		return results;
	}

	private ObjectData compileChangeObjectData(String repositoryId, Change change,
			Content content, Boolean includePolicyIds, Boolean includeAcl) {
		ObjectDataImpl o = new ObjectDataImpl();

		// Set Properties
		PropertiesImpl properties = new PropertiesImpl();
		setCmisBasicChangeProperties(properties, change);
		o.setProperties(properties);
		// Set PolicyIds
		setPolcyIds(o, change, includePolicyIds);
		// Set Acl
		if (!change.getChangeType().equals(ChangeType.DELETED)) {
			setAcl(repositoryId, o, content, includeAcl);
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

	private void setAcl(String repositoryId, ObjectDataImpl object,
			Content content, Boolean includeAcl) {
		boolean iacl = (includeAcl == null ? false : includeAcl.booleanValue());
		if (iacl) {
			if (content != null) {
				Acl acl = contentService.calculateAcl(repositoryId, content);
				//object.setAcl(compileAcl(acl, content.isAclInherited(), false));
				object.setAcl(compileAcl(acl, contentService.getAclInheritedWithDefault(repositoryId, content), false));
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
	 * @param content
	 */
	@Override
	public AllowableActions compileAllowableActions(CallContext callContext,
			String repositoryId, Content content) {
		// Get parameters to calculate AllowableActions
		TypeDefinition tdf = typeManager.getTypeDefinition(repositoryId, content
				.getObjectType());
		jp.aegif.nemaki.model.Acl contentAcl = content.getAcl();
		if (tdf.isControllableAcl() && contentAcl == null)
			return null;
		Acl acl = contentService.calculateAcl(repositoryId, content);
		Map<String, PermissionMapping> permissionMap = repositoryInfoMap.get(repositoryId)
				.getAclCapabilities().getPermissionMapping();
		String baseType = content.getType();

		// Calculate AllowableActions
		Set<Action> actionSet = new HashSet<Action>();
		VersionSeries versionSeries = null;
		if(content.isDocument()){
			Document d = (Document)content;
			versionSeries = contentService.getVersionSeries(repositoryId, d);
 		}


		for (Entry<String, PermissionMapping> mappingEntry : permissionMap
				.entrySet()) {
			String key = mappingEntry.getValue().getKey();
			// TODO WORKAROUND. implement class cast check

			// FIXME WORKAROUND: skip canCreatePolicy.Folder
			if (PermissionMapping.CAN_CREATE_POLICY_FOLDER.equals(key)) {
				continue;
			}

			// Additional check
			if (!isAllowableByCapability(repositoryId, key)) {
				continue;
			}
			if (!isAllowableByType(key, content, tdf)) {
				continue;
			}
			if (contentService.isRoot(repositoryId, content)) {
				if (Action.CAN_MOVE_OBJECT == convertKeyToAction(key)) {
					continue;
				}
			}
			if (versionSeries != null) {
				Document d = (Document) content;
				DocumentTypeDefinition dtdf = (DocumentTypeDefinition) tdf;
				if (!isAllowableActionForVersionableDocument(callContext,
						mappingEntry.getKey(), d, versionSeries, dtdf)) {
					continue;
				}
			}

			// Add an allowable action
			boolean allowable = permissionService.checkPermission(callContext,
					repositoryId, mappingEntry.getKey(), acl, baseType, content);

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
			Document document, VersionSeries versionSeries, DocumentTypeDefinition dtdf) {

		//Versioning action(checkOut / checkIn)
		if (permissionMappingKey
				.equals(PermissionMapping.CAN_CHECKOUT_DOCUMENT)) {
			return dtdf.isVersionable() && !versionSeries.isVersionSeriesCheckedOut() && document.isLatestVersion();
		} else if (permissionMappingKey
				.equals(PermissionMapping.CAN_CHECKIN_DOCUMENT)) {
			return dtdf.isVersionable() && versionSeries.isVersionSeriesCheckedOut() && document.isPrivateWorkingCopy();
		} else if (permissionMappingKey
				.equals(PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT)) {
			return dtdf.isVersionable() && versionSeries.isVersionSeriesCheckedOut() && document.isPrivateWorkingCopy();
		}


		//Lock as an effect of checkOut
		if(dtdf.isVersionable()){
			if(isLockableAction(permissionMappingKey)){
				if(document.isLatestVersion()){
					//LocK only when checked out
					return !versionSeries.isVersionSeriesCheckedOut();
				}else if(document.isPrivateWorkingCopy()){
					//Only owner can do actions on pwc
					return callContext.getUsername().equals(versionSeries.getVersionSeriesCheckedOutBy());
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

	private boolean isAllowableByCapability(String repositoryId, String key) {
		RepositoryCapabilities capabilities = repositoryInfoMap.get(repositoryId).getCapabilities();

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
			String repositoryId, Content content, IncludeRelationships irl) {
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
				repositoryId, content.getId(), rd);

		List<ObjectData> rels = new ArrayList<ObjectData>();
		if (CollectionUtils.isNotEmpty(_rels)) {
			for (Relationship _rel : _rels) {
				ObjectData rel = compileObjectData(context, repositoryId, _rel, null,
						false, IncludeRelationships.NONE, null, false);
				rels.add(rel);
			}
		}

		return rels;
	}

	private List<RenditionData> compileRenditions(CallContext callContext,
			String repositoryId, Content content) {
		List<RenditionData> renditions = new ArrayList<RenditionData>();

		List<Rendition> _renditions = contentService.getRenditions(repositoryId, content
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
	public PropertiesImpl compileProperties(CallContext callContext, String repositoryId, Content content) {
		TypeDefinitionContainer tdfc = typeManager.getTypeById(repositoryId, content.getObjectType());
		TypeDefinition tdf = tdfc.getTypeDefinition();

		PropertiesImpl properties = new PropertiesImpl();
		if (content.isFolder()) {
			Folder folder = (Folder) content;
			// Root folder
			if (contentService.isRoot(repositoryId, folder)) {
				properties = compileRootFolderProperties(repositoryId, folder,
						properties, tdf);
				// Other than root folder
			} else {
				properties = compileFolderProperties(repositoryId, folder, properties, tdf);
			}
		} else if (content.isDocument()) {
			Document document = (Document) content;
			properties = compileDocumentProperties(callContext, repositoryId, document, properties, tdf);
		} else if (content.isRelationship()) {
			Relationship relationship = (Relationship) content;
			properties = compileRelationshipProperties(repositoryId,
					relationship, properties, tdf);
		} else if (content.isPolicy()) {
			Policy policy = (Policy) content;
			properties = compilePolicyProperties(repositoryId, policy, properties, tdf);
		} else if (content.isItem()) {
			Item item = (Item) content;
			properties = compileItemProperties(repositoryId, item, properties, tdf);
		}

		return properties;
	}

	private PropertiesImpl compileRootFolderProperties(String repositoryId,
			Folder folder, PropertiesImpl properties, TypeDefinition tdf) {
		setCmisBaseProperties(repositoryId, properties, tdf, folder);

		// Add parentId property without value
		String _null = null;
		PropertyIdImpl parentId = new PropertyIdImpl(PropertyIds.PARENT_ID, _null);
		properties.addProperty(parentId);
		setCmisFolderProperties(repositoryId, properties, tdf, folder);

		return properties;
	}

	private PropertiesImpl compileFolderProperties(String repositoryId,
			Folder folder, PropertiesImpl properties, TypeDefinition tdf) {
		setCmisBaseProperties(repositoryId, properties, tdf, folder);
		addProperty(properties, tdf, PropertyIds.PARENT_ID, folder.getParentId());
		setCmisFolderProperties(repositoryId, properties, tdf, folder);
		return properties;
	}

	private PropertiesImpl compileDocumentProperties(CallContext callContext,
			String repositoryId, Document document, PropertiesImpl properties, TypeDefinition tdf) {
		setCmisBaseProperties(repositoryId, properties, tdf, document);
		setCmisDocumentProperties(callContext, repositoryId, properties, tdf, document);
		setCmisAttachmentProperties(repositoryId, properties, tdf, document);
		return properties;
	}

	private PropertiesImpl compileRelationshipProperties(
			String repositoryId, Relationship relationship,
			PropertiesImpl properties, TypeDefinition tdf) {
		setCmisBaseProperties(repositoryId, properties, tdf, relationship);
		setCmisRelationshipProperties(properties, tdf, relationship);
		return properties;
	}

	private PropertiesImpl compilePolicyProperties(String repositoryId,
			Policy policy, PropertiesImpl properties, TypeDefinition tdf) {
		setCmisBaseProperties(repositoryId, properties, tdf, policy);
		setCmisPolicyProperties(properties, tdf, policy);
		return properties;
	}

	private PropertiesImpl compileItemProperties(String repositoryId,
			Item item, PropertiesImpl properties, TypeDefinition tdf) {
		setCmisBaseProperties(repositoryId, properties, tdf, item);
		setCmisItemProperties(properties, tdf, item);
		return properties;
	}

	private void setCmisBaseProperties(String repositoryId,
			PropertiesImpl properties, TypeDefinition tdf, Content content) {
		addProperty(properties, tdf, PropertyIds.NAME, content.getName());

		addProperty(properties, tdf, PropertyIds.DESCRIPTION, content.getDescription());

		addProperty(properties, tdf, PropertyIds.OBJECT_ID, content.getId());

		addProperty(properties, tdf, PropertyIds.OBJECT_TYPE_ID, content.getObjectType());

		if (content.getCreated() != null)
			addProperty(properties, tdf, PropertyIds.CREATION_DATE, content.getCreated());

		if (content.getCreator() != null)
			addProperty(properties, tdf, PropertyIds.CREATED_BY, content.getCreator());

		if (content.getModified() != null) {
			addProperty(properties, tdf, PropertyIds.LAST_MODIFICATION_DATE,
					content.getModified());
		} else {
			addProperty(properties, tdf, PropertyIds.LAST_MODIFICATION_DATE,
					content.getCreated());
		}

		if (content.getModifier() != null) {
			addProperty(properties, tdf, PropertyIds.LAST_MODIFIED_BY,
					content.getModifier());
		} else {
			addProperty(properties, tdf, PropertyIds.LAST_MODIFIED_BY,
					content.getCreator());
		}

		addProperty(properties, tdf, PropertyIds.CHANGE_TOKEN, String.valueOf(content.getChangeToken()));

		// TODO If subType properties is not registered in DB, return void
		// properties via CMIS
		// SubType properties
		List<PropertyDefinition<?>> specificPropertyDefinitions = typeManager
				.getSpecificPropertyDefinitions(tdf.getId());
		if (!CollectionUtils.isEmpty(specificPropertyDefinitions)) {
			for (PropertyDefinition<?> propertyDefinition : specificPropertyDefinitions) {
				Property property = extractSubTypeProperty(content,
						propertyDefinition.getId());
				Object value = (property == null) ? null : property.getValue();
				addProperty(properties,tdf, propertyDefinition.getId(),
						value);
			}
		}

		// Secondary properties
		setCmisSecondaryTypes(repositoryId, properties, content, tdf);
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

	private void setCmisFolderProperties(String repositoryId,
			PropertiesImpl properties, TypeDefinition tdf, Folder folder) {

		addProperty(properties, tdf, PropertyIds.BASE_TYPE_ID, BaseTypeId.CMIS_FOLDER.value());
		addProperty(properties, tdf, PropertyIds.PATH, contentService.calculatePath(repositoryId, folder));

		if (checkAddProperty(properties, tdf, PropertyIds.ALLOWED_CHILD_OBJECT_TYPE_IDS)) {
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

	private void setCmisDocumentProperties(CallContext callContext,
			String repositoryId, PropertiesImpl properties, TypeDefinition tdf, Document document) {
		addProperty(properties, tdf, PropertyIds.BASE_TYPE_ID, BaseTypeId.CMIS_DOCUMENT.value());

		Boolean isImmutable = (document.isImmutable() == null) ? (Boolean) typeManager
				.getSingleDefaultValue(PropertyIds.IS_IMMUTABLE, tdf.getId(), repositoryId)
				: document.isImmutable();
		addProperty(properties, tdf, PropertyIds.IS_IMMUTABLE, isImmutable);


		DocumentTypeDefinition type = (DocumentTypeDefinition) typeManager
				.getTypeDefinition(repositoryId, tdf.getId());
		if (type.isVersionable()) {
			addProperty(properties, tdf, PropertyIds.IS_PRIVATE_WORKING_COPY,
					document.isPrivateWorkingCopy());
			addProperty(properties, tdf, PropertyIds.IS_LATEST_VERSION,
					document.isLatestVersion());
			addProperty(properties, tdf, PropertyIds.IS_MAJOR_VERSION,
					document.isMajorVersion());
			addProperty(properties, tdf, PropertyIds.IS_LATEST_MAJOR_VERSION,
					document.isLatestMajorVersion());
			addProperty(properties, tdf, PropertyIds.VERSION_LABEL, document.getVersionLabel());
			addProperty(properties, tdf, PropertyIds.VERSION_SERIES_ID,
					document.getVersionSeriesId());
			addProperty(properties, tdf, PropertyIds.CHECKIN_COMMENT,
					document.getCheckinComment());

			VersionSeries vs = contentService.getVersionSeries(repositoryId, document);
			addProperty(properties, tdf, PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
					vs.isVersionSeriesCheckedOut());
			if (vs.isVersionSeriesCheckedOut()) {
				String userId = callContext.getUsername();
				String checkedOutBy = vs.getVersionSeriesCheckedOutBy();

				if(userId.equals(checkedOutBy)){
					addProperty(properties, tdf, PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
							vs.getVersionSeriesCheckedOutId());
					addProperty(properties, tdf, PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
							checkedOutBy);
				}else{
					addProperty(properties, tdf, PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
							null);
					addProperty(properties, tdf, PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
							checkedOutBy);
				}

			} else {
				addProperty(properties, tdf, PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
						null);
				addProperty(properties, tdf, PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
						null);
			}

			// TODO comment
		} else {
			addProperty(properties, tdf, PropertyIds.IS_PRIVATE_WORKING_COPY,
					false);
			addProperty(properties, tdf, PropertyIds.IS_LATEST_VERSION,
					false);
			addProperty(properties, tdf, PropertyIds.IS_MAJOR_VERSION,
					false);
			addProperty(properties, tdf, PropertyIds.IS_LATEST_MAJOR_VERSION,
					false);
			addProperty(properties, tdf, PropertyIds.VERSION_LABEL, "");
			addProperty(properties, tdf, PropertyIds.VERSION_SERIES_ID,
					"");
			addProperty(properties, tdf, PropertyIds.CHECKIN_COMMENT,
					"");
			addProperty(properties, tdf, PropertyIds.IS_VERSION_SERIES_CHECKED_OUT,
					false);

			addProperty(properties, tdf, PropertyIds.VERSION_SERIES_CHECKED_OUT_ID,
					"");
			addProperty(properties, tdf, PropertyIds.VERSION_SERIES_CHECKED_OUT_BY,
					"");
		}
	}

	private void setCmisAttachmentProperties(String repositoryId,
			PropertiesImpl properties, TypeDefinition tdf, Document document) {
		Long length = null;
		String mimeType = null;
		String fileName = null;
		String streamId = null;

		//Check if ContentStream is attached
		DocumentTypeDefinition dtdf = (DocumentTypeDefinition)tdf;
		ContentStreamAllowed csa = dtdf.getContentStreamAllowed();
		if(ContentStreamAllowed.REQUIRED == csa ||
			ContentStreamAllowed.ALLOWED == csa && StringUtils.isNotBlank(document.getAttachmentNodeId())){

			AttachmentNode attachment = contentService.getAttachmentRef(repositoryId, document
					.getAttachmentNodeId());

			if(attachment == null){
				String attachmentId = (document.getAttachmentNodeId() == null) ? "" : document.getAttachmentNodeId();
				log.warn("[objectId=" + document.getId() + " has no file (" +
						attachmentId + ")");
			}else{
				length = attachment.getLength();
				mimeType = attachment.getMimeType();
				fileName = document.getName();
				streamId = attachment.getId();
			}
		}

		//Add ContentStream properties to Document object
		addProperty(properties, dtdf, PropertyIds.CONTENT_STREAM_LENGTH,
				length);
		addProperty(properties, dtdf, PropertyIds.CONTENT_STREAM_MIME_TYPE,
				mimeType);
		addProperty(properties, dtdf, PropertyIds.CONTENT_STREAM_FILE_NAME,
				fileName);
		addProperty(properties, dtdf, PropertyIds.CONTENT_STREAM_ID, streamId);
	}

	private void setCmisRelationshipProperties(PropertiesImpl properties,
			TypeDefinition typeId, Relationship relationship) {
		addProperty(properties, typeId, PropertyIds.BASE_TYPE_ID, BaseTypeId.CMIS_RELATIONSHIP.value());
		addProperty(properties, typeId, PropertyIds.SOURCE_ID, relationship.getSourceId());
		addProperty(properties, typeId, PropertyIds.TARGET_ID, relationship.getTargetId());
	}

	private void setCmisPolicyProperties(PropertiesImpl properties,
			TypeDefinition tdf, Policy policy) {
		addProperty(properties, tdf, PropertyIds.BASE_TYPE_ID, BaseTypeId.CMIS_POLICY.value());
		addProperty(properties, tdf, PropertyIds.POLICY_TEXT, policy.getPolicyText());
	}

	private void setCmisItemProperties(PropertiesImpl properties,
			TypeDefinition tdf, Item item) {
		addProperty(properties, tdf, PropertyIds.BASE_TYPE_ID, BaseTypeId.CMIS_ITEM.value());
	}

	private void setCmisSecondaryTypes(String repositoryId, PropertiesImpl props,
			Content content, TypeDefinition tdf) {
		List<Aspect> aspects = content.getAspects();
		List<String> secondaryIds = new ArrayList<String>();

		// cmis:secondaryObjectTypeIds
		if (CollectionUtils.isNotEmpty(content.getSecondaryIds())) {
			for (String secondaryId : content.getSecondaryIds()) {
				secondaryIds.add(secondaryId);
			}
		}

		new PropertyIdImpl(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, secondaryIds);
		addProperty(props, tdf, PropertyIds.SECONDARY_OBJECT_TYPE_IDS,
				secondaryIds);

		// each secondary properties
		for (String secondaryId : secondaryIds) {
			List<PropertyDefinition<?>> secondaryPropertyDefinitions = typeManager
					.getSpecificPropertyDefinitions(secondaryId);
			if (CollectionUtils.isEmpty(secondaryPropertyDefinitions))
				continue;

			Aspect aspect = extractAspect(aspects, secondaryId);
			List<Property> properties = (aspect == null) ? new ArrayList<Property>()
					: aspect.getProperties();

			SecondaryTypeDefinition stdf = (SecondaryTypeDefinition)typeManager.getTypeDefinition(repositoryId, secondaryId);
			for (PropertyDefinition<?> secondaryPropertyDefinition : secondaryPropertyDefinitions) {
				Property property = extractProperty(properties,
						secondaryPropertyDefinition.getId());
				Object value = (property == null) ? null : property.getValue();
				addProperty(props, stdf, secondaryPropertyDefinition.getId(),
						value);
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
	private boolean checkAddProperty(Properties properties, TypeDefinition tdf, String id) {

		if ((properties == null) || (properties.getProperties() == null))
			throw new IllegalArgumentException("Properties must not be null!");

		if (StringUtils.isEmpty(id))
			throw new IllegalArgumentException("ID must not be null!");

		// TODO :performance
		if (!tdf.getPropertyDefinitions().containsKey(id))
			throw new IllegalArgumentException("Unknown property: " + id);

		//String queryName = tdf.getPropertyDefinitions().get(id).getQueryName();
/*
		if ((queryName != null) && (filter != null)) {
			if (!filter.contains(queryName)) {
				return false;
			} else {
				filter.remove(queryName);
			}
		}*/
		return true;
	}

	/**
	 * Adds specified property in property set.
	 *
	 * @param props
	 *            property set
	 * @param tdf
	 *            object type (e.g. cmis:document)
	 * @param id
	 *            property ID
	 * @param value
	 *            actual property value
	 */
	// TODO if cast fails, continue the operation
	private void addProperty(PropertiesImpl props, TypeDefinition tdf,
			String id, Object value) {
		try {
			PropertyDefinition<?> pdf = tdf.getPropertyDefinitions().get(id);
			if (!checkAddProperty(props, tdf, id))
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
						String msg = buildCastErrMsg(tdf.getId(), id,
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
						String msg = buildCastErrMsg(tdf.getId(), id,
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
						String msg = buildCastErrMsg(tdf.getId(), id,
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
						String msg = buildCastErrMsg(tdf.getId(), id,
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
						String msg = buildCastErrMsg(tdf.getId(), id,
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
			log.warn("typeId:" + tdf + ", propertyId:" + id
					+ " Fail to add a property!", e);
		}
	}

	private String buildCastErrMsg(String typeId, String propertyId,
			PropertyType propertyType, String sourceClass, String targetClass) {
		return "[typeId:" + typeId + ", propertyId:" + propertyId
				+ ", propertyType:" + propertyType.value() + "]Cannot convert "
				+ sourceClass + " to " + targetClass;
	}

	private <T> void addPropertyBase(PropertiesImpl props, String id,
			AbstractPropertyData<T> p, PropertyDefinition<?> pdf) {
		p.setDisplayName(pdf.getDisplayName());
		p.setLocalName(id);
		p.setQueryName(pdf.getQueryName());
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
		// set a few base properties for ObjetInfo
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

	@Override
	public org.apache.chemistry.opencmis.commons.data.Acl compileAcl(
			Acl acl, Boolean isInherited, Boolean onlyBasicPermissions){
		//Default to FALSE
				boolean obp = (onlyBasicPermissions == null) ? false : onlyBasicPermissions;

				AccessControlListImpl cmisAcl = new AccessControlListImpl();
				cmisAcl.setAces(new ArrayList<org.apache.chemistry.opencmis.commons.data.Ace>());
				if(acl != null){
					// Set local ACEs
					buildCmisAce(cmisAcl, true, acl.getLocalAces(), obp);

					// Set inherited ACEs
					buildCmisAce(cmisAcl, false, acl.getInheritedAces(), obp);
				}

				// Set "exact" property
				cmisAcl.setExact(true);

				// Set "inherited" property, which is out of bounds to CMIS
				String namespace = CmisExtensionToken.ACL_INHERITANCE_NAMESPACE;
				//boolean iht = (isInherited == null)? false : isInherited;
				CmisExtensionElementImpl inherited = new CmisExtensionElementImpl(
						namespace, CmisExtensionToken.ACL_INHERITANCE_INHERITED, null, String.valueOf(isInherited));
				List<CmisExtensionElement> exts = new ArrayList<CmisExtensionElement>();
				exts.add(inherited);
				cmisAcl.setExtensions(exts);

				return cmisAcl;
	}

	private void buildCmisAce(AccessControlListImpl cmisAcl, boolean direct, List<Ace> aces, boolean onlyBasicPermissions){
		if(CollectionUtils.isNotEmpty(aces)){
			for (Ace ace : aces) {
				//Set principal
				Principal principal = new AccessControlPrincipalDataImpl(
						ace.getPrincipalId());

				//Set permissions
				List<String> permissions= new ArrayList<String>();
				if(onlyBasicPermissions && CollectionUtils.isNotEmpty(ace.getPermissions())){
					HashMap<String,String> map = aclCapabilities.getBasicPermissionConversion();

					//Translate permissions as CMIS Basic permissions
					for(String p : ace.getPermissions()){
						permissions.add(map.get(p));
					}
				}else{
					permissions = ace.getPermissions();
				}

				//Build CMIS ACE
				AccessControlEntryImpl cmisAce = new AccessControlEntryImpl(
						principal, permissions);

				//Set direct flag
				cmisAce.setDirect(direct);

				cmisAcl.getAces().add(cmisAce);
			}
		}
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
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

	public void setAclCapabilities(AclCapabilities aclCapabilities) {
		this.aclCapabilities = aclCapabilities;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setNemakiCachePool(NemakiCachePool nemakiCachePool) {
		this.nemakiCachePool = nemakiCachePool;
	}

	public void setIncludeRelationshipsEnabled(boolean includeRelationshipsEnabled) {
		this.includeRelationshipsEnabled = includeRelationshipsEnabled;
	}

	public void setSortUtil(SortUtil sortUtil) {
		this.sortUtil = sortUtil;
	}

	private void setPluginExtentionData(ObjectData result){
		// Add extension deta from plugin
		try (GenericApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class)) {
			NemakiActionPlugin acionPlugin = context.getBean(NemakiActionPlugin.class);
			Map<String, JavaBackedAction> pluginMap = acionPlugin.getPluginsMap();

			String ns = "http://aegif.jp/nemakiware/action";
			// set the extension list
			List<CmisExtensionElement> extensions = result.getExtensions();
			if (extensions == null){
				extensions = new ArrayList<CmisExtensionElement>();
			}
			for (String beanId : pluginMap.keySet()) {
				JavaBackedAction plugin = acionPlugin.getPlugin(beanId);
				if(plugin.canExecute(result)){
					String action_id = beanId;
					List<CmisExtensionElement> extElements = new ArrayList<CmisExtensionElement>();
					extElements.add(new CmisExtensionElementImpl(ns, "actionId", null, action_id));
					ActionTriggerBase trigger = plugin.getActionTrigger();
					if(trigger instanceof  UserButtonActionTrigger){
						extElements.add(new CmisExtensionElementImpl(ns, "actionButtonLabel", null, ((UserButtonActionTrigger) trigger).getDisplayName()));
						extElements.add(new CmisExtensionElementImpl(ns, "actionButtonIcon", null, ((UserButtonActionTrigger) trigger).getFontAwesomeName()));
					}
					extensions.add(new CmisExtensionElementImpl(ns, "actionPluginExtension", null, extElements));
				}
			}
			result.setExtensions(extensions);
		}
	}

}