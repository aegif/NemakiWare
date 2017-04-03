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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.chemistry.opencmis.commons.data.AllowableActions;
import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.enums.Action;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.businesslogic.ContentService;
import jp.aegif.nemaki.businesslogic.PrincipalService;
import jp.aegif.nemaki.cmis.aspect.PermissionService;
import jp.aegif.nemaki.cmis.aspect.type.TypeManager;
import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Document;
import jp.aegif.nemaki.model.Folder;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.UserItem;
import jp.aegif.nemaki.model.VersionSeries;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.CmisPermission;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * Permission Service implementation.
 *
 */
public class PermissionServiceImpl implements PermissionService {

	private static final Log log = LogFactory.getLog(PermissionServiceImpl.class);

	private PrincipalService principalService;
	private ContentService contentService;
	private TypeManager typeManager;
	private RepositoryInfoMap repositoryInfoMap;
	private PropertyManager propertyManager;

	private List<String> topLevelAllowableKeys;
	private List<String> topLevelNotAllowableKeysWithFolder;


	public void init(){
		topLevelAllowableKeys = new ArrayList<String>();
		Collections.addAll(topLevelAllowableKeys,
				PermissionMapping.CAN_GET_ACL_OBJECT,
				PermissionMapping.CAN_GET_ALL_VERSIONS_VERSION_SERIES,
				PermissionMapping.CAN_GET_APPLIED_POLICIES_OBJECT,
				PermissionMapping.CAN_GET_CHILDREN_FOLDER,
				PermissionMapping.CAN_GET_DESCENDENTS_FOLDER,
				PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT,
				PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT,
				PermissionMapping.CAN_GET_PARENTS_FOLDER,
				PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
				PermissionMapping.CAN_VIEW_CONTENT_OBJECT);

		topLevelNotAllowableKeysWithFolder = new ArrayList<String>();
		Collections.addAll(topLevelNotAllowableKeysWithFolder,
				PermissionMapping.CAN_ADD_TO_FOLDER_FOLDER,
				PermissionMapping.CAN_ADD_TO_FOLDER_OBJECT,
				PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER,
				PermissionMapping.CAN_CREATE_FOLDER_FOLDER,
				PermissionMapping.CAN_REMOVE_FROM_FOLDER_FOLDER,
				PermissionMapping.CAN_REMOVE_FROM_FOLDER_OBJECT);
	}


	// //////////////////////////////////////////////////////////////////////////
	// Permission Check called from each CMIS method
	// //////////////////////////////////////////////////////////////////////////
	@Override
	public boolean checkPermission(CallContext callContext, Action action, ObjectData objectData){
		AllowableActions _actions = objectData.getAllowableActions();
		if(_actions == null){
			return false;
		}else{
			Set<Action> actions = _actions.getAllowableActions();
			if(CollectionUtils.isEmpty(actions)){
				return false;
			}else{
				return actions.contains(action);
			}
		}
	}

	/**
	 *
	 */
	//TODO Merge arguments(acl, content)
	//FIXME Refactor duplicate isAllowableBaseType
	@Override
	public Boolean checkPermission(CallContext callContext, String repositoryId, String key,
			Acl acl, String baseType, Content content) {
		log.info("PermissionService#checkPermission method: " + content.getName());

		//All permission checks must go through baseType check
		if(!isAllowableBaseType(key, baseType, content, repositoryId)) return false;

		// Admin always pass a permission check
		String userName = callContext.getUsername();
		UserItem u = contentService.getUserItemById(repositoryId, userName);
		if (u != null && u.isAdmin()) return true;

		//PWC doesn't accept any actions from a non-owner user
		//TODO admin can manipulate PWC even when it is checked out ?
		if(content.isDocument()){
			Document document = (Document)content;
			if(document.isPrivateWorkingCopy()){
				VersionSeries vs = contentService.getVersionSeries(repositoryId, document);
				if(!userName.equals(vs.getVersionSeriesCheckedOutBy())){
					return false;
				}
			}
		}

		// Relation has no ACL stored in DB.
		// Though some actions are defined in the specs,
		// Some other direct actions is needed to be set here.
		if(content.isRelationship()){
			Relationship relationship = (Relationship)content;
			boolean hasRelationshipPermission =  checkRelationshipPermission(callContext, repositoryId, key, relationship);
			return hasRelationshipPermission;
		}

		// Void Acl fails(but Admin can do an action)
		if (acl == null){
			return false;
		}

		// Even if a user has multiple ACEs, the permissions is pushed into
		// Set<String> and remain unique.
		// Get ACL for the current user

		List<Ace> aces = acl.getAllAces();
		Set<String> userPermissions = new HashSet<String>();
		Set<String> groups = contentService.getGroupIdsContainingUser(repositoryId, userName);
		for (Ace ace : aces) {
			// Filter ace which has not permissions
			if (ace.getPermissions() == null)
				continue;

			// Add user permissions
			if (ace.getPrincipalId().equals(userName)) {
				userPermissions.addAll(ace.getPermissions());
			}
			// Add inherited permissions which user inherits
			if(CollectionUtils.isNotEmpty(groups) && groups.contains(ace.getPrincipalId())){
				userPermissions.addAll(ace.getPermissions());
			}
		}

		// Check mapping between the user and the content
		boolean calcPermission =  checkCalculatedPermissions(repositoryId, key, userPermissions);
		return calcPermission;
	}

