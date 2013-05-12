package jp.aegif.nemaki.model;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

public class Document extends Content{
	private static final long serialVersionUID = -5818553410615887453L;
	public static final String TYPE = "document";

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
				put("aspects", getAspects());
			}
		};
		return m.toString();
	}
}
