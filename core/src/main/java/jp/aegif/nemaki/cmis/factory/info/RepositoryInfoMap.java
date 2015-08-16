package jp.aegif.nemaki.cmis.factory.info;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.aegif.nemaki.util.YamlManager;

public class RepositoryInfoMap {
	private Capabilities capabilities;
	private AclCapabilities aclCapabilities;
	
	private Map<String, RepositoryInfo> map = new HashMap<String, RepositoryInfo>();
	
	public void init(){
		loadRepositoriesSetting();
	}
	
	public void add(RepositoryInfo info){
		map.put(info.getId(), info);
	}
	
	public RepositoryInfo get(String repositoryId){
		return map.get(repositoryId);
	}
	
	public Set<String> keys(){
		return map.keySet();
	}
	
	private void loadRepositoriesSetting(){
		YamlManager ymlMgr = new YamlManager("repositories.yml");
		Map<String, Object> data = (Map<String, Object>)ymlMgr.loadYml();
		Map<String, String> defaultSetting = (Map<String, String>)data.get("default");
		List<Map<String, String>> repositoriesSetting = (List<Map<String, String>>)data.get("repositories");
		
		for(Map<String, String> repStg : repositoriesSetting){
			RepositoryInfo info = buildDefaultInfo(defaultSetting);
			modifyInfo(repStg, info);
			map.put(info.getId(), info);
		}
	}
	
	private void modifyInfo(Map<String, String> setting, RepositoryInfo info){
		for(String key :setting.keySet()){
			String val = String.valueOf(setting.get(key));
			
			//TODO hard-coding
			info.setCmisVersionSupported("1.1");
			info.setCapabilities(capabilities);
			info.setAclCapabilities(aclCapabilities);
			
			if(key.equals("id")){
				info.setId(val);
			}else if(key.equals("name")){
				info.setName(val);
			}else if(key.equals("description")){
				info.setDescription(val);
			}else if(key.equals("root")){
				info.setRootFolder(val);
			}else if(key.equals("principal.anonymous")){
				info.setPrincipalAnonymous(val);
			}else if(key.equals("principal.anyone")){
				info.setPrincipalAnyone(val);
			}else if(key.equals("thinClientUri")){
				info.setThinClientUri(val);
			}else if(key.equals("vendor")){
				info.setVendorName(val);
			}else if(key.equals("product.name")){
				info.setProductName(val);
			}else if(key.equals("product.version")){
				info.setProductVersion(val);
			}else if(key.equals("nameSpace")){
				info.setNameSpace(val);
			}
		}
	}
	
	private RepositoryInfo buildDefaultInfo(Map<String, String> setting){
		RepositoryInfo info = new RepositoryInfo();
		modifyInfo(setting, info);
		return info;
	}

	public void setCapabilities(Capabilities capabilities) {
		this.capabilities = capabilities;
	}

	public void setAclCapabilities(AclCapabilities aclCapabilities) {
		this.aclCapabilities = aclCapabilities;
	}
}