	/**
	 *
	 * @param repositoryId TODO
	 * @param key
	 * @param userPermissions
	 * @return
	 */
	private boolean checkCalculatedPermissions(String repositoryId, String key, Set<String> userPermissions) {
		Map<String, PermissionMapping> table = repositoryInfoMap.get(repositoryId).getAclCapabilities().getPermissionMapping();
		List<String> actionPermissions = table.get(key).getPermissions();

		for (String up : userPermissions) {
			if (actionPermissions.contains(up) ||
				CmisPermission.ALL.equals(up)) {
				//If any one of user permissions is contained, action is allowed.

				return true;
			}
		}
		return false;
	}

	/**
	 * TODO In the future, enable different configuration for Read/Update/Delete.
	 * @param callContext
	 * @param repositoryId TODO
	 * @param key
	 * @param relationship
	 * @return
	 */
	private Boolean checkRelationshipPermission(CallContext callContext, String repositoryId, String key, Relationship relationship){
		Content source = contentService.getContent(repositoryId, relationship.getSourceId());
		Content target = contentService.getContent(repositoryId, relationship.getTargetId());

		if(source == null || target == null){
			log.warn("[objectId=" + relationship.getId() + "]Source or target of this relationship is missing");
			return false;
		}

		//Read action when a relationship is specified directly
		if(PermissionMapping.CAN_GET_PROPERTIES_OBJECT.equals(key)){
			boolean readSource =
					checkPermission(callContext, repositoryId, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(repositoryId, source), source.getType(), source);
			boolean readTarget =
					checkPermission(callContext, repositoryId, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(repositoryId, target), target.getType(), target);
			return readSource | readTarget;
		}

		//Update action
		if(PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT.equals(key)){
			boolean updateSource =
					checkPermission(callContext, repositoryId, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(repositoryId, source), source.getType(), source);
			boolean updateTarget =
					checkPermission(callContext, repositoryId, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(repositoryId, target), target.getType(), target);
			return updateSource | updateTarget;
		}

		//Delete action
		if(PermissionMapping.CAN_DELETE_OBJECT.equals(key)){
			boolean deleteSource =
					checkPermission(callContext, repositoryId, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(repositoryId, source), source.getType(), source);
			boolean deleteTarget =
					checkPermission(callContext, repositoryId, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(repositoryId, target), target.getType(), target);
			return deleteSource | deleteTarget;
		}

		return false;
	}

