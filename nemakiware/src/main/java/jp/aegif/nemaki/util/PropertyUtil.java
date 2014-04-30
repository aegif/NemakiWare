package jp.aegif.nemaki.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.commons.data.CmisExtensionElement;
import org.apache.chemistry.opencmis.commons.data.Principal;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlEntryImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlListImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.AccessControlPrincipalDataImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.CmisExtensionElementImpl;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;
import jp.aegif.nemaki.model.Content;
import jp.aegif.nemaki.model.NemakiPermissionDefinition;
import jp.aegif.nemaki.util.constant.NemakiConstant;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class PropertyUtil {
	private static final Log log = LogFactory.getLog(PropertyUtil.class);
	
	private NemakiPropertyManager propertyManager;

	//////////////////////////////////////////////////
	//Utilities
	//////////////////////////////////////////////////
	public boolean isRoot(Content content){
		String rootObjectId = propertyManager.readValue(PropertyKey.CMIS_REPOSITORY_MAIN_ROOT);
		if(content.isFolder() && rootObjectId.equals(content.getId())){
			return true;
		}else{
			return false;
		}
	}
	
	
	//////////////////////////////////////////////////
	//Permission utilities
	//////////////////////////////////////////////////
	public org.apache.chemistry.opencmis.commons.data.Acl convertToCmisAcl(
			Acl acl, Boolean isInherited, Boolean onlyBasicPermissions) {

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
		String namespace = NemakiConstant.NAMESPACE_ACL_INHERITANCE;
		boolean iht = (isInherited == null)? false : isInherited;
		CmisExtensionElementImpl inherited = new CmisExtensionElementImpl(
				namespace, NemakiConstant.EXTNAME_ACL_INHERITED, null, String.valueOf(iht));
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
					HashMap<String,String> map = convertToMap(readPermissionDefinitions());

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


	private HashMap<String, String> convertToMap(List<NemakiPermissionDefinition> list){
		HashMap<String, String> result = new HashMap<String, String>();
		for(NemakiPermissionDefinition p : list){
			result.put(p.getId(), p.getAsCmisBasicPermission());
		}
		return result;
	}
	
	
	@SuppressWarnings("unchecked")
	public List<NemakiPermissionDefinition> readPermissionDefinitions() {
		List<NemakiPermissionDefinition> results = new ArrayList<NemakiPermissionDefinition>();

		//Get definition file
		String definitionFile = "";
		try {
			definitionFile = propertyManager.readValue(PropertyKey.PERMISSION_DEFINITION);
		} catch (Exception e) {
			log.error("Cannot read a permission definition file", e);
		}

		//Parse definition file
		YamlManager manager = new YamlManager(definitionFile);
		List<Map<String, Object>> yml = (List<Map<String, Object>>) manager
				.loadYml();
		for (Map<String, Object> y : yml) {
			NemakiPermissionDefinition cp = new NemakiPermissionDefinition(y);
			results.add(cp);
		}

		return results;
	}
	
	@SuppressWarnings("unchecked")
	public HashMap<String, ArrayList<String>> readPermissionMappingDefinitions(){
		//Decide base definition file
		String definitionFile = "";
		try {
			definitionFile = propertyManager.readValue(PropertyKey.PERMISSION_MAPPING_DEFINITION);
		} catch (Exception e) {
			log.error("Cannot read a permission mapping definition file", e);
		}

		//Get mapping info
		YamlManager ymgr = new YamlManager(definitionFile);
		Object yaml = ymgr.loadYml();

		HashMap<String, ArrayList<String>> map = new HashMap<String, ArrayList<String>>();
		try{
			map = (HashMap<String, ArrayList<String>>) yaml;
		}catch(Exception e){
			log.error(definitionFile + " is not well-formatted.", e);
		}

		return map;
	}
	
	
	public NemakiPropertyManager getPropertyManager() {
		return propertyManager;
	}
	
	
	public void setPropertyManager(NemakiPropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
