package jp.aegif.nemaki.cmis.factory.info;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.collections.MapUtils;

import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.YamlManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

public class RepositoryInfoMap {
	private Capabilities capabilities;
	private AclCapabilities aclCapabilities;
	private PropertyManager propertyManager;
	
	private Map<String, RepositoryInfo> map = new HashMap<String, RepositoryInfo>();
	private String superUsersId;
	
	public void init(){
		loadRepositoriesSetting();
	}
	
	public void add(RepositoryInfo info){
		map.put(info.getId(), info);
	}
	
	public RepositoryInfo get(String repositoryId){
		return map.get(repositoryId);
	}
	
	public boolean contains(String repositoryId){
		return get(repositoryId) != null;
	}
	
	public Set<String> keys(){
		return map.keySet();
	}
	
	public String getArchiveId(String repositoryId){
		return map.get(repositoryId).getArchiveId();
	}
	
	public RepositoryInfo getSuperUsers(){
		return map.get(this.superUsersId);
	}
	
	private void hoge(){
		InputStream is = this.getClass().getClassLoader().getResourceAsStream("app.properties");
		Properties props = new Properties();
		try {
			props.load(is);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String version = props.getProperty("application.version");
		System.out.println("hoge");
	}
	
	private void loadRepositoriesSetting(){
		Map<String, String> defaultSetting = loadDefaultRepositorySetting();
		loadOverrideRepositorySetting(defaultSetting);
		loadSuperUsersId();
	}
	
	private Map<String, String> loadDefaultRepositorySetting(){
		String file = propertyManager.readValue(PropertyKey.REPOSITORY_DEFINITION_DEFAULT);
		YamlManager ymlMgr = new YamlManager(file);
		Map<String, Object> data = (Map<String, Object>)ymlMgr.loadYml();
		Map<String, String> defaultSetting = (Map<String, String>)data.get("default");
		
		return defaultSetting;
	}
	
	private Map<String, String> overrideMap(Map<String, String> newMap, Map<String, String> oldMap){
		if(MapUtils.isNotEmpty(newMap)){
			Map<String, String> map = new HashMap<>(oldMap);
			map.putAll(newMap);
			return map;
		}
		
		return oldMap;
	}
	
	private void loadOverrideRepositorySetting(Map<String, String> defaultSetting){
		String file = propertyManager.readValue(PropertyKey.REPOSITORY_DEFINITION);
		YamlManager ymlMgr = new YamlManager(file);
		Map<String, Object> data = (Map<String, Object>)ymlMgr.loadYml();
		
		//Override default info if it exists
		Map<String, String> overrideDefault = (Map<String, String>)data.get("default");
		defaultSetting = overrideMap(overrideDefault, defaultSetting);
		
		//Each repository's setting
		List<Map<String, String>> repositoriesSetting = (List<Map<String, String>>)data.get("repositories");
		for(Map<String, String> repStg : repositoriesSetting){
			RepositoryInfo info = buildDefaultInfo(defaultSetting);
			modifyInfo(repStg, info);
			map.put(info.getId(), info);
		}
	}
	
	private void loadSuperUsersId(){
		String f1 = propertyManager.readValue(PropertyKey.REPOSITORY_DEFINITION_DEFAULT);
		YamlManager mgr1 = new YamlManager(f1);
		Map<String, Object> data1 = (Map<String, Object>)mgr1.loadYml();
		Object su1 = data1.get("super.users");
		
		if(su1 != null){
			String f2 = propertyManager.readValue(PropertyKey.REPOSITORY_DEFINITION_DEFAULT);
			YamlManager mgr2 = new YamlManager(f1);
			Map<String, Object> data2 = (Map<String, Object>)mgr2.loadYml();
			Object su2 = data2.get("super.users");
			
			if(su2 == null){
				this.superUsersId = su1.toString();
			}else{
				this.superUsersId = su2.toString();
			}
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
			}else if(key.equals("archive")){
				info.setArchiveId(val);
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

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
