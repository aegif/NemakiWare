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
