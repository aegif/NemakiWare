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
package jp.aegif.nemaki.model.couch;

import java.util.List;
import java.util.Map;

import jp.aegif.nemaki.model.Ace;
import jp.aegif.nemaki.model.Acl;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class CouchAcl {
	private JSONArray entries;
	
	public CouchAcl(){
		entries = new JSONArray();
	}

	/*
	 * Getters/Setters
	 */
	public JSONArray getEntries() {
		return entries;
	}

	public void setEntries(JSONArray acl) {
		this.entries = acl;
	}

	public Acl convertToNemakiAcl(){
		JSONArray entries = this.getEntries();
		if(entries == null) return null;

		Acl acl = new Acl();
		for(Object o : entries){
			Map<String,Object>entry = (Map<String, Object>) o;
			String principal = entry.get("principal").toString();
			List<String> permissions = (List<String>) entry.get("permissions");
			//Defaults to false when objectOnly is null
			boolean objectOnly = (entry.get("objectOnly") != null) ? (Boolean) entry.get("objectOnly") : false; 
			
			Ace ace = new Ace(principal, permissions, objectOnly);
			acl.getLocalAces().add(ace);
		}
		return acl;
	}
	
	/*public org.apache.chemistry.opencmis.commons.data.Acl convertToCmisAcl(){
		AccessControlListImpl result = new AccessControlListImpl();
		result.setAces(new ArrayList<Ace>());

		AddAce(result);
		
		return result;
	}
	
*/	/*@SuppressWarnings("unchecked")
	private void AddAce(AccessControlListImpl acl){
		JSONArray entries = this.getEntries();
		if(entries == null) return;
		
		for(Object o : entries){
			Map<String,Object>entry = (Map<String, Object>) o;
			String principal = entry.get("principal").toString();
			List<String> permissions = (List<String>) entry.get("permissions");
			Boolean direct = (Boolean) entry.get("direct");

			AccessControlEntryImpl ace = new AccessControlEntryImpl();
			ace.setPrincipal(new AccessControlPrincipalDataImpl(principal));
			ace.setPermissions((permissions));
			ace.setDirect(direct);
			
			acl.getAces().add(ace);
		}
	}
	
	
	@SuppressWarnings("unchecked")
	static public JSONObject makeEntry(String principal, String permission, Boolean direct){
		JSONObject entry = new JSONObject();
		entry.put("principal", principal);
		JSONArray permissions = new JSONArray();
		permissions.add(permission);
		entry.put("permissions", permissions);
		entry.put("direct", true);
		return entry;
	}*/
	
	@SuppressWarnings("unchecked")
	@Override
	public String toString() {
		JSONObject json = new JSONObject();
		json.put("entries", entries);
		return json.toJSONString();
	}
}
