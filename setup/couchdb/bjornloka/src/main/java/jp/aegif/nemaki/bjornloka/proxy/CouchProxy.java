package jp.aegif.nemaki.bjornloka.proxy;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;

public interface CouchProxy {
	public List<String> getAllDocIds();
	public List<ObjectNode> getDocs(List<String> keys);
	public Map<String, Map<String, Object>> getAttachments(JsonNode o);
	public List<String> bulkInsert(List<ObjectNode> subList);
	public void createAttachment(String docId, String attachmentId, ObjectNode attachment) throws Exception;
}
