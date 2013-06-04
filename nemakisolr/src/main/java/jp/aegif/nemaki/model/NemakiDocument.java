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

import org.apache.solr.client.solrj.beans.Field;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class NemakiDocument {
	//attachmentではなくドキュメント本体のid
	@Field
	String id;
	
	@Field
	String name;
	
	@Field
	JSONArray aspects;
	
	//最新のnemakiAttachment1件のid
	@Field
	String nemakiAttachment;
	
	//attachmentの本文
	@Field
	String content;
	
	@Field
	JSONObject permission;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public JSONArray getAspects() {
		return aspects;
	}

	public void setAspects(JSONArray aspects) {
		this.aspects = aspects;
	}

	public String getNemakiAttachment() {
		return nemakiAttachment;
	}

	public void setNemakiAttachment(String nemakiAttachment) {
		this.nemakiAttachment = nemakiAttachment;
	}

	public JSONObject getPermission() {
		return permission;
	}

	public void setPermission(JSONObject permission) {
		this.permission = permission;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}
	
	
	
}
