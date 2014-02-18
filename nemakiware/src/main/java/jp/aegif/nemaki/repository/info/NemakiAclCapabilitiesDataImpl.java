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
package jp.aegif.nemaki.repository.info;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jp.aegif.nemaki.model.NemakiPermissionDefinition;
import jp.aegif.nemaki.model.constant.PropertyKey;
import jp.aegif.nemaki.util.NemakiPropertyManager;
import jp.aegif.nemaki.util.PermissionDataUtil;
import jp.aegif.nemaki.util.YamlManager;

import org.apache.chemistry.opencmis.commons.data.PermissionMapping;
import org.apache.chemistry.opencmis.commons.definitions.PermissionDefinition;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.SupportedPermissions;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AclCapabilitiesDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionDefinitionDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PermissionMappingDataImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class NemakiAclCapabilitiesDataImpl extends AclCapabilitiesDataImpl {

	private static final long serialVersionUID = 8654484629504222836L;
	private static final Log log = LogFactory.getLog(NemakiAclCapabilitiesDataImpl.class);

	private PermissionDataUtil permissionDataUtil;
	
	public NemakiAclCapabilitiesDataImpl() {
		
	}
	
	public NemakiAclCapabilitiesDataImpl(PermissionDataUtil permissionDataUtil) {
		setPermissionDataUtil(permissionDataUtil);
		
		setSupportedPermissions(SupportedPermissions.BOTH);
		setAclPropagation(AclPropagation.PROPAGATE);
		setPermissionDefinitionData(buildPermissionDefinitions());
		setPermissionMappingData(buildPermissionMaps());
	}

	
	// //////////////////////////////////////////////////////////////////////////
	// Permission Definitions
	// //////////////////////////////////////////////////////////////////////////
	public List<PermissionDefinition> buildPermissionDefinitions() {
		List<PermissionDefinition> permissions = new ArrayList<PermissionDefinition>();
		for (NemakiPermissionDefinition np : permissionDataUtil.readPermissionDefinitions()) {
			permissions.add(createPermission(np.getId(), np.getDescription()));
		}
		return permissions;
	}

	/**
	 * Create a new permission.
	 */
	private PermissionDefinition createPermission(String permission,
			String description) {
		PermissionDefinitionDataImpl pd = new PermissionDefinitionDataImpl();
		pd.setId(permission);
		pd.setDescription(description);
		return pd;
	}

	
	// //////////////////////////////////////////////////////////////////////////
	// Permission Mappings
	// //////////////////////////////////////////////////////////////////////////
	public LinkedHashMap<String, PermissionMapping> buildPermissionMaps() {
		LinkedHashMap<String, PermissionMapping> table
			= new LinkedHashMap<String, PermissionMapping>();

		HashMap<String, ArrayList<String>> map = permissionDataUtil.readPermissionMappingDefinitions();

		//Build table
		for(Entry<String, ArrayList<String>> entry : map.entrySet()){
			//FIXME WORKAROUND: skip canCreatePolicy.Folder
			if(PermissionMapping.CAN_CREATE_POLICY_FOLDER.equals(entry.getKey())){
				continue;
			}

			PermissionMappingDataImpl mapping = new PermissionMappingDataImpl();
			mapping.setKey(entry.getKey());
			mapping.setPermissions(entry.getValue());

			table.put(mapping.getKey(), mapping);
		}

		//Add customized permissions to table
		List<NemakiPermissionDefinition> customs = permissionDataUtil.readPermissionDefinitions();
		for(NemakiPermissionDefinition custom : customs){
			customizeTable(custom, table);
		}

		return table;
	}

	private  void customizeTable(NemakiPermissionDefinition custom, LinkedHashMap<String, PermissionMapping> table){
		for(Entry<String, PermissionMapping> entry : table.entrySet()){
			String k = entry.getKey();
			PermissionMapping pm = entry.getValue();

			//Add where base exists
			if(CollectionUtils.isNotEmpty(custom.getBase())){
				//Check if any base is contained
				boolean baseContained = false;
				for(String base : custom.getBase()){
					if(pm.getPermissions().contains(base)){
						baseContained = true;
						break;
					}
				}

				//Check mapped flag
				Boolean mapped = custom.getPermissionMapping().get(k);
				//Default to true
				if(custom.getPermissionMapping().containsKey(k) && mapped == null){
					mapped = true;
				}

				//Customize table
				if(Boolean.TRUE == mapped || ((mapped == null) && baseContained)){
					//Add without duplicate
					boolean duplicate = pm.getPermissions().contains(custom.getId());
					if(!duplicate){
						List<String> permissions = new ArrayList<String>(pm.getPermissions());
						permissions.add(custom.getId());

						PermissionMappingDataImpl pmdi = new PermissionMappingDataImpl();
						pmdi.setKey(k);
						pmdi.setPermissions(permissions);

						table.put(k, pmdi);
					}
				}
			}

		}
	}

	public void setPermissionDataUtil(PermissionDataUtil permissionDataUtil) {
		this.permissionDataUtil = permissionDataUtil;
	}
	
}