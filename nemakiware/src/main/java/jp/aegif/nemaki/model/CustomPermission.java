package jp.aegif.nemaki.model;

import java.util.List;
import java.util.Map;

public class CustomPermission {
	String id;
	String description;
	List<String> base;
	Map<String,Boolean> permissionMapping;
	
	public CustomPermission(){
		
	}
	
	public CustomPermission(Map<String, Object> map){
		//TODO try-catch
		this.setId(map.get("id").toString());
		this.setDescription(map.get("description").toString());
		this.setBase((List<String>)map.get("base"));
		this.setPermissionMapping((Map<String,Boolean>)map.get("permissionMapping"));
	}
	
	
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public List<String> getBase() {
		return base;
	}
	public void setBase(List<String> base) {
		this.base = base;
	}
	public Map<String, Boolean> getPermissionMapping() {
		return permissionMapping;
	}
	public void setPermissionMapping(
			Map<String, Boolean> permissionMapping) {
		this.permissionMapping = permissionMapping;
	}
}
