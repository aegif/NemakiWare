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
package jp.aegif.nemaki.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.CustomPermission;
import jp.aegif.nemaki.model.constant.CmisPermission;
import jp.aegif.nemaki.util.YamlManager;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.SupportedPermissions;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AclCapabilitiesDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionDefinitionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionMappingDataImpl;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.LoggerFactory;

class NemakiAclCapabilitiesDataImpl extends AclCapabilitiesDataImpl {

	private static final long serialVersionUID = 8654484629504222836L;
	private static final Log log = LogFactory.getLog(NemakiAclCapabilitiesDataImpl.class);
	
	private	List<PermissionMapping> permissionMappings;
	private LinkedHashMap<String, PermissionMapping> permissionMappingsMap;

	public NemakiAclCapabilitiesDataImpl() {
		preparePermissionMaps();
	}
	
	public void setup() {
		setSupportedPermissions(SupportedPermissions.BOTH);
		setAclPropagation(AclPropagation.PROPAGATE);
		setPermissionDefinitionData(getPermissionDefinitions());
		setPermissionMappingData(getPermissionMap());
	}

	public List<PermissionDefinition> getPermissionDefinitions() {
		List<PermissionDefinition> permissions = new ArrayList<PermissionDefinition>();
		// CMIS basic permissions
		permissions.add(createPermission(CmisPermission.READ, "Read"));
		permissions.add(createPermission(CmisPermission.WRITE, "Write"));
		permissions.add(createPermission(CmisPermission.ALL, "All"));

		// Repository specific permissions
		for (CustomPermission cp : getCustomPermissions()) {
			permissions.add(createPermission(cp.getId(), cp.getDescription()));
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

	// //////////////////////////////////////////////////////////////////////////
	// Permission Mappings
	// //////////////////////////////////////////////////////////////////////////
	public void preparePermissionMaps() {
		// FIXME externalize configuration
		
		permissionMappings = new ArrayList<PermissionMapping>();
		
		// Navigation Services
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_GET_DESCENDENTS_FOLDER,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_GET_CHILDREN_FOLDER, CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_GET_FOLDER_PARENT_OBJECT,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_GET_PARENTS_FOLDER, CmisPermission.READ));

		// Object Services
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_CREATE_DOCUMENT_FOLDER,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_CREATE_FOLDER_FOLDER,
				CmisPermission.READ));
		/*
		 * list.add(createPermissionMapping( "canCreatePolicy.Folder", //FIXME
		 * OpenCMIS has no constant CMIS_READ_PERMISSION));
		 */
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_CREATE_RELATIONSHIP_SOURCE,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_CREATE_RELATIONSHIP_TARGET,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_GET_PROPERTIES_OBJECT,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_UPDATE_PROPERTIES_OBJECT,
				CmisPermission.WRITE));
		permissionMappings.add(createPermissionMapping(PermissionMapping.CAN_MOVE_OBJECT,
				CmisPermission.WRITE));
		permissionMappings.add(createPermissionMapping(PermissionMapping.CAN_MOVE_TARGET,
				CmisPermission.WRITE));
		permissionMappings.add(createPermissionMapping(PermissionMapping.CAN_MOVE_SOURCE,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(PermissionMapping.CAN_DELETE_OBJECT,
				CmisPermission.ALL));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_VIEW_CONTENT_OBJECT, CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_SET_CONTENT_DOCUMENT,
				CmisPermission.WRITE));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_DELETE_CONTENT_DOCUMENT,
				CmisPermission.WRITE));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_DELETE_TREE_FOLDER, CmisPermission.WRITE));

		// Filing Services
		// Nemaki doesn't support Filing Services

		// Versioning Services
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_CHECKOUT_DOCUMENT, CmisPermission.WRITE));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_CANCEL_CHECKOUT_DOCUMENT,
				CmisPermission.WRITE));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_CHECKIN_DOCUMENT, CmisPermission.WRITE));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_GET_ALL_VERSIONS_VERSION_SERIES,
				CmisPermission.READ));

		// Relationship Services
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_GET_OBJECT_RELATIONSHIPS_OBJECT,
				CmisPermission.READ));

		// Policy Services
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_ADD_POLICY_OBJECT, CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_ADD_POLICY_POLICY, CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_REMOVE_POLICY_OBJECT,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_REMOVE_POLICY_POLICY,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_GET_APPLIED_POLICIES_OBJECT,
				CmisPermission.READ));

		// ACL Services
		permissionMappings.add(createPermissionMapping(PermissionMapping.CAN_GET_ACL_OBJECT,
				CmisPermission.READ));
		permissionMappings.add(createPermissionMapping(
				PermissionMapping.CAN_APPLY_ACL_OBJECT, CmisPermission.WRITE));
	
		permissionMappingsMap = new LinkedHashMap<String, PermissionMapping>();
		for (PermissionMapping pm : permissionMappings) {
			permissionMappingsMap.put(pm.getKey(), pm);
		}
	}
	/**
	 * Mapping permission group to elemental permission (repository static)
	 */
	// FIXME externalize configuration
	public Map<String, PermissionMapping> getPermissionMap() {
		return permissionMappingsMap;
	}

	/**
	 * Create a new permission mapping.
	 */
	private PermissionMapping createPermissionMapping(String key,
			String permission) {
		PermissionMappingDataImpl pm = new PermissionMappingDataImpl();
		pm.setKey(key);
		pm.setPermissions(Collections.singletonList(permission));
		return pm;
	}

}
