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

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class Permission {

	/**
	 * Group-right and person-right couples.
	 */
	private JSONArray entries;

	/*
	 * Getters/Setters
	 */
	public JSONArray getEntries() {
		return entries;
	}

	public void setEntries(JSONArray permissionEntries) {
		this.entries = permissionEntries;
	}

	@SuppressWarnings("unchecked")
	@Override
	public String toString() {
		JSONObject json = new JSONObject();
		json.put("entries", entries);
		return json.toJSONString();
	}
}
