package jp.aegif.nemaki.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

public class Folder extends Content{

	private static final long serialVersionUID = 6163910273366541497L;
	public static final String TYPE = "folder";

	/**
	 * Documents this folder includes.
	 */
	private List<String> documents;

	public List<String> getDocuments() {
		return documents;
	}

	public void setDocuments(List<String> documents) {
		this.documents = documents;
	}

	@Override
	public String toString() {
		@SuppressWarnings("serial")
		Map<String, Object> m = new HashMap<String, Object>() {
			{
				put("id", getId());
				put("revision", getRevision());
				put("name", getName());
				put("type", getType());
				put("creator", getCreator());
				put("created", getCreated());
				put("modifier", getModifier());
				put("modified", getModified());
				put("parentId", getParentId());
				put("path", getPath());
				put("documents", getDocuments());
			}
		};
		return m.toString();
	}
}
