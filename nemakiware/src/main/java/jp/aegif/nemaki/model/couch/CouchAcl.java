package jp.aegif.nemaki.model.couch;

import java.util.ArrayList;
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
