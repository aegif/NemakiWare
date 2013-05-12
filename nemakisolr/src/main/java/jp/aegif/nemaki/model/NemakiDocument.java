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
