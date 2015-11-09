package jp.aegif.nemaki.bjornloka.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import com.cloudant.client.api.CloudantClient;
import com.cloudant.client.api.Database;
import com.cloudant.client.api.model.Document;
import com.cloudant.client.api.model.Params;
import com.cloudant.client.api.model.Response;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;

import jp.aegif.nemaki.bjornloka.StringPool;
import jp.aegif.nemaki.bjornloka.util.Util;

public class CloudantProxy implements CouchProxy {
	CloudantClient client;
	Database database;

	public CloudantProxy(CloudantClient client, Database databse) {
		super();
		this.client = client;
		this.database = databse;
	}

	public CloudantProxy(CloudantClient client, String repositoryId, boolean force) {
		super();
		this.client = client;

		this.database = client.database(repositoryId, true);
	}

	@Override
	//TODO can get just ids somehow?
	public List<String> getAllDocIds() {
		List<Document> docs = database.view("_all_docs").includeDocs(true).query(Document.class);
		System.out.println("docs num:" + docs.size());
		List<String> result = new ArrayList<String>();
		for(Document doc : docs){
			result.add(doc.getId());
		}
		return result;
	}

	@Override
	public List<ObjectNode> getDocs(List<String> keys) {
		List<ObjectNode> result = new ArrayList<ObjectNode>();
		for(String key : keys){
			if(key == null){
				continue;
			}
			System.out.println(key);
			
			InputStream is = database.find(key);
			ObjectMapper mapper = new ObjectMapper();
			try {
				JsonNode jackson = mapper.readTree(is);
				result.add((ObjectNode)jackson);
			} catch (JsonProcessingException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
			
		}
		
		return result;
	}

	public Map<String, Map<String, Object>> getAttachments(JsonNode o) {

		String docId = o.get(StringPool.FIELD_ID).textValue();
		Document doc = database.find(Document.class, docId, new Params().attachments());
		Map<String, org.lightcouch.Attachment> map = doc.getAttachments();
		Map<String, Map<String, Object>> attachments = new HashMap<String, Map<String, Object>>();

		if (map != null) { // TODO
			for (String key : map.keySet()) {
				org.lightcouch.Attachment a = map.get(key);
				Map<String, Object> attachment = new HashMap<String, Object>();

				attachment.put(StringPool.FIELD_CONTENT_TYPE, a.getContentType());
				String data = a.getData();
				byte[] bytes = data.getBytes();
				attachment.put(StringPool.FIELD_DATA, bytes);
				attachments.put(a.getDigest(), attachment);
			}
		}

		return attachments;
	}

	@Override
	public List<String> bulkInsert(List<ObjectNode> subList) {
		List<Response> responses = new ArrayList<Response>();

		List<JsonObject> _subList = new ArrayList<JsonObject>();
		for (ObjectNode jackson : subList) {
			JsonObject gson = Util.convertToGson(jackson);
			_subList.add(gson);
		}

		responses = database.bulk(_subList);

		List<String> errorList = new ArrayList<String>();
		if (CollectionUtils.isNotEmpty(responses)) {
			for (Response r : responses) {
				String err = r.getError();
				System.out.println(err);
				if (err != null && (!err.equals("") || !err.equals("null"))) {
					errorList.add(r.getId());
				}
			}
		}

		return errorList;
	}

	@Override
	public void createAttachment(String docId, String attachmentId, ObjectNode attachment) throws Exception {
		// Binary data
		String data = attachment.get(StringPool.FIELD_DATA).textValue();

		// Content type
		String contentType = attachment.get(StringPool.FIELD_CONTENT_TYPE).textValue();

		// Document doc = database.find(Document.class, docId);
		Document doc = new Document();
		doc.setId(docId);

		Map<String, org.lightcouch.Attachment> attachments = new HashMap<String, org.lightcouch.Attachment>();
		attachments.put(attachmentId, new org.lightcouch.Attachment(data, contentType));
		doc.setAttachments(attachments);

		Response response = database.save(doc);

	}

	class Foo {
		public String hoge;
	}
}
