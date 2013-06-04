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
 *     linzhixing - initial API and implementation
 ******************************************************************************/
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
