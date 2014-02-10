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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.Relationship;
import jp.aegif.nemaki.model.constant.PropertyKey;
import jp.aegif.nemaki.repository.type.TypeManager;
import jp.aegif.nemaki.service.cmis.PermissionService;
import jp.aegif.nemaki.service.node.ContentService;
import jp.aegif.nemaki.service.node.PrincipalService;
import jp.aegif.nemaki.util.NemakiPropertyManager;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Permission Service implementation.
 *
 */
public class PermissionServiceImpl implements PermissionService {

	private static final Log log = LogFactory
			.getLog(PermissionServiceImpl.class);

	private PrincipalService principalService;
	private ContentService contentService;
	private TypeManager typeManager;
	private RepositoryInfo repositoryInfo;
	private NemakiPropertyManager propertyManager;


	// //////////////////////////////////////////////////////////////////////////
	// Permission Check called from each CMIS method
	// //////////////////////////////////////////////////////////////////////////

	/**
	 *
	 */
	//TODO Merge arguments(acl, content)
	@Override
	public Boolean checkPermission(CallContext callContext, String key, Acl acl,
			String baseType, Content content) {
		// Admin always pass a permission check
		String admin = new String();
		admin = propertyManager.readValue(PropertyKey.PRINCIPAL_ADMIN);
		if (callContext.getUsername().equals(admin))
			return isAllowableBaseType(key, baseType, content);

		// Relation has no ACL stored in DB.
		// Though some actions are defined in the specs,
		// Some other direct actions is needed to be set here.
		if(content.isRelationship()){
			Relationship relationship = (Relationship)content;
			return checkRelationshipPermission(callContext, key, relationship);
		}

		// Void Acl fails(but Admin can do an action)
		if (acl == null)
			return false;

		// Even if a user has multiple ACEs, the permissions is pushed into
		// Set<String> and remain unique.
		// Get ACL for the current user
		String userName = callContext.getUsername();
		List<Ace> aces = acl.getAllAces();
		Set<String> userPermissions = new HashSet<String>();
		Set<String> groups = principalService.getGroupIdsContainingUser(userName);
		for (Ace ace : aces) {
			// Filter ace which has not permissions
			if (ace.getPermissions() == null)
				continue;

			// User permission
			if (ace.getPrincipalId().equals(userName)) {
				userPermissions.addAll(ace.getPermissions());
			}
			// Group permission(which user inherits)
			if(CollectionUtils.isNotEmpty(groups) && groups.contains(ace.getPrincipalId())){
				userPermissions.addAll(ace.getPermissions());
			}
		}

		// Check mapping between the user and the content
		return isAllowableBaseType(key, baseType, content) && checkCalculatedPermissions(key, userPermissions);
	}

	/**
	 *
	 * @param key
	 * @param userPermissions
	 * @return
	 */
	private Boolean checkCalculatedPermissions(String key, Set<String> userPermissions) {
		Map<String, PermissionMapping> table = repositoryInfo.getAclCapabilities().getPermissionMapping();
		List<String> actionPermissions = table.get(key).getPermissions();

		for (String up : userPermissions) {
			if (actionPermissions.contains(up)) {
				//If one of user permissions is contained, allowed.
				return true;
			}
		}
		return false;
	}

	/**
	 * TODO In the future, enable different configuration for Read/Update/Delete.
	 * @param callContext
	 * @param key
	 * @param relationship
	 * @return
	 */
	private Boolean checkRelationshipPermission(CallContext callContext, String key, Relationship relationship){
		Content source = contentService.getRelationship(relationship.getSourceId());
		Content target = contentService.getRelationship(relationship.getTargetId());

		if(source == null || target == null){
			log.warn("[objectId=" + relationship.getId() + "]Source or target of this relationship is missing");
			return false;
		}

		//Read action when a relationship is specified directly
		if(PermissionMapping.CAN_GET_PROPERTIES_OBJECT.equals(key)){
			boolean readSource =
					checkPermission(callContext, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(source), source.getType(), source);
			boolean readTarget =
					checkPermission(callContext, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(target), target.getType(), target);
			return readSource | readTarget;
		}

		//Update action
		if(PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT.equals(key)){
			boolean updateSource =
					checkPermission(callContext, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(source), source.getType(), source);
			boolean updateTarget =
					checkPermission(callContext, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(target), target.getType(), target);
			return updateSource | updateTarget;
		}

		//Delete action
		if(PermissionMapping.CAN_DELETE_OBJECT.equals(key)){
			boolean deleteSource =
					checkPermission(callContext, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(source), source.getType(), source);
			boolean deleteTarget =
					checkPermission(callContext, PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT, contentService.calculateAcl(target), target.getType(), target);
			return deleteSource | deleteTarget;
		}

		return false;
	}

	private Boolean isAllowableBaseType(String key, String baseType, Content content) {
		// NavigationServices
		if (PermissionMapping.CAN_GET_DESCENDENTS_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_GET_CHILDREN_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT.equals(key))
			if(content.isRoot()){
				return false;
			}else{
				return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
			}
		if (PermissionMapping.CAN_GET_PARENTS_FOLDER.equals(key))
			if(content.isRoot()){
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
			if(content.isRoot()){
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
			if(content.isRoot()){
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
	public List<Content> getFiltered(CallContext callContext,
			List<Content> contents) {
		List<Content> result = new ArrayList<Content>();

		// Validation
		// TODO refine the logic
		if (CollectionUtils.isEmpty(contents)){
			return null;
		}

		// Filtering
		for (Content content : contents) {
			Acl acl = contentService.calculateAcl(content);

			Boolean filtered = checkPermission(callContext,
					PermissionMapping.CAN_GET_PROPERTIES_OBJECT, acl, content.getType(), content);
			if (filtered) {
				result.add(content);
			}
		}
		return result;
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

	public void setRepositoryInfo(RepositoryInfo repositoryInfo) {
		this.repositoryInfo = repositoryInfo;
	}

	public void setPropertyManager(NemakiPropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