	private Boolean isAllowableBaseType(String key, String baseType, Content content, String repositoryId) {
		// NavigationServices
		if (PermissionMapping.CAN_GET_DESCENDENTS_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_GET_CHILDREN_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT.equals(key))
			if(contentService.isRoot(repositoryId, content)){
				return false;
			}else{
				return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
			}
		if (PermissionMapping.CAN_GET_PARENTS_FOLDER.equals(key))
			if(contentService.isRoot(repositoryId, content)){
				return false;
			}else{
				return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType) || BaseTypeId.CMIS_FOLDER.value().equals(baseType)
						|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM.value().equals(baseType));
			}

		// Object Services
		if (PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_CREATE_FOLDER_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_CREATE_POLICY_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_CREATE_RELATIONSHIP_SOURCE.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
						.equals(baseType));
		if (PermissionMapping.CAN_CREATE_RELATIONSHIP_TARGET.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
					.value().equals(baseType));
		if (PermissionMapping.CAN_GET_PROPERTIES_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
					.value().equals(baseType));
		if (PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
					.value().equals(baseType));
		if (PermissionMapping.CAN_MOVE_OBJECT.equals(key))
			if(contentService.isRoot(repositoryId, content)){
				return false;
			}else{
				return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
						|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
						|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
						.value().equals(baseType));
			}
		if (PermissionMapping.CAN_MOVE_TARGET.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_MOVE_SOURCE.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_DELETE_OBJECT.equals(key))
			if(contentService.isRoot(repositoryId, content)){
				return false;
			}else{
				return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
						|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
						|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
						|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
						.value().equals(baseType));
			}
		if (PermissionMapping.CAN_VIEW_CONTENT_OBJECT.equals(key))
			return BaseTypeId.CMIS_DOCUMENT.value().equals(baseType);
		if (PermissionMapping.CAN_SET_CONTENT_DOCUMENT.equals(key))
			return BaseTypeId.CMIS_DOCUMENT.value().equals(baseType);
		if (PermissionMapping.CAN_DELETE_CONTENT_DOCUMENT.equals(key))
			return BaseTypeId.CMIS_DOCUMENT.value().equals(baseType);
		if (PermissionMapping.CAN_DELETE_TREE_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		// Filing Services
		if (PermissionMapping.CAN_ADD_TO_FOLDER_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
					.value().equals(baseType));
		if (PermissionMapping.CAN_ADD_TO_FOLDER_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
					.value().equals(baseType));
		if (PermissionMapping.CAN_REMOVE_FROM_FOLDER_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
					.value().equals(baseType));
		if (PermissionMapping.CAN_REMOVE_FROM_FOLDER_FOLDER.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType) || BaseTypeId.CMIS_POLICY
					.value().equals(baseType));
		// Versioning Services
		if (PermissionMapping.CAN_CHECKOUT_DOCUMENT.equals(key))
			return BaseTypeId.CMIS_DOCUMENT.value().equals(baseType);
		if (PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT.equals(key))
			return BaseTypeId.CMIS_DOCUMENT.value().equals(baseType);
		if (PermissionMapping.CAN_CHECKIN_DOCUMENT.equals(key))
			return BaseTypeId.CMIS_DOCUMENT.value().equals(baseType);
		if (PermissionMapping.CAN_GET_ALL_VERSIONS_VERSION_SERIES.equals(key))
			return BaseTypeId.CMIS_DOCUMENT.value().equals(baseType);
		// Relationship Services
		if (PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
					.value().equals(baseType));
		// Policy Services
		if (PermissionMapping.CAN_ADD_POLICY_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
					.value().equals(baseType));
		if (PermissionMapping.CAN_ADD_POLICY_POLICY.equals(key))
			return BaseTypeId.CMIS_POLICY.value().equals(baseType);
		if (PermissionMapping.CAN_REMOVE_POLICY_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM.value()
						.equals(baseType));
		if (PermissionMapping.CAN_REMOVE_POLICY_POLICY.equals(key))
			return BaseTypeId.CMIS_POLICY.value().equals(baseType);
		if (PermissionMapping.CAN_GET_APPLIED_POLICIES_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM.value()
						.equals(baseType));
		// ACL Services
		if (PermissionMapping.CAN_GET_ACL_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM.value()
						.equals(baseType));
		if (PermissionMapping.CAN_APPLY_ACL_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM.value()
						.equals(baseType));

		return false;
	}

	/**
	 * Filtering check to a list of contents based on the permission
	 */
	@Override
	public <T> List<T> getFiltered(CallContext callContext,
			String repositoryId, List<T> contents) {
		List<T> result = new ArrayList<T>();

		// Validation
		// TODO refine the logic
		if (CollectionUtils.isEmpty(contents)){
			return null;
		}

		// Filtering
		for (T _content : contents) {
			Content content = (Content) _content;
			Acl acl = contentService.calculateAcl(repositoryId, content);

			Boolean filtered = checkPermission(callContext,
					repositoryId, PermissionMapping.CAN_GET_PROPERTIES_OBJECT, acl, content.getType(), content);
			if (filtered) {
				result.add(_content);
			}
		}
		return result;
	}

	@Override
	public boolean checkPermissionAtTopLevel(CallContext context, String repositoryId, String key, Content content){
		boolean capability = propertyManager.readBoolean(PropertyKey.CAPABILITY_EXTENDED_PERMISSION_TOPLEVEL);
		if(capability){
			if(!topLevelAllowableKeys.contains(key)){
				Folder folderChecked;
				if(topLevelNotAllowableKeysWithFolder.contains(key)){
					//canCreateDocument.Folder type
					folderChecked = (Folder)content;
				}else{
					//canDelete.Object type
					folderChecked = contentService.getFolder(repositoryId, content.getParentId());
				}

				String rootId = repositoryInfoMap.get(repositoryId).getRootFolderId();
				//Check top level or not
				if(folderChecked == null || rootId.equals(folderChecked.getId())){
					UserItem user = contentService.getUserItemById(repositoryId, context.getUsername());
					if(!user.isAdmin()){
						return false;
					}
				}
			}
		}

		return true;
	}

	public void setNemakiPermissions(List<Map<String, ?>> nemakiPermissions) {
	}

	public void setPrincipalService(PrincipalService principalService) {
		this.principalService = principalService;
	}

	public void setContentService(ContentService contentService) {
		this.contentService = contentService;
	}


	public TypeManager getTypeManager() {
		return typeManager;
	}

	public void setTypeManager(TypeManager typeManager) {
		this.typeManager = typeManager;
	}

	public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
		this.repositoryInfoMap = repositoryInfoMap;
	}


	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}