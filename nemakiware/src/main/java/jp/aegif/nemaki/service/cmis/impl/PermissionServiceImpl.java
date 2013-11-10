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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.CustomPermission;
import jp.aegif.nemaki.repository.TypeManager;
import jp.aegif.nemaki.service.cmis.PermissionService;
import jp.aegif.nemaki.service.node.ContentService;
import jp.aegif.nemaki.service.node.PrincipalService;
import jp.aegif.nemaki.util.YamlManager;

import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionDefinitionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionMappingDataImpl;
import org.apache.chemistry.opencmis.commons.server.CallContext;
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

	// //////////////////////////////////////////////////////////////////////////
	// Permission Definitions
	// //////////////////////////////////////////////////////////////////////////
	/**
	 * Get the list of existing permissions. For instance: read, write, all
	 */
	public List<PermissionDefinition> getPermissionDefinitions() {
		List<PermissionDefinition> permissions = new ArrayList<PermissionDefinition>();
		// CMIS basic permissions
		permissions.add(createPermission(CMIS_READ_PERMISSION, "Read"));
		permissions.add(createPermission(CMIS_WRITE_PERMISSION, "Write"));
		permissions.add(createPermission(CMIS_ALL_PERMISSION, "All"));

		// Repository specific permissions
		for (CustomPermission cp : getCustomPermissions()) {
			permissions.add(createPermission(cp.getId(), cp.getDescription()));
		}

		return permissions;
	}

	public List<String> getPermissions() {
		List<String> permissions = new ArrayList<String>();
		permissions.add(CMIS_READ_PERMISSION);
		permissions.add(CMIS_WRITE_PERMISSION);
		permissions.add(CMIS_ALL_PERMISSION);
		for (CustomPermission cp : getCustomPermissions()) {
			permissions.add(cp.getId());
		}
		return permissions;
	}

	/**
	 * Create a new permission.
	 */
	private PermissionDefinition createPermission(String permission,
			String description) {
		PermissionDefinitionDataImpl pd = new PermissionDefinitionDataImpl();
		pd.setPermission(permission);
		pd.setDescription(description);
		return pd;
	}

	// //////////////////////////////////////////////////////////////////////////
	// Permission Mappings
	// //////////////////////////////////////////////////////////////////////////
	/**
	 * Mapping permission group to elemental permission (repository static)
	 */
	public Map<String, PermissionMapping> getPermissionMap() {
		return this.repositoryInfo.getAclCapabilities().getPermissionMapping();
	}

	// //////////////////////////////////////////////////////////////////////////
	// Permission Check called from each CMIS method
	// //////////////////////////////////////////////////////////////////////////
	/**
	 * permissionDenied Exception check
	 * 
	 * @return
	 */
	// TODO Before this method, perform objectNotFound check!
	@Override
	public Boolean checkPermission(CallContext callContext, String key, Acl acl,
			String baseType, Content content) {
		// Admin always pass a permission check
		// TODO externalize Admin ID
		if (callContext.getUsername().equals("admin"))
			return isAllowableBaseType(key, baseType, content);
		
		if (acl == null)
			return false;

		// Even if a user has multiple ACEs, the permissions is pushed into
		// Set<String> and remain unique.
		// Get ACL for the current user
		String userName = callContext.getUsername();
		List<Ace> aces = acl.getAces();
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
			if(groups.contains(ace.getPrincipalId())){
				userPermissions.addAll(ace.getPermissions());
			}
		}

		// Check mapping between the user and the content
		return isAllowableBaseType(key, baseType, content) && checkCalculatedPermissions(key, userPermissions);
	}

	// TODO User permission is inherited from Group permission.
	/**
	 * each permission(ex.cmis:write) will be extended to [cmis:read,cmis:write] etc.
	 * @param key
	 * @param userPermissions
	 * @return
	 */
	private Boolean checkCalculatedPermissions(String key, Set<String> userPermissions) {
		// CMIS default permission mapping
		Map<String, PermissionMapping> map = getPermissionMap();
		// Check allowable key
		List<String> mappedPermissions = map.get(key).getPermissions();
		// Repository-specific custom permission mapping
		List<CustomPermission> customPermissions = getCustomPermissions();
		List<String> customPermissionIds = new ArrayList<String>();
		for (CustomPermission customPermission : customPermissions) {
			customPermissionIds.add(customPermission.getId());
		}

		// Customize the permission mapping of the key
		Set<String> extend = new HashSet<String>();
		//Boolean customAllowable = false;
		for (String userPermission : userPermissions) {
			// if cmis:all, return true without condition
			if (userPermission.equals("cmis:all")) {
				return true;
			}

			// Extend permissions
			// CASE cmis:write
			if (userPermission.equals(CMIS_WRITE_PERMISSION)) {
				extend.add(CMIS_READ_PERMISSION);
			}
			// CASE custom permission
			if (customPermissionIds.contains(userPermission)) {
				CustomPermission cp = getNemakiPermission(userPermission,
						customPermissions);
				extend.addAll(cp.getBase());
				// custom permission mapping
				Map<String, Boolean> pm = cp.getPermissionMapping();
				if (pm.containsKey(key)) {
					//customAllowable = customAllowable || pm.get(key);
					if ( pm.get(key)) {
						return true;
					}
				}
			}
		}

		// Check
		for (String up : userPermissions) {
			// If a user base permission is allowed
			if (mappedPermissions.contains(up)) {
				return true;
			}
		}

		// If all the check hasnt't been passed, permission denied.
		return false;
	}

	@SuppressWarnings("unchecked")
	/**
	 * custom permissionの詳細情報を取得
	 * @param nemakiPermissions
	 * @return
	 */
	private List<CustomPermission> getCustomPermissions() {
		List<CustomPermission> results = new ArrayList<CustomPermission>();

		YamlManager manager = new YamlManager("custom_permission.yml");
		List<Map<String, Object>> yml = (List<Map<String, Object>>) manager
				.loadYml();
		for (Map<String, Object> y : yml) {
			CustomPermission cp = new CustomPermission(y);
			results.add(cp);
		}

		return results;
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
						|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
							.equals(baseType));
			}
			
		// Object Services
		if (PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if (PermissionMapping.CAN_CREATE_FOLDER_FOLDER.equals(key))
			return BaseTypeId.CMIS_FOLDER.value().equals(baseType);
		if ("canCreatePolicy.Folder".equals(key)) // FIXME the constant already
													// implemented?
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
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
						.equals(baseType));
		if (PermissionMapping.CAN_REMOVE_POLICY_POLICY.equals(key))
			return BaseTypeId.CMIS_POLICY.value().equals(baseType);
		if (PermissionMapping.CAN_GET_APPLIED_POLICIES_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
						.equals(baseType));
		// ACL Services
		if (PermissionMapping.CAN_GET_ACL_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
						.equals(baseType));
		if (PermissionMapping.CAN_APPLY_ACL_OBJECT.equals(key))
			return (BaseTypeId.CMIS_DOCUMENT.value().equals(baseType)
					|| BaseTypeId.CMIS_FOLDER.value().equals(baseType)
					|| BaseTypeId.CMIS_RELATIONSHIP.value().equals(baseType)
					|| BaseTypeId.CMIS_POLICY.value().equals(baseType) || BaseTypeId.CMIS_ITEM
						.equals(baseType));

		return false;
	}
	
	private CustomPermission getNemakiPermission(String id,
			List<CustomPermission> list) {
		for (CustomPermission np : list) {
			if (np.getId().equals(id)) {
				return np;
			}
		}
		return null;
	}

	/**
	 * Filtering check to a list of contents based on the permission
	 */
	public List<Content> getFiltered(CallContext callContext,
			List<Content> contents) {
		List<Content> result = new ArrayList<Content>();

		// Validation
		// TODO refine the logic
		if (contents == null)
			return null;
		while (contents.remove(null))
			;
		if (contents == null || contents.size() == 0)
			return null;

		// Filtering
		for (Content content : contents) {
			Acl acl = contentService.convertToCmisAcl(content, false);

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
	
}
