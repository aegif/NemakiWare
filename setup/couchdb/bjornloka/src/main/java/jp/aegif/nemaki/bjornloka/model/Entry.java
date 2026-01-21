package jp.aegif.nemaki.bjornloka.model;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

public class Entry {
	private JsonNode document;

	Map<String,  Map<String, Object>> attachments;

	public JsonNode getDocument() {
		return document;
	}
	public void setDocument(JsonNode document) {
		this.document = document;
	}
	public Map<String,  Map<String, Object>> getAttachments() {
		return attachments;
	}
	public void setAttachments(Map<String,  Map<String, Object>> attachments) {
		this.attachments = attachments;
	}
}
