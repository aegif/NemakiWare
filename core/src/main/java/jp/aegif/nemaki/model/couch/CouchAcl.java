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

			//DB-stored ACL is only localACE(direct = true)
			Ace ace = new Ace(principal, permissions, true);
			acl.getLocalAces().add(ace);
		}
		return acl;
	}

	@SuppressWarnings("unchecked")
	@Override
	public String toString() {
		JSONObject json = new JSONObject();
		json.put("entries", entries);
		return json.toJSONString();
	}
}
